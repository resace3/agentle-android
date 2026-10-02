package dev.agentle.feature.connections.wearable

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.common.Outcome
import dev.agentle.feature.connections.STOP_TIMEOUT_MS
import dev.agentle.feature.connections.port.DisplayZonePort
import dev.agentle.feature.connections.port.WearableAuthorizationPurpose
import dev.agentle.feature.connections.port.WearableAuthorizationResult
import dev.agentle.feature.connections.port.WearableConnectionPort
import dev.agentle.feature.connections.port.WearableConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Wearable screen (Google Health API; Journeys 3 and 8). The connection itself lives behind
 * [WearableConnectionPort]; this ViewModel adds what only the screen knows: which action runs, the outcome message,
 * the disconnect dialog, and a disconnect that the port has not reflected yet (shown at once).
 *
 * One action at a time: while connecting, waiting for Google's consent screen, syncing or disconnecting, other actions
 * are ignored. Google's consent screen is launched by the screen ([WearableEffect.LaunchConsent]); its result comes
 * back as [WearableAction.ConsentResult], also after the process was recreated meanwhile.
 */
@HiltViewModel
public class WearableViewModel @Inject constructor(
    private val port: WearableConnectionPort,
    private val zones: DisplayZonePort,
) : ViewModel() {
    private val reload = MutableStateFlow(0)
    private val local = MutableStateFlow(WearableLocal())
    private val effectChannel = Channel<WearableEffect>(Channel.BUFFERED)

    /** One-off effects for the screen; each is delivered to one collector. */
    internal val effects: Flow<WearableEffect> = effectChannel.receiveAsFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val remote: Flow<WearableRemote> = reload.flatMapLatest {
        port.state
            .map<WearableConnectionState, WearableRemote> { WearableRemote.Ready(it) }
            .onStart { emit(WearableRemote.Loading) }
            .catch { emit(WearableRemote.Failed) }
    }

    internal val uiState: StateFlow<WearableUiState> =
        combine(remote, local) { remote, local -> reduceWearable(remote, local, zones.zone()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WearableUiState())

    internal fun onAction(action: WearableAction) {
        when (action) {
            WearableAction.Connect -> authorize(WearableAuthorizationPurpose.CONNECT)
            WearableAction.Reconnect -> authorize(WearableAuthorizationPurpose.RECONNECT)
            WearableAction.GrantMore -> authorize(WearableAuthorizationPurpose.GRANT_MORE)
            is WearableAction.ConsentResult -> completeConsent(action.resultCode, action.data)
            WearableAction.ConsentLaunchFailed ->
                local.update { it.copy(busy = null, notice = WearableNotice.ConsentScreenFailed) }
            WearableAction.SyncNow -> syncNow()
            WearableAction.RequestDisconnect ->
                local.update { if (it.busy == null) it.copy(disconnectDialog = WearableDisconnectDialog()) else it }
            is WearableAction.SetDeleteData ->
                local.update { it.copy(disconnectDialog = it.disconnectDialog?.copy(deleteData = action.delete)) }
            WearableAction.ConfirmDisconnect -> disconnect()
            WearableAction.DismissDisconnect -> local.update { it.copy(disconnectDialog = null) }
            WearableAction.DismissNotice -> local.update { it.copy(notice = null) }
            WearableAction.Retry -> reload.update { it + 1 }
        }
    }

    /** Marks [busy] and clears the last message, unless another action runs; returns whether it started. */
    private fun begin(busy: WearableBusy, newAttempt: Boolean = false): Boolean {
        val previous = local.getAndUpdate { state ->
            when {
                state.busy != null -> state
                newAttempt -> state.copy(busy = busy, notice = null, resultProblem = null, disconnected = false)
                else -> state.copy(busy = busy, notice = null)
            }
        }
        return previous.busy == null
    }

    private fun authorize(purpose: WearableAuthorizationPurpose) {
        if (!begin(WearableBusy.CONNECTING, newAttempt = true)) return
        viewModelScope.launch { handle(port.authorize(purpose)) }
    }

    private fun completeConsent(resultCode: Int, data: Intent?) {
        // No busy check: after a process death the result reaches a fresh ViewModel that started nothing.
        local.update { it.copy(busy = WearableBusy.CONNECTING, notice = null, disconnectDialog = null) }
        viewModelScope.launch { handle(port.completeAuthorization(resultCode, data)) }
    }

    private fun handle(result: WearableAuthorizationResult) {
        when (result) {
            is WearableAuthorizationResult.NeedsResolution -> {
                local.update { it.copy(busy = WearableBusy.AWAITING_CONSENT) }
                effectChannel.trySend(WearableEffect.LaunchConsent(result.pendingIntent))
            }
            is WearableAuthorizationResult.AccountProblem ->
                local.update { it.copy(busy = null, resultProblem = result.problem, disconnected = false) }
            is WearableAuthorizationResult.Connected ->
                local.update { it.copy(busy = null, notice = noticeFor(result), disconnected = false) }
            else -> local.update { it.copy(busy = null, notice = noticeFor(result)) }
        }
    }

    private fun syncNow() {
        if (!begin(WearableBusy.SYNCING)) return
        viewModelScope.launch {
            val result = port.syncNow()
            local.update {
                it.copy(busy = null, notice = WearableNotice.SyncFinished(result.status, result.committed, result.error))
            }
        }
    }

    private fun disconnect() {
        val deleteData = local.value.disconnectDialog?.deleteData ?: return
        if (!begin(WearableBusy.DISCONNECTING)) return
        local.update { it.copy(disconnectDialog = null) }
        viewModelScope.launch {
            when (val outcome = port.disconnect(deleteSyncedData = deleteData)) {
                is Outcome.Success -> local.update {
                    it.copy(
                        busy = null,
                        notice = WearableNotice.Disconnected(outcome.value),
                        resultProblem = null,
                        disconnected = true,
                    )
                }
                is Outcome.Failure -> local.update {
                    it.copy(busy = null, notice = WearableNotice.DisconnectFailed(outcome.error))
                }
            }
        }
    }
}
