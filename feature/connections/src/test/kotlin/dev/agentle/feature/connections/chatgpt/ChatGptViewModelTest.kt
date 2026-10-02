package dev.agentle.feature.connections.chatgpt

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.AiCapability
import dev.agentle.ai.api.AiProviderState
import dev.agentle.ai.api.CapabilitySupport
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.feature.connections.port.ChatGptConnectRequest
import dev.agentle.feature.connections.port.ChatGptConnectResult
import dev.agentle.feature.connections.port.ChatGptDisconnectResult
import dev.agentle.feature.connections.port.PlanUsageAvailability
import dev.agentle.feature.connections.testing.ConnectionsFixtures
import dev.agentle.feature.connections.testing.ConnectionsFixtures.NOW
import dev.agentle.feature.connections.testing.FakeChatGptPort
import dev.agentle.feature.connections.testing.MainDispatcherRule
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.hours

class ChatGptViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun TestScope.viewModel(port: FakeChatGptPort): ChatGptViewModel {
        val viewModel = ChatGptViewModel(port, ConnectionsFixtures.zonePort)
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    private fun ChatGptViewModel.state(): ChatGptUiState = uiState.value

    private fun TestScope.act(viewModel: ChatGptViewModel, vararg actions: ChatGptAction) {
        actions.forEach(viewModel::onAction)
        runCurrent()
    }

    @Test
    fun `starts loading until the connection reports`() = runTest(main.dispatcher) {
        val viewModel = ChatGptViewModel(FakeChatGptPort(), ConnectionsFixtures.zonePort)

        assertThat(viewModel.state().phase).isEqualTo(ChatGptPhase.LOADING)
    }

    @Test
    fun `a failing connection state shows the load error and retry reloads it`() = runTest(main.dispatcher) {
        val port = FakeChatGptPort().apply { failState = true }
        val viewModel = viewModel(port)
        assertThat(viewModel.state().phase).isEqualTo(ChatGptPhase.LOAD_FAILED)

        port.failState = false
        act(viewModel, ChatGptAction.Retry)

        assertThat(viewModel.state().phase).isEqualTo(ChatGptPhase.DISCONNECTED)
    }

    @Test
    fun `no sign-in in this build shows not available`() = runTest(main.dispatcher) {
        val port = FakeChatGptPort(ConnectionsFixtures.chatGptDisconnected().copy(available = false))

        assertThat(viewModel(port).state().phase).isEqualTo(ChatGptPhase.NOT_AVAILABLE)
    }

    @Test
    fun `disconnected shows unknown capabilities and unknown plan usage`() = runTest(main.dispatcher) {
        val state = viewModel(FakeChatGptPort()).state()

        assertThat(state.phase).isEqualTo(ChatGptPhase.DISCONNECTED)
        assertThat(state.capabilities).isNull()
        assertThat(state.planUsage).isEqualTo(PlanUsageAvailability.Unknown)
        assertThat(state.canDisconnect).isFalse()
        assertThat(state.zone).isEqualTo(ConnectionsFixtures.ZONE)
    }

    @Test
    fun `connect waits for the browser, then shows the connected account`() = runTest(main.dispatcher) {
        val port = FakeChatGptPort().apply { holdSignIn = true }
        val viewModel = viewModel(port)

        act(viewModel, ChatGptAction.Connect)
        assertThat(viewModel.state().phase).isEqualTo(ChatGptPhase.WAITING_FOR_BROWSER)
        assertThat(viewModel.state().busy).isEqualTo(ChatGptBusy.SIGNING_IN)

        port.answer(ChatGptConnectResult.Connected(ConnectionsFixtures.CHATGPT_ACCOUNT))
        runCurrent()
        val state = viewModel.state()
        assertThat(port.connectRequests).containsExactly(ChatGptConnectRequest())
        assertThat(state.phase).isEqualTo(ChatGptPhase.CONNECTED)
        assertThat(state.accountLabel).isEqualTo(ConnectionsFixtures.CHATGPT_ACCOUNT)
        assertThat(state.notice)
            .isEqualTo(ChatGptNotice.SignIn(ChatGptConnectResult.Connected(ConnectionsFixtures.CHATGPT_ACCOUNT)))
        assertThat(state.showPlanNotice).isTrue()
        assertThat(state.busy).isNull()
    }

    @Test
    fun `capabilities and plan usage are shown exactly as reported`() = runTest(main.dispatcher) {
        val port = FakeChatGptPort(ConnectionsFixtures.chatGptConnected())

        val state = viewModel(port).state()
        val rows = checkNotNull(state.capabilities)

        assertThat(rows.map { it.capability }).containsExactlyElementsIn(AiCapability.entries).inOrder()
        assertThat(rows.single { it.capability == AiCapability.IMAGE_GENERATION }.support).isEqualTo(CapabilitySupport.UNSUPPORTED)
        assertThat(rows.single { it.capability == AiCapability.STRUCTURED_OUTPUT }.support).isEqualTo(CapabilitySupport.PROMPTED_JSON)
        assertThat(state.planUsage).isEqualTo(PlanUsageAvailability.Unknown)
    }

