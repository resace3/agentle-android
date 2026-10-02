package dev.agentle.jitai.engine.outcome

import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.core.common.Outcome
import dev.agentle.core.time.ClosedOpenRange
import dev.agentle.core.time.dayBounds
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.OutcomeMetric
import dev.agentle.jitai.dsl.model.OutcomeMetricRef
import dev.agentle.jitai.dsl.model.OutcomeRole
import dev.agentle.jitai.engine.decision.DecisionRecord
import dev.agentle.jitai.engine.decision.JitaiResponse
import dev.agentle.jitai.engine.delivery.DeliveryProtocol
import dev.agentle.jitai.engine.time.LocalWindow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * One outcome computation to schedule (R10 §8.7, §15.1): unique work [uniqueName] with policy KEEP at [dueAt], the end
 * of the outcome window plus the metric's data-latency allowance. Without data at [unavailableAt] (48 h after the
 * window) the outcome is UNAVAILABLE.
 */
public data class PlannedOutcome(
    val decisionKey: String,
    val jitaiId: String,
    val role: OutcomeRole,
    val metric: OutcomeMetricRef,
    val window: ClosedOpenRange,
    val dueAt: Instant,
    val unavailableAt: Instant,
) {
    /** `jitai-outcome-<key>` for the proximal outcome, `jitai-outcome-distal-<key>` for the distal one. */
    public val uniqueName: String
        get() = if (role == OutcomeRole.PROXIMAL) "$PREFIX$decisionKey" else "${PREFIX}distal-$decisionKey"

    public companion object {
        public const val PREFIX: String = "jitai-outcome-"
    }
}

/** State of a computed outcome (`jitai_outcome.state`). */
public enum class OutcomeState { PENDING, AVAILABLE, UNAVAILABLE }

/**
 * A computed outcome. [value] is null for AVAILABLE `NOTIFICATION_OPENED` of a decision without a delivery (R10 §15.1)
 * and for PENDING and UNAVAILABLE results.
 */
public data class OutcomeResult(
    val decisionKey: String,
    val role: OutcomeRole,
    val metric: OutcomeMetric,
    val state: OutcomeState,
    val value: FeatureScalar?,
    val computedAt: Instant,
)

/**
 * Stored data behind the window metrics (steps, screen and app minutes, sleep), read by the analytics team's
 * implementation. Every answer must be a pure function of stored data so re-runs give the same value.
 */
public interface OutcomeDataPort {
    /**
     * The value of [metric] over [window] in [zone], or null while the data does not yet cover the window. Expected
     * failures are [Outcome.Failure].
     */
    public suspend fun measure(metric: OutcomeMetricRef, window: ClosedOpenRange, zone: TimeZone): Outcome<FeatureScalar?>
}

/**
 * Outcome scheduling and the outcomes computed from the decision row itself (R10 §8.7, §15.1). `t0` is the row's
 * `decisionPointAt`. Outcomes are planned for every row a pass writes (scheduled points, delivered or not, and event rows).
 */
public object OutcomePlanner {
    /** Waiting time after the window before an outcome without data becomes UNAVAILABLE. */
    public val UNAVAILABLE_AFTER: Duration = 48.hours

    /** `BEDTIME_NEXT` and `SLEEP_MINUTES_NEXT` look for a main sleep session starting within this time after `t0`. */
    public val SLEEP_SEARCH: Duration = 18.hours

    /** Window used when a windowed metric has no `windowMinutes` (the validator requires one; this only guards). */
    public const val DEFAULT_WINDOW_MINUTES: Int = 30

    /** `SELF_REPORT_HELPFUL` of a notification without `notificationTimeoutMinutes` is read after this long. */
    public val DEFAULT_FEEDBACK_PERIOD: Duration = 24.hours

    private const val STEPS_LATENCY_MINUTES = 60
    private const val USAGE_LATENCY_MINUTES = 5
    private const val SLEEP_DUE_MINUTE = 14 * 60
    private const val STEPS_DAY_DUE_MINUTE = 5 * 60

    /** The outcomes of [record] for [definition] (none when the rule has no outcome spec). */
    public fun plan(record: DecisionRecord, definition: JitaiDefinition, zone: TimeZone): List<PlannedOutcome> {
        val spec = definition.outcome ?: return emptyList()
        return listOfNotNull(
            plan(record, definition, spec.proximal, OutcomeRole.PROXIMAL, zone),
            spec.distal?.let { plan(record, definition, it, OutcomeRole.DISTAL, zone) },
        )
    }

    private fun plan(
        record: DecisionRecord,
        definition: JitaiDefinition,
        metric: OutcomeMetricRef,
        role: OutcomeRole,
        zone: TimeZone,
    ): PlannedOutcome {
        val t0 = record.decisionPointAt
        val (window, due) = windowAndDue(metric, t0, definition, zone)
        return PlannedOutcome(
            decisionKey = record.decisionKey,
            jitaiId = record.jitaiId,
            role = role,
            metric = metric,
            window = window,
            dueAt = due,
            unavailableAt = maxOf(window.end, due) + UNAVAILABLE_AFTER,
        )
    }

