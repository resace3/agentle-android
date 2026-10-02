package dev.agentle.analytics.insights

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.EvidenceStrength
import dev.agentle.core.model.Insight
import dev.agentle.core.model.InsightOrigin
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/** Regression tests for the integrator's review findings R2-1, R2-2 and the Insight lineage default. */
class InsightRepairsTest {
    @Test
    fun `R2-1 createdAt is written in whole seconds even from a sub-second clock`() {
        val run = PatternAnalyzer.analyze(Controls.p1(), T0)

        val proposal = DiscoveredProposals.build(run.result(hypothesis("H04")), run, "p", Instant.parse("2026-10-05T18:00:00.123456Z"))

        assertThat(proposal.getValue("createdAt").jsonPrimitive.content).isEqualTo("2026-10-05T18:00:00Z")
    }

    @Test
    fun `R2-2 an additive feature with coverage below 1 is unknown, a full one is known`() {
        val last = date("2026-10-20")
        val config = InsightConfig(windowNights = 28)
        val rows = listOf(
            dailyRow(last, "screen_minutes_22_24", 50.0).copy(coverage = 0.96),
            dailyRow(last.plusDays(-1), "screen_minutes_22_24", 50.0),
        )

        val table = NightTableBuilder.build(last, rows, config)

        assertThat(table.nights.single { it.date == last }.exposures).isEmpty()
        assertThat(table.nights.single { it.date == last.plusDays(-1) }.exposures).containsEntry(Exposure.SCREEN_45, true)
    }

    @Test
    fun `an insight without recorded lineage counts as using every category and family`() {
        val insight = Insight(
            id = "i", kind = "k", title = "t", finding = "f", periodStart = T0, periodEnd = T0,
            strength = EvidenceStrength.WEAK, origin = InsightOrigin.LOCAL, createdAt = T0,
        )

        assertThat(insight.categories).isEqualTo(DataCategory.entries.toSet())
    }
}
