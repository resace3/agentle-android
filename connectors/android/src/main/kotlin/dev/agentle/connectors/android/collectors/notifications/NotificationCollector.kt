package dev.agentle.connectors.android.collectors.notifications

import android.os.Build
import dev.agentle.connectors.android.core.AndroidConnectorIds
import dev.agentle.connectors.android.core.AndroidSources
import dev.agentle.connectors.android.core.CollectorRuntime
import dev.agentle.connectors.android.core.CoverageIds
import dev.agentle.connectors.android.core.LiveCursor
import dev.agentle.connectors.android.core.LiveEventBuffer
import dev.agentle.connectors.android.core.cursorSafely
import dev.agentle.connectors.android.core.epochSafely
import dev.agentle.connectors.android.core.writeSafely
import dev.agentle.connectors.api.CollectionSettings
import dev.agentle.connectors.api.CoverageEndCause
import dev.agentle.connectors.api.NotificationContentPurger
import dev.agentle.connectors.api.SyncCursor
import dev.agentle.connectors.api.WriteBatch
import dev.agentle.connectors.api.WriteResult
import dev.agentle.core.model.EventType
import dev.agentle.core.model.NotificationPayload
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.Sensitivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The notification collector behind [AgentleNotificationListener] (docs/ARCHITECTURE.md §6.4 row 2, docs/research/02
 * §6.10, red team lifecycle-battery-02, database-sync-17).
 *
 * - One NOTIFICATION_POSTED row per key instance, keyed `notif|<keyHash>|<firstPostMs>`; every update of a posted
 *   notification folds into that row's `updateCount` / `lastUpdateEpochMs` (5,000 updates of one key are one row).
 * - NOTIFICATION_REMOVED rows (`notif|<keyHash>|<firstPostMs>|removed`) carry the platform removal reason; removals that
 *   happened while the listener was unbound are found on reconnect by diffing the stored active keys against
 *   `getActiveNotifications()` and get reason 0 (unknown).
 * - Agentle's own notifications are dropped. Ongoing notifications are excluded: by `default_filter_types` on API 31+
 *   (the user may change it there) and in code on API 29-30.
 * - Title and text are stored only for packages the user opted in, never for the default SMS app or dialer unless the
 *   user allowed that too; otherwise only `hasText` is kept. Keys and channel ids are stored as salted hashes.
 * - Coverage is recorded under `notification_events_metadata` ([CoverageIds.NOTIFICATIONS]): open while the listener
 *   is connected, closed when it disconnects or a write is lost.
 * - Callbacks run on the main thread and only enqueue; one consumer processes them in order and writes through a
 *   [LiveEventBuffer] (in-process batching, at most 500 rows per transaction). The active keys live in the
 *   `("android", "notifications")` cursor, committed with the rows.
 */
