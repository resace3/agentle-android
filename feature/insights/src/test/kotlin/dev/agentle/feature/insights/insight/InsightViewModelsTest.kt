package dev.agentle.feature.insights.insight

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.core.common.AppException
import dev.agentle.core.common.Outcome
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.feature.insights.common.AiProblem
import dev.agentle.feature.insights.common.LoadError
import dev.agentle.feature.insights.common.ScreenEffect
import dev.agentle.feature.insights.port.InterpretationEvent
import dev.agentle.feature.insights.testing.FakeInsightsPort
import dev.agentle.feature.insights.testing.Fixtures
import dev.agentle.feature.insights.testing.MainDispatcherRule
import dev.agentle.feature.insights.testing.keepCollecting
import dev.agentle.feature.insights.testing.record
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test

class InsightViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val port = FakeInsightsPort()

    @Test
    fun `the list loads, then shows cards with local days in the feed's zone`() = runTest {
        val vm = InsightListViewModel(port)
        keepCollecting(vm.state)
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(InsightListUiState.Loading)

        port.feed.value = Fixtures.feed(interpreted = setOf(Fixtures.INSIGHT_ID))
        advanceUntilIdle()
        val content = vm.state.value as InsightListUiState.Content
        val card = content.insights.single()
        assertThat(card.periodStart).isEqualTo(LocalDate(2026, 8, 6))
        assertThat(card.periodEnd).isEqualTo(LocalDate(2026, 10, 1))
        assertThat(card.hasInterpretation).isTrue()
        assertThat(content.isPartial).isFalse()
    }

    @Test
    fun `empty and partial feeds are reported as such`() = runTest {
        val vm = InsightListViewModel(port)
        keepCollecting(vm.state)
        port.feed.value = Fixtures.feed(insights = emptyList(), missing = listOf(Fixtures.MISSING_SLEEP))
        advanceUntilIdle()
        val content = vm.state.value as InsightListUiState.Content
        assertThat(content.isEmpty).isTrue()
        assertThat(content.isPartial).isTrue()
    }

    @Test
    fun `a storage failure shows its code and retry collects again`() = runTest {
        port.feedError = AppException(AppError.DatabaseError("secret"))
        val vm = InsightListViewModel(port)
        keepCollecting(vm.state)
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(InsightListUiState.Error(LoadError("database_error")))

        port.feedError = null
        port.feed.value = Fixtures.feed()
        vm.retry()
        advanceUntilIdle()
        assertThat(vm.state.value).isInstanceOf(InsightListUiState.Content::class.java)
    }

    @Test
    fun `an unknown insight is not found`() = runTest {
        val vm = InsightDetailViewModel("missing", port)
        keepCollecting(vm.state)
        advanceUntilIdle()
        assertThat(vm.state.value.load).isEqualTo(DetailLoad.NotFound)
    }

    @Test
    fun `interpretation previews, sends only after confirmation and keeps the completed answer`() = runTest {
        port.details.value = mapOf(Fixtures.INSIGHT_ID to Fixtures.detail())
        port.previewResult = Outcome.success(Fixtures.preview())
        port.answer = flowOf(
            InterpretationEvent.Delta("Late "),
            InterpretationEvent.Delta("evenings"),
            InterpretationEvent.Completed(Fixtures.interpretation()),
        )
        val vm = InsightDetailViewModel(Fixtures.INSIGHT_ID, port)
        keepCollecting(vm.state)
        vm.requestInterpretation()
        advanceUntilIdle()
        assertThat(vm.state.value.ai).isEqualTo(AiPanel.Preview(Fixtures.preview()))
        assertThat(port.sent).isEmpty()

        vm.confirmSend()
        advanceUntilIdle()
        assertThat(port.sent).containsExactly("preview-1")
        assertThat(vm.state.value.interpretation).isEqualTo(Fixtures.interpretation())
    }

    @Test
    fun `a stream that fails or ends early discards the partial answer`() = runTest {
        port.details.value = mapOf(Fixtures.INSIGHT_ID to Fixtures.detail())
        port.previewResult = Outcome.success(Fixtures.preview())
        port.answer = flowOf(InterpretationEvent.Delta("half"), InterpretationEvent.Failed(AppError.NetworkUnavailable()))
        val vm = InsightDetailViewModel(Fixtures.INSIGHT_ID, port)
        keepCollecting(vm.state)
        vm.requestInterpretation()
        advanceUntilIdle()
        vm.confirmSend()
        advanceUntilIdle()
        assertThat(vm.state.value.ai).isEqualTo(AiPanel.Problem(AiProblem.OFFLINE))
        assertThat(vm.state.value.interpretation).isNull()

        port.answer = flow { emit(InterpretationEvent.Delta("half")) }
        vm.requestInterpretation()
        advanceUntilIdle()
        vm.confirmSend()
        advanceUntilIdle()
        assertThat(vm.state.value.ai).isEqualTo(AiPanel.Problem(AiProblem.FAILED))
    }

    @Test
    fun `preview errors map to the AI states and their fixes navigate`() = runTest {
        val cases = mapOf(
            AppError.AuthenticationRequired("chatgpt") to AiProblem.NOT_CONNECTED,
            AppError.ConsentViolation(setOf("SLEEP")) to AiProblem.CONSENT_MISSING,
            AppError.RateLimited() to AiProblem.USAGE_LIMIT,
            AppError.NetworkUnavailable() to AiProblem.OFFLINE,
        )
        val vm = InsightDetailViewModel(Fixtures.INSIGHT_ID, port)
        keepCollecting(vm.state)
        val effects = record(vm.effects)
        cases.forEach { (error, problem) ->
            port.previewResult = Outcome.failure(error)
            vm.requestInterpretation()
            advanceUntilIdle()
            assertThat(vm.state.value.ai).isEqualTo(AiPanel.Problem(problem))
        }
        vm.fix(AppRoute.ChatGpt)
        advanceUntilIdle()
        assertThat(effects).containsExactly(ScreenEffect.Navigate(AppRoute.ChatGpt))
    }
}
