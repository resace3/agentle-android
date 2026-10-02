package dev.agentle.background

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import dev.agentle.background.port.CollectionProfile
import dev.agentle.background.port.DeletionMarker
import dev.agentle.background.port.JitaiRunner
import dev.agentle.core.common.getOrNull
import dev.agentle.core.time.AgentleClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The only class that enqueues work (§13). Policies:
 * - periodic: KEEP on reconcile, UPDATE on a profile change, never REPLACE (R02 §1.3);
 * - `jitai-timer`: re-armed to `nextDueAt` without ever cancelling a running pass (see [armJitaiTimer]);
 * - `jitai-eval-events`, `sync-googlehealth-now`: KEEP, expedited on 31+, plus one follow-up while running;
 * - `reconcile`: debounced reasons wait [Cadences.RECONCILE_DEBOUNCE]; BOOT and PACKAGE_REPLACED run at once.
 * Nothing is enqueued while the "delete everything" marker exists. Single process (§13): the in-process [timerLock]
 * makes every timer arm read the latest plan, whatever order callers arrive in.
 */
public class WorkScheduler(
    private val gateway: WorkGateway,
    private val store: SchedulerStore,
    private val deletion: DeletionMarker,
    private val runner: JitaiRunner,
    private val signals: DeviceSignals,
    private val clock: AgentleClock,
) : ReconcileRequester {
    private val timerLock = Mutex()
    private val oneTimeLock = Mutex()

    // ------------------------------------------------------------------ periodic

    /** Enqueues every periodic work that is missing (KEEP): idempotent, any number of times. */
    public suspend fun reconcilePeriodic(userProfile: CollectionProfile) {
        val profile = effectiveProfile(userProfile)
        store.putString(KEY_APPLIED_PROFILE, profile.name)
        Cadences.periodic(profile).forEach { enqueuePeriodic(it, ExistingPeriodicWorkPolicy.KEEP) }
    }

    /**
     * The user changed the profile, or a periodic run re-checked Battery Saver: when the effective profile differs from
     * the applied one, every periodic work is updated in place (UPDATE keeps the enqueue time and never interrupts a
     * running worker). Returns the effective profile.
     */
    public suspend fun onProfileChanged(userProfile: CollectionProfile): CollectionProfile {
        val profile = effectiveProfile(userProfile)
        if (store.getString(KEY_APPLIED_PROFILE) != profile.name) {
            store.putString(KEY_APPLIED_PROFILE, profile.name)
            Cadences.periodic(profile).forEach { enqueuePeriodic(it, ExistingPeriodicWorkPolicy.UPDATE) }
        }
        return profile
    }

    /**
     * Battery Saver on for 30 minutes drops to LOW; the user's profile returns after Saver has been off for 30 minutes
     * (R02 §2.4, lifecycle-battery-11). The user's choice is never overwritten: only the applied profile changes.
     */
    public fun effectiveProfile(userProfile: CollectionProfile): CollectionProfile {
        val now = clock.now().toEpochMilliseconds()
        val degraded = store.getString(KEY_APPLIED_PROFILE) == DEGRADED && store.getLong(KEY_SAVER_ON) != null
        return if (signals.isPowerSaveMode()) {
            store.putLong(KEY_SAVER_OFF, null)
            val since = store.getLong(KEY_SAVER_ON) ?: now.also { store.putLong(KEY_SAVER_ON, it) }
            if (now - since >= SAVER_DEBOUNCE.inWholeMilliseconds || degraded) CollectionProfile.LOW else userProfile
        } else {
            if (!degraded) {
                store.putLong(KEY_SAVER_ON, null)
                return userProfile
            }
            val off = store.getLong(KEY_SAVER_OFF) ?: now.also { store.putLong(KEY_SAVER_OFF, it) }
            if (now - off >= SAVER_DEBOUNCE.inWholeMilliseconds) {
                store.putLong(KEY_SAVER_ON, null)
                store.putLong(KEY_SAVER_OFF, null)
                userProfile
            } else {
                CollectionProfile.LOW
            }
        }
    }

    private suspend fun enqueuePeriodic(spec: PeriodicSpec, policy: ExistingPeriodicWorkPolicy) {
        if (blocked()) return
        gateway.enqueuePeriodic(spec.name, policy, Requests.periodic(spec))
    }

    // ------------------------------------------------------------------ sync

    /** "Sync now" (user action, prefetch, staleness retry): an explicit action also resets the sync circuit breakers. */
    public suspend fun syncWearableNow(streams: Set<String> = emptySet(), userInitiated: Boolean = false) {
        if (blocked()) return
        if (userInitiated) {
            store.resetFailures(WorkNames.SYNC_GOOGLEHEALTH)
            store.resetFailures(WorkNames.SYNC_GOOGLEHEALTH_NOW)
            store.putLong(KEY_REAUTH_NOTIFIED, null)
        }
        store.addSyncStreams(streams)
        enqueueCoalesced(WorkNames.SYNC_GOOGLEHEALTH_NOW, NetworkType.CONNECTED)
    }

    /** The wearable was disconnected: its sync work is cancelled (data is never deleted). */
    public suspend fun onWearableDisconnected() {
        gateway.cancelUnique(WorkNames.SYNC_GOOGLEHEALTH)
        gateway.cancelUnique(WorkNames.SYNC_GOOGLEHEALTH_NOW)
    }

    /** The wearable was (re)connected: periodic sync back with KEEP, breaker and reconnect notice cleared. */
    public suspend fun onWearableConnected(userProfile: CollectionProfile) {
        store.resetFailures(WorkNames.SYNC_GOOGLEHEALTH)
        store.resetFailures(WorkNames.SYNC_GOOGLEHEALTH_NOW)
        store.putLong(KEY_REAUTH_NOTIFIED, null)
        val spec = Cadences.periodic(effectiveProfile(userProfile)).first { it.name == WorkNames.SYNC_GOOGLEHEALTH }
        enqueuePeriodic(spec, ExistingPeriodicWorkPolicy.KEEP)
    }

    // ------------------------------------------------------------------ JITAI

    /**
     * A trigger-relevant event was ingested (the data layer already set the dirty flag in the same transaction). KEEP
     * makes a burst one work; if the work is already running, one follow-up is appended so an event that lands after
     * the engine's last dirty check is not left to the backstop (database-sync-04).
     */
    public suspend fun onTriggerEventIngested() {
        if (blocked()) return
        enqueueCoalesced(WorkNames.JITAI_EVAL_EVENTS, NetworkType.NOT_REQUIRED, capped = true)
    }

    /**
     * Daily cap (R02 §2.3 jitai.check 12/48/96), keyed on the local date: above it the dirty flag waits for the timer
     * backstop. Called under [oneTimeLock] with the enqueue decision; returns false when the cap is reached.
     */
    private fun admitCapped(): Boolean {
        val day = clock.now().toLocalDateTime(clock.zone()).date.toEpochDays().toLong()
        if (store.getLong(KEY_EVENTS_DAY) != day) {
            store.putLong(KEY_EVENTS_DAY, day)
            store.putLong(KEY_EVENTS_COUNT, 0)
        }
        val count = store.getLong(KEY_EVENTS_COUNT) ?: 0
        if (count >= eventsCap()) return false
        store.putLong(KEY_EVENTS_COUNT, count + 1)
        return true
    }

    private fun eventsCap(): Int = when (
        store.getString(KEY_APPLIED_PROFILE)?.let {
            runCatching { CollectionProfile.valueOf(it) }.getOrNull()
        }
    ) {
        CollectionProfile.LOW -> EVENTS_CAP_LOW
        CollectionProfile.HIGH -> EVENTS_CAP_HIGH
        else -> EVENTS_CAP_BALANCED
    }

    /** Called by the running events worker when the engine reports a follow-up (KEEP would be a no-op while RUNNING). */
    internal suspend fun appendEventsFollowUp() {
        if (blocked()) return
        oneTimeLock.withLock {
            val infos = gateway.infos(WorkNames.JITAI_EVAL_EVENTS)
            if (infos.any { it.state == WorkInfo.State.BLOCKED || it.state == WorkInfo.State.ENQUEUED }) return
            gateway.enqueueOneTime(
                WorkNames.JITAI_EVAL_EVENTS,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                Requests.oneTime(WorkNames.JITAI_EVAL_EVENTS, expedited = true),
            )
        }
    }

    /** Returns true when a new request was enqueued (not coalesced into a pending one). */
    private suspend fun enqueueCoalesced(name: String, network: NetworkType, capped: Boolean = false): Boolean = oneTimeLock.withLock {
        val infos = gateway.infos(name)
        val pending = infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        val running = infos.any { it.state == WorkInfo.State.RUNNING }
        when {
            pending -> return@withLock false

            capped && !admitCapped() -> return@withLock false

            running -> gateway.enqueueOneTime(
                name,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                Requests.oneTime(name, expedited = true, network = network),
            )

            else -> gateway.enqueueOneTime(name, ExistingWorkPolicy.KEEP, Requests.oneTime(name, expedited = true, network = network))
        }
        true
    }

    /**
     * Re-arms `jitai-timer` from outside (replan, definition change, event pass, response). Reads the latest
     * `nextDueAt` under [timerLock] and never cancels a running pass:
     * - nothing pending, or only an ENQUEUED (not running) request: REPLACE with the new delay;
     * - RUNNING with its BLOCKED re-arm already appended: that request is updated in place (same id, new delay);
     * - RUNNING without a re-arm yet: nothing; the running worker re-arms at its end under the same lock and reads
     *   a plan that already includes this caller's change.
     * No due row: the pending request is cancelled only when nothing runs (no exact alarm, no per-rule work).
     */
    public suspend fun armJitaiTimer(): Unit = timerLock.withLock {
        if (blocked()) return
        val due = runner.nextDueAt().getOrNull()
        val infos = gateway.infos(WorkNames.JITAI_TIMER)
        val running = infos.any { it.state == WorkInfo.State.RUNNING }
        val blockedChild = infos.firstOrNull { it.state == WorkInfo.State.BLOCKED }
        when {
            running && blockedChild != null && due != null -> gateway.update(timerRequest(due, blockedChild.id))
            running -> Unit
            due == null -> if (infos.any { it.state == WorkInfo.State.ENQUEUED }) gateway.cancelUnique(WorkNames.JITAI_TIMER)
            else -> gateway.enqueueOneTime(WorkNames.JITAI_TIMER, ExistingWorkPolicy.REPLACE, timerRequest(due, null))
        }
    }

    /** The running timer re-arms itself as its last step: a child of itself (APPEND_OR_REPLACE), never REPLACE. */
    internal suspend fun armFromRunningTimer(): Unit = timerLock.withLock {
        if (blocked()) return
        val due = runner.nextDueAt().getOrNull() ?: return
        // A retried attempt already appended its child: update that one, never append a second.
        val child = gateway.infos(WorkNames.JITAI_TIMER).firstOrNull { it.state == WorkInfo.State.BLOCKED }
        if (child != null) {
            gateway.update(timerRequest(due, child.id))
        } else {
            gateway.enqueueOneTime(WorkNames.JITAI_TIMER, ExistingWorkPolicy.APPEND_OR_REPLACE, timerRequest(due, null))
        }
    }

    private fun timerRequest(due: kotlin.time.Instant, id: java.util.UUID?): OneTimeWorkRequest {
        val delay = (due - clock.now()).coerceAtLeast(Duration.ZERO)
        return Requests.oneTime(WorkNames.JITAI_TIMER, delay = delay, expedited = false, id = id)
    }

    // ------------------------------------------------------------------ reconcile

    /** Records [reasons] and enqueues `reconcile`; debounced reasons coalesce, BOOT and PACKAGE_REPLACED do not. */
    override suspend fun requestReconcile(reasons: Set<ReconcileReason>) {
        if (blocked() || reasons.isEmpty()) return
        store.addReconcileReasons(reasons)
        oneTimeLock.withLock {
            val infos = gateway.infos(WorkNames.RECONCILE)
            val running = infos.any { it.state == WorkInfo.State.RUNNING }
            val pendingInfo = infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            val pending = pendingInfo != null
            val debounced = reasons.all { it.debounced }
            val delay = if (debounced) Cadences.RECONCILE_DEBOUNCE else Duration.ZERO
            val request = Requests.oneTime(WorkNames.RECONCILE, delay = delay, expedited = !debounced)
            when {
                // An urgent reason must not wait behind a debounced child: same id, zero delay.
                running && pendingInfo != null && !debounced ->
                    gateway.update(Requests.oneTime(WorkNames.RECONCILE, expedited = false, id = pendingInfo.id))

                running && pending -> Unit

                running -> gateway.enqueueOneTime(WorkNames.RECONCILE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)

                debounced -> gateway.enqueueOneTime(WorkNames.RECONCILE, ExistingWorkPolicy.KEEP, request)

                else -> gateway.enqueueOneTime(WorkNames.RECONCILE, ExistingWorkPolicy.REPLACE, request)
            }
        }
    }

    /**
     * Process start (call from `Application.onCreate` off the main thread): a cheap comparison with the last
     * reconciled boot count, app version and zone; enqueues `reconcile` only when something changed or the last one is
     * older than [SELF_HEAL] (lifecycle-battery-10).
     */
    public suspend fun onProcessStart() {
        if (blocked()) return
        val reasons = mutableSetOf<ReconcileReason>()
        val boot = signals.bootCount().toLong()
        val version = signals.appVersionCode()
        val zone = clock.zone().id
        if (store.getLong(KEY_BOOT) != boot) reasons += ReconcileReason.BOOT
        if (store.getLong(KEY_VERSION) != version) reasons += ReconcileReason.PACKAGE_REPLACED
        if (store.getString(KEY_ZONE) != zone) reasons += ReconcileReason.TIMEZONE
        val last = store.getLong(KEY_LAST_RECONCILE)
        if (last == null ||
            clock.now().toEpochMilliseconds() - last > SELF_HEAL.inWholeMilliseconds
        ) {
            reasons += ReconcileReason.PROCESS_START
        }
        if (reasons.isNotEmpty()) requestReconcile(reasons)
    }

    /** Called by the reconcile worker after a successful run. */
    internal fun recordReconciled() {
        store.putLong(KEY_BOOT, signals.bootCount().toLong())
        store.putLong(KEY_VERSION, signals.appVersionCode())
        store.putString(KEY_ZONE, clock.zone().id)
        store.putLong(KEY_LAST_RECONCILE, clock.now().toEpochMilliseconds())
    }

    // ------------------------------------------------------------------ delete everything

    /** "Delete everything" step 2: cancel all work. The marker (already written) keeps every later enqueue out. */
    public suspend fun stopEverything() {
        gateway.cancelAll()
    }

    private fun blocked(): Boolean = deletion.isDeletionInProgress()

    internal companion object {
        const val KEY_APPLIED_PROFILE = "profile.applied"
        const val KEY_SAVER_ON = "saver.on_since"
        const val KEY_SAVER_OFF = "saver.off_since"
        const val KEY_REAUTH_NOTIFIED = "wearable.reauth_notified"
        const val KEY_BOOT = "record.boot"
        const val KEY_VERSION = "record.version"
        const val KEY_ZONE = "record.zone"
        const val KEY_LAST_RECONCILE = "record.last_reconcile"
        val DEGRADED = CollectionProfile.LOW.name
        val SAVER_DEBOUNCE = 30.minutes
        val SELF_HEAL = 24.hours
        const val KEY_EVENTS_DAY = "events.day"
        const val KEY_EVENTS_COUNT = "events.count"
        const val EVENTS_CAP_LOW = 12
        const val EVENTS_CAP_BALANCED = 48
        const val EVENTS_CAP_HIGH = 96
    }
}