    @Test
    fun `capabilities not reported yet stay unknown`() = runTest(main.dispatcher) {
        val port = FakeChatGptPort(ConnectionsFixtures.chatGptConnected(capabilities = null))

        assertThat(viewModel(port).state().capabilities).isNull()
    }

    @Test
    fun `every reported plan usage value passes through unchanged`() = runTest(main.dispatcher) {
        val values = listOf(
            PlanUsageAvailability.Available,
            PlanUsageAvailability.NotGranted,
            PlanUsageAvailability.NotEligible("account_not_eligible"),
            PlanUsageAvailability.LimitReached(until = NOW + 3.hours),
            PlanUsageAvailability.LimitReached(until = null),
            PlanUsageAvailability.TemporarilyUnavailable,
        )
        for (value in values) {
            val state = viewModel(FakeChatGptPort(ConnectionsFixtures.chatGptConnected(planUsage = value))).state()

            assertThat(state.planUsage).isEqualTo(value)
        }
    }

    @Test
    fun `every sign-in outcome without a connection leaves a message and stays disconnected`() = runTest(main.dispatcher) {
        val outcomes = listOf(
            ChatGptConnectResult.Denied,
            ChatGptConnectResult.NotCompleted,
            ChatGptConnectResult.Cancelled,
            ChatGptConnectResult.TimedOut,
            ChatGptConnectResult.NoBrowser,
            ChatGptConnectResult.AttemptRejected,
            ChatGptConnectResult.AccountMismatch,
            ChatGptConnectResult.DeviceClockWrong,
            ChatGptConnectResult.NetworkError,
            ChatGptConnectResult.ServiceUnavailable,
            ChatGptConnectResult.Failed(AppError.Unexpected()),
        )
        for (outcome in outcomes) {
            val viewModel = viewModel(FakeChatGptPort().apply { connectResult = outcome })

            act(viewModel, ChatGptAction.Connect)

            assertThat(viewModel.state().notice).isEqualTo(ChatGptNotice.SignIn(outcome))
            assertThat(viewModel.state().phase).isEqualTo(ChatGptPhase.DISCONNECTED)
        }
    }

    @Test
    fun `cancel while waiting for the browser gives up the attempt`() = runTest(main.dispatcher) {
        val port = FakeChatGptPort().apply { holdSignIn = true }
        val viewModel = viewModel(port)
        act(viewModel, ChatGptAction.Connect)

        act(viewModel, ChatGptAction.CancelSignIn)

        assertThat(port.cancelCalls).isEqualTo(1)
        assertThat(viewModel.state().phase).isEqualTo(ChatGptPhase.DISCONNECTED)
        assertThat(viewModel.state().notice).isEqualTo(ChatGptNotice.SignIn(ChatGptConnectResult.Cancelled))
    }

    @Test
    fun `connect again while waiting re-opens the same attempt and reports it once`() = runTest(main.dispatcher) {
        val port = FakeChatGptPort().apply { holdSignIn = true }
        val viewModel = viewModel(port)
        act(viewModel, ChatGptAction.Connect)

        act(viewModel, ChatGptAction.Connect)
        port.answer(ChatGptConnectResult.Connected())
        runCurrent()

        assertThat(port.connectRequests).hasSize(2)
        assertThat(viewModel.state().phase).isEqualTo(ChatGptPhase.CONNECTED)
        assertThat(viewModel.state().busy).isNull()
    }

    @Test
    fun `a sign-in still waiting after the screen was recreated shows waiting for the browser`() = runTest(main.dispatcher) {
        val port = FakeChatGptPort(ConnectionsFixtures.chatGptDisconnected().copy(signInInProgress = true))

        val state = viewModel(port).state()

        assertThat(state.phase).isEqualTo(ChatGptPhase.WAITING_FOR_BROWSER)
        assertThat(state.busy).isNull()
    }

    @Test
    fun `declined plan usage offers to use the plan, which asks for consent again`() = runTest(main.dispatcher) {
        val port = FakeChatGptPort().apply { connectResult = ChatGptConnectResult.PlanUsageNotGranted }
        val viewModel = viewModel(port)
        act(viewModel, ChatGptAction.Connect)
        assertThat(viewModel.state().phase).isEqualTo(ChatGptPhase.NOT_ELIGIBLE)
        assertThat(viewModel.state().planUsageDeclined).isTrue()

        port.connectResult = ChatGptConnectResult.Connected()
        act(viewModel, ChatGptAction.UsePlan)

        assertThat(port.connectRequests.last()).isEqualTo(ChatGptConnectRequest(enablePlanUsage = true))
        assertThat(viewModel.state().phase).isEqualTo(ChatGptPhase.CONNECTED)
    }

