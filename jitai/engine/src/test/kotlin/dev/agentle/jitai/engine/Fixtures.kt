package dev.agentle.jitai.engine

import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.model.ActiveWindow
import dev.agentle.jitai.dsl.model.ContentStrategy
import dev.agentle.jitai.dsl.model.CreatedBy
import dev.agentle.jitai.dsl.model.Delivery
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.ExperimentSpec
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiKind
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.OutcomeSpec
import dev.agentle.jitai.dsl.model.QuietHoursPolicy
import dev.agentle.jitai.dsl.model.SnoozePolicy
import dev.agentle.jitai.dsl.model.SuppressionTarget
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.dsl.model.WeekDay
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.OnUnknown
import dev.agentle.jitai.dsl.rule.RuleLiteral
import dev.agentle.jitai.engine.decision.DecisionContent
import dev.agentle.jitai.engine.decision.DecisionKeys
import dev.agentle.jitai.engine.decision.DecisionRecord
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.JitaiResponse
import dev.agentle.jitai.engine.decision.TriggerKind
import dev.agentle.jitai.engine.pipeline.TimerReport
import dev.agentle.jitai.engine.pipeline.TraceCodec
import dev.agentle.jitai.engine.ports.EngineSettings
import dev.agentle.jitai.engine.ports.QuietHours
import dev.agentle.jitai.engine.testing.EngineHarness
import dev.agentle.jitai.engine.time.EngineDays
import dev.agentle.jitai.engine.time.MonotonicStamp
import kotlinx.coroutines.test.TestScope
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * One row of the R10 §12 test matrix run as a parameterized case. Its display name is `R10 <id> <title>`, so the evidence
 * report lists every vector id with its result (testing-build-10/17).
 */
class Vector(val id: String, private val title: String, val body: suspend TestScope.() -> Unit) {
    override fun toString(): String = "R10 $id $title"
}

/** The zones every zone-sensitive suite runs in (testing-build-04); the test JVM itself defaults to America/St_Johns. */
@JvmField
val ZONES: List<String> = listOf("UTC", "America/Los_Angeles", "Asia/Kolkata", "Pacific/Chatham", "Australia/Adelaide")

/** Fixture F0 of R10 §12: Europe/Berlin, Thu 2026-10-01, quiet hours off, caps 6/30, gap 30 min, rollover 04:00. */
object F0 {
    val BERLIN: TimeZone = TimeZone.of("Europe/Berlin")
    val NEW_YORK: TimeZone = TimeZone.of("America/New_York")
    val LONDON: TimeZone = TimeZone.of("Europe/London")
    val CREATED: Instant = Instant.parse("2026-09-01T08:00:00Z")
    val SETTINGS: EngineSettings = EngineSettings(quietHours = QuietHours(enabled = false))

    /** The instant of local [text] (`2026-10-01T22:30`) in [zone]. */
    fun local(text: String, zone: TimeZone = BERLIN): Instant = LocalDateTime.parse(text).toInstant(zone)

    /**
     * A harness at [start] with [definitions], F0 settings and live state: interactive, not charging, interruption
     * filter ALL, every permission granted, notifications enabled.
     */
    fun harness(
        start: Instant,
        vararg definitions: JitaiDefinition,
        settings: EngineSettings = SETTINGS,
        zone: TimeZone = BERLIN,
    ): EngineHarness {
        val harness = EngineHarness(start, zone, definitions.toList(), settings)
        harness.features.set("device_interactive", FeatureValue.Known(FeatureScalar.BoolValue(true), start))
        harness.features.set("charging", FeatureValue.Known(FeatureScalar.BoolValue(false), start))
        return harness
    }
}

fun int(value: Long, asOf: Instant): FeatureValue = FeatureValue.Known(FeatureScalar.IntValue(value), asOf)

fun bool(value: Boolean, asOf: Instant): FeatureValue = FeatureValue.Known(FeatureScalar.BoolValue(value), asOf)

fun enumValue(value: String, asOf: Instant): FeatureValue = FeatureValue.Known(FeatureScalar.EnumValue(value), asOf)

fun missing(reason: MissingReason = MissingReason.NO_DATA): FeatureValue = FeatureValue.Missing(reason)

