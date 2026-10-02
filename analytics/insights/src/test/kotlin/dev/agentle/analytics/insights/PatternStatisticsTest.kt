package dev.agentle.analytics.insights

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Hand-computed statistics (docs/research/10 §14.4). */
class PatternStatisticsTest {
    @Test
    fun `rates, risk difference and lift of a 2x2 table`() {
        val t = TwoByTwo(17, 7, 9, 27)

        assertThat(t.n).isEqualTo(60)
        assertThat(t.rateExposed!!).isWithin(1e-12).of(17.0 / 24)
        assertThat(t.rateUnexposed!!).isWithin(1e-12).of(0.25)
        assertThat(t.riskDifference!!).isWithin(1e-12).of(17.0 / 24 - 0.25)
        assertThat(t.lift!!).isWithin(1e-12).of((17.0 / 24) / 0.25)
        assertThat(TwoByTwo(3, 1, 0, 9).lift).isNull()
        assertThat(TwoByTwo(0, 0, 3, 9).riskDifference).isNull()
        assertThat(TwoByTwo(1, 2, 3, 4) + TwoByTwo(1, 1, 1, 1)).isEqualTo(TwoByTwo(2, 3, 4, 5))
        assertThat(TwoByTwo.of(listOf(true to true, true to false, false to true, false to false, false to false)))
            .isEqualTo(TwoByTwo(1, 1, 1, 2))
        assertThrows<IllegalArgumentException> { TwoByTwo(-1, 0, 0, 0) }
    }

    @ParameterizedTest(name = "W{index} Wilson {0}/{1}")
    @CsvSource(
        // x, n, lower, upper: Wilson score intervals with z = 1.959964 (closed form, checked with the R10 calibration script).
        "17, 24, 0.5083, 0.8509",
        "9, 36, 0.1375, 0.4107",
        "0, 10, 0.0, 0.2775",
        "10, 10, 0.7225, 1.0",
        "0, 0, 0.0, 1.0",
    )
    fun `Wilson score intervals`(x: Int, n: Int, lower: Double, upper: Double) {
        val w = PatternStatistics.wilson(x, n)

        assertWithMessage("$x/$n").that(w.lower).isWithin(5e-5).of(lower)
        assertThat(w.upper).isWithin(5e-5).of(upper)
    }

    @Test
    fun `Newcombe interval of P1 is 0_202 to 0_640`() {
        val ci = PatternStatistics.newcombe(TwoByTwo(17, 7, 9, 27))!!

        assertThat(PatternStatistics.significant(ci.lower)).isEqualTo(0.202)
        assertThat(PatternStatistics.significant(ci.upper)).isEqualTo(0.64)
        assertThat(PatternStatistics.newcombe(TwoByTwo(0, 0, 1, 1))).isNull()
    }

    @Test
    fun `Benjamini-Hochberg q-values are step-up, monotone and capped at 1`() {
        // m = 4, sorted p: 0.01, 0.02, 0.03, 0.5 -> 0.04, 0.04, 0.04, 0.5 (0.02*4/2 = 0.04, 0.03*4/3 = 0.04).
        val q = PatternStatistics.benjaminiHochberg(listOf(0.03, 0.5, 0.01, 0.02))

        assertThat(q[0]).isWithin(1e-12).of(0.04)
        assertThat(q[1]).isWithin(1e-12).of(0.5)
        assertThat(q[2]).isWithin(1e-12).of(0.04)
        assertThat(q[3]).isWithin(1e-12).of(0.04)
        assertThat(PatternStatistics.benjaminiHochberg(listOf(0.00103) + List(17) { 1.0 })[0]).isWithin(1e-12).of(0.00103 * 18)
        assertThat(PatternStatistics.benjaminiHochberg(listOf(0.9, 0.95))).containsExactly(0.95, 0.95).inOrder()
    }

