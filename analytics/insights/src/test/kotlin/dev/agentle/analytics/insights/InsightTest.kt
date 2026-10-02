package dev.agentle.analytics.insights

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.AiPurpose
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.EvidenceStrength
import dev.agentle.core.model.InsightOrigin
import dev.agentle.core.model.Lineage
import dev.agentle.core.model.SourceFamily
import dev.agentle.core.model.SupportItem
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Instant

/** Insight cards and the aggregate-only AI wording request of a finding (docs/research/10 §14.4, §14.8, §14.9). */
class InsightTest {
    private val run = PatternAnalyzer.analyze(Controls.p1(), T0)
    private val h04 = run.result(hypothesis("H04"))

    @Test
    fun `the P1 finding becomes one non-causal insight with its lineage`() {
        val insights = PatternInsights.of(run, TimeZone.UTC, T0)

        val insight = insights.single()
        assertThat(insight.id).isEqualTo("pattern-2026-10-04-H04:+")
        assertThat(insight.kind).isEqualTo("pattern.H04")
        assertThat(insight.title).isEqualTo("A possible pattern in your data")
        assertThat(insight.finding).isEqualTo(P1_TEXT)
        assertThat(insight.supportingData).containsExactly(
            SupportItem("Nights compared", "60"),
            SupportItem("Outcome", "Bedtime 30+ minutes later than usual"),
            SupportItem("45+ minutes of screen time after 10 PM", "17 of 24 nights (71%)"),
            SupportItem("Other nights", "9 of 36 nights (25%)"),
            SupportItem("Likely range of the difference", "20 to 64 percentage points"),
        ).inOrder()
        assertThat(insight.periodStart).isEqualTo(Instant.parse("2026-08-06T04:00:00Z"))
        assertThat(insight.periodEnd).isEqualTo(Instant.parse("2026-10-05T04:00:00Z"))
        assertThat(insight.strength).isEqualTo(EvidenceStrength.MODERATE)
        assertThat(insight.confidence).isNull()
        assertThat(insight.origin).isEqualTo(InsightOrigin.LOCAL)
        assertThat(insight.categories).containsExactly(DataCategory.SCREEN, DataCategory.SLEEP)
        assertThat(insight.sourceFamilies).containsExactly(SourceFamily.ON_DEVICE, SourceFamily.GH_API)
        assertThat(insight.createdAt).isEqualTo(T0)
        assertThat(TextLint.check(insight.title + " " + insight.finding)).isEmpty()
    }

    @Test
    fun `a STRONG finding is a clear pattern`() {
        val strong = claim(hypothesis("H04"), tier = PatternTier.STRONG, q = 0.005)

        val insight = requireNotNull(PatternInsights.of(strong, runOf(date("2026-10-04"), strong), TimeZone.UTC, T0))

        assertThat(insight.title).isEqualTo("A clear pattern in your data")
        assertThat(insight.strength).isEqualTo(EvidenceStrength.STRONG)
    }

    @Test
    fun `WEAK, NONE and INSUFFICIENT results are never shown`() {
        val c1 = PatternAnalyzer.analyze(Controls.table(Controls.C_FIRST, 56, Controls.C1), T0)
        val weak = claim(hypothesis("H04"), tier = PatternTier.WEAK)

        assertThat(PatternInsights.of(c1, TimeZone.UTC, T0)).isEmpty()
        assertThat(PatternInsights.of(weak, runOf(date("2026-10-04"), weak), TimeZone.UTC, T0)).isNull()
        assertThat(InsightAiRequestBuilder.build(weak)).isNull()
        assertThat(InsightAiRequestBuilder.build(untested(hypothesis("H04")))).isNull()
    }

    @Test
    fun `a finding without recorded lineage counts as every category and family`() {
        val unknown = claim(hypothesis("H04"), lineage = Lineage.NONE)

        val insight = requireNotNull(PatternInsights.of(unknown, runOf(date("2026-10-04"), unknown), TimeZone.UTC, T0))

        assertThat(insight.categories).isEqualTo(Lineage.UNKNOWN.categories)
        assertThat(insight.sourceFamilies).isEqualTo(Lineage.UNKNOWN.sourceFamilies)
        assertThat(requireNotNull(InsightAiRequestBuilder.build(unknown)).lineage).isEqualTo(Lineage.UNKNOWN)
    }

