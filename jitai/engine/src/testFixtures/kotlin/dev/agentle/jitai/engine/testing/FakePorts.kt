package dev.agentle.jitai.engine.testing

import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureResolver
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureSnapshot
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.time.ClosedOpenRange
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiEventType
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.OutcomeMetric
import dev.agentle.jitai.dsl.model.OutcomeMetricRef
import dev.agentle.jitai.engine.content.RenderedIntervention
import dev.agentle.jitai.engine.outcome.OutcomeDataPort
import dev.agentle.jitai.engine.outcome.OutcomeResult
import dev.agentle.jitai.engine.ports.AiTextPoolPort
import dev.agentle.jitai.engine.ports.ChangeTransition
import dev.agentle.jitai.engine.ports.DailyFeatureRefresher
import dev.agentle.jitai.engine.ports.DefinitionState
import dev.agentle.jitai.engine.ports.DeliveryPort
import dev.agentle.jitai.engine.ports.DeliveryPrerequisite
import dev.agentle.jitai.engine.ports.EngineSettings
import dev.agentle.jitai.engine.ports.EventOrigin
import dev.agentle.jitai.engine.ports.JitaiRepositoryPort
import dev.agentle.jitai.engine.ports.NonceSource
import dev.agentle.jitai.engine.ports.NotificationSystemState
import dev.agentle.jitai.engine.ports.PooledText
import dev.agentle.jitai.engine.ports.PostResult
import dev.agentle.jitai.engine.ports.PrepareResult
import dev.agentle.jitai.engine.ports.PreparedDelivery
import dev.agentle.jitai.engine.ports.SettingsPort
import dev.agentle.jitai.engine.ports.TriggerEvent
import dev.agentle.jitai.engine.ports.TriggerEventFeed
import dev.agentle.jitai.engine.time.CalendarFeatures
import dev.agentle.jitai.engine.time.EngineDays
import dev.agentle.jitai.engine.time.MonotonicStamp
import kotlinx.coroutines.yield
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.time.Instant

/**
 * In-memory `jitai_definition` ([JitaiRepositoryPort]) with the lifecycle effects of the real one: every change runs
 * [onChange] (the harness deletes the JITAI's timer rows there, like the change's own transaction does), and the engine's
 * expiry and backoff pause change the status. [definitionState] is what the store's transaction re-reads for G01/G02.
 * Safe on real threads.
 */
public class InMemoryJitaiRepository(
    definitions: List<JitaiDefinition> = emptyList(),
    private val onChange: suspend (String) -> Unit = {},
) : JitaiRepositoryPort {
    private val byId = LinkedHashMap<String, JitaiDefinition>()

    /** JITAIs paused for the in-app backoff question, with the run that caused it. */
    public val backoffQuestions: MutableMap<String, Int> = Collections.synchronizedMap(linkedMapOf())

    /** Operations that fail once (`"definitions"`, `"expire"`, `"pause"`). */
    public val failNext: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    init {
        definitions.forEach { byId[it.id] = it }
    }

    /** Saves [definition] (a new JITAI or a new version). */
    public suspend fun put(definition: JitaiDefinition) {
        synchronized(byId) { byId[definition.id] = definition }
        onChange(definition.id)
    }

    /** Changes the stored definition of [jitaiId] (an edit, enable, disable or status change). */
    public suspend fun update(jitaiId: String, transform: (JitaiDefinition) -> JitaiDefinition) {
        synchronized(byId) { byId[jitaiId] = transform(byId.getValue(jitaiId)) }
        onChange(jitaiId)
    }

    public suspend fun remove(jitaiId: String) {
        synchronized(byId) { byId.remove(jitaiId) }
        onChange(jitaiId)
    }

    public operator fun get(jitaiId: String): JitaiDefinition? = synchronized(byId) { byId[jitaiId] }

    public fun definitionState(jitaiId: String): DefinitionState? =
        get(jitaiId)?.let { DefinitionState(it.version, it.enabled, it.status, it.expiresAt) }

    override suspend fun definitions(): Outcome<List<JitaiDefinition>> {
        if (failNext.remove("definitions")) return Outcome.failure(AppError.DatabaseError("injected"))
        return Outcome.success(synchronized(byId) { byId.values.filter { it.status != JitaiStatus.ARCHIVED } })
    }

    override suspend fun markExpired(jitaiId: String, at: Instant): Outcome<Unit> {
        if (failNext.remove("expire")) return Outcome.failure(AppError.DatabaseError("injected"))
        val changed = synchronized(byId) {
            byId[jitaiId]?.let { byId[jitaiId] = it.copy(status = JitaiStatus.EXPIRED, modifiedAt = at) } != null
        }
        if (changed) onChange(jitaiId)
        return Outcome.success(Unit)
    }

    override suspend fun pauseForBackoff(jitaiId: String, consecutiveIgnored: Int, at: Instant): Outcome<Unit> {
        if (failNext.remove("pause")) return Outcome.failure(AppError.DatabaseError("injected"))
        val changed = synchronized(byId) {
            byId[jitaiId]?.let { byId[jitaiId] = it.copy(status = JitaiStatus.PAUSED, modifiedAt = at) } != null
        }
        backoffQuestions[jitaiId] = consecutiveIgnored
        if (changed) onChange(jitaiId)
        return Outcome.success(Unit)
    }
}

