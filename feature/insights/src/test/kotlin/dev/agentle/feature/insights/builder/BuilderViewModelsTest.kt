package dev.agentle.feature.insights.builder

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.core.ui.navigation.JitaiBuilderMode
import dev.agentle.feature.insights.common.AiProblem
import dev.agentle.feature.insights.common.ScreenEffect
import dev.agentle.feature.insights.common.UserMessage
import dev.agentle.feature.insights.port.NlConversion
import dev.agentle.feature.insights.testing.FakeJitaiBuilderPort
import dev.agentle.feature.insights.testing.Fixtures
import dev.agentle.feature.insights.testing.MainDispatcherRule
import dev.agentle.feature.insights.testing.record
import dev.agentle.jitai.dsl.model.CreatedBy
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.nl.UnsupportedReason
import dev.agentle.jitai.dsl.validation.IssueCode
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class BuilderViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val port = FakeJitaiBuilderPort()

    private fun editing(vm: ManualBuilderViewModel) = vm.state.value as ManualBuilderUiState.Editing

    @Test
    fun `a new form shows issues on their fields and cannot be activated`() = runTest {
        val vm = ManualBuilderViewModel(null, port)
        advanceUntilIdle()
        val validation = editing(vm).validation
        assertThat(validation.canActivate).isFalse()
        assertThat(validation.issuesFor(Fields.NAME).map { it.code }).isNotEmpty()
        assertThat(validation.issuesFor(Fields.CONTENT_TITLE).map { it.code }).isNotEmpty()
    }

    @Test
    fun `a complete form previews its sentence and activates as ACTIVE`() = runTest {
        val vm = ManualBuilderViewModel(null, port)
        val effects = record(vm.effects)
        advanceUntilIdle()
        vm.update(Fixtures.walkForm(editing(vm).form.base.id))
        val validation = editing(vm).validation
        assertThat(validation.errorCount).isEqualTo(0)
        assertThat(validation.preview).contains("5:00 PM")
        vm.activate()
        advanceUntilIdle()
        assertThat(port.saved.single().status).isEqualTo(JitaiStatus.ACTIVE)
        assertThat(effects).containsExactly(ScreenEffect.Message(UserMessage.ACTIVATED), ScreenEffect.Back)
    }

    @Test
    fun `a number typo is a local issue that blocks saving`() = runTest {
        val vm = ManualBuilderViewModel(null, port)
        val effects = record(vm.effects)
        advanceUntilIdle()
        val form = Fixtures.walkForm(editing(vm).form.base.id)
        vm.update(form.copy(frequency = form.frequency.copy(maxPerDay = "two")))
        assertThat(editing(vm).validation.issuesFor(Fields.MAX_PER_DAY).single().local).isEqualTo(LocalIssue.NOT_A_NUMBER)
        vm.saveDraft()
        advanceUntilIdle()
        assertThat(port.saved).isEmpty()
        assertThat(effects).containsExactly(ScreenEffect.Message(UserMessage.SAVE_BLOCKED))
        assertThat(editing(vm).showAllIssues).isTrue()
    }

    @Test
    fun `editing a stored rule loads its form and saves a draft`() = runTest {
        port.definitions[Fixtures.RULE_ID] = Fixtures.walkRule()
        val vm = ManualBuilderViewModel(Fixtures.RULE_ID, port)
        advanceUntilIdle()
        assertThat(editing(vm).isEdit).isTrue()
        assertThat(editing(vm).form.name).isEqualTo("Afternoon walk")
        vm.saveDraft()
        advanceUntilIdle()
        assertThat(port.saved.single().status).isEqualTo(JitaiStatus.DRAFT)
    }

    @Test
    fun `an edited AI rule can only be saved as a draft`() = runTest {
        port.definitions[Fixtures.RULE_ID] = Fixtures.walkRule().copy(
            createdBy = CreatedBy.AI_NATURAL_LANGUAGE,
            description = "A walk in the afternoon.",
        )
        val vm = ManualBuilderViewModel(Fixtures.RULE_ID, port)
        val effects = record(vm.effects)
        advanceUntilIdle()
        assertThat(editing(vm).needsApproval).isTrue()
        vm.activate()
        advanceUntilIdle()
        assertThat(port.saved).isEmpty()
        assertThat(effects).containsExactly(ScreenEffect.Message(UserMessage.SAVE_BLOCKED))
        vm.saveDraft()
        advanceUntilIdle()
        assertThat(port.saved.single().status).isEqualTo(JitaiStatus.DRAFT)
    }

    @Test
    fun `a failing environment is an error state`() = runTest {
        port.environmentResult = Outcome.failure(AppError.DatabaseError())
        val vm = ManualBuilderViewModel(null, port)
        advanceUntilIdle()
        assertThat(vm.state.value).isInstanceOf(ManualBuilderUiState.Error::class.java)
    }

    @Test
    fun `natural language opens the review of a proposal`() = runTest {
        port.conversion = Outcome.success(NlConversion.Proposed(Fixtures.PROPOSAL_ID, report = emptyReport(), interpretation = ""))
        val vm = NlBuilderViewModel(port)
        val effects = record(vm.effects)
        vm.updateText(Fixtures.REQUEST_WALK)
        vm.submit()
        assertThat(vm.state.value.phase).isEqualTo(NlPhase.Converting)
        advanceUntilIdle()
        assertThat(port.requests).containsExactly(Fixtures.REQUEST_WALK)
        assertThat(effects).containsExactly(ScreenEffect.Navigate(AppRoute.ProposalReview(Fixtures.PROPOSAL_ID)))
    }

    @Test
    fun `refusals hide health detail, invalid output offers the manual builder, AI errors map to states`() = runTest {
        val vm = NlBuilderViewModel(port)
        val effects = record(vm.effects)
        vm.updateText(Fixtures.REQUEST_WALK)
        port.conversion = Outcome.success(NlConversion.Refused(UnsupportedReason.HEALTH_OR_SAFETY, "model text"))
        vm.submit()
        advanceUntilIdle()
        assertThat(vm.state.value.phase).isEqualTo(NlPhase.Refused(UnsupportedReason.HEALTH_OR_SAFETY, null))

        vm.startOver()
        port.conversion = Outcome.success(NlConversion.Invalid(emptyList(), "draft-9"))
        vm.submit()
        advanceUntilIdle()
        vm.editManually()
        advanceUntilIdle()
        assertThat(effects).containsExactly(ScreenEffect.Navigate(AppRoute.JitaiBuilder(JitaiBuilderMode.MANUAL, "draft-9")))

        vm.startOver()
        port.conversion = Outcome.failure(AppError.ConsentViolation(setOf("ACTIVITY")))
        vm.submit()
        advanceUntilIdle()
        assertThat(vm.state.value.phase).isEqualTo(NlPhase.Problem(AiProblem.CONSENT_MISSING))
    }

    private fun emptyReport() = dev.agentle.jitai.dsl.validation.ValidationReport(
        errors = emptyList(),
        errorCount = 0,
        confirmItems = emptyList(),
        warnings = emptyList(),
        origin = dev.agentle.jitai.dsl.model.RuleOrigin.AI,
    )

    @Suppress("unused")
    private val codes = IssueCode.entries
}