fun stale(value: Long, asOf: Instant, reason: MissingReason = MissingReason.NOT_SYNCED): FeatureValue =
    FeatureValue.Stale(FeatureScalar.IntValue(value), asOf, reason)

/** Leaf builders. */
object Leaves {
    fun gte(feature: String, value: Long, onUnknown: OnUnknown? = null, args: Map<String, String> = emptyMap()): Condition =
        Condition.Gte(feature, args, RuleLiteral.of(value), onUnknown)

    fun gte(feature: String, time: String): Condition = Condition.Gte(feature, value = RuleLiteral.of(time))

    fun lt(feature: String, value: Long, onUnknown: OnUnknown? = null): Condition =
        Condition.Lt(feature, value = RuleLiteral.of(value), onUnknown = onUnknown)

    fun eq(feature: String, value: String, onUnknown: OnUnknown? = null): Condition =
        Condition.Eq(feature, value = RuleLiteral.of(value), onUnknown = onUnknown)

    fun eq(feature: String, value: Boolean, onUnknown: OnUnknown? = null): Condition =
        Condition.Eq(feature, value = RuleLiteral.of(value), onUnknown = onUnknown)

    fun all(vararg of: Condition): Condition = Condition.AllOf(of.toList())

    fun any(vararg of: Condition): Condition = Condition.AnyOf(of.toList())

    fun not(of: Condition): Condition = Condition.Not(of)

    const val SCREEN = "screen_minutes_last_60m"
    const val STEPS = "steps_today"
    const val SLEEP = "sleep_minutes_last_night"
}

/** The rules of R10 §12 (ids written as `R1` etc.). */
object Rules {
    @Suppress("LongParameterList") // A fixture builder: every parameter has a default and call sites name what they set.
    fun rule(
        id: String,
        trigger: Trigger?,
        conditions: Condition? = null,
        window: ActiveWindow? = null,
        category: JitaiCategory = JitaiCategory.DIGITAL_WELLBEING,
        channel: DeliveryChannel = DeliveryChannel.NOTIFICATION,
        quietHoursPolicy: QuietHoursPolicy = QuietHoursPolicy.RESPECT,
        cooldown: Int? = 60,
        maxPerDay: Int? = 3,
        maxPerWeek: Int? = 21,
        priority: Int = 50,
        createdBy: CreatedBy = CreatedBy.USER_MANUAL,
        content: ContentStrategy? = ContentStrategy.Static("Time for a break", "You have been on your phone a while."),
        snooze: SnoozePolicy? = null,
        expiresAt: Instant? = null,
        context: Condition? = null,
        experiment: ExperimentSpec = ExperimentSpec(),
        createdAt: Instant = F0.CREATED,
        timeoutMinutes: Int? = null,
        outcome: OutcomeSpec? = null,
        version: Int = 1,
    ): JitaiDefinition = JitaiDefinition(
        id = id,
        version = version,
        name = "Rule $id",
        kind = JitaiKind.INTERVENTION,
        category = category,
        status = JitaiStatus.ACTIVE,
        enabled = true,
        trigger = trigger,
        activeWindow = window,
        conditions = conditions,
        contextRequirements = context,
        delivery = Delivery(channel = channel, quietHoursPolicy = quietHoursPolicy, notificationTimeoutMinutes = timeoutMinutes),
        content = content,
        cooldownMinutes = cooldown,
        maxPerDay = maxPerDay,
        maxPerWeek = maxPerWeek,
        priority = priority,
        snooze = snooze,
        expiresAt = expiresAt,
        createdBy = createdBy,
        createdAt = createdAt,
        modifiedAt = createdAt,
        outcome = outcome,
        experiment = experiment,
    )

    /** R1: the task's rule (interval 15, window 20:00-02:00, screen >= 45 and local_time >= 22:00). */
    val R1: JitaiDefinition = rule(
        id = "R1",
        trigger = Trigger.Interval(15),
        window = ActiveWindow("20:00", "02:00"),
        conditions = Leaves.all(Leaves.gte(Leaves.SCREEN, 45), Leaves.gte("local_time", "22:00")),
    )