/**
 * The notification shade as seen through [DeliveryPort]: active notifications by tag (posting an active tag updates it
 * and does not alert again, like `setOnlyAlertOnce`), dismissal, the delivery prerequisite per category, blocked posts,
 * TTS/media availability and process death while rendering or right after posting ([death]). Safe on real threads.
 */
public class FakeDeliveryPort(private val death: ProcessDeath = ProcessDeath()) : DeliveryPort {
    private val lock = Any()
    private val active = LinkedHashMap<String, RenderedIntervention>()
    private val postLog = mutableListOf<RenderedIntervention>()
    private val prepareLog = mutableListOf<RenderedIntervention>()
    private val discardLog = mutableListOf<RenderedIntervention>()
    private val prerequisites = ConcurrentHashMap<JitaiCategory, DeliveryPrerequisite>()
    private var alertCount = 0

    /** The prerequisite of categories without their own entry. */
    @Volatile
    public var defaultPrerequisite: DeliveryPrerequisite = DeliveryPrerequisite.MET

    /** [prerequisite] fails (an unreadable platform state). */
    @Volatile
    public var prerequisiteFails: Boolean = false

    /** [post] reports the prerequisite failed at the post itself. */
    @Volatile
    public var blocked: Boolean = false

    @Volatile
    public var failCode: String? = null

    @Volatile
    public var ttsAvailable: Boolean = true

    @Volatile
    public var mediaAvailable: Boolean = true

    @Volatile
    public var throwOnPost: Boolean = false

    @Volatile
    public var isActiveFails: Boolean = false

    /** [isActive] throws instead of answering (a crashing platform call). */
    @Volatile
    public var isActiveThrows: Boolean = false

    /** Runs at the start of every [prepare] (for example to read the row state before the claim). */
    @Volatile
    public var onPrepare: (suspend (RenderedIntervention) -> Unit)? = null

    /** Runs at the start of every [post], after the claim (for example to race another worker or cancel this one). */
    @Volatile
    public var onPost: (suspend (PreparedDelivery) -> Unit)? = null

    /** Every post call that reached the shade, in order. */
    public val posts: List<RenderedIntervention> get() = synchronized(lock) { postLog.toList() }

    public val prepared: List<RenderedIntervention> get() = synchronized(lock) { prepareLog.toList() }

    public val discarded: List<RenderedIntervention> get() = synchronized(lock) { discardLog.toList() }

    /** Alerts the user noticed: a post of a tag that was not active. */
    public val alerts: Int get() = synchronized(lock) { alertCount }

    public fun activeTags(): Set<String> = synchronized(lock) { active.keys.toSet() }

    public fun activeNotification(tag: String): RenderedIntervention? = synchronized(lock) { active[tag] }

    /** The user swipes the notification away. */
    public fun dismiss(tag: String) {
        synchronized(lock) { active.remove(tag) }
    }

    /** Sets the prerequisite of [category]. */
    public fun setPrerequisite(category: JitaiCategory, prerequisite: DeliveryPrerequisite) {
        prerequisites[category] = prerequisite
    }

