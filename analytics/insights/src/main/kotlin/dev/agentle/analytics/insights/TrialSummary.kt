package dev.agentle.analytics.insights

import kotlin.math.roundToLong
import kotlin.math.sqrt

/** What happened at one decision point of a trial (docs/research/10 §8.3, §15.3). */
public enum class DecisionResult {
    /** The rule was FALSE or UNKNOWN: not an available decision point. */
    NOT_AVAILABLE,

    /** The rule was TRUE but a safety gate stopped the delivery. */
    SUPPRESSED,

    /** Delivered. */
    DELIVERED,

    /** Experiment mode skipped it at random (`NOT_RANDOMIZED`). */
    SKIPPED_AT_RANDOM,
}

/** One decision point of a trial with its responses and proximal outcome. [suppressedBy] is the failing gate's code. */
public data class TrialDecision(
    val decisionKey: String,
    val result: DecisionResult,
    val suppressedBy: String? = null,
    val responses: Set<ResponseKind> = emptySet(),
    val proximal: OutcomeValue? = null,
)

/** Delivered versus skipped at random (experiment mode only); the range is approximate (docs/research/10 §15.2). */
public data class TrialComparison(
    val meanDelivered: Double,
    val meanSkipped: Double,
    val difference: Double,
    val low: Double,
    val high: Double,
    val delivered: Int,
    val skipped: Int,
)

/** The trial summary of docs/research/10 §15.4, computed locally and never sent anywhere. */
public data class TrialSummary(
    val evaluated: Int,
    val available: Int,
    val delivered: Int,
    val skippedAtRandom: Int,
    val suppressed: Int,
    val topSuppressionReasons: List<Pair<String, Int>>,
    val opened: Int,
    val snoozed: Int,
    val dismissed: Int,
    val helpful: Int,
    val notHelpful: Int,
    val meanAfterReminder: Double?,
    val outcomesAfterReminder: Int,
    val comparison: TrialComparison?,
    val text: String,
)

/**
 * Builds [TrialSummary]s. The comparison needs experiment mode and at least [MIN_PER_ARM] available outcomes in each
 * arm: the difference of means with `diff +/- 1.96 x sqrt(s1^2/n1 + s0^2/n0)` for numeric outcomes and the Newcombe
 * interval for BOOL outcomes. A non-randomized trial never gets a comparison.
 */
public object TrialSummaries {
    public const val MIN_PER_ARM: Int = 10
    private const val TOP_REASONS = 3

    /**
     * @param subjectLabel the app or category label for app metrics, shown on the device only (it is user-visible
     *   third-party text and never leaves the phone).
     */
    public fun summarize(
        decisions: List<TrialDecision>,
        metric: OutcomeMetric,
        windowMinutes: Int?,
        randomized: Boolean,
        subjectLabel: String? = null,
    ): TrialSummary {
        val delivered = decisions.filter { it.result == DecisionResult.DELIVERED }
        val skipped = decisions.filter { it.result == DecisionResult.SKIPPED_AT_RANDOM }
        val suppressed = decisions.filter { it.result == DecisionResult.SUPPRESSED }
        val reasons = suppressed.groupingBy { it.suppressedBy ?: "UNKNOWN" }.eachCount().entries
            .sortedWith(compareBy({ -it.value }, { it.key })).take(TOP_REASONS).map { it.key to it.value }
        fun count(kind: ResponseKind) = delivered.count { kind in it.responses }
        val after = delivered.mapNotNull { valueOf(it) }
        val comparison = if (randomized) compare(after, skipped.mapNotNull { valueOf(it) }, metric) else null
        val summary = TrialSummary(
            evaluated = decisions.size,
            available = delivered.size + skipped.size,
            delivered = delivered.size,
            skippedAtRandom = skipped.size,
            suppressed = suppressed.size,
            topSuppressionReasons = reasons,
            opened = count(ResponseKind.OPENED),
            snoozed = count(ResponseKind.SNOOZED),
            dismissed = count(ResponseKind.DISMISSED),
            helpful = count(ResponseKind.HELPFUL),
            notHelpful = count(ResponseKind.NOT_HELPFUL),
            meanAfterReminder = after.takeIf { it.isNotEmpty() }?.average(),
            outcomesAfterReminder = after.size,
            comparison = comparison,
            text = "",
        )
        return summary.copy(text = wording(summary, metric, windowMinutes, randomized, subjectLabel))
    }

    /** The available value of a decision's proximal outcome as a number (BOOL as 0/1). */
    private fun valueOf(decision: TrialDecision): Double? {
        val outcome = decision.proximal ?: return null
        if (outcome.state != OutcomeState.AVAILABLE) return null
        return outcome.number?.toDouble() ?: outcome.flag?.let { if (it) 1.0 else 0.0 }
    }

