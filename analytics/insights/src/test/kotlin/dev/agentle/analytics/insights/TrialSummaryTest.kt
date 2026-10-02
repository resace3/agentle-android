package dev.agentle.analytics.insights

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The trial summary of docs/research/10 §15.4: counts, responses, the descriptive mean and the randomized comparison. */
class TrialSummaryTest {
    private var next = 0

    private fun decision(
        result: DecisionResult,
        minutes: Long? = null,
        responses: Set<ResponseKind> = emptySet(),
        suppressedBy: String? = null,
        metric: OutcomeMetric = OutcomeMetric.APP_MINUTES_AFTER,
        state: OutcomeState = OutcomeState.AVAILABLE,
        flag: Boolean? = null,
        randomized: Boolean = result == DecisionResult.DELIVERED || result == DecisionResult.SKIPPED_AT_RANDOM,
    ): TrialDecision {
        val key = "d${next++}"
        val proximal = if (minutes != null || flag != null) OutcomeValue(key, metric, state, number = minutes, flag = flag) else null
        return TrialDecision(key, result, suppressedBy, responses, proximal, randomized)
    }

    @Test
    fun `a randomized trial compares delivered and skipped moments in the R10 wording`() {
        val delivered = listOf(0L, 0, 1, 2, 3, 5, 6, 6, 7, 9, 10, 11, 12, 12).map { decision(DecisionResult.DELIVERED, it) }
        val skipped = listOf(3L, 4, 6, 7, 9, 10, 11, 12, 13, 15, 16, 18, 19).map { decision(DecisionResult.SKIPPED_AT_RANDOM, it) }

        val summary = TrialSummaries.summarize(
            delivered + skipped,
            OutcomeMetric.APP_MINUTES_AFTER,
            30,
            randomized = true,
            subjectLabel = "Instagram",
        )

        assertThat(summary.text).isEqualTo(
            "In the 30 minutes after a reminder you used Instagram for 6 minutes on average; at comparable moments when Agentle " +
                "skipped it at random, 11 minutes (difference -5 minutes, approximate range -9 to -1; 14 and 13 moments).",
        )
        val comparison = requireNotNull(summary.comparison)
        assertThat(comparison.meanDelivered).isEqualTo(6.0)
        assertThat(comparison.meanSkipped).isEqualTo(11.0)
        assertThat(comparison.difference).isEqualTo(-5.0)
        // half width 1.959964 * sqrt(var1/14 + var0/13) = 3.6087 (checked with Python's statistics module)
        assertThat(comparison.low).isWithin(1e-4).of(-8.6087)
        assertThat(comparison.high).isWithin(1e-4).of(-1.3913)
        assertThat(comparison.delivered).isEqualTo(14)
        assertThat(comparison.skipped).isEqualTo(13)
        assertThat(summary.available).isEqualTo(27)
    }

    @Test
    fun `a trial without randomization is described and never compared`() {
        val opened = (0 until 18).map { i ->
            decision(DecisionResult.DELIVERED, if (i % 2 == 0) 4 else 8, if (i < 7) setOf(ResponseKind.OPENED) else emptySet())
        }
        val other = listOf(
            decision(DecisionResult.NOT_AVAILABLE),
            decision(DecisionResult.NOT_AVAILABLE),
            decision(DecisionResult.SUPPRESSED, suppressedBy = "QUIET_HOURS"),
            decision(DecisionResult.SUPPRESSED, suppressedBy = "DAILY_CAP"),
            decision(DecisionResult.SUPPRESSED, suppressedBy = "QUIET_HOURS"),
            decision(DecisionResult.SUPPRESSED, suppressedBy = "DAILY_CAP"),
            decision(DecisionResult.SUPPRESSED, suppressedBy = "COOLDOWN"),
            decision(DecisionResult.SUPPRESSED),
            // Present by mistake in a non-randomized trial: still no comparison.
            decision(DecisionResult.SKIPPED_AT_RANDOM, 30),
        )

        val summary = TrialSummaries.summarize(
            opened + other,
            OutcomeMetric.APP_MINUTES_AFTER,
            30,
            randomized = false,
            subjectLabel = "Instagram",
        )

        assertThat(summary.text).isEqualTo(
            "Sent 18 times, opened 7 times. In the 30 minutes after a reminder you used Instagram for 6 minutes on average. " +
                "Without the experiment option Agentle cannot tell whether the reminder made a difference.",
        )
        assertThat(summary.comparison).isNull()
        assertThat(summary.evaluated).isEqualTo(27)
        assertThat(summary.available).isEqualTo(19)
        assertThat(summary.suppressed).isEqualTo(6)
        assertThat(summary.topSuppressionReasons).containsExactly("DAILY_CAP" to 2, "QUIET_HOURS" to 2, "COOLDOWN" to 1).inOrder()
        assertThat(summary.meanAfterReminder).isEqualTo(6.0)
        assertThat(summary.outcomesAfterReminder).isEqualTo(18)
    }

