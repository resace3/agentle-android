package dev.agentle.feature.insights.testing

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.agentle.core.common.Outcome
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.core.ui.navigation.JitaiTab
import dev.agentle.feature.insights.builder.ManualBuilderUiState
import dev.agentle.feature.insights.builder.ManualBuilderViewModel
import dev.agentle.feature.insights.builder.NlBuilderUiState
import dev.agentle.feature.insights.builder.NlBuilderViewModel
import dev.agentle.feature.insights.insight.InsightDetailUiState
import dev.agentle.feature.insights.insight.InsightDetailViewModel
import dev.agentle.feature.insights.insight.InsightListUiState
import dev.agentle.feature.insights.insight.InsightListViewModel
import dev.agentle.feature.insights.intervention.InterventionDetailUiState
import dev.agentle.feature.insights.intervention.InterventionDetailViewModel
import dev.agentle.feature.insights.jitai.JitaiListUiState
import dev.agentle.feature.insights.jitai.JitaiListViewModel
import dev.agentle.feature.insights.port.DeliveryResult
import dev.agentle.feature.insights.port.InterpretationEvent
import dev.agentle.feature.insights.port.JitaiSummary
import dev.agentle.feature.insights.port.OutcomeReason
import dev.agentle.feature.insights.review.ProposalReviewUiState
import dev.agentle.feature.insights.review.ProposalReviewViewModel
import dev.agentle.jitai.dsl.model.JitaiStatus
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.hours

/** Theme frame of the screenshot and journey tests: light/dark and a font scale (as the hub's AgentleTestFrame). */
@Composable
internal fun InsightsTestFrame(dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) { content() }
        }
    }
}

/** Records navigation instead of performing it. */
internal class RecordingNavigator : AppNavigator {
    val routes = mutableListOf<AppRoute>()
    var backs = 0

    override fun navigate(route: AppRoute) {
        routes += route
    }

    override fun back() {
        backs++
    }

    override fun resetTo(route: AppRoute) {
        routes += route
    }
}

/**
 * Screen states produced by the real ViewModels from the fake ports (so screenshots show what users get).
 * Needs `Dispatchers.Main` replaced ([MainDispatcherRule]).
 */
internal object ScreenStates {
    private fun <T> produce(block: suspend TestScope.() -> T): T {
        var result: T? = null
        runTest { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    fun insightList(): InsightListUiState = produce {
        val port = FakeInsightsPort().apply { feed.value = Fixtures.feed(interpreted = setOf(Fixtures.INSIGHT_ID)) }
        val vm = InsightListViewModel(port)
        keepCollecting(vm.state)
        advanceUntilIdle()
        vm.state.value
    }

    fun insightDetailAiFinished(): InsightDetailUiState = produce {
        val port = FakeInsightsPort().apply {
            details.value = mapOf(Fixtures.INSIGHT_ID to Fixtures.detail())
            previewResult = Outcome.success(Fixtures.preview())
            answer = flowOf(InterpretationEvent.Completed(Fixtures.interpretation()))
        }
        val vm = InsightDetailViewModel(Fixtures.INSIGHT_ID, port)
        keepCollecting(vm.state)
        vm.requestInterpretation()
        advanceUntilIdle()
        vm.confirmSend()
        advanceUntilIdle()
        vm.state.value
    }

    fun jitaiList(tab: JitaiTab): JitaiListUiState = produce {
        val port = FakeJitaiListPort().apply {
            overview.value = Fixtures.overview(
                rules = listOf(
                    JitaiSummary(Fixtures.walkRule(), deliveredToday = 1, deliveredThisWeek = 3),
                    JitaiSummary(Fixtures.walkRule(Fixtures.OTHER_RULE_ID, "Evening stretch", JitaiStatus.PAUSED)),
                ),
            )
            suggestions.value = listOf(Fixtures.suggestion())
            history.value = listOf(
                Fixtures.record("a", DeliveryResult.OPENED),
                Fixtures.record("b", DeliveryResult.SUPPRESSED, OutcomeReason.QUIET_HOURS, Fixtures.NOW - 5.hours),
                Fixtures.record("c", DeliveryResult.CARD_PENDING, at = Fixtures.NOW - 26.hours),
                Fixtures.record("d", DeliveryResult.DELIVERY_UNCERTAIN, at = Fixtures.NOW - 30.hours),
            )
        }
        val vm = JitaiListViewModel(tab, port)
        keepCollecting(vm.state)
        advanceUntilIdle()
        vm.state.value
    }

    fun builderWithError(): ManualBuilderUiState = produce {
        val vm = ManualBuilderViewModel(null, FakeJitaiBuilderPort())
        advanceUntilIdle()
        val form = Fixtures.walkForm((vm.state.value as ManualBuilderUiState.Editing).form.base.id)
        vm.update(form.copy(frequency = form.frequency.copy(maxPerDay = "two")))
        vm.saveDraft()
        advanceUntilIdle()
        vm.state.value
    }

    fun nlBuilder(): NlBuilderUiState = produce {
        val vm = NlBuilderViewModel(FakeJitaiBuilderPort())
        vm.updateText(Fixtures.REQUEST_WALK)
        vm.state.value
    }

    fun review(discovered: Boolean = false): ProposalReviewUiState = produce {
        val port = FakeProposalReviewPort().apply {
            proposals.value = mapOf(Fixtures.PROPOSAL_ID to if (discovered) Fixtures.discoveredReview() else Fixtures.nlReview())
        }
        val vm = ProposalReviewViewModel(Fixtures.PROPOSAL_ID, port)
        keepCollecting(vm.state)
        advanceUntilIdle()
        vm.state.value
    }

    fun intervention(): InterventionDetailUiState = produce {
        val port = FakeInterventionPort().apply {
            details.value = mapOf(Fixtures.DECISION_KEY to Fixtures.intervention(DeliveryResult.OPENED))
        }
        val vm = InterventionDetailViewModel(Fixtures.DECISION_KEY, port)
        keepCollecting(vm.state)
        advanceUntilIdle()
        vm.state.value
    }
}