    override suspend fun prerequisite(category: JitaiCategory): Outcome<DeliveryPrerequisite> {
        death.check()
        if (prerequisiteFails) return Outcome.failure(AppError.Unexpected("injected"))
        return Outcome.success(prerequisites[category] ?: defaultPrerequisite)
    }

    override suspend fun prepare(intervention: RenderedIntervention): PrepareResult {
        death.check()
        yield()
        onPrepare?.invoke(intervention)
        death.fireIf(CrashPoint.DURING_RENDER)
        val available = when (intervention.channel) {
            DeliveryChannel.VOICE -> ttsAvailable
            DeliveryChannel.IMAGE, DeliveryChannel.VIDEO -> mediaAvailable
            else -> true
        }
        if (!available) return PrepareResult.Unavailable("unavailable_${intervention.channel.name.lowercase()}")
        synchronized(lock) { prepareLog += intervention }
        return PrepareResult.Ready(
            PreparedDelivery(intervention, mediaRef = if (intervention.speak) "tts:${intervention.decisionKey}" else null),
        )
    }

    override suspend fun post(prepared: PreparedDelivery): PostResult {
        death.check()
        yield()
        onPost?.invoke(prepared)
        death.check()
        check(!throwOnPost) { "post failed" }
        if (blocked) return PostResult.Blocked
        failCode?.let { return PostResult.Failed(it) }
        val intervention = prepared.intervention
        synchronized(lock) {
            postLog += intervention
            if (intervention.notificationTag !in active) alertCount++
            active[intervention.notificationTag] = intervention
        }
        death.fireIf(CrashPoint.AFTER_POST)
        return PostResult.Posted
    }

    override suspend fun isActive(tag: String): Outcome<Boolean> {
        death.check()
        check(!isActiveThrows) { "active notifications unavailable" }
        if (isActiveFails) return Outcome.failure(AppError.Unexpected("injected"))
        return Outcome.success(synchronized(lock) { tag in active })
    }

    override suspend fun discard(prepared: PreparedDelivery) {
        death.check()
        synchronized(lock) { discardLog += prepared.intervention }
    }
}

/** Settings and live notification state; [salt] is the install salt of R10 §15.3. */
public class FakeSettingsPort(
    settings: EngineSettings = EngineSettings(),
    notifications: NotificationSystemState = NotificationSystemState(),
    salt: ByteArray = ByteArray(SALT_BYTES) { it.toByte() },
) : SettingsPort {
    @Volatile
    public var settings: EngineSettings = settings

    @Volatile
    public var notifications: NotificationSystemState = notifications

    @Volatile
    public var salt: ByteArray = salt

    /** Operations that fail once (`"settings"`, `"notifications"`, `"salt"`). */
    public val failNext: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    override suspend fun engineSettings(): Outcome<EngineSettings> =
        if (failNext.remove("settings")) Outcome.failure(AppError.DatabaseError("injected")) else Outcome.success(settings)

    override suspend fun notificationState(): Outcome<NotificationSystemState> =
        if (failNext.remove("notifications")) Outcome.failure(AppError.Unexpected("injected")) else Outcome.success(notifications)

    override suspend fun installSalt(): Outcome<ByteArray> =
        if (failNext.remove("salt")) Outcome.failure(AppError.DatabaseError("injected")) else Outcome.success(salt.copyOf())

    private companion object {
        const val SALT_BYTES = 16
    }
}

/**
 * A [FeatureResolver] backed by a table of values. Calendar features are computed from the instant and the current
 * zone (R10 §5.4 A); any other unset reference is `Missing(NO_DATA)` (absence is never zero). Values may depend on the
 * evaluation instant through [provide]. [resolutions] records every call, to check that a pass resolves once. Safe on
 * real threads.
 */