public class NotificationCollector(
    private val runtime: CollectorRuntime,
    private val handlers: DefaultHandlers,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    flushDelay: Duration = 2.seconds,
    private val purger: NotificationContentPurger? = null,
) : LiveCursor {
    private val handlersLock = Mutex()

    @Volatile private var knownHandlers: Set<String>? = null

    private val ownPackage: String = runtime.context.packageName
    private val buffer =
        LiveEventBuffer(AndroidConnectorIds.NOTIFICATIONS, CoverageIds.NOTIFICATIONS, runtime, cursor = this, flushDelay = flushDelay)
    private val signals = Channel<Signal>(Channel.UNLIMITED)
    private val active = LinkedHashMap<String, ActiveEntry>()
    private var consumer: Job? = null

    @Volatile private var loaded = false

    @Volatile private var generation = 0L

    @Volatile private var settings: CollectionSettings? = null

    @Volatile private var suspended = false

    /** Starts the consumer; idempotent. */
    @Synchronized
    public fun start() {
        if (consumer?.isActive == true) return
        consumer = runtime.scope.launch {
            launch { runtime.settings.flow.collect { settings = it } }
            for (signal in signals) process(signal)
        }
    }

    /** Whether title and text of [packageName] may be stored (the listener reads them only then). */
    public fun wantsContent(packageName: String): Boolean {
        val current = settings ?: return false
        return packageName in current.notificationContentPackages
    }

    public fun onPosted(snapshot: NotificationSnapshot) {
        signals.trySend(Signal.Posted(snapshot, runtime.clock.now()))
    }

    public fun onRemoved(snapshot: NotificationSnapshot, reason: Int) {
        signals.trySend(Signal.Removed(snapshot, reason, runtime.clock.now()))
    }

    /** [active] is `getActiveNotifications()`, or null when it could not be read. */
    public fun onListenerConnected(active: List<NotificationSnapshot>?) {
        signals.trySend(Signal.Connected(active, runtime.clock.now()))
    }

    public fun onListenerDisconnected() {
        signals.trySend(Signal.Disconnected(runtime.clock.now()))
    }

    /** Writes everything pending now. */
    public suspend fun flush(): WriteResult? = buffer.flush()

    /** Rows waiting for the next write (tests). */
    public suspend fun pendingCount(): Int = buffer.pendingCount()

    /** Drops everything pending (the user disabled the connector). */
    public suspend fun dropPending(): Unit = buffer.discard()

    /** Delete-all: drops pending rows and the active keys; nothing is collected until [resumeAfterDeletion]. */
    public suspend fun suspendForDeletion() {
        suspended = true
        buffer.discard()
        synchronized(active) { active.clear() }
        loaded = false
    }

    public fun resumeAfterDeletion() {
        suspended = false
    }

    override suspend fun cursorForWrite(): SyncCursor {
        val state = synchronized(active) { ActiveState(active.toMap()) }
        return SyncCursor(
            connectorId = AndroidSources.CURSOR_CONNECTOR,
            stream = STREAM,
            lastSuccessCursor = state.encode(),
            syncFinishedAt = runtime.clock.now(),
            generation = generation,
        )
    }

    override fun onCommitted() {
        generation += 1
    }

    override suspend fun onRejected() {
        generation = runtime.writer.cursorSafely(AndroidSources.CURSOR_CONNECTOR, STREAM)?.generation ?: 0L
    }

    override suspend fun onEpochChanged() {
        synchronized(active) { active.clear() }
        loaded = false
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun process(signal: Signal) {
        try {
            when (signal) {
                is Signal.Posted -> posted(signal.snapshot, signal.at)
                is Signal.Removed -> removed(signal.snapshot, signal.reason, signal.at)
                is Signal.Connected -> connected(signal.active, signal.at)
                is Signal.Disconnected -> disconnected(signal.at)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runtime.logger.w(COMPONENT, "Notification signal dropped", fields = mapOf("error" to e::class.simpleName))
        }
    }

    private suspend fun posted(snapshot: NotificationSnapshot, at: Instant) {
        val current = currentSettings()
        if (!collecting(current) || !keep(snapshot)) return
        ensureLoaded()
        val defaults = refreshHandlers()
        val keyHash = runtime.hasher.shortHash(snapshot.key)
        val previous = synchronized(active) { active[keyHash] }
        val entry = previous?.copy(
            updates = previous.updates + 1,
            lastUpdateMs = at.toEpochMilliseconds(),
            channelHash = channelHash(snapshot),
            category = snapshot.category,
            ongoing = snapshot.ongoing,
            groupSummary = snapshot.groupSummary,
        ) ?: newEntry(snapshot)
        // The row goes first: a crash before the cursor moves re-creates the same key on reconnect (it converges).
        buffer.submit(listOf(postedEvent(snapshot, keyHash, entry, current, defaults)), cursorChanged = true)
        synchronized(active) {
            active[keyHash] = entry
            trim()
        }
    }

    private suspend fun removed(snapshot: NotificationSnapshot, reason: Int, at: Instant) {
        val current = currentSettings()
        if (!collecting(current) || !keep(snapshot)) return
        ensureLoaded()
        val keyHash = runtime.hasher.shortHash(snapshot.key)
        val entry = synchronized(active) { active[keyHash] } ?: newEntry(snapshot)
        buffer.submit(listOf(removedEvent(keyHash, entry, reason, at)), cursorChanged = true)
        synchronized(active) { active.remove(keyHash) }
    }

    private suspend fun connected(snapshots: List<NotificationSnapshot>?, at: Instant) {
        runtime.coverage.open(CoverageIds.NOTIFICATIONS, at)
        val current = currentSettings()
        if (!collecting(current)) return
        ensureLoaded()
        val now = (snapshots ?: return).filter(::keep).associateBy { runtime.hasher.shortHash(it.key) }
        val known = synchronized(active) { active.toMap() }
        val events = ArrayList<PersonalEvent>()
        val gone = known.keys.filter { it !in now }
        gone.forEach { key -> events += removedEvent(key, known.getValue(key), REASON_UNKNOWN, at) }
        val added = now.filterKeys { it !in known }.mapValues { (_, snapshot) -> newEntry(snapshot) }
        val defaults = if (added.isEmpty()) emptySet() else refreshHandlers()
        added.forEach { (key, entry) -> events += postedEvent(now.getValue(key), key, entry, current, defaults) }
        if (events.isNotEmpty()) buffer.submit(events, cursorChanged = true)
        synchronized(active) {
            gone.forEach(active::remove)
            active.putAll(added)
            trim()
        }
    }

    private suspend fun disconnected(at: Instant) {
        runtime.coverage.close(CoverageIds.NOTIFICATIONS, at, CoverageEndCause.LISTENER_DISCONNECTED)
        if (!suspended) buffer.flush()
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val stored = runtime.writer.cursorSafely(AndroidSources.CURSOR_CONNECTOR, STREAM)
        generation = stored?.generation ?: 0L
        val state = ActiveState.decode(stored?.lastSuccessCursor)
        synchronized(active) {
            active.clear()
            active.putAll(state.active)
        }
        loaded = true
    }

    private suspend fun currentSettings(): CollectionSettings = settings ?: runtime.settings.current().also { settings = it }

    private fun collecting(current: CollectionSettings): Boolean =
        !suspended && AndroidConnectorIds.NOTIFICATIONS !in current.disabledConnectors

    /** Own package dropped; ongoing notifications filtered in code on API 29-30 (API 31+ uses default_filter_types). */
    private fun keep(snapshot: NotificationSnapshot): Boolean =
        snapshot.packageName != ownPackage && (sdkInt >= Build.VERSION_CODES.S || !snapshot.ongoing)

    private fun newEntry(snapshot: NotificationSnapshot) = ActiveEntry(
        firstPostMs = snapshot.postTimeMs,
        pkg = snapshot.packageName,
        channelHash = channelHash(snapshot),
        category = snapshot.category,
        ongoing = snapshot.ongoing,
        groupSummary = snapshot.groupSummary,
    )

    private fun channelHash(snapshot: NotificationSnapshot): String? = snapshot.channelId?.let(runtime.hasher::shortHash)

    /** Opted-in packages only, and never the default SMS app or dialer (privacy-ai-16; the old opt-out is ignored). */
    private fun contentAllowed(packageName: String, current: CollectionSettings, defaults: Set<String>): Boolean =
        packageName in current.notificationContentPackages && packageName !in defaults

    /**
     * Re-resolves the default SMS app and dialer (each collection, red team privacy-ai-16). A package that became one
     * since the last resolution has its stored notification content purged through [NotificationContentPurger]; the
     * known set is kept in the `("android", "notification_defaults")` cursor so a change while the process was dead is
     * found too. A failed purge is retried at the next resolution.
     */
    @Suppress("TooGenericExceptionCaught")
    public suspend fun refreshHandlers(): Set<String> = handlersLock.withLock {
        val now = try {
            handlers.packages()
        } catch (e: RuntimeException) {
            runtime.logger.w(COMPONENT, "Default handlers unreadable", fields = mapOf("error" to e::class.simpleName))
            return@withLock knownHandlers.orEmpty()
        }
        val known = knownHandlers ?: runtime.writer.cursorSafely(AndroidSources.CURSOR_CONNECTOR, DEFAULTS_STREAM)
            ?.lastSuccessCursor?.split(',')?.filter { it.isNotEmpty() }?.toSet()
        val purged = (now - known.orEmpty()).filter { purge(it) }.toSet()
        val settled = now.intersect(known.orEmpty()) + purged
        if (settled != known) persistDefaults(settled)
        knownHandlers = settled
        now
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun purge(packageName: String): Boolean {
        val target = purger ?: return true
        return try {
            target.purgeContent(packageName)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runtime.logger.w(COMPONENT, "Content purge failed", fields = mapOf("error" to e::class.simpleName))
            false
        }
    }

    private suspend fun persistDefaults(packages: Set<String>) {
        val epoch = runtime.writer.epochSafely() ?: return
        val stored = runtime.writer.cursorSafely(AndroidSources.CURSOR_CONNECTOR, DEFAULTS_STREAM)
        val cursor = (stored ?: SyncCursor(AndroidSources.CURSOR_CONNECTOR, DEFAULTS_STREAM)).copy(
            lastSuccessCursor = packages.sorted().joinToString(","),
            syncFinishedAt = runtime.clock.now(),
        )
        runtime.writer.writeSafely(WriteBatch(epoch = epoch, cursor = cursor))
    }

    private fun postedEvent(
        snapshot: NotificationSnapshot,
        keyHash: String,
        entry: ActiveEntry,
        current: CollectionSettings,
        defaults: Set<String>,
    ): PersonalEvent {
        val content = snapshot.hasText && contentAllowed(snapshot.packageName, current, defaults)
        return runtime.events.create(
            type = EventType.NOTIFICATION_POSTED,
            source = AndroidSources.NOTIFICATIONS,
            start = Instant.fromEpochMilliseconds(entry.firstPostMs),
            payload = NotificationPayload(
                packageName = snapshot.packageName,
                category = snapshot.category,
                channelHash = entry.channelHash,
                ongoing = snapshot.ongoing,
                groupSummary = snapshot.groupSummary,
                keyHash = keyHash,
                foregroundService = snapshot.foregroundService,
                localOnly = snapshot.localOnly,
                updateCount = entry.updates,
                lastUpdateEpochMs = entry.lastUpdateMs,
                hasText = snapshot.hasText,
                title = if (content) snapshot.title else null,
                text = if (content) snapshot.text else null,
            ),
            dedupKey = postedKey(keyHash, entry.firstPostMs),
            sensitivity = if (content) Sensitivity.PERSONAL else Sensitivity.NORMAL,
            origin = snapshot.packageName,
        )
    }

    private fun removedEvent(keyHash: String, entry: ActiveEntry, reason: Int, at: Instant): PersonalEvent = runtime.events.create(
        type = EventType.NOTIFICATION_REMOVED,
        source = AndroidSources.NOTIFICATIONS,
        start = at,
        payload = NotificationPayload(
            packageName = entry.pkg,
            category = entry.category,
            channelHash = entry.channelHash,
            ongoing = entry.ongoing,
            groupSummary = entry.groupSummary,
            keyHash = keyHash,
            updateCount = entry.updates,
            lastUpdateEpochMs = entry.lastUpdateMs,
            removalReason = reason,
        ),
        dedupKey = removedKey(keyHash, entry.firstPostMs),
        origin = entry.pkg,
    )

    /** Keeps the newest [MAX_ACTIVE] keys (a listener that missed removals must not grow the cursor forever). */
    private fun trim() {
        if (active.size <= MAX_ACTIVE) return
        active.entries.sortedBy { it.value.firstPostMs }.take(active.size - MAX_ACTIVE).map { it.key }.forEach(active::remove)
    }

    private sealed interface Signal {
        class Posted(val snapshot: NotificationSnapshot, val at: Instant) : Signal

        class Removed(val snapshot: NotificationSnapshot, val reason: Int, val at: Instant) : Signal

        class Connected(val active: List<NotificationSnapshot>?, val at: Instant) : Signal

        class Disconnected(val at: Instant) : Signal
    }

    public companion object {
        public const val STREAM: String = "notifications"
        public const val DEFAULTS_STREAM: String = "notification_defaults"

        /** A removal found by the reconnect diff: the platform reason is unknown (REASON_* constants start at 1). */
        public const val REASON_UNKNOWN: Int = 0

        private const val MAX_ACTIVE = 1_000
        private const val COMPONENT = "collectors.notifications"

        public fun postedKey(keyHash: String, firstPostMs: Long): String = "notif|$keyHash|$firstPostMs"

        public fun removedKey(keyHash: String, firstPostMs: Long): String = "notif|$keyHash|$firstPostMs|removed"
    }
}
