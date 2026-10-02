package dev.agentle.feature.connections.aisharing

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.AiPurpose
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.feature.connections.port.AiCategoryRestriction
import dev.agentle.feature.connections.port.AiRequestOutcome
import dev.agentle.feature.connections.port.AiSharingCategory
import dev.agentle.feature.connections.testing.ConnectionsFixtures
import dev.agentle.feature.connections.testing.ConnectionsFixtures.NOW
import dev.agentle.feature.connections.testing.FakeAiSharingPort
import dev.agentle.feature.connections.testing.MainDispatcherRule
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class AiSharingViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun TestScope.viewModel(port: FakeAiSharingPort): AiSharingViewModel {
        val viewModel = AiSharingViewModel(port, ConnectionsFixtures.zonePort)
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    private fun AiSharingViewModel.state(): AiSharingUiState = uiState.value

    private fun AiSharingUiState.row(category: AiSharingCategory): AiCategoryRow = categories.single { it.category == category }

    private fun TestScope.act(viewModel: AiSharingViewModel, vararg actions: AiSharingAction) {
        actions.forEach(viewModel::onAction)
        runCurrent()
    }

    @Test
    fun `starts loading until the consent store reports`() = runTest(main.dispatcher) {
        val viewModel = AiSharingViewModel(FakeAiSharingPort(), ConnectionsFixtures.zonePort)

        assertThat(viewModel.state().phase).isEqualTo(AiSharingPhase.LOADING)
    }

    @Test
    fun `a failing consent store shows the load error and retry reloads it`() = runTest(main.dispatcher) {
        val port = FakeAiSharingPort().apply { failState = true }
        val viewModel = viewModel(port)
        assertThat(viewModel.state().phase).isEqualTo(AiSharingPhase.LOAD_FAILED)

        port.failState = false
        act(viewModel, AiSharingAction.Retry)

        assertThat(viewModel.state().phase).isEqualTo(AiSharingPhase.READY)
    }

    @Test
    fun `no consent layer in this build shows not available`() = runTest(main.dispatcher) {
        val port = FakeAiSharingPort(ConnectionsFixtures.consent().copy(available = false, categories = emptyList()))

        assertThat(viewModel(port).state().phase).isEqualTo(AiSharingPhase.NOT_AVAILABLE)
    }

    @Test
    fun `every category starts off, with the consent version and disclosure`() = runTest(main.dispatcher) {
        val state = viewModel(FakeAiSharingPort()).state()

        assertThat(state.phase).isEqualTo(AiSharingPhase.READY)
        assertThat(state.categories.map { it.category }).containsExactlyElementsIn(AiSharingCategory.entries).inOrder()
        assertThat(state.categories.none { it.allowed }).isTrue()
        assertThat(state.allowedCount).isEqualTo(0)
        assertThat(state.consentVersion).isEqualTo(1)
        assertThat(state.disclosure).isEqualTo(ConnectionsFixtures.DISCLOSURE)
        assertThat(state.lastChangedAt).isNull()
        assertThat(state.zone).isEqualTo(ConnectionsFixtures.ZONE)
    }

    @Test
    fun `an unreadable consent store says so and treats everything as off`() = runTest(main.dispatcher) {
        val port = FakeAiSharingPort(ConnectionsFixtures.consent(allowed = setOf(AiSharingCategory.SLEEP), readFailed = true))

        val state = viewModel(port).state()

        assertThat(state.readFailed).isTrue()
        assertThat(state.allowedCount).isEqualTo(0)
    }

    @Test
    fun `turning a category on asks for confirmation first`() = runTest(main.dispatcher) {
        val port = FakeAiSharingPort()
        val viewModel = viewModel(port)

        act(viewModel, AiSharingAction.Toggle(AiSharingCategory.SLEEP, allowed = true))

        assertThat(viewModel.state().confirm).isEqualTo(AiSharingCategory.SLEEP)
        assertThat(port.setCalls).isEmpty()
        assertThat(viewModel.state().row(AiSharingCategory.SLEEP).allowed).isFalse()
    }

    @Test
    fun `dismissing the confirmation leaves the category off`() = runTest(main.dispatcher) {
        val port = FakeAiSharingPort()
        val viewModel = viewModel(port)

        act(viewModel, AiSharingAction.Toggle(AiSharingCategory.SLEEP, allowed = true), AiSharingAction.DismissConfirm)

        assertThat(viewModel.state().confirm).isNull()
        assertThat(port.setCalls).isEmpty()
    }

    @Test
    fun `confirming turns the category on and records when`() = runTest(main.dispatcher) {
        val port = FakeAiSharingPort()
        val viewModel = viewModel(port)

        act(viewModel, AiSharingAction.Toggle(AiSharingCategory.SLEEP, allowed = true), AiSharingAction.ConfirmTurnOn)

        val state = viewModel.state()
        assertThat(port.setCalls).containsExactly(AiSharingCategory.SLEEP to true)
        assertThat(state.row(AiSharingCategory.SLEEP).allowed).isTrue()
        assertThat(state.row(AiSharingCategory.SLEEP).grantedAt).isEqualTo(NOW)
        assertThat(state.lastChangedAt).isEqualTo(NOW)
        assertThat(state.notice).isEqualTo(AiSharingNotice.TurnedOn(AiSharingCategory.SLEEP))
        assertThat(state.confirm).isNull()
    }

    @Test
    fun `turning a category off takes effect at once and says what was cancelled`() = runTest(main.dispatcher) {
        val port = FakeAiSharingPort(ConnectionsFixtures.consent(allowed = setOf(AiSharingCategory.SLEEP))).apply {
            cancelOnRevoke = listOf(AiPurpose.SLEEP_INSIGHT)
        }
        val viewModel = viewModel(port)

        act(viewModel, AiSharingAction.Toggle(AiSharingCategory.SLEEP, allowed = false))

        assertThat(port.setCalls).containsExactly(AiSharingCategory.SLEEP to false)
        assertThat(viewModel.state().confirm).isNull()
        assertThat(viewModel.state().row(AiSharingCategory.SLEEP).allowed).isFalse()
        assertThat(viewModel.state().notice)
            .isEqualTo(AiSharingNotice.TurnedOff(AiSharingCategory.SLEEP, persistentListOf(AiPurpose.SLEEP_INSIGHT)))
    }

    @Test
    fun `a switch shows the chosen value while it is written`() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val port = FakeAiSharingPort(ConnectionsFixtures.consent(allowed = setOf(AiSharingCategory.HEART))).apply {
            this.gate = gate
        }
        val viewModel = viewModel(port)

        act(viewModel, AiSharingAction.Toggle(AiSharingCategory.HEART, allowed = false))
        assertThat(viewModel.state().row(AiSharingCategory.HEART).pending).isTrue()
        assertThat(viewModel.state().row(AiSharingCategory.HEART).allowed).isFalse()
        act(viewModel, AiSharingAction.Toggle(AiSharingCategory.HEART, allowed = true))
        assertThat(viewModel.state().confirm).isNull()

        gate.complete(Unit)
        runCurrent()
        assertThat(viewModel.state().row(AiSharingCategory.HEART).pending).isFalse()
        assertThat(port.setCalls).containsExactly(AiSharingCategory.HEART to false)
    }

    @Test
    fun `a failed change keeps the stored value and says why`() = runTest(main.dispatcher) {
        val port = FakeAiSharingPort(ConnectionsFixtures.consent(allowed = setOf(AiSharingCategory.STEPS))).apply {
            setFailure = AppError.DatabaseError()
        }
        val viewModel = viewModel(port)

        act(viewModel, AiSharingAction.Toggle(AiSharingCategory.STEPS, allowed = false))

        assertThat(viewModel.state().row(AiSharingCategory.STEPS).allowed).isTrue()
        assertThat(viewModel.state().notice)
            .isEqualTo(AiSharingNotice.ChangeFailed(AiSharingCategory.STEPS, turningOn = false, error = AppError.DatabaseError()))
    }

    @Test
    fun `without a ChatGPT account nothing can be turned on`() = runTest(main.dispatcher) {
        val port = FakeAiSharingPort(ConnectionsFixtures.consent(accountConnected = false))
        val viewModel = viewModel(port)
        val state = viewModel.state()
        assertThat(state.accountConnected).isFalse()
        assertThat(state.row(AiSharingCategory.SLEEP).canTurnOn(state.accountConnected)).isFalse()

        act(viewModel, AiSharingAction.Toggle(AiSharingCategory.SLEEP, allowed = true), AiSharingAction.ConfirmTurnOn)

        assertThat(viewModel.state().notice).isEqualTo(
            AiSharingNotice.ChangeFailed(AiSharingCategory.SLEEP, turningOn = true, error = AppError.AuthenticationRequired("chatgpt")),
        )
    }

    @Test
    fun `text written by others can never be turned on in this version`() = runTest(main.dispatcher) {
        val state = viewModel(FakeAiSharingPort()).state()

        val row = state.row(AiSharingCategory.NOTIFICATION_TEXT)
        assertThat(row.restriction).isEqualTo(AiCategoryRestriction.NEVER_SENT)
        assertThat(row.canTurnOn(accountConnected = true)).isFalse()
        assertThat(state.row(AiSharingCategory.SLEEP).restriction).isEqualTo(AiCategoryRestriction.WEARABLE_API_EXCLUDED)
    }

    @Test
    fun `a grant of an older consent version shows as off and asks to grant again`() = runTest(main.dispatcher) {
        val port = FakeAiSharingPort(ConnectionsFixtures.consent(outdated = setOf(AiSharingCategory.GOALS)))

        val row = viewModel(port).state().row(AiSharingCategory.GOALS)

        assertThat(row.allowed).isFalse()
        assertThat(row.outdatedGrant).isTrue()
    }

    @Test
    fun `preview builds exactly what a request for the chosen purpose would send`() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val port = FakeAiSharingPort().apply { this.gate = gate }
        val viewModel = viewModel(port)

        act(viewModel, AiSharingAction.SelectPurpose(AiPurpose.ACTIVITY_INSIGHT), AiSharingAction.BuildPreview)
        assertThat(viewModel.state().preview).isEqualTo(AiPreviewUi.Building(AiPurpose.ACTIVITY_INSIGHT))

        gate.complete(Unit)
        runCurrent()
        assertThat(port.previewCalls).containsExactly(AiPurpose.ACTIVITY_INSIGHT)
        assertThat(viewModel.state().preview).isEqualTo(AiPreviewUi.Shown(ConnectionsFixtures.preview(AiPurpose.ACTIVITY_INSIGHT)))
    }

    @Test
    fun `a preview that cannot be built says why`() = runTest(main.dispatcher) {
        val failure = AppError.ConsentViolation(setOf("SLEEP"))
        val port = FakeAiSharingPort().apply { previewOutcome = { Outcome.Failure(failure) } }
        val viewModel = viewModel(port)

        act(viewModel, AiSharingAction.BuildPreview)

        assertThat(viewModel.state().preview).isEqualTo(AiPreviewUi.Failed(AiPurpose.entries.first(), failure))
    }

    @Test
    fun `closing the preview or choosing another purpose hides it`() = runTest(main.dispatcher) {
        val viewModel = viewModel(FakeAiSharingPort())

        act(viewModel, AiSharingAction.BuildPreview, AiSharingAction.ClosePreview)
        assertThat(viewModel.state().preview).isEqualTo(AiPreviewUi.Hidden)
        act(viewModel, AiSharingAction.BuildPreview, AiSharingAction.SelectPurpose(AiPurpose.GENERAL_QUESTION))

        assertThat(viewModel.state().preview).isEqualTo(AiPreviewUi.Hidden)
        assertThat(viewModel.state().purpose).isEqualTo(AiPurpose.GENERAL_QUESTION)
    }

    @Test
    fun `changing a category hides a preview that no longer matches`() = runTest(main.dispatcher) {
        val port = FakeAiSharingPort(ConnectionsFixtures.consent(allowed = setOf(AiSharingCategory.SLEEP)))
        val viewModel = viewModel(port)
        act(viewModel, AiSharingAction.BuildPreview)

        act(viewModel, AiSharingAction.Toggle(AiSharingCategory.SLEEP, allowed = false))

        assertThat(viewModel.state().preview).isEqualTo(AiPreviewUi.Hidden)
    }

    @Test
    fun `the request history is metadata, newest first as reported`() = runTest(main.dispatcher) {
        val history = listOf(
            ConnectionsFixtures.request(id = "b"),
            ConnectionsFixtures.request(id = "a", outcome = AiRequestOutcome.CANCELLED, background = true),
        )
        val state = viewModel(FakeAiSharingPort(history = history)).state()

        assertThat(state.history.map { it.id }).containsExactly("b", "a").inOrder()
        assertThat(state.historyUnavailable).isFalse()
    }

    @Test
    fun `an unreadable request log says so`() = runTest(main.dispatcher) {
        val port = FakeAiSharingPort(ConnectionsFixtures.consent().copy(historyUnavailable = true))

        assertThat(viewModel(port).state().historyUnavailable).isTrue()
    }

    @Test
    fun `dismissing a message clears it`() = runTest(main.dispatcher) {
        val port = FakeAiSharingPort(ConnectionsFixtures.consent(allowed = setOf(AiSharingCategory.SLEEP)))
        val viewModel = viewModel(port)
        act(viewModel, AiSharingAction.Toggle(AiSharingCategory.SLEEP, allowed = false))

        act(viewModel, AiSharingAction.DismissNotice)

        assertThat(viewModel.state().notice).isNull()
    }

    @Test
    fun `the screen state never prints the preview content`() = runTest(main.dispatcher) {
        val viewModel = viewModel(FakeAiSharingPort())
        act(viewModel, AiSharingAction.BuildPreview)

        val printed = viewModel.state().toString() + ConnectionsFixtures.preview().toString()

        assertThat(printed).doesNotContain(ConnectionsFixtures.preview().instructions)
        assertThat(printed).doesNotContain("sleep.minutes")
    }
}