    /** R2: the walk nudge (`daily_at 17:00`, lateness 30, steps_today < 3000). */
    val R2: JitaiDefinition = rule(
        id = "R2",
        trigger = Trigger.DailyAt(listOf("17:00"), maxLatenessMinutes = 30),
        conditions = Leaves.lt(Leaves.STEPS, 3000),
        category = JitaiCategory.PHYSICAL_ACTIVITY,
        maxPerDay = 1,
        maxPerWeek = 7,
        content = ContentStrategy.Template("Time for a walk", "Only {{steps_today}} steps so far today."),
    )

    /** R3: interval 30, window 22:00-02:00, screen >= 45, maxPerDay 1. */
    val R3: JitaiDefinition = rule(
        id = "R3",
        trigger = Trigger.Interval(30),
        window = ActiveWindow("22:00", "02:00"),
        conditions = Leaves.gte(Leaves.SCREEN, 45),
        maxPerDay = 1,
        maxPerWeek = 7,
    )

    /** S1: SUPPRESSION, window 22:00-23:00, no conditions, targets DIGITAL_WELLBEING. */
    val S1: JitaiDefinition =
        suppression("S1", ActiveWindow("22:00", "23:00"), null, SuppressionTarget(categories = listOf(JitaiCategory.DIGITAL_WELLBEING)))

    fun suppression(
        id: String,
        window: ActiveWindow?,
        conditions: Condition?,
        target: SuppressionTarget,
        createdBy: CreatedBy = CreatedBy.USER_MANUAL,
    ): JitaiDefinition = JitaiDefinition(
        id = id,
        name = "Suppression $id",
        kind = JitaiKind.SUPPRESSION,
        category = JitaiCategory.GENERAL,
        status = JitaiStatus.ACTIVE,
        enabled = true,
        activeWindow = window,
        conditions = conditions,
        delivery = Delivery(channel = DeliveryChannel.NONE),
        createdBy = createdBy,
        createdAt = F0.CREATED,
        modifiedAt = F0.CREATED,
        suppression = target,
    )

    fun window(start: String, end: String, vararg days: WeekDay): ActiveWindow = ActiveWindow(start, end, days.toList().ifEmpty { null })
}

/** The stamp of wall instant [at] in the current boot of the harness clock (same-boot elapsed shift). */
fun EngineHarness.stampAt(at: Instant): MonotonicStamp = clock.stamp() + (at - clock.now())

/** One run of the `jitai-timer` work at the current instant. */
suspend fun EngineHarness.timer(): TimerReport = engine.runTimer().getOrThrow()

/** Moves the clock to [at] (never back) and runs the `jitai-timer` work once. */
suspend fun EngineHarness.timerAt(at: Instant): TimerReport {
    clock.advanceTo(at)
    return timer()
}

/** The stored row of [key]; fails when there is none. */
fun EngineHarness.row(key: String): DecisionRecord = checkNotNull(store.row(key)) { "no row $key" }

/** The decoded full trace of [key]'s row. */
fun EngineHarness.trace(key: String) = checkNotNull(row(key).content.traceJson?.let(TraceCodec::decode)) { "no trace for $key" }

/** Seeds a counted row of [jitaiId] at [at] as an earlier pass would have written it. */
fun EngineHarness.seedCounted(
    jitaiId: String,
    at: Instant,
    state: DecisionState = DecisionState.DELIVERED,
    channel: DeliveryChannel = DeliveryChannel.NOTIFICATION,
    category: JitaiCategory = JitaiCategory.GENERAL,
    response: JitaiResponse = JitaiResponse.NONE,
    key: String = DecisionKeys.event(jitaiId, at),
    version: Int = 1,
): DecisionRecord {
    val stamp = stampAt(at)
    val zone = clock.zone()
    val record = DecisionRecord(
        decisionKey = key,
        jitaiId = jitaiId,
        jitaiVersion = version,
        triggerKind = DecisionKeys.kindOf(key) ?: TriggerKind.EVENT,
        category = category,
        channel = channel,
        state = state,
        decided = stamp,
        zoneId = zone.id,
        localDateTime = at.toLocalDateTime(zone),
        engineDay = EngineDays.of(at, zone),
        nominalAt = at,
        nonce = "seed-nonce",
        claimed = stamp,
        delivered = if (state == DecisionState.DELIVERED) stamp else null,
        content = DecisionContent(response = response, respondedAt = if (response == JitaiResponse.NONE) null else at),
    )
    store.seed(record)
    return row(key)
}
