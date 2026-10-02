package dev.agentle.analytics.insights

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.SourceFamily
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** The deterministic controls of docs/research/10 §14.6 and the discovery goldens R6-R8 of §12.R. */
class PatternAnalyzerTest {
    private val h04 = HypothesisFamily.byId("H04")!!

    @Test
    fun `R6 C1 exact weekend confound is NONE with RD_MH 0 and p 1`() {
        val run = PatternAnalyzer.analyze(Controls.table(Controls.C_FIRST, 56, Controls.C1), T0)
        val r = run.result(h04)

        assertThat(r.eligible).isTrue()
        assertThat(r.table).isEqualTo(TwoByTwo(7, 13, 1, 35))
        assertThat(PatternStatistics.significant(r.riskDifference!!)).isEqualTo(0.322)
        assertThat(PatternStatistics.significant(r.interval!!.lower)).isEqualTo(0.119)
        assertThat(PatternStatistics.significant(r.interval!!.upper)).isEqualTo(0.541)
        assertThat(PatternStatistics.significant(r.table.lift!!)).isEqualTo(12.6)
        assertThat(r.riskDifferenceMh).isEqualTo(0.0)
        assertThat(r.pValue).isWithin(1e-12).of(1.0)
        assertThat(r.tier).isEqualTo(PatternTier.NONE)
        assertThat(run.claims).isEmpty()
    }

    @Test
    fun `C2 equal rates within each night type is NONE`() {
        val r = PatternAnalyzer.analyze(Controls.table(Controls.C_FIRST, 56, Controls.C2), T0).result(h04)

        assertThat(r.riskDifferenceMh).isEqualTo(0.0)
        assertThat(r.pValue).isWithin(1e-12).of(1.0)
        assertThat(r.tier).isEqualTo(PatternTier.NONE)
    }

    @Test
    fun `R7 C3 with 27 complete nights is INSUFFICIENT`() {
        val r = PatternAnalyzer.analyze(Controls.table(Controls.C_FIRST, 27, Controls.C3), T0).result(h04)

        assertThat(r.table.n).isEqualTo(27)
        assertThat(r.eligible).isFalse()
        assertThat(r.riskDifferenceMh).isNull()
        assertThat(r.pValue).isEqualTo(1.0)
        assertThat(r.tier).isEqualTo(PatternTier.INSUFFICIENT)
        assertThat(r.patternId).isNull()
    }

    @Test
    fun `R8 P1 planted control is MODERATE with RD_MH 0_460, p 0_00103 and q 0_0185`() {
        val run = PatternAnalyzer.analyze(Controls.p1(), T0)
        val r = run.result(h04)

        assertThat(r.table).isEqualTo(TwoByTwo(17, 7, 9, 27))
        assertThat(r.strata.map { it.table }).containsExactly(TwoByTwo(10, 4, 7, 21), TwoByTwo(7, 3, 2, 6)).inOrder()
        assertThat(PatternStatistics.significant(r.riskDifference!!)).isEqualTo(0.458)
        assertThat(PatternStatistics.significant(r.table.lift!!)).isEqualTo(2.83)
        assertThat(PatternStatistics.significant(r.riskDifferenceMh!!)).isEqualTo(0.46)
        assertThat(PatternStatistics.significant(r.pValue)).isEqualTo(0.00103)
        assertThat(PatternStatistics.significant(r.qValue)).isEqualTo(0.0185)
        assertThat(r.strataCheck).isTrue()
        assertThat(r.splitHalfCheck).isTrue()
        assertThat(r.tier).isEqualTo(PatternTier.MODERATE)
        assertThat(run.claims.keys).containsExactly("H04:+")
        assertThat(run.firstNight).isEqualTo(date("2026-08-06"))
        assertThat(run.lastNight).isEqualTo(date("2026-10-04"))
    }

    @Test
    fun `untestable hypotheses count in the family with p 1`() {
        val run = PatternAnalyzer.analyze(Controls.p1(), T0)

        assertThat(run.results).hasSize(18)
        assertThat(run.results.filter { it.hypothesis != h04 }.map { it.pValue }.toSet()).containsExactly(1.0)
        assertThat(run.results.filter { it.hypothesis != h04 }.map { it.tier }.toSet()).containsExactly(PatternTier.INSUFFICIENT)
        assertThat(run.results.filter { it.hypothesis != h04 }.map { it.qValue }.toSet()).containsExactly(1.0)
    }

