package dev.agentle.app.shell

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.screen.CardPart
import dev.agentle.ai.api.screen.ColumnPart
import dev.agentle.ai.api.screen.MetricTilePart
import dev.agentle.ai.api.screen.ScreenRules
import dev.agentle.ai.api.screen.ScreenSpec
import dev.agentle.ai.api.screen.TrendChartPart
import dev.agentle.ai.api.validation.ChatReplySchema
import org.junit.Test

class DashboardSpecTest {
    @Test
    fun `every metric ChatGPT may pick is one the app can chart`() {
        assertThat(DashboardMetric.entries.map { it.name }).containsExactlyElementsIn(ChatReplySchema.METRICS.keys)
    }

    @Test
    fun `a model's dashboard becomes a spec only when it is usable`() {
        val spec = DashboardSpec.validated("id", "  Sleep  ", listOf("SLEEP_MINUTES", "SLEEP_MINUTES", "CAFFEINE"), 14)

        assertThat(spec).isEqualTo(DashboardSpec("id", "Sleep", listOf(DashboardMetric.SLEEP_MINUTES), 14))
        assertThat(DashboardSpec.validated("id", " ", listOf("STEPS"), 7)).isNull()
        assertThat(DashboardSpec.validated("id", "Steps", listOf("CAFFEINE"), 7)).isNull()
        assertThat(DashboardSpec.validated("id", "Steps", listOf("STEPS"), DashboardSpec.MAX_DAYS + 1)).isNull()
    }

    @Test
    fun `a dashboard from before saved screens is drawn as one bar chart card per metric`() {
        val spec = DashboardSpec(
            "id",
            "Activity Dashboard",
            listOf(DashboardMetric.SCREEN_TIME_MINUTES, DashboardMetric.RESTING_HEART_RATE),
            14,
        )

        assertThat(spec.layout().components).containsExactly(
            ColumnPart("root", listOf("card_1", "card_2")),
            CardPart("card_1", "chart_1"),
            TrendChartPart("chart_1", "SCREEN_TIME_MINUTES", 14, "bar"),
            CardPart("card_2", "chart_2"),
            TrendChartPart("chart_2", "RESTING_HEART_RATE", 14, "bar"),
        ).inOrder()
        assertThat(ScreenRules.recheck(spec.layout())).isEmpty()
    }

    @Test
    fun `a screen ChatGPT designed becomes a dashboard only when it passes the recheck`() {
        val screen = ScreenSpec(
            "Steps this month",
            listOf(
                ColumnPart("root", listOf("tile", "card")),
                MetricTilePart("tile", "STEPS", 30, "total"),
                CardPart("card", "chart"),
                TrendChartPart("chart", "SLEEP_MINUTES", 7, "line"),
            ),
        )

        assertThat(DashboardSpec.fromScreen("id", screen)).isEqualTo(
            DashboardSpec("id", "Steps this month", listOf(DashboardMetric.STEPS, DashboardMetric.SLEEP_MINUTES), 30, screen),
        )
        assertThat(DashboardSpec.fromScreen("id", screen.copy(title = " "))).isNull()
        assertThat(DashboardSpec.fromScreen("id", screen.copy(components = screen.components.drop(1)))).isNull()
        assertThat(DashboardSpec.fromScreen("id", screen.copy(components = screen.components.dropLast(1)))).isNull()
    }
}
