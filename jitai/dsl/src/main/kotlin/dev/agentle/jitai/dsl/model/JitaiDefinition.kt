package dev.agentle.jitai.dsl.model

import dev.agentle.jitai.dsl.rule.ArgsSerializer
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.UtcInstantSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * One JITAI (R10 §2.1, §3.1): the stored, canonical form produced by validation and normalization (R10 §11.4).
 *
 * Property order is the canonical JSON order (kotlinx.serialization writes declaration order), every property is
 * written (nulls explicit, `args` as `{}` when empty), times are `HH:mm` strings and instants are UTC with `Z` and whole
 * seconds. The engine runs a JITAI only when `enabled && status == ACTIVE && !expired`.
 *
 * Construct new rules through the validator (proposals) or validate builder output with [dev.agentle.jitai.dsl.validation.RuleValidator]
 * before saving; the data class itself does not enforce the rules of R10 §11.
 */
@Serializable
public data class JitaiDefinition(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val version: Int = 1,
    val name: String,
    val description: String = "",
    val kind: JitaiKind = JitaiKind.INTERVENTION,
    val category: JitaiCategory = JitaiCategory.GENERAL,
    val status: JitaiStatus = JitaiStatus.DRAFT,
    val enabled: Boolean = false,
    val trigger: Trigger? = null,
    val activeWindow: ActiveWindow? = null,
    val conditions: Condition? = null,
    val contextRequirements: Condition? = null,
    val delivery: Delivery = Delivery(),
    val content: ContentStrategy? = null,
    val cooldownMinutes: Int? = null,
    val maxPerDay: Int? = null,
    val maxPerWeek: Int? = null,
    val priority: Int = DEFAULT_PRIORITY,
    val snooze: SnoozePolicy? = null,
    @Serializable(with = UtcInstantSerializer::class) val expiresAt: Instant? = null,
    val createdBy: CreatedBy,
    @Serializable(with = UtcInstantSerializer::class) val createdAt: Instant,
    @Serializable(with = UtcInstantSerializer::class) val modifiedAt: Instant,
    val outcome: OutcomeSpec? = null,
    val suppression: SuppressionTarget? = null,
    val experiment: ExperimentSpec = ExperimentSpec(),
    val userConfirmedUnknownOverrides: Boolean = false,
    val provenance: Provenance? = null,
) {
    public companion object {
        public const val SCHEMA_VERSION: Int = 1
        public const val DEFAULT_PRIORITY: Int = 50
    }
}

/**
 * Half-open local-time window `[start, end)` (R10 §3.2, §10.3). It crosses midnight when `end < start`; `start == end`
 * is invalid (E025). [days] filters by the start date of the window instance; `null` means every day.
 */
@Serializable
public data class ActiveWindow(val start: String, val end: String, val days: List<WeekDay>? = null)

/** Delivery settings (R10 §2.1). `channel == NONE` iff the rule is a SUPPRESSION. */
@Serializable
public data class Delivery(
    val channel: DeliveryChannel = DeliveryChannel.NOTIFICATION,
    val quietHoursPolicy: QuietHoursPolicy = QuietHoursPolicy.RESPECT,
    val notificationTimeoutMinutes: Int? = null,
    val deliveryDeadlineMinutes: Int = DEFAULT_DELIVERY_DEADLINE_MINUTES,
) {
    public companion object {
        public const val DEFAULT_DELIVERY_DEADLINE_MINUTES: Int = 10
    }
}

/** Snooze actions offered on the notification (R10 §9.5). */
@Serializable
public data class SnoozePolicy(val mode: SnoozeMode, val options: List<SnoozeOption>) {
    public companion object {
        /** The default for an INTERVENTION without a snooze policy (R10 §11.4 step 4). */
        public val DEFAULT: SnoozePolicy =
            SnoozePolicy(SnoozeMode.SUPPRESS_ONLY, listOf(SnoozeOption.MINUTES_60, SnoozeOption.UNTIL_TOMORROW))

        /**
         * The default for a `daily_at` INTERVENTION (integrator correction jitai-correctness-14): a snoozed daily check
         * re-runs once at the end of the snooze ("remind me in an hour" re-checks the step count), R10 §9.5.
         */
        public val DEFAULT_DAILY_AT: SnoozePolicy =
            SnoozePolicy(SnoozeMode.RE_EVALUATE_AFTER, listOf(SnoozeOption.MINUTES_60, SnoozeOption.UNTIL_TOMORROW))

        /** [DEFAULT_DAILY_AT] for a `daily_at` trigger, else [DEFAULT]. */
        public fun defaultFor(trigger: Trigger?): SnoozePolicy = if (trigger is Trigger.DailyAt) DEFAULT_DAILY_AT else DEFAULT
    }
}

/** Proximal and distal outcome metrics (R10 §15.1). */
@Serializable
public data class OutcomeSpec(val proximal: OutcomeMetricRef, val distal: OutcomeMetricRef? = null)

/** One outcome metric with its args (same syntax as feature args) and window. */
@Serializable
public data class OutcomeMetricRef(
    val metric: OutcomeMetric,
    @Serializable(with = ArgsSerializer::class) val args: Map<String, String> = emptyMap(),
    val windowMinutes: Int? = null,
)

/** Targets of a SUPPRESSION rule (R10 §3.2): categories and/or other JITAI ids (USER rules only). */
@Serializable
public data class SuppressionTarget(val categories: List<JitaiCategory> = emptyList(), val jitaiIds: List<String> = emptyList())

/** Optional consented micro-randomization (R10 §15.3): `deliverProbability` is null or 0.3-0.7. */
@Serializable
public data class ExperimentSpec(val mode: ExperimentMode = ExperimentMode.NONE, val deliverProbability: Double? = null)

/**
 * Where a rule came from (R10 §2.1, §11.4, §11.5, §13.1). Kept on the device only.
 *
 * @property appLabels package -> the app name the request or model used, kept for display after resolution (R10 §13.4).
 * @property expiresInDays the trial length of a proposal; [JitaiLifecycle] turns it into `expiresAt` at approval.
 * @property approvedRendering the exact sentence the user saw when approving (R10 §11.5).
 */
@Serializable
public data class Provenance(
    val nlRequest: String? = null,
    val proposalId: String? = null,
    val patternId: String? = null,
    val templateId: String? = null,
    val evidence: JsonObject? = null,
    val promptVersion: String? = null,
    val catalogVersion: String? = null,
    @Serializable(with = ArgsSerializer::class) val appLabels: Map<String, String> = emptyMap(),
    val expiresInDays: Int? = null,
    val approvedRendering: String? = null,
)
