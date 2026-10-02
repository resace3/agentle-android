package dev.agentle.feature.insights.review

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.core.ui.navigation.JitaiTab
import dev.agentle.feature.insights.common.ScreenEffect
import dev.agentle.feature.insights.common.UserMessage
import dev.agentle.feature.insights.intervention.InterventionDetailViewModel
import dev.agentle.feature.insights.jitai.Load
import dev.agentle.feature.insights.port.DeliveryResult
import dev.agentle.feature.insights.port.ProposalRejection
import dev.agentle.feature.insights.testing.FakeInterventionPort
import dev.agentle.feature.insights.testing.FakeProposalReviewPort
import dev.agentle.feature.insights.testing.Fixtures
import dev.agentle.feature.insights.testing.MainDispatcherRule
import dev.agentle.feature.insights.testing.keepCollecting
import dev.agentle.feature.insights.testing.record
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.validation.IssueCode
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ReviewAndInterventionTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val port = FakeProposalReviewPort()

    private fun model(vm: ProposalReviewViewModel) = (vm.state.value.load as Load.Loaded).value

    @Test
    fun `an NL proposal activates only after its assumption is accepted`() = runTest {
        port.proposals.value = mapOf(Fixtures.PROPOSAL_ID to Fixtures.nlReview())
        val vm = ProposalReviewViewModel(Fixtures.PROPOSAL_ID, port)
        keepCollecting(vm.state)
        val effects = record(vm.effects)
        advanceUntilIdle()
        val model = model(vm)
        assertThat(model.renderingIsFinal).isTrue()
        assertThat(model.features).contains("steps_today")
        assertThat(model.request).isEqualTo(Fixtures.REQUEST_WALK)
        assertThat(vm.state.value.canActivate).isFalse()

        model.acknowledgeable.forEach { vm.setAcknowledged(it.reviewKey, true) }
        advanceUntilIdle()
        assertThat(vm.state.value.canActivate).isTrue()
        vm.activate()
        advanceUntilIdle()
        val stored = port.activated.single()
        assertThat(stored.status).isEqualTo(JitaiStatus.ACTIVE)
        assertThat(stored.provenance?.approvedRendering).isEqualTo(model.rendering)
        assertThat(effects).containsExactly(
            ScreenEffect.Message(UserMessage.ACTIVATED),
            ScreenEffect.Navigate(AppRoute.Jitais(JitaiTab.ACTIVE)),
        )
    }

    @Test
    fun `an unknown app needs a choice before the proposal can be turned on`() = runTest {
        port.environmentResult = dev.agentle.core.common.Outcome.success(Fixtures.environment(Fixtures.context(apps = emptyList())))
        port.proposals.value = mapOf(Fixtures.PROPOSAL_ID to Fixtures.nlReview(Fixtures.instagramProposal(), Fixtures.REQUEST_INSTAGRAM))
        val vm = ProposalReviewViewModel(Fixtures.PROPOSAL_ID, port)
        keepCollecting(vm.state)
        advanceUntilIdle()
        assertThat(model(vm).confirmItems.map { it.code }).contains(IssueCode.C01)
        model(vm).acknowledgeable.forEach { vm.setAcknowledged(it.reviewKey, true) }
        vm.activate()
        advanceUntilIdle()
        assertThat(port.activated).isEmpty()
    }

    @Test
    fun `a discovered proposal shows its evidence and can be rejected for good`() = runTest {
        port.proposals.value = mapOf(Fixtures.PROPOSAL_ID to Fixtures.discoveredReview())
        val vm = ProposalReviewViewModel(Fixtures.PROPOSAL_ID, port)
        keepCollecting(vm.state)
        val effects = record(vm.effects)
        advanceUntilIdle()
        assertThat(model(vm).evidence?.exposedNights).isEqualTo(24)
        assertThat(model(vm).trialDays).isEqualTo(28)
        vm.reject(ProposalRejection.NEVER)
        advanceUntilIdle()
        assertThat(port.rejected).containsExactly(ProposalRejection.NEVER)
        assertThat(effects).containsExactly(ScreenEffect.Message(UserMessage.PROPOSAL_REJECTED), ScreenEffect.Back)
    }

    @Test
    fun `a missing proposal is not found`() = runTest {
        val vm = ProposalReviewViewModel("nope", port)
        keepCollecting(vm.state)
        advanceUntilIdle()
        assertThat(vm.state.value.load).isInstanceOf(Load.Error::class.java)
    }

    @Test
    fun `intervention detail rates, opens the rule and marks a pending card shown once`() = runTest {
        val interventions = FakeInterventionPort()
        interventions.details.value = mapOf(Fixtures.DECISION_KEY to Fixtures.intervention(DeliveryResult.CARD_PENDING))
        val vm = InterventionDetailViewModel(Fixtures.DECISION_KEY, interventions)
        keepCollecting(vm.state)
        val effects = record(vm.effects)
        advanceUntilIdle()
        assertThat(interventions.cardsShown).containsExactly(Fixtures.DECISION_KEY)
        val content = (vm.state.value.load as Load.Loaded).value
        assertThat(content.conditions.first().text).contains("steps")
        vm.sendFeedback(helpful = true)
        vm.openRule()
        advanceUntilIdle()
        assertThat(interventions.feedback).containsExactly(true)
        assertThat(effects).containsExactly(
            ScreenEffect.Navigate(AppRoute.JitaiDetail(Fixtures.RULE_ID)),
            ScreenEffect.Message(UserMessage.FEEDBACK_SAVED),
        )
    }

    @Test
    fun `a suppressed decision cannot be rated`() = runTest {
        val interventions = FakeInterventionPort()
        interventions.details.value = mapOf(Fixtures.DECISION_KEY to Fixtures.intervention(DeliveryResult.SUPPRESSED))
        val vm = InterventionDetailViewModel(Fixtures.DECISION_KEY, interventions)
        keepCollecting(vm.state)
        advanceUntilIdle()
        vm.sendFeedback(helpful = false)
        advanceUntilIdle()
        assertThat(interventions.feedback).isEmpty()
    }
}
