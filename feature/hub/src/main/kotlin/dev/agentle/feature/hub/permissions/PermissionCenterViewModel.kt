package dev.agentle.feature.hub.permissions

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.CapabilityCategory
import dev.agentle.core.ui.permission.PermissionDialogResult
import dev.agentle.feature.hub.Load
import dev.agentle.feature.hub.loadOf
import dev.agentle.feature.hub.port.CapabilityItem
import dev.agentle.feature.hub.port.HubClockPort
import dev.agentle.feature.hub.port.PermissionCenterPort
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class CapabilityGroup(val category: CapabilityCategory, val items: List<CapabilityItem>)

internal data class PermissionCenterUiState(
    val content: Load<List<CapabilityGroup>> = Load.Loading,
    val focusId: String? = null,
    val revokeHelpFor: CapabilityItem? = null,
    val actionError: AppError? = null,
)

internal sealed interface PermissionCenterEffect {
    data class Request(val permissions: List<String>) : PermissionCenterEffect

    data class OpenSettings(val intent: Intent?) : PermissionCenterEffect
}

@HiltViewModel(assistedFactory = PermissionCenterViewModel.Factory::class)
internal class PermissionCenterViewModel @AssistedInject constructor(
    @Assisted private val focusId: String?,
    private val port: PermissionCenterPort,
    clockPort: HubClockPort,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(focusId: String?): PermissionCenterViewModel
    }

    val clock = clockPort.clock
    private val retries = MutableStateFlow(0)
    private val local = MutableStateFlow(PermissionCenterUiState(focusId = focusId))
    private val effects = Channel<PermissionCenterEffect>(Channel.BUFFERED)
    private var pendingCapability: String? = null

    val effect: Flow<PermissionCenterEffect> = effects.receiveAsFlow()

    val state: StateFlow<PermissionCenterUiState> =
        combine(loadOf(retries) { port.capabilities }, local) { load, local ->
            local.copy(content = load.groupByCategory())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PermissionCenterUiState(focusId = focusId))

    private fun Load<List<CapabilityItem>>.groupByCategory(): Load<List<CapabilityGroup>> = when (this) {
        is Load.Ready -> Load.Ready(
            value.groupBy { it.capability.category }.toSortedMap().map { (category, items) -> CapabilityGroup(category, items) },
        )

        is Load.Failed -> this

        Load.Loading -> Load.Loading
    }

    fun retry() {
        retries.value++
    }

    /** On resume: re-evaluate with the rationale flags the screen read from the activity. */
    fun refresh(rationale: Map<String, Boolean>) = launchAction { port.refresh(rationale) }

    fun request(item: CapabilityItem) {
        pendingCapability = item.capability.id
        effects.trySend(PermissionCenterEffect.Request(item.missingPermissions))
    }

    fun onPermissionResult(result: PermissionDialogResult) {
        val capabilityId = pendingCapability ?: return
        pendingCapability = null
        if (result.cancelled) return
        launchAction { port.reportPermissionResult(capabilityId, result.granted, result.showRationale) }
    }

    fun openSettings(item: CapabilityItem) {
        effects.trySend(PermissionCenterEffect.OpenSettings(port.settingsIntent(item.capability.id)))
    }

    fun setCollectionEnabled(item: CapabilityItem, enabled: Boolean) =
        launchAction { port.setCollectionEnabled(item.capability.id, enabled) }

    fun showRevokeHelp(item: CapabilityItem?) {
        local.value = local.value.copy(revokeHelpFor = item)
    }

    fun dismissActionError() {
        local.value = local.value.copy(actionError = null)
    }

    private fun launchAction(action: suspend () -> Outcome<Unit>) {
        viewModelScope.launch {
            val outcome = action()
            if (outcome is Outcome.Failure) local.value = local.value.copy(actionError = outcome.error)
        }
    }
}