public class FakeFeatureResolver(
    private val zone: () -> TimeZone,
    rolloverMinute: Int = EngineDays.DEFAULT_ROLLOVER_MINUTE,
    weekend: Set<DayOfWeek> = CalendarFeatures.DEFAULT_WEEKEND,
) : FeatureResolver {
    private val values = ConcurrentHashMap<String, FeatureValue>()
    private val providers = ConcurrentHashMap<String, (Instant) -> FeatureValue>()
    private val calls: MutableList<Set<FeatureRef>> = Collections.synchronizedList(mutableListOf())

    @Volatile
    public var rolloverMinute: Int = rolloverMinute

    @Volatile
    public var weekend: Set<DayOfWeek> = weekend

    @Volatile
    public var throwOnResolve: Boolean = false

    /** Runs inside every resolution, i.e. between a pass's evaluation start and its commit (R10 §12.M1). */
    @Volatile
    public var onResolve: (suspend () -> Unit)? = null

    /** The references of every resolution, in order. */
    public val resolutions: List<Set<FeatureRef>> get() = synchronized(calls) { calls.toList() }

    public fun set(ref: FeatureRef, value: FeatureValue) {
        providers.remove(ref.key)
        values[ref.key] = value
    }

    public fun set(featureId: String, value: FeatureValue): Unit = set(FeatureRef(featureId), value)

    public fun provide(ref: FeatureRef, provider: (Instant) -> FeatureValue) {
        values.remove(ref.key)
        providers[ref.key] = provider
    }

    public fun clear(ref: FeatureRef) {
        values.remove(ref.key)
        providers.remove(ref.key)
    }

    override suspend fun resolve(refs: Set<FeatureRef>, at: Instant): FeatureSnapshot {
        yield()
        check(!throwOnResolve) { "resolver failure" }
        calls += refs
        onResolve?.invoke()
        val currentZone = zone()
        val resolved = refs.associateWith { ref ->
            values[ref.key]
                ?: providers[ref.key]?.invoke(at)
                ?: CalendarFeatures.value(ref.featureId, at, currentZone, rolloverMinute, weekend)
                ?: FeatureValue.Missing(MissingReason.NO_DATA)
        }
        return FeatureSnapshot.of(at, currentZone.id, resolved)
    }
}

/**
 * The daily features of dirty days (jitai-correctness-08): an ingest ([ingest]) marks its day dirty without changing
 * what the resolver reports; [refreshDirtyDays] recomputes the dirty days and only then the resolver reports the new
 * values. [refreshes] counts the calls.
 */
public class FakeDailyFeatures(private val resolver: FakeFeatureResolver) : DailyFeatureRefresher {
    private val pending = ConcurrentHashMap<String, FeatureValue>()

    @Volatile
    public var refreshes: Int = 0
        private set

    @Volatile
    public var fails: Boolean = false

    /** Ingested data that changes daily feature [featureId] once its day is recomputed. */
    public fun ingest(featureId: String, value: FeatureValue) {
        pending[featureId] = value
    }

    override suspend fun refreshDirtyDays(at: Instant): Outcome<Unit> {
        refreshes++
        if (fails) return Outcome.failure(AppError.DatabaseError("injected"))
        pending.keys.toList().forEach { id -> pending.remove(id)?.let { resolver.set(id, it) } }
        return Outcome.success(Unit)
    }
}

/**
 * The trigger-event table: [emit] assigns the next `change_seq` (also for semantic updates and tombstones, never the
 * insert-only rowid) and runs [onIngest] like the ingest transaction that sets the dirty flag. Safe on real threads.
 */
public class InMemoryTriggerEventFeed(private val onIngest: suspend () -> Unit = {}) : TriggerEventFeed {
    private val events = mutableListOf<TriggerEvent>()
    private var sequence = 0L

    @Volatile
    public var failNext: Boolean = false

    public fun all(): List<TriggerEvent> = synchronized(events) { events.toList() }

    public suspend fun emit(
        type: JitaiEventType,
        eventAt: Instant,
        origin: EventOrigin = EventOrigin.LIVE,
        transition: ChangeTransition = ChangeTransition.INSERTED,
        activityState: String? = null,
        stamp: MonotonicStamp? = null,
    ): TriggerEvent {
        val event = synchronized(events) {
            sequence += 1
            TriggerEvent(sequence, type, eventAt, origin, transition, activityState, stamp).also { events += it }
        }
        onIngest()
        return event
    }

