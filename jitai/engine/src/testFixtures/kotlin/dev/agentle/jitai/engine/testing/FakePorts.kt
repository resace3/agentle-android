package dev.agentle.jitai.engine.testing

import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureResolver
import dev.agentle.analytics.features.FeatureSnapshot
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiEventType
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.engine.content.RenderedIntervention
import dev.agentle.jitai.engine.ports.AiTextPoolPort
import dev.agentle.jitai.engine.ports.ChangeTransition
import dev.agentle.jitai.engine.ports.DefinitionState
import dev.agentle.jitai.engine.ports.DeliveryPort
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
import kotlin.random.Random
import kotlin.time.Instant

/**
 * In-memory `jitai_definition` ([JitaiRepositoryPort]) with the lifecycle effects the engine triggers: expiry and the
 * backoff pause. [definitionState] is what the store's transaction re-reads for G01/G02.
 */
public class InMemoryJitaiRepository(definitions: List<JitaiDefinition> = emptyList()) : JitaiRepositoryPort {
    private val byId = LinkedHashMap<String, JitaiDefinition>()

    /** JITAIs paused for the in-app backoff question, with the run that caused it. */
    public val backoffQuestions: MutableMap<String, Int> = linkedMapOf()

    /** Operations that fail once (`"definitions"`, `"expire"`, `"pause"`). */
    public val failNext: MutableSet<String> = mutableSetOf()

    init {
        definitions.forEach { put(it) }
    }

    public fun put(definition: JitaiDefinition) {
        byId[definition.id] = definition
    }

    public fun update(jitaiId: String, transform: (JitaiDefinition) -> JitaiDefinition) {
        byId[jitaiId] = transform(byId.getValue(jitaiId))
    }

    public fun remove(jitaiId: String) {
        byId.remove(jitaiId)
    }

    public operator fun get(jitaiId: String): JitaiDefinition? = byId[jitaiId]

    public fun definitionState(jitaiId: String): DefinitionState? =
        byId[jitaiId]?.let { DefinitionState(it.version, it.enabled, it.status, it.expiresAt) }

    override suspend fun definitions(): Outcome<List<JitaiDefinition>> {
        if (failNext.remove("definitions")) return Outcome.failure(AppError.DatabaseError("injected"))
        return Outcome.success(byId.values.filter { it.status != JitaiStatus.ARCHIVED })
    }

    override suspend fun markExpired(jitaiId: String, at: Instant): Outcome<Unit> {
        if (failNext.remove("expire")) return Outcome.failure(AppError.DatabaseError("injected"))
        byId[jitaiId]?.let { byId[jitaiId] = it.copy(status = JitaiStatus.EXPIRED, modifiedAt = at) }
        return Outcome.success(Unit)
    }

    override suspend fun pauseForBackoff(jitaiId: String, consecutiveIgnored: Int, at: Instant): Outcome<Unit> {
        if (failNext.remove("pause")) return Outcome.failure(AppError.DatabaseError("injected"))
        byId[jitaiId]?.let { byId[jitaiId] = it.copy(status = JitaiStatus.PAUSED, modifiedAt = at) }
        backoffQuestions[jitaiId] = consecutiveIgnored
        return Outcome.success(Unit)
    }
}

/**
 * The notification shade as seen through [DeliveryPort]: active notifications by tag (posting an active tag updates
 * it and does not alert again, like `setOnlyAlertOnce`), dismissal, blocked notifications, TTS/media availability and
 * process death right after posting.
 */
public class FakeDeliveryPort : DeliveryPort {
    private val active = LinkedHashMap<String, RenderedIntervention>()

    /** Every post call in order. */
    public val posts: MutableList<RenderedIntervention> = mutableListOf()

    /** Alerts the user noticed: a post of a tag that was not active. */
    public var alerts: Int = 0
        private set

    public val prepared: MutableList<RenderedIntervention> = mutableListOf()
    public val discarded: MutableList<RenderedIntervention> = mutableListOf()

    public var blocked: Boolean = false
    public var failCode: String? = null
    public var ttsAvailable: Boolean = true
    public var mediaAvailable: Boolean = true
    public var crashAfterPost: Boolean = false
    public var throwOnPost: Boolean = false
    public var isActiveFails: Boolean = false

    public fun activeTags(): Set<String> = active.keys.toSet()

    public fun activeNotification(tag: String): RenderedIntervention? = active[tag]

    /** The user swipes the notification away. */
    public fun dismiss(tag: String) {
        active.remove(tag)
    }

    override suspend fun prepare(intervention: RenderedIntervention): PrepareResult {
        yield()
        val available = when (intervention.channel) {
            DeliveryChannel.VOICE -> ttsAvailable
            DeliveryChannel.IMAGE, DeliveryChannel.VIDEO -> mediaAvailable
            else -> true
        }
        if (!available) return PrepareResult.Unavailable("unavailable_${intervention.channel.name.lowercase()}")
        prepared += intervention
        return PrepareResult.Ready(
            PreparedDelivery(intervention, mediaRef = if (intervention.speak) "tts:${intervention.decisionKey}" else null),
        )
    }

