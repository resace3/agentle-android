package dev.agentle.feature.connections.chatgpt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.common.Outcome
import dev.agentle.feature.connections.STOP_TIMEOUT_MS
import dev.agentle.feature.connections.port.ChatGptConnectRequest
import dev.agentle.feature.connections.port.ChatGptConnectResult
import dev.agentle.feature.connections.port.ChatGptConnectionPort
import dev.agentle.feature.connections.port.ChatGptConnectionState
import dev.agentle.feature.connections.port.ChatGptDisconnectResult
import dev.agentle.feature.connections.port.DisplayZonePort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The ChatGPT screen (Sign in with ChatGPT; Journeys 4 and 9). The sign-in attempt belongs to the application behind
 * [ChatGptConnectionPort]: this ViewModel only starts it, shows "waiting for the browser" and reports how it ended.
 * A disconnect shows the disconnected state as soon as the port returns, whatever the port's flow says meanwhile.
 */
@HiltViewModel
public class ChatGptViewModel @Inject constructor(private val port: ChatGptConnectionPort, zones: DisplayZonePort) : ViewModel() {
    private val reload = MutableStateFlow(0)
    private val local = MutableStateFlow(ChatGptLocal())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val remote: Flow<ChatGptRemote> = reload.flatMapLatest { _ ->
        port.state
            .map<ChatGptConnectionState, ChatGptRemote> { ChatGptRemote.Ready(it) }
            .onStart { emit(ChatGptRemote.Loading) }
            .catch { emit(ChatGptRemote.Failed) }
    }

    internal val uiState: StateFlow<ChatGptUiState> =
        combine(remote, local) { remote, local -> reduceChatGpt(remote, local, zones.zone()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ChatGptUiState())

    init {
        // A sign-in that the app's death interrupted is reported once, on the first screen after the restart.
        viewModelScope.launch {
            val interrupted = port.takeInterruptedConnect() ?: return@launch
            local.update { if (it.notice == null) it.copy(notice = ChatGptNotice.SignIn(interrupted)) else it }
        }
    }

    internal fun onAction(action: ChatGptAction) {
        when (action) {
            ChatGptAction.Connect -> connect(ChatGptConnectRequest())

            ChatGptAction.UsePlan -> connect(ChatGptConnectRequest(enablePlanUsage = true))

            ChatGptAction.UseDifferentAccount -> connect(ChatGptConnectRequest(addAccount = true))

            ChatGptAction.CancelSignIn -> port.cancelConnect()

            ChatGptAction.AcknowledgePlanNotice -> acknowledgePlanNotice()

            ChatGptAction.RequestDisconnect ->
                local.update { if (it.busy == null) it.copy(disconnectDialog = ChatGptDisconnectDialog()) else it }

            is ChatGptAction.SetForgetRegistration -> local.update {
                it.copy(disconnectDialog = it.disconnectDialog?.copy(forgetRegistration = action.forget))
            }

            ChatGptAction.ConfirmDisconnect -> disconnect()

            ChatGptAction.DismissDisconnect -> local.update { it.copy(disconnectDialog = null) }

            ChatGptAction.DismissNotice -> local.update { it.copy(notice = null) }

            ChatGptAction.Retry -> reload.update { it + 1 }
        }
    }

    private fun connect(request: ChatGptConnectRequest) {
        val previous = local.getAndUpdate {
            if (it.busy == null) it.copy(busy = ChatGptBusy.SIGNING_IN, notice = null, disconnected = false) else it
        }
        if (previous.busy != null) {
            // Single flight: while waiting for the browser, "Open the sign-in page again" re-opens the live attempt's
            // page; the first caller reports the outcome.
            if (previous.busy == ChatGptBusy.SIGNING_IN) viewModelScope.launch { port.connect(request) }
            return
        }
        viewModelScope.launch {
            val result = port.connect(request)
            local.update {
                it.copy(
                    busy = null,
                    notice = ChatGptNotice.SignIn(result),
                    planNoticeAcknowledged = it.planNoticeAcknowledged && result !is ChatGptConnectResult.Connected,
                )
            }
        }
    }

    private fun acknowledgePlanNotice() {
        local.update { it.copy(planNoticeAcknowledged = true) }
        viewModelScope.launch {
            val outcome = port.acknowledgePlanNotice()
            if (outcome is Outcome.Failure) local.update { it.copy(notice = ChatGptNotice.ActionFailed(outcome.error)) }
        }
    }

    private fun disconnect() {
        val forget = local.value.disconnectDialog?.forgetRegistration ?: return
        val previous = local.getAndUpdate {
            if (it.busy == null) it.copy(busy = ChatGptBusy.DISCONNECTING, notice = null, disconnectDialog = null) else it
        }
        if (previous.busy != null) return
        viewModelScope.launch {
            val result = port.disconnect(forgetRegistration = forget)
            local.update {
                it.copy(
                    busy = null,
                    notice = ChatGptNotice.Disconnect(result, forgotRegistration = forget),
                    disconnected = it.disconnected || result !is ChatGptDisconnectResult.Failed,
                )
            }
        }
    }
}
