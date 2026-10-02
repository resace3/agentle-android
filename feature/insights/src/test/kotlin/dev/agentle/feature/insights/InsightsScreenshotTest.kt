package dev.agentle.feature.insights

import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentle.core.ui.navigation.JitaiTab
import dev.agentle.feature.insights.builder.ui.BuilderActions
import dev.agentle.feature.insights.builder.ui.ManualBuilderScreen
import dev.agentle.feature.insights.builder.ui.NlActions
import dev.agentle.feature.insights.builder.ui.NlBuilderScreen
import dev.agentle.feature.insights.insight.ui.InsightDetailActions
import dev.agentle.feature.insights.insight.ui.InsightDetailScreen
import dev.agentle.feature.insights.insight.ui.InsightListScreen
import dev.agentle.feature.insights.intervention.ui.InterventionActions
import dev.agentle.feature.insights.intervention.ui.InterventionDetailScreen
import dev.agentle.feature.insights.jitai.ui.JitaiListActions
import dev.agentle.feature.insights.jitai.ui.JitaiListScreen
import dev.agentle.feature.insights.review.ui.ProposalReviewScreen
import dev.agentle.feature.insights.review.ui.ReviewActions
import dev.agentle.feature.insights.testing.InsightsTestFrame
import dev.agentle.feature.insights.testing.MainDispatcherRule
import dev.agentle.feature.insights.testing.ScreenStates
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Insights screens with states from the real ViewModels and fake ports: light/dark, font scale 1.0/2.0. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = RobolectricDeviceQualifiers.MediumPhone)
class InsightsScreenshotTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun capture(name: String, dark: Boolean, fontScale: Float, content: @Composable () -> Unit) {
        captureRoboImage("src/screenshots/$name.png") { InsightsTestFrame(dark, fontScale, content) }
    }

    @Test
    fun insightList() {
        val state = ScreenStates.insightList()
        VARIANTS.forEach { (suffix, dark, scale) ->
            capture("insightList_$suffix", dark, scale) { InsightListScreen(state, {}, {}, {}) }
        }
    }

    @Test
    fun insightDetailAiFinished() {
        val state = ScreenStates.insightDetailAiFinished()
        VARIANTS.forEach { (suffix, dark, scale) ->
            capture("insightDetailAiFinished_$suffix", dark, scale) { InsightDetailScreen(state, InsightDetailActions()) }
        }
    }

    @Test
    fun jitaiActive() {
        val state = ScreenStates.jitaiList(JitaiTab.ACTIVE)
        VARIANTS.forEach { (suffix, dark, scale) ->
            capture("jitaiActive_$suffix", dark, scale) { JitaiListScreen(state, JitaiListActions()) }
        }
    }

    @Test
    fun jitaiSuggested() {
        val state = ScreenStates.jitaiList(JitaiTab.SUGGESTED)
        VARIANTS.forEach { (suffix, dark, scale) ->
            capture("jitaiSuggested_$suffix", dark, scale) { JitaiListScreen(state, JitaiListActions()) }
        }
    }

    @Test
    fun jitaiPaused() {
        val state = ScreenStates.jitaiList(JitaiTab.PAUSED)
        VARIANTS.forEach { (suffix, dark, scale) ->
            capture("jitaiPaused_$suffix", dark, scale) { JitaiListScreen(state, JitaiListActions()) }
        }
    }

    @Test
    fun jitaiHistory() {
        val state = ScreenStates.jitaiList(JitaiTab.HISTORY)
        VARIANTS.forEach { (suffix, dark, scale) ->
            capture("jitaiHistory_$suffix", dark, scale) { JitaiListScreen(state, JitaiListActions()) }
        }
    }

    @Test
    fun builderManualError() {
        val state = ScreenStates.builderWithError()
        VARIANTS.forEach { (suffix, dark, scale) ->
            capture("builderManualError_$suffix", dark, scale) { ManualBuilderScreen(state, BuilderActions()) }
        }
    }

    @Test
    fun builderNaturalLanguage() {
        val state = ScreenStates.nlBuilder()
        VARIANTS.forEach { (suffix, dark, scale) ->
            capture("builderNaturalLanguage_$suffix", dark, scale) { NlBuilderScreen(state, NlActions()) }
        }
    }

    @Test
    fun proposalReview() {
        val state = ScreenStates.review()
        VARIANTS.forEach { (suffix, dark, scale) ->
            capture("proposalReview_$suffix", dark, scale) { ProposalReviewScreen(state, ReviewActions()) }
        }
    }

    @Test
    fun proposalReviewDiscovered() {
        val state = ScreenStates.review(discovered = true)
        VARIANTS.forEach { (suffix, dark, scale) ->
            capture("proposalReviewDiscovered_$suffix", dark, scale) { ProposalReviewScreen(state, ReviewActions()) }
        }
    }

    @Test
    fun interventionDetail() {
        val state = ScreenStates.intervention()
        VARIANTS.forEach { (suffix, dark, scale) ->
            capture("interventionDetail_$suffix", dark, scale) { InterventionDetailScreen(state, InterventionActions()) }
        }
    }

    private companion object {
        val VARIANTS = listOf(
            Triple("light", false, 1f),
            Triple("dark", true, 1f),
            Triple("light_fontScale2", false, 2f),
            Triple("dark_fontScale2", true, 2f),
        )
    }
}