    override suspend fun eventsAfter(changeSeq: Long, limit: Int): Outcome<List<TriggerEvent>> {
        yield()
        if (failNext) {
            failNext = false
            return Outcome.failure(AppError.DatabaseError("injected"))
        }
        return Outcome.success(synchronized(events) { events.filter { it.changeSeq > changeSeq }.sortedBy { it.changeSeq }.take(limit) })
    }
}

/** The pooled `ai_text` items, keyed by the rule's content hash; a used item leaves the pool (never shown twice). */
public class InMemoryAiTextPool(items: List<PooledText> = emptyList()) : AiTextPoolPort {
    private val pool = LinkedHashMap<String, PooledText>()
    private val usedItems = LinkedHashMap<String, String>()

    init {
        items.forEach { add(it) }
    }

    /** `itemId -> decisionKey` for every item used. */
    public val used: Map<String, String> get() = synchronized(pool) { LinkedHashMap(usedItems) }

    public fun items(): List<PooledText> = synchronized(pool) { pool.values.toList() }

    public fun add(item: PooledText) {
        synchronized(pool) { pool[item.id] = item }
    }

    override suspend fun pooled(contentHash: String): Outcome<List<PooledText>> =
        Outcome.success(synchronized(pool) { pool.values.filter { it.contentHash == contentHash } })

    override suspend fun get(itemId: String): Outcome<PooledText?> = Outcome.success(synchronized(pool) { pool[itemId] })

    override suspend fun markUsed(itemId: String, decisionKey: String): Outcome<Unit> {
        synchronized(pool) {
            pool.remove(itemId)
            usedItems[itemId] = decisionKey
        }
        return Outcome.success(Unit)
    }

    override suspend fun purge(jitaiId: String, keepContentHash: String?): Outcome<Int> = Outcome.success(
        synchronized(pool) {
            val gone = pool.values.filter { it.jitaiId == jitaiId && it.contentHash != keepContentHash }.map { it.id }
            gone.forEach { pool.remove(it) }
            gone.size
        },
    )

    override suspend fun purgeExpired(createdBefore: Instant): Outcome<Int> = Outcome.success(
        synchronized(pool) {
            val gone = pool.values.filter { it.createdAt < createdBefore }.map { it.id }
            gone.forEach { pool.remove(it) }
            gone.size
        },
    )
}

/**
 * Stored outcome data: [measure] answers from [values] (a metric without an entry has no data yet) and [record] keeps the
 * recorded results, idempotent by decision key and role like `jitai_outcome`.
 */
public class FakeOutcomeData : OutcomeDataPort {
    private val stored = LinkedHashMap<Pair<String, String>, OutcomeResult>()

    /** The measured value of each metric (null: the data does not cover the window yet). */
    public val values: MutableMap<OutcomeMetric, FeatureScalar?> = Collections.synchronizedMap(linkedMapOf())

    /** Every window asked for, in order. */
    public val windows: MutableList<Pair<OutcomeMetricRef, ClosedOpenRange>> = Collections.synchronizedList(mutableListOf())

    @Volatile
    public var failRecord: Boolean = false

    @Volatile
    public var failMeasure: Boolean = false

    /** The recorded outcomes in order of their first recording. */
    public val recorded: List<OutcomeResult> get() = synchronized(stored) { stored.values.toList() }

    override suspend fun measure(metric: OutcomeMetricRef, window: ClosedOpenRange, zone: TimeZone): Outcome<FeatureScalar?> {
        if (failMeasure) return Outcome.failure(AppError.DatabaseError("injected"))
        windows += metric to window
        return Outcome.success(values[metric.metric])
    }

    override suspend fun record(result: OutcomeResult): Outcome<Unit> {
        if (failRecord) return Outcome.failure(AppError.DatabaseError("injected"))
        synchronized(stored) { stored[result.decisionKey to result.role.name] = result }
        return Outcome.success(Unit)
    }
}

/** Deterministic nonces: 32 hex characters from a seeded generator. */
public class SeededNonceSource(seed: Long = 7L) : NonceSource {
    private val random = Random(seed)

    override fun nextNonce(): String =
        synchronized(random) { (1..NONCE_BYTES).joinToString("") { "%02x".format(random.nextInt(BYTE_VALUES)) } }

    private companion object {
        const val NONCE_BYTES = 16
        const val BYTE_VALUES = 256
    }
}