    @Test
    fun `responses are counted on delivered reminders`() {
        val decisions = listOf(
            decision(DecisionResult.DELIVERED, responses = setOf(ResponseKind.OPENED, ResponseKind.HELPFUL)),
            decision(DecisionResult.DELIVERED, responses = setOf(ResponseKind.SNOOZED)),
            decision(DecisionResult.DELIVERED, responses = setOf(ResponseKind.DISMISSED, ResponseKind.NOT_HELPFUL)),
            decision(DecisionResult.SKIPPED_AT_RANDOM, responses = setOf(ResponseKind.OPENED)),
        )

        val summary = TrialSummaries.summarize(decisions, OutcomeMetric.SCREEN_MINUTES_AFTER, null, randomized = false)

        assertThat(
            listOf(summary.opened, summary.snoozed, summary.dismissed, summary.helpful, summary.notHelpful),
        ).containsExactly(1, 1, 1, 1, 1).inOrder()
        assertThat(summary.meanAfterReminder).isNull()
        assertThat(
            summary.text,
        ).isEqualTo(
            "Sent 3 times, opened 1 time. Without the experiment option Agentle cannot tell whether the reminder made a difference.",
        )
    }

    @Test
    fun `fewer than 10 outcomes in an arm give no comparison yet`() {
        val delivered = (0 until 12).map { decision(DecisionResult.DELIVERED, 6) }
        val skipped = (0 until 9).map { decision(DecisionResult.SKIPPED_AT_RANDOM, 11) } +
            decision(DecisionResult.SKIPPED_AT_RANDOM, 11, state = OutcomeState.PENDING)

        val summary = TrialSummaries.summarize(
            delivered + skipped,
            OutcomeMetric.APP_MINUTES_AFTER,
            30,
            randomized = true,
            subjectLabel = "Instagram",
        )

        assertThat(summary.comparison).isNull()
        assertThat(summary.text).isEqualTo(
            "Sent 12 times and skipped 10 times at random. Agentle compares them once it has 10 outcomes of each. " +
                "In the 30 minutes after a reminder you used Instagram for 6 minutes on average.",
        )
        assertThat(TrialSummaries.summarize(emptyList(), OutcomeMetric.STEPS_AFTER, 60, randomized = true).text)
            .isEqualTo("Sent 0 times and skipped 0 times at random. Agentle compares them once it has 10 outcomes of each.")
    }

    @Test
    fun `a BOOL outcome is compared with the Newcombe interval`() {
        val metric = OutcomeMetric.NOTIFICATION_OPENED
        val delivered = (0 until 12).map { decision(DecisionResult.DELIVERED, metric = metric, flag = it < 9) }
        val skipped = (0 until 10).map { decision(DecisionResult.SKIPPED_AT_RANDOM, metric = metric, flag = it < 3) }

        val summary = TrialSummaries.summarize(delivered + skipped, metric, 60, randomized = true)

        val comparison = requireNotNull(summary.comparison)
        val ci = requireNotNull(PatternStatistics.newcombe(TwoByTwo(9, 3, 3, 7)))
        assertThat(comparison.difference).isWithin(1e-12).of(0.75 - 0.3)
        assertThat(comparison.low).isEqualTo(ci.lower)
        assertThat(comparison.high).isEqualTo(ci.upper)
        assertThat(summary.text).startsWith("In the 60 minutes after a reminder you opened 75% of reminders on average;")
        assertThat(summary.text).contains("30% (difference 45 points, approximate range")
    }