    @Test
    fun `the strata sentence names the night types that were checked`() {
        val both = claim(hypothesis("H04"))
        val workOnly = both.copy(
            strata = listOf(StratumResult(NightType.WORK_NIGHT, P1_WORK), StratumResult(NightType.WEEKEND_NIGHT, TwoByTwo(3, 0, 1, 1))),
        )
        val weekendOnly = both.copy(
            strata = listOf(StratumResult(NightType.WORK_NIGHT, TwoByTwo(1, 1, 3, 0)), StratumResult(NightType.WEEKEND_NIGHT, P1_WEEKEND)),
        )
        val neither = both.copy(strata = NightType.entries.map { StratumResult(it, TwoByTwo(3, 0, 0, 3)) })

        assertThat(PatternText.strataSentence(both)).isEqualTo("The pattern showed up on work nights and on weekend nights.")
        assertThat(
            PatternText.strataSentence(workOnly),
        ).isEqualTo("The pattern showed up on work nights; weekend nights were too few to check.")
        assertThat(
            PatternText.strataSentence(weekendOnly),
        ).isEqualTo("The pattern showed up on weekend nights; work nights were too few to check.")
        assertThat(PatternText.strataSentence(neither)).isEqualTo("Work nights and weekend nights were too few to check separately.")
    }

    @Test
    fun `the other nights sentence counts none, once and several times`() {
        val none = claim(hypothesis("H04")).copy(table = TwoByTwo(17, 7, 0, 36))
        val once = claim(hypothesis("H04")).copy(table = TwoByTwo(17, 7, 1, 35))

        assertThat(PatternText.finding(none)).contains("On the other 36 nights this did not happen.")
        assertThat(PatternText.finding(once)).contains("On the other 36 nights this happened once (3%).")
        assertThat(PatternText.supportItems(none).map { it.label }).contains("Likely range of the difference")
    }

    @Test
    fun `the AI wording request carries the template and aggregate numbers only`() {
        val request = requireNotNull(InsightAiRequestBuilder.build(h04))

        assertThat(request.purpose).isEqualTo(AiPurpose.PATTERN_EXPLANATION)
        assertThat(request.instructions).isEqualTo(InsightAiRequestBuilder.INSTRUCTIONS)
        assertThat(request.template).isEqualTo(P1_TEXT)
        assertThat(request.values).containsExactly(
            InsightAiValue.Code(InsightAiField.TIER, "MODERATE"),
            InsightAiValue.Code(InsightAiField.EXPOSURE, "E_screen45"),
            InsightAiValue.Code(InsightAiField.OUTCOME, "O_late"),
            InsightAiValue.Number(InsightAiField.NIGHTS_COMPLETE, 60),
            InsightAiValue.Number(InsightAiField.EXPOSED_NIGHTS, 24),
            InsightAiValue.Number(InsightAiField.EXPOSED_WITH_OUTCOME, 17),
            InsightAiValue.Number(InsightAiField.OTHER_NIGHTS, 36),
            InsightAiValue.Number(InsightAiField.OTHER_WITH_OUTCOME, 9),
            InsightAiValue.Number(InsightAiField.PERCENT_EXPOSED, 71),
            InsightAiValue.Number(InsightAiField.PERCENT_OTHER, 25),
            InsightAiValue.Number(InsightAiField.RANGE_LOW_POINTS, 20),
            InsightAiValue.Number(InsightAiField.RANGE_HIGH_POINTS, 64),
        ).inOrder()
        assertThat(request.numbers).containsExactly("17", "24", "71", "10", "45", "30", "36", "9", "25", "60", "20", "64")
        assertThat(request.lineage).isEqualTo(SCREEN_LINEAGE + SLEEP_LINEAGE)
        // No dates, app or package names and no event data.
        assertThat(Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(request.toString())).isFalse()
        assertThat(request.toString()).doesNotContain("2026")
        assertThat(InsightAiField.entries.map { it.code }).contains("difference_range_low_points")
    }

    @Test
    fun `codes cannot carry package names, dates or free text`() {
        assertThrows<IllegalArgumentException> { InsightAiValue.Code(InsightAiField.EXPOSURE, "com.instagram.android") }
        assertThrows<IllegalArgumentException> { InsightAiValue.Code(InsightAiField.EXPOSURE, "2026-10-04") }
        assertThrows<IllegalArgumentException> { InsightAiValue.Code(InsightAiField.EXPOSURE, "late night") }
        assertThat(InsightAiValue.Code(InsightAiField.TIER, "STRONG").field).isEqualTo(InsightAiField.TIER)
    }
}
