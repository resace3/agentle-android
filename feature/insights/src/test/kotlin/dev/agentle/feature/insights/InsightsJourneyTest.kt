package dev.agentle.feature.insights

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.core.common.Outcome
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.core.ui.navigation.JitaiTab
import dev.agentle.feature.insights.builder.NlBuilderViewModel
import dev.agentle.feature.insights.builder.ui.NlBuilderRoute
import dev.agentle.feature.insights.insight.InsightDetailViewModel
import dev.agentle.feature.insights.insight.InsightListViewModel
import dev.agentle.feature.insights.insight.ui.InsightDetailRoute
import dev.agentle.feature.insights.insight.ui.InsightListRoute
import dev.agentle.feature.insights.jitai.JitaiListViewModel
import dev.agentle.feature.insights.jitai.ui.JitaiListRoute
import dev.agentle.feature.insights.port.DeliveryResult
import dev.agentle.feature.insights.port.InterpretationEvent
import dev.agentle.feature.insights.port.NlConversion
import dev.agentle.feature.insights.review.ProposalReviewViewModel
import dev.agentle.feature.insights.review.ui.ProposalReviewRoute
import dev.agentle.feature.insights.testing.FakeInsightsPort
import dev.agentle.feature.insights.testing.FakeJitaiBuilderPort
import dev.agentle.feature.insights.testing.FakeJitaiListPort
import dev.agentle.feature.insights.testing.FakeProposalReviewPort
import dev.agentle.feature.insights.testing.Fixtures
import dev.agentle.feature.insights.testing.InsightsTestFrame
import dev.agentle.feature.insights.testing.MainDispatcherRule
import dev.agentle.feature.insights.testing.RecordingNavigator
import dev.agentle.jitai.dsl.model.RuleOrigin
import dev.agentle.jitai.dsl.validation.ValidationReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Journeys 5-7 on the real screens and ViewModels with fake ports, plus semantic accessibility checks (touch targets
 * of at least 48dp, labelled controls). Compose's automatic accessibility checks need an instrumented device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "en-rUS")
class InsightsJourneyTest {
    @get:Rule(order = 0)
    val main = MainDispatcherRule(UnconfinedTestDispatcher())

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val navigator = RecordingNavigator()

    /** Every clickable node has a label and a touch target of at least 48dp in both directions. */
    private fun assertTouchTargets() {
        val clickable = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Role).or(isToggleable()))
        clickable.fetchSemanticsNodes().forEach { node ->
            val density = node.layoutInfo.density.density
            val label = node.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: node.config.toString()
            assertWithMessage(label).that(node.size.width / density).isAtLeast(47.5f)
            assertWithMessage(label).that(node.size.height / density).isAtLeast(47.5f)
        }
    }

    @Test
    fun journey5_insightInterpretedWithAi() {
        val port = FakeInsightsPort().apply {
            feed.value = Fixtures.feed()
            details.value = mapOf(Fixtures.INSIGHT_ID to Fixtures.detail())
            previewResult = Outcome.success(Fixtures.preview())
            answer = flowOf(InterpretationEvent.Delta("Late "), InterpretationEvent.Completed(Fixtures.interpretation()))
        }
        val viewModel = InsightListViewModel(port)
        compose.setContent { InsightsTestFrame { InsightListRoute(viewModel, navigator, onBack = null) } }
        compose.onAllNodesWithText(Fixtures.insight().title, substring = true).onFirst().performClick()
        assertThat(navigator.routes).containsExactly(AppRoute.InsightDetail(Fixtures.INSIGHT_ID))
    }

    @Test
    fun journey5_detailPreviewThenSend() {
        val port = FakeInsightsPort().apply {
            details.value = mapOf(Fixtures.INSIGHT_ID to Fixtures.detail())
            previewResult = Outcome.success(Fixtures.preview())
            answer = flowOf(InterpretationEvent.Delta("Late "), InterpretationEvent.Completed(Fixtures.interpretation()))
        }
        val viewModel = InsightDetailViewModel(Fixtures.INSIGHT_ID, port)
        compose.setContent { InsightsTestFrame { InsightDetailRoute(viewModel, navigator) } }
        compose.onNodeWithText("Explain with AI").performScrollTo().performClick()
        compose.onNodeWithText("What will be sent").performScrollTo()
        assertThat(port.sent).isEmpty()
        assertTouchTargets()
        compose.onNodeWithText("Send").performScrollTo().performClick()
        compose.onNodeWithText(Fixtures.interpretation().text, substring = true).performScrollTo()
        assertThat(port.sent).containsExactly("preview-1")
    }

    @Test
    fun journey6_pauseAndOpenHistory() {
        val port = FakeJitaiListPort().apply {
            overview.value = Fixtures.overview()
            history.value = listOf(Fixtures.record(result = DeliveryResult.OPENED))
        }
        val viewModel = JitaiListViewModel(JitaiTab.ACTIVE, port)
        compose.setContent { InsightsTestFrame { JitaiListRoute(viewModel, navigator) } }
        assertTouchTargets()
        compose.onNodeWithText("Pause").performScrollTo().performClick()
        assertThat(port.calls).containsExactly("pause:${Fixtures.RULE_ID}")

        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Opened", substring = true).performClick()
        assertThat(navigator.routes).contains(AppRoute.InterventionDetail(Fixtures.DECISION_KEY))
    }

    @Test
    fun journey7_naturalLanguageToActiveRule() {
        val builder = FakeJitaiBuilderPort().apply {
            conversion =
                Outcome.success(
                    NlConversion.Proposed(
                        Fixtures.PROPOSAL_ID,
                        ValidationReport(emptyList(), 0, emptyList(), emptyList(), origin = RuleOrigin.AI),
                        "",
                    ),
                )
        }
        val viewModel = NlBuilderViewModel(builder)
        compose.setContent { InsightsTestFrame { NlBuilderRoute(viewModel, navigator) } }
        compose.onNodeWithText("Your request").performTextInput(Fixtures.REQUEST_WALK)
        compose.onNodeWithText("Create rule").performClick()
        assertThat(navigator.routes).containsExactly(AppRoute.ProposalReview(Fixtures.PROPOSAL_ID))
    }

    @Test
    fun journey7_reviewConfirmAndTurnOn() {
        val port = FakeProposalReviewPort().apply { proposals.value = mapOf(Fixtures.PROPOSAL_ID to Fixtures.nlReview()) }
        val viewModel = ProposalReviewViewModel(Fixtures.PROPOSAL_ID, port)
        compose.setContent { InsightsTestFrame { ProposalReviewRoute(viewModel, navigator) } }
        compose.onNodeWithText("You asked", substring = true).performScrollTo()
        compose.onAllNodes(isToggleable()).fetchSemanticsNodes().indices.forEach { index ->
            compose.onAllNodes(isToggleable())[index].performScrollTo().performClick()
        }
        assertTouchTargets()
        compose.onNodeWithText("Turn on").performScrollTo().performClick()
        assertThat(port.activated).hasSize(1)
        assertThat(navigator.routes).containsExactly(AppRoute.Jitais(JitaiTab.ACTIVE))
    }
}