    override suspend fun post(prepared: PreparedDelivery): PostResult {
        yield()
        if (throwOnPost) throw IllegalStateException("post failed")
        if (blocked) return PostResult.Blocked
        failCode?.let { return PostResult.Failed(it) }
        val intervention = prepared.intervention
        posts += intervention
        if (intervention.notificationTag !in active) alerts++
        active[intervention.notificationTag] = intervention
        if (crashAfterPost) {
            crashAfterPost = false
            throw SimulatedCrash(CrashPoint.AFTER_POST)
        }
        return PostResult.Posted
    }

    override suspend fun isActive(tag: String): Outcome<Boolean> {
        if (isActiveFails) return Outcome.failure(AppError.Unexpected("injected"))
        return Outcome.success(tag in active)
    }

    override suspend fun discard(prepared: PreparedDelivery) {
        discarded += prepared.intervention
    }
}

/** Settings and live notification state; [salt] is the install salt of R10 §15.3. */
public class FakeSettingsPort(
    public var settings: EngineSettings = EngineSettings(),
    public var notifications: NotificationSystemState = NotificationSystemState(),
    public var salt: ByteArray = ByteArray(SALT_BYTES) { it.toByte() },
) : SettingsPort {
    public val failNext: MutableSet<String> = mutableSetOf()

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
 * evaluation instant through [provide]. [resolutions] records every call, to check that a pass resolves once.
 */
public class FakeFeatureResolver(
    private val zone: () -> TimeZone,
    public var rolloverMinute: Int = EngineDays.DEFAULT_ROLLOVER_MINUTE,
    public var weekend: Set<DayOfWeek> = CalendarFeatures.DEFAULT_WEEKEND,
) : FeatureResolver {
    private val values = LinkedHashMap<String, FeatureValue>()
    private val providers = LinkedHashMap<String, (Instant) -> FeatureValue>()

    public val resolutions: MutableList<Set<FeatureRef>> = mutableListOf()
    public var throwOnResolve: Boolean = false

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
        resolutions += refs
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
 * The trigger-event table: [emit] assigns the next `change_seq` (also for semantic updates and tombstones, never the
 * insert-only rowid) and runs [onIngest] like the ingest transaction that sets the dirty flag.
 */
public class InMemoryTriggerEventFeed(private val onIngest: suspend () -> Unit = {}) : TriggerEventFeed {
    private val events = mutableListOf<TriggerEvent>()
    private var sequence = 0L

    public var failNext: Boolean = false

    public fun all(): List<TriggerEvent> = events.toList()

    public suspend fun emit(
        type: JitaiEventType,
        eventAt: Instant,
        origin: EventOrigin = EventOrigin.LIVE,
        transition: ChangeTransition = ChangeTransition.INSERTED,
        activityState: String? = null,
        stamp: MonotonicStamp? = null,
    ): TriggerEvent {
        sequence += 1
        val event = TriggerEvent(sequence, type, eventAt, origin, transition, activityState, stamp)
        events += event
        onIngest()
        return event
    }

    override suspend fun eventsAfter(changeSeq: Long, limit: Int): Outcome<List<TriggerEvent>> {
        yield()
        if (failNext) {
            failNext = false
            return Outcome.failure(AppError.DatabaseError("injected"))
        }
        return Outcome.success(events.filter { it.changeSeq > changeSeq }.sortedBy { it.changeSeq }.take(limit))
    }
}

/** The pooled `ai_text` items; a used item leaves the pool (it is never shown twice). */
public class InMemoryAiTextPool(items: List<PooledText> = emptyList()) : AiTextPoolPort {
    private val pool = LinkedHashMap<String, PooledText>()

    /** `itemId -> decisionKey` for every item used. */
    public val used: MutableMap<String, String> = linkedMapOf()

    init {
        items.forEach { add(it) }
    }

    public fun add(item: PooledText) {
        pool[item.id] = item
    }

    override suspend fun pooled(jitaiId: String): Outcome<List<PooledText>> = Outcome.success(pool.values.filter { it.jitaiId == jitaiId })

    override suspend fun get(itemId: String): Outcome<PooledText?> = Outcome.success(pool[itemId])

    override suspend fun markUsed(itemId: String, decisionKey: String): Outcome<Unit> {
        pool.remove(itemId)
        used[itemId] = decisionKey
        return Outcome.success(Unit)
    }
}

/** Deterministic nonces: 32 hex characters from a seeded generator. */
public class SeededNonceSource(seed: Long = 7L) : NonceSource {
    private val random = Random(seed)

    override fun nextNonce(): String = (1..NONCE_BYTES).joinToString("") { "%02x".format(random.nextInt(BYTE_VALUES)) }

    private companion object {
        const val NONCE_BYTES = 16
        const val BYTE_VALUES = 256
    }
}