    @Test
    fun `after an account mismatch the user can connect the other account instead`() = runTest(main.dispatcher) {
        val port = FakeChatGptPort().apply { connectResult = ChatGptConnectResult.AccountMismatch }
        val viewModel = viewModel(port)
        act(viewModel, ChatGptAction.Connect)

        act(viewModel, ChatGptAction.UseDifferentAccount)

        assertThat(port.connectRequests.last()).isEqualTo(ChatGptConnectRequest(addAccount = true))
    }

    @Test
    fun `an interrupted sign-in is reported once after a restart`() = runTest(main.dispatcher) {
        val port = FakeChatGptPort().apply { interrupted = ChatGptConnectResult.Interrupted(firstRegistration = true) }

        val first = viewModel(port)
        val second = viewModel(port)

        assertThat(first.state().notice).isEqualTo(ChatGptNotice.SignIn(ChatGptConnectResult.Interrupted(true)))
        assertThat(second.state().notice).isNull()
    }

    @Test
    fun `acknowledging the plan notice hides it and records it`() = runTest(main.dispatcher) {
        val port = FakeChatGptPort(ConnectionsFixtures.chatGptConnected(noticeAcknowledged = false))
        val viewModel = viewModel(port)
        assertThat(viewModel.state().showPlanNotice).isTrue()

        act(viewModel, ChatGptAction.AcknowledgePlanNotice)

        assertThat(port.acknowledgeCalls).isEqualTo(1)
        assertThat(viewModel.state().showPlanNotice).isFalse()
    }

    @Test
    fun `a failure to record the plan notice is reported`() = runTest(main.dispatcher) {
        val port = FakeChatGptPort(ConnectionsFixtures.chatGptConnected(noticeAcknowledged = false)).apply {
            acknowledgeOutcome = Outcome.Failure(AppError.DatabaseError())
        }
        val viewModel = viewModel(port)

        act(viewModel, ChatGptAction.AcknowledgePlanNotice)

        assertThat(viewModel.state().notice).isEqualTo(ChatGptNotice.ActionFailed(AppError.DatabaseError()))
    }

    @Test
    fun `provider states of a bound account map to their phases`() = runTest(main.dispatcher) {
        val until = NOW + 2.hours
        val cases = mapOf(
            AiProviderState.NeedsReauth to ChatGptPhase.NEEDS_REAUTH,
            AiProviderState.NotEligible("account_not_eligible") to ChatGptPhase.NOT_ELIGIBLE,
            AiProviderState.UsageLimited(until.toEpochMilliseconds()) to ChatGptPhase.USAGE_LIMITED,
            AiProviderState.Unavailable("network_unavailable") to ChatGptPhase.PROVIDER_UNAVAILABLE,
        )
        for ((provider, phase) in cases) {
            val port = FakeChatGptPort(ConnectionsFixtures.chatGptConnected().copy(provider = provider))

            val state = viewModel(port).state()

            assertThat(state.phase).isEqualTo(phase)
            assertThat(state.canDisconnect).isTrue()
        }
    }

    @Test
    fun `usage limited shows the time the provider gave and the reason codes pass through`() = runTest(main.dispatcher) {
        val until = NOW + 2.hours
        val limited = viewModel(
            FakeChatGptPort(
                ConnectionsFixtures.chatGptConnected().copy(provider = AiProviderState.UsageLimited(until.toEpochMilliseconds())),
            ),
        ).state()
        val notEligible = viewModel(
            FakeChatGptPort(ConnectionsFixtures.chatGptConnected().copy(provider = AiProviderState.NotEligible("policy_restricted"))),
        ).state()

        assertThat(limited.usageLimitedUntil).isEqualTo(until)
        assertThat(notEligible.providerReason).isEqualTo("policy_restricted")
        assertThat(notEligible.planUsageDeclined).isFalse()
    }

    @Test
    fun `the last AI request is shown as metadata`() = runTest(main.dispatcher) {
        val request = ConnectionsFixtures.request()
        val port = FakeChatGptPort(ConnectionsFixtures.chatGptConnected(lastRequest = request))

        assertThat(viewModel(port).state().lastRequest).isEqualTo(request)
    }