    @Test
    fun `other metrics use their own units`() {
        val steps = (0 until 3).map { decision(DecisionResult.DELIVERED, 250, metric = OutcomeMetric.STEPS_AFTER) }
        val screen = (0 until 3).map { decision(DecisionResult.DELIVERED, 9, metric = OutcomeMetric.SCREEN_MINUTES_AFTER) }
        val category = (0 until 3).map { decision(DecisionResult.DELIVERED, 4, metric = OutcomeMetric.APP_CATEGORY_MINUTES_AFTER) }
        val bedtime = (0 until 3).map { decision(DecisionResult.DELIVERED, 680, metric = OutcomeMetric.BEDTIME_NEXT) }

        assertThat(
            TrialSummaries.summarize(steps, OutcomeMetric.STEPS_AFTER, 60, randomized = false).text,
        ).contains("you walked 250 steps on average")
        assertThat(
            TrialSummaries.summarize(screen, OutcomeMetric.SCREEN_MINUTES_AFTER, 30, false).text,
        ).contains("you used your phone for 9 minutes")
        assertThat(TrialSummaries.summarize(category, OutcomeMetric.APP_CATEGORY_MINUTES_AFTER, 30, false, "social").text)
            .contains("you used social apps for 4 minutes")
        assertThat(
            TrialSummaries.summarize(bedtime, OutcomeMetric.BEDTIME_NEXT, null, false).text,
        ).contains("After a reminder the outcome was 680 on average")
        assertThat(
            TrialSummaries.summarize(steps, OutcomeMetric.APP_MINUTES_AFTER, 30, false).text,
        ).contains("you used the app for 250 minutes")
    }

    @Test
    fun `the sample variance needs two values`() {
        assertThat(TrialSummaries.variance(listOf(1.0, 3.0))).isEqualTo(2.0)
        assertThrows<IllegalArgumentException> { TrialSummaries.variance(listOf(1.0)) }
    }

    @Test
    fun `R2-3 only randomized decisions are compared`() {
        val delivered =
            List(10) { decision(DecisionResult.DELIVERED, 5) } + List(10) { decision(DecisionResult.DELIVERED, 100, randomized = false) }
        val skipped = List(10) { decision(DecisionResult.SKIPPED_AT_RANDOM, 5) }

        val comparison =
            requireNotNull(TrialSummaries.summarize(delivered + skipped, OutcomeMetric.APP_MINUTES_AFTER, 30, randomized = true).comparison)

        assertThat(comparison.delivered).isEqualTo(10)
        assertThat(comparison.meanDelivered).isEqualTo(5.0)
        assertThat(comparison.difference).isEqualTo(0.0)
    }

    @Test
    fun `R2-4 the shown difference is the difference of the rounded means`() {
        // Means 6.4 and 10.6: shown as 6 and 11, so the difference reads -5, never -4.
        val delivered =
            List(5) { decision(DecisionResult.DELIVERED, 6) } + List(5) { decision(DecisionResult.DELIVERED, if (it < 4) 7 else 6) }
        val skipped =
            List(5) { decision(DecisionResult.SKIPPED_AT_RANDOM, 10) } +
                List(5) { decision(DecisionResult.SKIPPED_AT_RANDOM, if (it < 3) 12 else 10) }

        val summary = TrialSummaries.summarize(delivered + skipped, OutcomeMetric.APP_MINUTES_AFTER, 30, randomized = true)

        assertThat(requireNotNull(summary.comparison).difference).isWithin(1e-9).of(-4.2)
        assertThat(summary.text).contains("6 minutes on average")
        assertThat(summary.text).contains("11 minutes (difference -5 minutes")
    }
}