    private fun compare(delivered: List<Double>, skipped: List<Double>, metric: OutcomeMetric): TrialComparison? {
        if (delivered.size < MIN_PER_ARM || skipped.size < MIN_PER_ARM) return null
        val m1 = delivered.average()
        val m0 = skipped.average()
        val diff = m1 - m0
        val (low, high) = if (metric == OutcomeMetric.NOTIFICATION_OPENED) {
            val ci = requireNotNull(PatternStatistics.newcombe(TwoByTwo(ones(delivered), zeros(delivered), ones(skipped), zeros(skipped))))
            ci.lower to ci.upper
        } else {
            val half = PatternStatistics.Z95 * sqrt(variance(delivered) / delivered.size + variance(skipped) / skipped.size)
            (diff - half) to (diff + half)
        }
        return TrialComparison(m1, m0, diff, low, high, delivered.size, skipped.size)
    }

    private fun ones(values: List<Double>): Int = values.count { it > 0.0 }

    private fun zeros(values: List<Double>): Int = values.count { it <= 0.0 }

    /** Sample variance (n - 1). */
    public fun variance(values: List<Double>): Double {
        require(values.size >= 2) { "variance needs two values" }
        val mean = values.average()
        return values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
    }

    private fun wording(s: TrialSummary, metric: OutcomeMetric, windowMinutes: Int?, randomized: Boolean, label: String?): String {
        val window = windowMinutes?.let { "In the $it minutes after a reminder" } ?: "After a reminder"
        val comparison = s.comparison
        val unit = unitOf(metric)
        return when {
            comparison != null ->
                "$window ${phrase(
                    metric,
                    comparison.meanDelivered,
                    label,
                )} on average; at comparable moments when Agentle skipped it at random, " +
                    "${amount(
                        metric,
                        comparison.meanSkipped,
                    )} (difference ${signed(comparison.difference, metric)}$unit, approximate range " +
                    "${signed(
                        comparison.low,
                        metric,
                    )} to ${signed(comparison.high, metric)}; ${comparison.delivered} and ${comparison.skipped} moments)."

            randomized ->
                "Sent ${times(s.delivered)} and skipped ${times(s.skippedAtRandom)} at random. Agentle compares them once it has " +
                    "$MIN_PER_ARM outcomes of each." +
                    (s.meanAfterReminder?.let { " $window ${phrase(metric, it, label)} on average." } ?: "")

            else ->
                "Sent ${times(s.delivered)}, opened ${times(s.opened)}." +
                    (s.meanAfterReminder?.let { " $window ${phrase(metric, it, label)} on average." } ?: "") +
                    " Without the experiment option Agentle cannot tell whether the reminder made a difference."
        }
    }

    private fun times(n: Int): String = if (n == 1) "1 time" else "$n times"

    private fun phrase(metric: OutcomeMetric, value: Double, label: String?): String = when (metric) {
        OutcomeMetric.SCREEN_MINUTES_AFTER -> "you used your phone for ${amount(metric, value)}"
        OutcomeMetric.APP_MINUTES_AFTER -> "you used ${label ?: "the app"} for ${amount(metric, value)}"
        OutcomeMetric.APP_CATEGORY_MINUTES_AFTER -> "you used ${label ?: "these"} apps for ${amount(metric, value)}"
        OutcomeMetric.STEPS_AFTER -> "you walked ${amount(metric, value)}"
        OutcomeMetric.NOTIFICATION_OPENED -> "you opened ${amount(metric, value)} of reminders"
        else -> "the outcome was ${amount(metric, value)}"
    }

    private fun amount(metric: OutcomeMetric, value: Double): String = when (metric) {
        OutcomeMetric.NOTIFICATION_OPENED -> "${PatternStatistics.percent(value)}%"
        else -> "${value.roundToLong()}${unitOf(metric)}"
    }

    private fun signed(value: Double, metric: OutcomeMetric): String =
        if (metric == OutcomeMetric.NOTIFICATION_OPENED) "${PatternStatistics.percent(value)} points" else "${value.roundToLong()}"

    private fun unitOf(metric: OutcomeMetric): String = when (metric) {
        OutcomeMetric.SCREEN_MINUTES_AFTER, OutcomeMetric.APP_MINUTES_AFTER, OutcomeMetric.APP_CATEGORY_MINUTES_AFTER,
        OutcomeMetric.SLEEP_MINUTES_NEXT,
        -> " minutes"

        OutcomeMetric.STEPS_AFTER, OutcomeMetric.STEPS_DAY_TOTAL -> " steps"

        else -> ""
    }
}