    @Test
    fun `disconnect with forget shows disconnected at once, even before the port reports it`() = runTest(main.dispatcher) {
        val port = FakeChatGptPort(ConnectionsFixtures.chatGptConnected()).apply { showDisconnect = false }
        val viewModel = viewModel(port)

        act(viewModel, ChatGptAction.RequestDisconnect, ChatGptAction.SetForgetRegistration(true))
        assertThat(viewModel.state().disconnectDialog).isEqualTo(ChatGptDisconnectDialog(forgetRegistration = true))
        act(viewModel, ChatGptAction.ConfirmDisconnect)

        val state = viewModel.state()
        assertThat(port.disconnectCalls).containsExactly(true)
        assertThat(state.phase).isEqualTo(ChatGptPhase.DISCONNECTED)
        assertThat(state.accountLabel).isNull()
        assertThat(state.capabilities).isNull()
        assertThat(state.notice).isEqualTo(ChatGptNotice.Disconnect(ChatGptDisconnectResult.Disconnected, forgotRegistration = true))
    }

    @Test
    fun `disconnect keeps the registration unless asked`() = runTest(main.dispatcher) {
        val port = FakeChatGptPort(ConnectionsFixtures.chatGptConnected())
        val viewModel = viewModel(port)

        act(viewModel, ChatGptAction.RequestDisconnect, ChatGptAction.ConfirmDisconnect)

        assertThat(port.disconnectCalls).containsExactly(false)
    }

    @Test
    fun `an unconfirmed revocation still disconnects and says so`() = runTest(main.dispatcher) {
        val port = FakeChatGptPort(ConnectionsFixtures.chatGptConnected()).apply {
            disconnectResult = ChatGptDisconnectResult.RevocationUnconfirmed
        }
        val viewModel = viewModel(port)

        act(viewModel, ChatGptAction.RequestDisconnect, ChatGptAction.ConfirmDisconnect)

        assertThat(viewModel.state().phase).isEqualTo(ChatGptPhase.DISCONNECTED)
        assertThat(viewModel.state().notice)
            .isEqualTo(ChatGptNotice.Disconnect(ChatGptDisconnectResult.RevocationUnconfirmed, forgotRegistration = false))
    }

    @Test
    fun `a failed disconnect keeps the connection and says why`() = runTest(main.dispatcher) {
        val failure = ChatGptDisconnectResult.Failed(AppError.DatabaseError())
        val port = FakeChatGptPort(ConnectionsFixtures.chatGptConnected()).apply { disconnectResult = failure }
        val viewModel = viewModel(port)

        act(viewModel, ChatGptAction.RequestDisconnect, ChatGptAction.ConfirmDisconnect)

        assertThat(viewModel.state().phase).isEqualTo(ChatGptPhase.CONNECTED)
        assertThat(viewModel.state().notice).isEqualTo(ChatGptNotice.Disconnect(failure, forgotRegistration = false))
    }

    @Test
    fun `dismissing the disconnect dialog keeps the connection`() = runTest(main.dispatcher) {
        val port = FakeChatGptPort(ConnectionsFixtures.chatGptConnected())
        val viewModel = viewModel(port)

        act(viewModel, ChatGptAction.RequestDisconnect, ChatGptAction.DismissDisconnect)

        assertThat(port.disconnectCalls).isEmpty()
        assertThat(viewModel.state().disconnectDialog).isNull()
    }

    @Test
    fun `connecting after a disconnect shows the new connection`() = runTest(main.dispatcher) {
        val port = FakeChatGptPort(ConnectionsFixtures.chatGptConnected()).apply { showDisconnect = false }
        val viewModel = viewModel(port)
        act(viewModel, ChatGptAction.RequestDisconnect, ChatGptAction.ConfirmDisconnect)

        act(viewModel, ChatGptAction.Connect)

        assertThat(viewModel.state().phase).isEqualTo(ChatGptPhase.CONNECTED)
    }

    @Test
    fun `dismissing a message clears it`() = runTest(main.dispatcher) {
        val viewModel = viewModel(FakeChatGptPort().apply { connectResult = ChatGptConnectResult.NoBrowser })
        act(viewModel, ChatGptAction.Connect)

        act(viewModel, ChatGptAction.DismissNotice)

        assertThat(viewModel.state().notice).isNull()
    }

    @Test
    fun `the screen state never prints the account`() = runTest(main.dispatcher) {
        val state = viewModel(FakeChatGptPort(ConnectionsFixtures.chatGptConnected())).state()

        assertThat(state.toString()).doesNotContain(ConnectionsFixtures.CHATGPT_ACCOUNT)
        assertThat(ConnectionsFixtures.chatGptConnected().toString()).doesNotContain(ConnectionsFixtures.CHATGPT_ACCOUNT)
        assertThat(ChatGptConnectResult.Connected(ConnectionsFixtures.CHATGPT_ACCOUNT).toString())
            .doesNotContain(ConnectionsFixtures.CHATGPT_ACCOUNT)
    }
}