    @Test
    fun `evidence keeps three significant digits and percentages round half up`() {
        assertThat(PatternStatistics.significant(0.70833333)).isEqualTo(0.708)
        assertThat(PatternStatistics.significant(0.0010343)).isEqualTo(0.00103)
        assertThat(PatternStatistics.significant(0.018548)).isEqualTo(0.0185)
        assertThat(PatternStatistics.significant(2.8333)).isEqualTo(2.83)
        assertThat(PatternStatistics.significant(-0.45833)).isEqualTo(-0.458)
        assertThat(PatternStatistics.significant(0.0)).isEqualTo(0.0)
        assertThat(PatternStatistics.percent(17.0 / 24)).isEqualTo(71)
        assertThat(PatternStatistics.percent(0.125)).isEqualTo(13)
        assertThrows<IllegalArgumentException> { PatternStatistics.significant(Double.NaN) }
    }

    @Test
    fun `exact test of one stratum enumerates the hypergeometric distribution`() {
        // N = 4, n1 = 2, Y = 2, a = 2: P(a = 0, 1, 2) = 1/6, 4/6, 1/6; RD(2) = 1, RD(1) = 0, RD(0) = -1, so p = 1/3.
        val strata = listOf(TwoByTwo(2, 0, 0, 2))

        assertThat(ExactStratifiedTest.riskDifference(strata)).isEqualTo(1.0)
        assertThat(ExactStratifiedTest.pValue(strata)).isWithin(1e-12).of(1.0 / 3)
    }

    @Test
    fun `exact test of two strata multiplies the hypergeometric distributions`() {
        // Stratum A: N = 4, n1 = 2, Y = 2, a = 2 (weight 1); stratum B: N = 2, n1 = 1, Y = 1, a = 1 (weight 1/2).
        // W*RD_MH = (2*4 - 2*2)/4 + (1*2 - 1*1)/2 = 1 + 1/2; over W = 3/2 gives RD_MH = 1. Only (2, 1) and (0, 0) reach
        // abs(RD_MH) = 1: p = 1/6 * 1/2 + 1/6 * 1/2 = 1/6.
        val strata = listOf(TwoByTwo(2, 0, 0, 2), TwoByTwo(1, 0, 0, 1))

        assertThat(ExactStratifiedTest.riskDifference(strata)).isEqualTo(1.0)
        assertThat(ExactStratifiedTest.pValue(strata)).isWithin(1e-12).of(1.0 / 6)
    }

    @Test
    fun `a stratum without exposed or unexposed nights carries no information`() {
        val informative = TwoByTwo(2, 0, 0, 2)

        assertThat(ExactStratifiedTest.pValue(listOf(informative, TwoByTwo(3, 2, 0, 0)))).isWithin(1e-12).of(1.0 / 3)
        assertThat(ExactStratifiedTest.riskDifference(listOf(TwoByTwo(3, 2, 0, 0), TwoByTwo(0, 0, 1, 4)))).isNull()
        assertThat(ExactStratifiedTest.pValue(listOf(TwoByTwo(3, 2, 0, 0)))).isEqualTo(1.0)
        assertThat(ExactStratifiedTest.pValue(listOf(TwoByTwo.EMPTY))).isEqualTo(1.0)
    }

    @Test
    fun `P1 gives RD_MH 0_460 and exact p 0_00103`() {
        val work = TwoByTwo(10, 4, 7, 21)
        val weekend = TwoByTwo(7, 3, 2, 6)

        assertThat(PatternStatistics.significant(ExactStratifiedTest.riskDifference(listOf(work, weekend))!!)).isEqualTo(0.46)
        assertThat(PatternStatistics.significant(ExactStratifiedTest.pValue(listOf(work, weekend)))).isEqualTo(0.00103)
    }

    @Test
    fun `C1 has RD_MH 0 and p 1 although the crude Fisher test gives 0_0021`() {
        val weekend = TwoByTwo(7, 7, 1, 1)
        val work = TwoByTwo(0, 6, 0, 34)

        assertThat(ExactStratifiedTest.riskDifference(listOf(work, weekend))).isEqualTo(0.0)
        assertThat(ExactStratifiedTest.pValue(listOf(work, weekend))).isWithin(1e-12).of(1.0)
        assertThat(PatternStatistics.significant(fisherTwoSided(work + weekend), 2)).isEqualTo(0.0021)
    }
}
