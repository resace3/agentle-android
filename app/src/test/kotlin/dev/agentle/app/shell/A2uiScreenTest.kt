package dev.agentle.app.shell

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.screen.CardPart
import dev.agentle.ai.api.screen.ColumnPart
import dev.agentle.ai.api.screen.DividerPart
import dev.agentle.ai.api.screen.MetricTilePart
import dev.agentle.ai.api.screen.RowPart
import dev.agentle.ai.api.screen.ScreenCatalog
import dev.agentle.ai.api.screen.ScreenJson
import dev.agentle.ai.api.screen.ScreenPart
import dev.agentle.ai.api.screen.ScreenSpec
import dev.agentle.ai.api.screen.TextPart
import dev.agentle.ai.api.screen.TrendChartPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Agentle's parts drawn by Google's A2UI renderer, with numbers the phone computed. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33, 37])
class A2uiScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun stopHost() = scope.cancel()

    @Test
    fun `Google's renderer knows every Agentle part by the fields ChatReplySchema checks`() {
        val samples = listOf(
            ColumnPart("a", listOf("b")),
            RowPart("a", listOf("b")),
            CardPart("a", "b"),
            TextPart("a", "Sleep", "h1"),
            DividerPart("a"),
            MetricTilePart("a", "STEPS", 7, "total"),
            TrendChartPart("a", "STEPS", 7, "bar"),
        )
        val checked = samples.associate { part ->
            val json = ScreenJson.encodeToJsonElement(ScreenPart.serializer(), part).jsonObject
            json.getValue(ScreenCatalog.TYPE_KEY).jsonPrimitive.content to json.keys - "id" - ScreenCatalog.TYPE_KEY
        }

        assertThat(AgentleA2uiCatalog.components.associate { it.name to it.properties.map { p -> p.key }.toSet() })
            .isEqualTo(checked)
        assertThat(AgentleA2uiCatalog.catalog.id).isEqualTo(ScreenCatalog.ID)
    }

    @Test
    fun `a saved screen is drawn from Agentle's parts with the phone's own numbers`() {
        val host = A2uiScreenHost(scope)
        val screen = ScreenSpec(
            "My week",
            listOf(
                ColumnPart("root", listOf("title", "tiles", "line", "card")),
                TextPart("title", "My week", "h2"),
                RowPart("tiles", listOf("total", "latest")),
                MetricTilePart("total", "STEPS", 7, "total"),
                MetricTilePart("latest", "SLEEP_MINUTES", 7, "latest"),
                DividerPart("line"),
                CardPart("card", "chart"),
                TrendChartPart("chart", "STEPS", 7, "line"),
            ),
        )
        val first = LocalDate(2026, 9, 28)
        val data = DashboardData(
            listOf(
                MetricSeries(DashboardMetric.STEPS, (0..6).map { first.plus(DatePeriod(days = it)) to 1_000L }),
                MetricSeries(DashboardMetric.SLEEP_MINUTES, (0..6).map { first.plus(DatePeriod(days = it)) to 400L + it }),
            ),
        )

        compose.setContent {
            MaterialTheme {
                val surfaces by host.surfaces.collectAsState()
                CompositionLocalProvider(LocalScreenData provides data) {
                    surfaces.firstOrNull { it.id == "s1" }?.let { A2uiScreen(it) }
                }
            }
        }
        compose.runOnIdle { host.show("s1", screen) }
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("My week").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("My week").assertIsDisplayed()
        compose.onNodeWithText("7,000").assertIsDisplayed()
        compose.onNodeWithText("Total, last 7 days").assertIsDisplayed()
        compose.onNodeWithText("406").assertIsDisplayed()
        compose.onNodeWithText("Latest day, last 7 days").assertIsDisplayed()
        compose.onNodeWithContentDescription("Steps per day").assertExists()
        assertThat(host.failures.value).isEmpty()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
