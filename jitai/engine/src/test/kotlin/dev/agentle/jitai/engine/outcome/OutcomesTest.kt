package dev.agentle.jitai.engine.outcome

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.model.OutcomeMetric
import dev.agentle.jitai.dsl.model.OutcomeMetricRef
import dev.agentle.jitai.dsl.model.OutcomeRole
import dev.agentle.jitai.dsl.model.OutcomeSpec
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.Leaves
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.JitaiResponse
import dev.agentle.jitai.engine.int
import dev.agentle.jitai.engine.schedule.TimerKind
import dev.agentle.jitai.engine.seedCounted
import dev.agentle.jitai.engine.timer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** R10 §8.7 / §15.1: outcome planning, computation and the engagement-backoff polarity. */
class OutcomesTest {
    private val spec = OutcomeSpec(
        proximal = OutcomeMetricRef(OutcomeMetric.SCREEN_MINUTES_AFTER, windowMinutes = 60),
        distal = OutcomeMetricRef(OutcomeMetric.STEPS_DAY_TOTAL),
    )
    private val r1 = Rules.R1.copy(outcome = spec)

    @Test
    fun `a delivery plans both outcome rows and the timer records them when due, UNAVAILABLE without data`() = runTest {
        val harness = F0.harness(F0.local("2026-10-01T22:30"), r1)
        harness.features.set(Leaves.SCREEN, int(50, F0.local("2026-10-01T22:30")))
        harness.timer()
        val outcomeRows = harness.store.timers().getOrThrow().filter { it.kind == TimerKind.OUTCOME }
        assertThat(outcomeRows.map { it.role }).containsExactly(OutcomeRole.PROXIMAL, OutcomeRole.DISTAL)
        assertThat(outcomeRows.single { it.role == OutcomeRole.PROXIMAL }.dueAt).isEqualTo(F0.local("2026-10-01T23:35"))
        harness.outcomes.values[OutcomeMetric.SCREEN_MINUTES_AFTER] = FeatureScalar.IntValue(0)

        // R1 is paused by the engagement backoff on 10-04 04:00; the distal row of slot 10 must survive the pause.
        harness.runTimerUntil(F0.local("2026-10-04T08:00"))

        val recorded = harness.outcomes.recorded.filter { it.decisionKey == "v1|R1|I|2026-10-01|10" }.associateBy { it.role }
        assertThat(recorded.getValue(OutcomeRole.PROXIMAL).state).isEqualTo(OutcomeState.AVAILABLE)
        assertThat(OutcomePositivity.isPositive(recorded.getValue(OutcomeRole.PROXIMAL))).isTrue()
        assertThat(recorded.getValue(OutcomeRole.DISTAL).state).isEqualTo(OutcomeState.UNAVAILABLE)
        assertThat(
            harness.store.timers().getOrThrow().none {
                it.kind == TimerKind.OUTCOME && it.decisionKey == "v1|R1|I|2026-10-01|10"
            },
        ).isTrue()
    }

    @ParameterizedTest(name = "{0}: window +{1} min, due +{2} min after 22:30")
    @CsvSource(
        "STEPS_AFTER, 60, 120",
        "SCREEN_MINUTES_AFTER, 60, 65",
        "APP_MINUTES_AFTER, 60, 65",
        "APP_CATEGORY_MINUTES_AFTER, 60, 65",
        "NOTIFICATION_OPENED, 72, 72",
        "SELF_REPORT_HELPFUL, 1452, 1452",
        "BEDTIME_NEXT, 1080, 930",
        "SLEEP_MINUTES_NEXT, 1080, 930",
    )
    fun `windows and due times follow the latency allowances`(metric: OutcomeMetric, windowEnd: Long, due: Long) {
        val t0 = F0.local("2026-10-01T22:30")
        val (window, dueAt) = OutcomePlanner.windowAndDue(OutcomeMetricRef(metric, windowMinutes = 60), t0, Rules.R1, F0.BERLIN)
        assertWithMessage("$metric window").that(window.end).isEqualTo(t0 + windowEnd.minutes)
        assertWithMessage("$metric due").that(dueAt).isEqualTo(t0 + due.minutes)
    }

    @Test
    fun `STEPS_DAY_TOTAL covers the local day and is due at 05_00 the next day, a missing window uses the default`() {
        val t0 = F0.local("2026-10-01T22:30")
        val (window, due) = OutcomePlanner.windowAndDue(OutcomeMetricRef(OutcomeMetric.STEPS_DAY_TOTAL), t0, Rules.R1, F0.BERLIN)
        assertThat(window.start).isEqualTo(F0.local("2026-10-01T00:00"))
        assertThat(due).isEqualTo(F0.local("2026-10-02T05:00"))
        val (defaulted, _) = OutcomePlanner.windowAndDue(OutcomeMetricRef(OutcomeMetric.STEPS_AFTER), t0, Rules.R1, F0.BERLIN)
        assertThat(defaulted.end).isEqualTo(t0 + OutcomePlanner.DEFAULT_WINDOW_MINUTES.minutes)
    }