    @Test
    fun `a result carries the union of its exposure's and outcome's lineage`() {
        val r = PatternAnalyzer.analyze(Controls.p1(), T0).result(h04)

        assertThat(r.lineage.categories).containsExactly(DataCategory.SCREEN, DataCategory.SLEEP)
        assertThat(r.lineage.sourceFamilies).containsExactly(SourceFamily.ON_DEVICE, SourceFamily.GH_API)
    }

    @Test
    fun `a large consistent association over 90 nights is STRONG`() {
        val cells = listOf(
            Cells(NightType.WORK_NIGHT, 16, exposed = true, outcome = true),
            Cells(NightType.WORK_NIGHT, 4, exposed = true, outcome = false),
            Cells(NightType.WORK_NIGHT, 8, exposed = false, outcome = true),
            Cells(NightType.WORK_NIGHT, 36, exposed = false, outcome = false),
            Cells(NightType.WEEKEND_NIGHT, 9, exposed = true, outcome = true),
            Cells(NightType.WEEKEND_NIGHT, 3, exposed = true, outcome = false),
            Cells(NightType.WEEKEND_NIGHT, 3, exposed = false, outcome = true),
            Cells(NightType.WEEKEND_NIGHT, 11, exposed = false, outcome = false),
        )
        val r = PatternAnalyzer.analyze(Controls.table(Controls.C_FIRST, 90, cells), T0).result(h04)

        assertThat(r.qValue).isAtMost(PatternAnalyzer.STRONG_Q)
        assertThat(r.tier).isEqualTo(PatternTier.STRONG)
    }

    @Test
    fun `a modest association in 28 nights is WEAK and never a finding`() {
        val cells = listOf(
            Cells(NightType.WORK_NIGHT, 2, exposed = true, outcome = true),
            Cells(NightType.WORK_NIGHT, 2, exposed = true, outcome = false),
            Cells(NightType.WORK_NIGHT, 2, exposed = false, outcome = true),
            Cells(NightType.WORK_NIGHT, 14, exposed = false, outcome = false),
            Cells(NightType.WEEKEND_NIGHT, 2, exposed = true, outcome = true),
            Cells(NightType.WEEKEND_NIGHT, 2, exposed = true, outcome = false),
            Cells(NightType.WEEKEND_NIGHT, 1, exposed = false, outcome = true),
            Cells(NightType.WEEKEND_NIGHT, 3, exposed = false, outcome = false),
        )
        val r = PatternAnalyzer.analyze(Controls.table(Controls.C_FIRST, 28, cells), T0).result(h04)

        assertThat(r.eligible).isTrue()
        assertThat(r.riskDifferenceMh!!).isAtLeast(PatternAnalyzer.MODERATE_RD)
        assertThat(r.qValue).isGreaterThan(PatternAnalyzer.MODERATE_Q)
        assertThat(r.tier).isEqualTo(PatternTier.WEAK)
        assertThat(r.isClaim).isFalse()
    }

    @Test
    fun `opposite signs in the two night types fail the strata check`() {
        val cells = listOf(
            Cells(NightType.WORK_NIGHT, 9, exposed = true, outcome = true),
            Cells(NightType.WORK_NIGHT, 1, exposed = true, outcome = false),
            Cells(NightType.WORK_NIGHT, 3, exposed = false, outcome = true),
            Cells(NightType.WORK_NIGHT, 27, exposed = false, outcome = false),
            Cells(NightType.WEEKEND_NIGHT, 2, exposed = true, outcome = true),
            Cells(NightType.WEEKEND_NIGHT, 6, exposed = true, outcome = false),
            Cells(NightType.WEEKEND_NIGHT, 6, exposed = false, outcome = true),
            Cells(NightType.WEEKEND_NIGHT, 2, exposed = false, outcome = false),
        )
        val r = PatternAnalyzer.analyze(Controls.table(Controls.C_FIRST, 56, cells), T0).result(h04)

        assertThat(r.riskDifferenceMh!!).isGreaterThan(0.0)
        assertThat(r.strataCheck).isFalse()
        assertThat(r.tier).isEqualTo(PatternTier.WEAK)
    }