    /**
     * The window and due time of [metric] for a decision at [t0] (R10 §15.1 latency allowances). Response-based metrics
     * extend their window by the delivery deadline and the lease, because `deliveredAt` follows `t0` by at most that much.
     */
    public fun windowAndDue(
        metric: OutcomeMetricRef,
        t0: Instant,
        definition: JitaiDefinition,
        zone: TimeZone,
    ): Pair<ClosedOpenRange, Instant> {
        val w = (metric.windowMinutes ?: DEFAULT_WINDOW_MINUTES).minutes
        val deliverySlack = definition.delivery.deliveryDeadlineMinutes.coerceAtLeast(0).minutes + DeliveryProtocol.LEASE
        val localDate = t0.toLocalDateTime(zone).date
        val nextDate = localDate.plus(1, DateTimeUnit.DAY)
        return when (metric.metric) {
            OutcomeMetric.STEPS_AFTER -> ClosedOpenRange(t0, t0 + w).let { it to it.end + STEPS_LATENCY_MINUTES.minutes }

            OutcomeMetric.SCREEN_MINUTES_AFTER,
            OutcomeMetric.APP_MINUTES_AFTER,
            OutcomeMetric.APP_CATEGORY_MINUTES_AFTER,
            -> ClosedOpenRange(t0, t0 + w).let { it to it.end + USAGE_LATENCY_MINUTES.minutes }

            OutcomeMetric.NOTIFICATION_OPENED -> ClosedOpenRange(t0, t0 + deliverySlack + w).let { it to it.end }

            OutcomeMetric.SELF_REPORT_HELPFUL -> {
                val period = definition.delivery.notificationTimeoutMinutes?.minutes ?: DEFAULT_FEEDBACK_PERIOD
                ClosedOpenRange(t0, t0 + deliverySlack + period).let { it to it.end }
            }

            OutcomeMetric.BEDTIME_NEXT, OutcomeMetric.SLEEP_MINUTES_NEXT ->
                ClosedOpenRange(t0, t0 + SLEEP_SEARCH) to LocalWindow.atMinute(nextDate, SLEEP_DUE_MINUTE, zone)

            OutcomeMetric.STEPS_DAY_TOTAL -> dayBounds(localDate, zone) to LocalWindow.atMinute(nextDate, STEPS_DAY_DUE_MINUTE, zone)
        }
    }
}

/** Computes one planned outcome: response metrics from the decision row, the others through [OutcomeDataPort]. */
public object OutcomeEvaluator {
    /**
     * The outcome of [planned] for [record] at [now]. Response metrics never wait: they are read from the row. Data metrics
     * are PENDING while the port has no value before [PlannedOutcome.unavailableAt], then UNAVAILABLE.
     */
    public suspend fun compute(
        planned: PlannedOutcome,
        record: DecisionRecord,
        now: Instant,
        zone: TimeZone,
        data: OutcomeDataPort?,
    ): Outcome<OutcomeResult> {
        val metric = planned.metric.metric
        fun result(state: OutcomeState, value: FeatureScalar?) = OutcomeResult(planned.decisionKey, planned.role, metric, state, value, now)
        return when (metric) {
            OutcomeMetric.NOTIFICATION_OPENED -> Outcome.success(result(OutcomeState.AVAILABLE, opened(record, planned)))

            OutcomeMetric.SELF_REPORT_HELPFUL -> Outcome.success(
                result(OutcomeState.AVAILABLE, FeatureScalar.EnumValue(feedback(record).name)),
            )

            else -> {
                val measured = data?.measure(planned.metric, planned.window, zone) ?: Outcome.success(null)
                when (measured) {
                    is Outcome.Failure -> measured

                    is Outcome.Success -> Outcome.success(
                        when {
                            measured.value != null -> result(OutcomeState.AVAILABLE, measured.value)
                            now >= planned.unavailableAt -> result(OutcomeState.UNAVAILABLE, null)
                            else -> result(OutcomeState.PENDING, null)
                        },
                    )
                }
            }
        }
    }

    /** `OPENED` within `windowMinutes` after `deliveredAt`; null for decisions without a delivery (R10 §15.1). */
    private fun opened(record: DecisionRecord, planned: PlannedOutcome): FeatureScalar? {
        val delivered = record.delivered ?: return null
        val window = (planned.metric.windowMinutes ?: OutcomePlanner.DEFAULT_WINDOW_MINUTES).minutes
        val respondedAt = record.content.respondedAt
        val opened = record.content.response == JitaiResponse.OPENED && respondedAt != null && respondedAt - delivered.wall <= window
        return FeatureScalar.BoolValue(opened)
    }

    private fun feedback(record: DecisionRecord): JitaiResponse = when (record.content.response) {
        JitaiResponse.HELPFUL, JitaiResponse.NOT_HELPFUL -> record.content.response
        else -> JitaiResponse.NONE
    }
}
