package dev.agentle.feature.settings.retention

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.common.Outcome
import dev.agentle.feature.settings.R
import dev.agentle.feature.settings.port.RetentionImpact
import dev.agentle.feature.settings.port.RetentionPeriod
import dev.agentle.feature.settings.port.RetentionPort
import dev.agentle.feature.settings.port.RetentionSettings
import dev.agentle.feature.settings.port.UserTimeZonePort
import dev.agentle.feature.settings.ui.EffectViewModel
import dev.agentle.feature.settings.ui.Loadable
import dev.agentle.feature.settings.ui.SettingsEffect
import dev.agentle.feature.settings.ui.WhileUiSubscribed
import dev.agentle.feature.settings.ui.reloading
import dev.agentle.feature.settings.ui.valueOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import javax.inject.Inject

internal data class RetentionUiState(
    val settings: Loadable<RetentionSettings> = Loadable.Loading,
    /** A period being stored; shown as selected until the write finishes. */
    val saving: RetentionPeriod? = null,
    /** A shorter period whose impact is being counted. */
    val counting: RetentionPeriod? = null,
    /** A shorter period waiting for confirmation, with what the next cleanup deletes. */
    val confirmation: RetentionImpact? = null,
    val zone: TimeZone = TimeZone.UTC,
) {
    val busy: Boolean get() = saving != null || counting != null
    val selected: RetentionPeriod? get() = saving ?: settings.valueOrNull()?.period
}

internal sealed interface RetentionAction {
    data object Retry : RetentionAction

    data class Select(val period: RetentionPeriod) : RetentionAction

    data object ConfirmChange : RetentionAction

    data object DismissChange : RetentionAction
}

/**
 * Data retention. A longer period is stored at once (nothing is deleted). A shorter one is counted first
 * ([RetentionPort.impactOf]) and needs confirmation when the next daily cleanup would delete anything.
 */
@HiltViewModel
internal class RetentionViewModel @Inject constructor(
    private val port: RetentionPort,
    @Suppress("UnusedPrivateProperty") private val zonePort: UserTimeZonePort,
) : EffectViewModel() {
    private data class Local(
        val saving: RetentionPeriod? = null,
        val counting: RetentionPeriod? = null,
        val confirmation: RetentionImpact? = null,
    )

    private val reloads = MutableStateFlow(0)
    private val local = MutableStateFlow(Local())

    val state: StateFlow<RetentionUiState> = combine(reloads.reloading { port.settings }, local) { settings, pending ->
        RetentionUiState(settings, pending.saving, pending.counting, pending.confirmation, zonePort.zone())
    }.stateIn(viewModelScope, WhileUiSubscribed, RetentionUiState(zone = zonePort.zone()))

    fun onAction(action: RetentionAction) {
        when (action) {
            RetentionAction.Retry -> reloads.update { it + 1 }
            is RetentionAction.Select -> select(action.period)
            RetentionAction.ConfirmChange -> local.value.confirmation?.let { save(it.period) }
            RetentionAction.DismissChange -> local.update { it.copy(confirmation = null) }
        }
    }

    private fun select(period: RetentionPeriod) {
        val current = state.value.settings.valueOrNull()?.period ?: return
        val pending = local.value
        if (pending.saving != null || pending.counting != null || period == current) return
        if (!period.isShorterThan(current)) {
            save(period)
            return
        }
        local.update { it.copy(counting = period) }
        viewModelScope.launch {
            val impact = port.impactOf(period)
            local.update { it.copy(counting = null) }
            when (impact) {
                is Outcome.Success ->
                    if (impact.value.recordsToDelete > 0) local.update { it.copy(confirmation = impact.value) } else save(period)

                is Outcome.Failure -> report(impact.error)
            }
        }
    }

    private fun save(period: RetentionPeriod) {
        local.update { it.copy(saving = period, confirmation = null) }
        viewModelScope.launch {
            val result = port.setPeriod(period)
            local.update { it.copy(saving = null) }
            when (result) {
                is Outcome.Success -> send(SettingsEffect.Message(R.string.settings_retention_saved))
                is Outcome.Failure -> report(result.error)
            }
        }
    }
}
