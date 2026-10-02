package dev.agentle.feature.connections.aisharing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.common.Outcome
import dev.agentle.feature.connections.STOP_TIMEOUT_MS
import dev.agentle.feature.connections.port.AiDataSharingPort
import dev.agentle.feature.connections.port.AiSharingCategory
import dev.agentle.feature.connections.port.DisplayZonePort
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The AI Data Sharing screen: one switch per category (all off until the user turns one on, after confirming the
 * disclosure), the consent version, a preview of exactly what a request for a purpose would send, and the request
 * history as metadata. Turning a category off is written at once and reports the requests it cancelled.
 */
@HiltViewModel
public class AiSharingViewModel @Inject constructor(private val port: AiDataSharingPort, zones: DisplayZonePort) :
    ViewModel() {
    private val reload = MutableStateFlow(0)
    private val local = MutableStateFlow(AiSharingLocal())
    private var previewJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val remote: Flow<AiSharingRemote> = reload.flatMapLatest { _ ->
        combine(port.consent, port.history) { consent, history -> AiSharingRemote.Ready(consent, history) }
            .onStart<AiSharingRemote> { emit(AiSharingRemote.Loading) }
            .catch { emit(AiSharingRemote.Failed) }
    }

    internal val uiState: StateFlow<AiSharingUiState> =
        combine(remote, local) { remote, local -> reduceAiSharing(remote, local, zones.zone()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AiSharingUiState())

    internal fun onAction(action: AiSharingAction) {
        when (action) {
            is AiSharingAction.Toggle ->
                if (action.allowed) {
                    local.update { if (action.category in it.pending) it else it.copy(confirm = action.category) }
                } else {
                    write(action.category, allowed = false)
                }

            AiSharingAction.ConfirmTurnOn -> {
                val category = local.value.confirm ?: return
                local.update { it.copy(confirm = null) }
                write(category, allowed = true)
            }

            AiSharingAction.DismissConfirm -> local.update { it.copy(confirm = null) }

            is AiSharingAction.SelectPurpose -> {
                previewJob?.cancel()
                local.update { it.copy(purpose = action.purpose, preview = AiPreviewUi.Hidden) }
            }

            AiSharingAction.BuildPreview -> buildPreview()

            AiSharingAction.ClosePreview -> {
                previewJob?.cancel()
                local.update { it.copy(preview = AiPreviewUi.Hidden) }
            }

            AiSharingAction.DismissNotice -> local.update { it.copy(notice = null) }

            AiSharingAction.Retry -> reload.update { it + 1 }
        }
    }

    private fun write(category: AiSharingCategory, allowed: Boolean) {
        if (category in local.value.pending) return
        // A shown preview no longer matches the choices once they change.
        previewJob?.cancel()
        local.update {
            it.copy(
                pending = (it.pending + (category to allowed)).toPersistentMap(),
                notice = null,
                preview = AiPreviewUi.Hidden,
            )
        }
        viewModelScope.launch {
            val outcome = port.setAllowed(category, allowed)
            local.update { state ->
                val notice = when (outcome) {
                    is Outcome.Success ->
                        if (allowed) {
                            AiSharingNotice.TurnedOn(category)
                        } else {
                            AiSharingNotice.TurnedOff(category, outcome.value.cancelledRequests.toImmutableList())
                        }

                    is Outcome.Failure -> AiSharingNotice.ChangeFailed(category, turningOn = allowed, error = outcome.error)
                }
                state.copy(pending = (state.pending - category).toPersistentMap(), notice = notice)
            }
        }
    }

    private fun buildPreview() {
        val purpose = local.value.purpose
        previewJob?.cancel()
        local.update { it.copy(preview = AiPreviewUi.Building(purpose)) }
        previewJob = viewModelScope.launch {
            val preview = when (val outcome = port.preview(purpose)) {
                is Outcome.Success -> AiPreviewUi.Shown(outcome.value)
                is Outcome.Failure -> AiPreviewUi.Failed(purpose, outcome.error)
            }
            local.update { it.copy(preview = preview) }
        }
    }
}
