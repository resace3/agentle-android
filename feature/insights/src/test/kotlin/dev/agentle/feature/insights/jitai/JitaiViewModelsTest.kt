package dev.agentle.feature.insights.jitai

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.core.ui.navigation.JitaiBuilderMode
import dev.agentle.core.ui.navigation.JitaiTab
import dev.agentle.feature.insights.common.ScreenEffect
import dev.agentle.feature.insights.common.UserMessage
import dev.agentle.feature.insights.port.DeliveryResult
import dev.agentle.feature.insights.port.JitaiSummary
import dev.agentle.feature.insights.port.OutcomeReason
import dev.agentle.feature.insights.testing.FakeJitaiListPort
import dev.agentle.feature.insights.testing.Fixtures
import dev.agentle.feature.insights.testing.MainDispatcherRule
import dev.agentle.feature.insights.testing.keepCollecting
import dev.agentle.feature.insights.testing.record
import dev.agentle.jitai.dsl.model.JitaiStatus
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class JitaiViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val port = FakeJitaiListPort()

    @Test
    fun `active and paused rules are split and rendered with their actions`() = runTest {
        port.overview.value = Fixtures.overview(
            rules = listOf(
                JitaiSummary(Fixtures.walkRule(), deliveredToday = 1),
                JitaiSummary(Fixtures.walkRule(Fixtures.OTHER_RULE_ID, "Paused one", JitaiStatus.PAUSED)),
            ),
        )
        val vm = JitaiListViewModel(JitaiTab.ACTIVE, port)
        keepCollecting(vm.state)
        advanceUntilIdle()
        val lists = (vm.state.value.rules as Load.Loaded).value
        assertThat(lists.active.single().rendering).contains("5:00 PM")
        assertThat(lists.active.single().actions).containsExactly(RuleAction.PAUSE, RuleAction.EDIT, RuleAction.DISABLE)
        assertThat(lists.paused.single().actions).contains(RuleAction.RESUME)
    }

    @Test
    fun `history lists every outcome with its reason`() = runTest {
        port.overview.value = Fixtures.overview()
        port.history.value = listOf(
            Fixtures.record("a", DeliveryResult.CARD_PENDING),
            Fixtures.record("b", DeliveryResult.SUPPRESSED, OutcomeReason.LOST_ARBITRATION),
            Fixtures.record("c", DeliveryResult.SUPPRESSED, OutcomeReason.NOTIFICATIONS_BLOCKED),
            Fixtures.record("d", DeliveryResult.DELIVERY_UNCERTAIN),
        )
        val vm = JitaiListViewModel(JitaiTab.HISTORY, port)
        keepCollecting(vm.state)
        advanceUntilIdle()
        val records = (vm.state.value.history as Load.Loaded).value.records
        assertThat(
            records.map { it.reason },
        ).containsExactly(null, OutcomeReason.LOST_ARBITRATION, OutcomeReason.NOTIFICATIONS_BLOCKED, null)
            .inOrder()
    }

    @Test
    fun `pause, resume and disable call the port and report the result`() = runTest {
        port.overview.value = Fixtures.overview()
        val vm = JitaiListViewModel(JitaiTab.ACTIVE, port)
        keepCollecting(vm.state)
        val effects = record(vm.effects)
        vm.pause(Fixtures.RULE_ID)
        advanceUntilIdle()
        port.result = Outcome.failure(AppError.ValidationError(listOf("E012")))
        vm.resume(Fixtures.RULE_ID)
        advanceUntilIdle()
        port.result = Outcome.success(Unit)
        vm.requestDisable(Fixtures.RULE_ID, "Afternoon walk")
        advanceUntilIdle()
        assertThat(vm.state.value.pendingDisable).isNotNull()
        vm.confirmDisable()
        advanceUntilIdle()
        assertThat(port.calls).containsExactly("pause:${Fixtures.RULE_ID}", "resume:${Fixtures.RULE_ID}", "disable:${Fixtures.RULE_ID}")
        assertThat(effects).containsExactly(
            ScreenEffect.Message(UserMessage.PAUSED),
            ScreenEffect.Message(UserMessage.RESUME_BLOCKED),
            ScreenEffect.Message(UserMessage.DISABLED),
        )
    }

    @Test
    fun `navigation actions open their routes`() = runTest {
        val vm = JitaiListViewModel(JitaiTab.SUGGESTED, port)
        val effects = record(vm.effects)
        vm.openSuggestion(Fixtures.PROPOSAL_ID)
        vm.openDecision(Fixtures.DECISION_KEY)
        vm.create(JitaiBuilderMode.NATURAL_LANGUAGE)
        vm.edit(Fixtures.RULE_ID)
        advanceUntilIdle()
        assertThat(effects).containsExactly(
            ScreenEffect.Navigate(AppRoute.ProposalReview(Fixtures.PROPOSAL_ID)),
            ScreenEffect.Navigate(AppRoute.InterventionDetail(Fixtures.DECISION_KEY)),
            ScreenEffect.Navigate(AppRoute.JitaiBuilder(JitaiBuilderMode.NATURAL_LANGUAGE)),
            ScreenEffect.Navigate(AppRoute.JitaiBuilder(JitaiBuilderMode.MANUAL, Fixtures.RULE_ID)),
        )
    }

    @Test
    fun `rule detail disables and goes back`() = runTest {
        port.overview.value = Fixtures.overview()
        val vm = JitaiDetailViewModel(Fixtures.RULE_ID, port)
        keepCollecting(vm.state)
        val effects = record(vm.effects)
        advanceUntilIdle()
        assertThat(vm.state.value.load).isInstanceOf(Load.Loaded::class.java)
        vm.requestDisable()
        vm.confirmDisable()
        advanceUntilIdle()
        assertThat(effects).containsExactly(ScreenEffect.Message(UserMessage.DISABLED), ScreenEffect.Back)
    }
}