    @Test
    fun `an association only in the newer half fails the split-half check`() {
        val nights = (0 until 56).map { i ->
            val j = i % 28
            val exposed = j % 3 == 0
            val outcome = if (i < 28) (if (exposed) j == 0 || j == 15 else j % 3 == 1 && j < 18) else (if (exposed) j != 0 else j == 1)
            Night(
                Controls.C_FIRST.plusDays(i),
                NightType.WORK_NIGHT,
                mapOf(Exposure.SCREEN_45 to exposed),
                mapOf(NightOutcome.LATE_BEDTIME to outcome),
            )
        }
        val r = PatternAnalyzer.analyze(NightTable(nights.first().date, nights.last().date, nights), T0).result(h04)

        assertThat(r.table).isEqualTo(TwoByTwo(11, 9, 7, 29))
        assertThat(r.riskDifferenceMh!!).isGreaterThan(0.0)
        assertThat(r.splitHalfCheck).isFalse()
        assertThat(r.isClaim).isFalse()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("eligibility")
    fun `eligibility needs 28 nights, 7 per arm and 5 with and without the outcome`(row: String, table: TwoByTwo, eligible: Boolean) {
        val r = PatternAnalyzer.analyze(single(table), T0).result(h04)

        assertWithMessage(row).that(r.eligible).isEqualTo(eligible)
        assertThat(r.tier == PatternTier.INSUFFICIENT).isEqualTo(!eligible)
    }

    @Test
    fun `the family is numbered 3 x (exposure - 1) + outcome`() {
        assertThat(HypothesisFamily.all.map { it.id }).isEqualTo((1..18).map { "H" + it.toString().padStart(2, '0') })
        assertThat(h04.exposure).isEqualTo(Exposure.SCREEN_45)
        assertThat(h04.outcome).isEqualTo(NightOutcome.LATE_BEDTIME)
        assertThat(HypothesisFamily.byId("H18")).isEqualTo(Hypothesis(Exposure.STEPS_UNDER_5K, NightOutcome.HIGH_RESTING_HR))
        assertThat(HypothesisFamily.byId("H19")).isNull()
        assertThat(h04.patternId(0.4)).isEqualTo("H04:+")
        assertThat(h04.patternId(-0.4)).isEqualTo("H04:-")
        assertThat(Exposure.byId("E_notif20")).isEqualTo(Exposure.NOTIFICATIONS_20)
        assertThat(NightOutcome.byId("O_rhr")).isEqualTo(NightOutcome.HIGH_RESTING_HR)
        assertThat(Exposure.byId("E_x")).isNull()
        assertThat(NightOutcome.byId("O_x")).isNull()
    }

    @Test
    fun `a run must hold the whole family in order`() {
        val run = PatternAnalyzer.analyze(Controls.p1(), T0)

        assertThrows<IllegalArgumentException> { run.copy(results = run.results.drop(1)) }
    }

    companion object {
        /** All nights WORK_NIGHT: one stratum, so RD_MH is the crude RD. */
        fun single(t: TwoByTwo): NightTable {
            val cells =
                List(t.a) { true to true } + List(t.b) { true to false } + List(t.c) { false to true } + List(t.d) { false to false }
            val nights = cells.mapIndexed { i, (e, o) ->
                Night(
                    Controls.C_FIRST.plusDays(i),
                    NightType.WORK_NIGHT,
                    mapOf(Exposure.SCREEN_45 to e),
                    mapOf(NightOutcome.LATE_BEDTIME to o),
                )
            }
            return NightTable(Controls.C_FIRST, Controls.C_FIRST.plusDays(maxOf(0, nights.size - 1)), nights)
        }

        @JvmStatic
        fun eligibility(): List<Arguments> = listOf(
            Arguments.of("E1 27 nights", TwoByTwo(5, 5, 5, 12), false),
            Arguments.of("E2 28 nights, 7 exposed, 5 with and 23 without the outcome", TwoByTwo(3, 4, 2, 19), true),
            Arguments.of("E3 6 exposed nights", TwoByTwo(3, 3, 5, 17), false),
            Arguments.of("E4 6 unexposed nights", TwoByTwo(3, 19, 2, 4), false),
            Arguments.of("E5 4 nights with the outcome", TwoByTwo(2, 8, 2, 16), false),
            Arguments.of("E6 4 nights without the outcome", TwoByTwo(8, 2, 16, 2), false),
        )
    }
}
