package dev.agentle.feature.settings.deletion

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.common.Outcome
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.feature.settings.port.CollectionControl
import dev.agentle.feature.settings.port.DeleteAllState
import dev.agentle.feature.settings.port.DeletionItem
import dev.agentle.feature.settings.port.DeletionOverview
import dev.agentle.feature.settings.port.DeletionPort
import dev.agentle.feature.settings.port.DeletionReport
import dev.agentle.feature.settings.port.DeletionTarget
import dev.agentle.feature.settings.port.UserTimeZonePort
import dev.agentle.feature.settings.ui.EffectViewModel
import dev.agentle.feature.settings.ui.Loadable
import dev.agentle.feature.settings.ui.SettingsEffect
import dev.agentle.feature.settings.ui.WhileUiSubscribed
import dev.agentle.feature.settings.ui.reloading
import dev.agentle.feature.settings.ui.valueOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import javax.inject.Inject

/** A per-target delete waiting for confirmation, with the "also stop collecting" choice. */
internal data class DeleteRequest(val item: DeletionItem, val stopCollecting: Boolean = false)

internal data class DeleteDataUiState(
    val overview: Loadable<DeletionOverview> = Loadable.Loading,
    val deleteAll: DeleteAllState = DeleteAllState.Idle,
    val request: DeleteRequest? = null,
    /** The per-target delete running now. */
    val deleting: DeletionTarget? = null,
    /** The verified result of the last per-target delete. */
    val report: DeletionReport? = null,
    /** The typed confirmation of "delete everything" is open. */
    val confirmingDeleteAll: Boolean = false,
    /** "Delete everything" was confirmed and the port has not answered yet. */
    val startingDeleteAll: Boolean = false,
    /** "Close Agentle" was tapped after the verified report. */
    val finishing: Boolean = false,
    val zone: TimeZone = TimeZone.UTC,
) {
    /** A deletion of any kind is running, so no other delete may start. */
    val busy: Boolean get() = deleting != null || startingDeleteAll || deleteAll !is DeleteAllState.Idle
}

internal sealed interface DeleteDataAction {
    data object Retry : DeleteDataAction

    data class RequestDelete(val target: DeletionTarget) : DeleteDataAction

    data class SetStopCollecting(val stop: Boolean) : DeleteDataAction

    data object ConfirmDelete : DeleteDataAction

    data object DismissDelete : DeleteDataAction

    data object DismissReport : DeleteDataAction

    data object RequestDeleteEverything : DeleteDataAction

    data object DismissDeleteEverything : DeleteDataAction

    /** Starts "delete everything" after the typed confirmation, or resumes it after a failure. */
    data object ConfirmDeleteEverything : DeleteDataAction

    /** "Close Agentle" after the verified report: clears the app data. */
    data object FinishDeleteEverything : DeleteDataAction
}

/**
 * Delete data (Journey 10). Per-target deletes return verified counts, which the screen shows. "Delete everything"
 * runs in the port's application scope; this ViewModel only starts it and follows [DeletionPort.deleteAllState], so
 * the progress, a failure or a resumed run after a restart all show the same way.
 */
@HiltViewModel
internal class DeleteDataViewModel @Inject constructor(
    private val port: DeletionPort,
    private val zone: UserTimeZonePort,
) : EffectViewModel() {
    private data class Local(
        val request: DeleteRequest? = null,
        val deleting: DeletionTarget? = null,
        val report: DeletionReport? = null,
        val confirmingDeleteAll: Boolean = false,
        val startingDeleteAll: Boolean = false,
        val finishing: Boolean = false,
    )

    private val reloads = MutableStateFlow(0)
    private val local = MutableStateFlow(Local())

    val state: StateFlow<DeleteDataUiState> = combine(
        reloads.reloading { port.overview },
        port.deleteAllState.onStart { emit(DeleteAllState.Idle) }.catch { emit(DeleteAllState.Idle) },
        local,
    ) { overview, deleteAll, pending ->
        DeleteDataUiState(
            overview = overview,
            deleteAll = deleteAll,
            request = pending.request,
            deleting = pending.deleting,
            report = pending.report,
            confirmingDeleteAll = pending.confirmingDeleteAll && deleteAll is DeleteAllState.Idle,
            startingDeleteAll = pending.startingDeleteAll,
            finishing = pending.finishing,
            zone = zone.zone(),
        )
    }.stateIn(viewModelScope, WhileUiSubscribed, DeleteDataUiState(zone = zone.zone()))

    fun onAction(action: DeleteDataAction) {
        when (action) {
            DeleteDataAction.Retry -> reloads.update { it + 1 }
            is DeleteDataAction.RequestDelete -> requestDelete(action.target)
            is DeleteDataAction.SetStopCollecting -> local.update { it.copy(request = it.request?.copy(stopCollecting = action.stop)) }
            DeleteDataAction.ConfirmDelete -> confirmDelete()
            DeleteDataAction.DismissDelete -> local.update { it.copy(request = null) }
            DeleteDataAction.DismissReport -> local.update { it.copy(report = null) }
            DeleteDataAction.RequestDeleteEverything -> if (!state.value.busy) local.update { it.copy(confirmingDeleteAll = true) }
            DeleteDataAction.DismissDeleteEverything -> local.update { it.copy(confirmingDeleteAll = false) }
            DeleteDataAction.ConfirmDeleteEverything -> deleteEverything()
            DeleteDataAction.FinishDeleteEverything -> finish()
        }
    }

    private fun requestDelete(target: DeletionTarget) {
        val current = state.value
        if (current.busy) return
        val item = current.overview.valueOrNull()?.items?.firstOrNull { it.target == target } ?: return
        local.update { it.copy(request = DeleteRequest(item)) }
    }

    private fun confirmDelete() {
        val request = local.value.request ?: return
        if (state.value.busy) return
        val target = request.item.target
        val stopCollecting = request.stopCollecting && request.item.collection == CollectionControl.ACTIVE
        local.update { it.copy(request = null, deleting = target, report = null) }
        viewModelScope.launch {
            val result = port.delete(target, stopCollecting)
            local.update { it.copy(deleting = null, report = (result as? Outcome.Success)?.value) }
            if (result is Outcome.Failure) report(result.error)
        }
    }

    private fun deleteEverything() {
        val current = state.value
        val canStart = current.deleteAll is DeleteAllState.Idle || current.deleteAll is DeleteAllState.Failed
        if (!canStart || current.startingDeleteAll || current.deleting != null) return
        local.update { it.copy(confirmingDeleteAll = false, startingDeleteAll = true, report = null) }
        viewModelScope.launch {
            val result = port.deleteEverything()
            local.update { it.copy(startingDeleteAll = false) }
            if (result is Outcome.Failure) report(result.error)
        }
    }

    private fun finish() {
        if (state.value.deleteAll !is DeleteAllState.Verified || local.value.finishing) return
        local.update { it.copy(finishing = true) }
        viewModelScope.launch {
            when (val result = port.finishDeleteEverything()) {
                // On a device the process ends inside the call; where it returns, the app restarts at onboarding.
                is Outcome.Success -> send(SettingsEffect.ResetTo(AppRoute.Onboarding))

                is Outcome.Failure -> {
                    local.update { it.copy(finishing = false) }
                    report(result.error)
                }
            }
        }
    }
}