    @Test
    fun `response metrics are read from the row, data metrics wait, fail or become UNAVAILABLE`() = runTest {
        val harness = F0.harness(F0.local("2026-10-01T23:00"), r1)
        val opened = harness.seedCounted("R1", F0.local("2026-10-01T22:30"), response = JitaiResponse.OPENED)
        val helpful = harness.seedCounted("R1", F0.local("2026-10-01T21:30"), response = JitaiResponse.HELPFUL)
        val dismissed = harness.seedCounted("R1", F0.local("2026-10-01T20:30"), response = JitaiResponse.DISMISSED)
        val undelivered = harness.seedCounted("R1", F0.local("2026-10-01T20:00"), state = DecisionState.DECIDED)
        fun planned(metric: OutcomeMetric, record: dev.agentle.jitai.engine.decision.DecisionRecord) =
            OutcomePlanner.plan(record, r1.copy(outcome = OutcomeSpec(OutcomeMetricRef(metric, windowMinutes = 60))), F0.BERLIN).single()
        val now = F0.local("2026-10-01T23:00")
        suspend fun compute(
            metric: OutcomeMetric,
            record: dev.agentle.jitai.engine.decision.DecisionRecord,
            at: kotlin.time.Instant = now,
        ) = OutcomeEvaluator.compute(planned(metric, record), record, at, F0.BERLIN, harness.outcomes).getOrThrow()

        assertThat(compute(OutcomeMetric.NOTIFICATION_OPENED, opened).value).isEqualTo(FeatureScalar.BoolValue(true))
        assertThat(compute(OutcomeMetric.NOTIFICATION_OPENED, undelivered).value).isNull()
        assertThat(compute(OutcomeMetric.SELF_REPORT_HELPFUL, helpful).value).isEqualTo(FeatureScalar.EnumValue("HELPFUL"))
        assertThat(compute(OutcomeMetric.SELF_REPORT_HELPFUL, dismissed).value).isEqualTo(FeatureScalar.EnumValue("NONE"))
        assertThat(compute(OutcomeMetric.STEPS_AFTER, opened).state).isEqualTo(OutcomeState.PENDING)
        assertThat(compute(OutcomeMetric.STEPS_AFTER, opened, now + 72.hours).state).isEqualTo(OutcomeState.UNAVAILABLE)
        val none = OutcomeEvaluator.compute(planned(OutcomeMetric.STEPS_AFTER, opened), opened, now, F0.BERLIN, null).getOrThrow()
        assertThat(none.state).isEqualTo(OutcomeState.PENDING)
        harness.outcomes.failMeasure = true
        val failed = OutcomeEvaluator.compute(planned(OutcomeMetric.STEPS_AFTER, opened), opened, now, F0.BERLIN, harness.outcomes)
        assertThat(failed).isInstanceOf(Outcome.Failure::class.java)
    }

    @Test
    fun `a MISSED row plans no outcome rows and a rule without a spec plans none`() = runTest {
        val harness = F0.harness(F0.local("2026-10-01T23:00"), r1)
        val missed = harness.seedCounted("R1", F0.local("2026-10-01T22:30"), state = DecisionState.MISSED)
        assertThat(OutcomePlanner.timerRows(missed, r1, F0.BERLIN)).isEmpty()
        assertThat(OutcomePlanner.plan(missed, Rules.R1, F0.BERLIN)).isEmpty()
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(OutcomeMetric::class)
    fun `positivity is false for non-available, distal and wrong-typed results`(metric: OutcomeMetric) {
        val at = F0.local("2026-10-01T23:00")
        fun result(role: OutcomeRole, state: OutcomeState, value: FeatureScalar?) = OutcomeResult("k", role, metric, state, value, at)
        assertThat(OutcomePositivity.isPositive(result(OutcomeRole.PROXIMAL, OutcomeState.PENDING, null))).isFalse()
        assertThat(OutcomePositivity.isPositive(result(OutcomeRole.DISTAL, OutcomeState.AVAILABLE, FeatureScalar.IntValue(0)))).isFalse()
        val positive = when (metric) {
            OutcomeMetric.NOTIFICATION_OPENED -> FeatureScalar.BoolValue(true)
            OutcomeMetric.SELF_REPORT_HELPFUL -> FeatureScalar.EnumValue("HELPFUL")
            OutcomeMetric.STEPS_AFTER -> FeatureScalar.IntValue(10)
            else -> FeatureScalar.IntValue(0)
        }
        val expected = metric !in setOf(OutcomeMetric.BEDTIME_NEXT, OutcomeMetric.SLEEP_MINUTES_NEXT, OutcomeMetric.STEPS_DAY_TOTAL)
        assertWithMessage(metric.name).that(OutcomePositivity.isPositive(result(OutcomeRole.PROXIMAL, OutcomeState.AVAILABLE, positive)))
            .isEqualTo(expected)
    }
}
