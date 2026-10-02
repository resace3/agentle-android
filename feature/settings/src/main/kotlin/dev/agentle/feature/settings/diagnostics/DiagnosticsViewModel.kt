package dev.agentle.feature.settings.diagnostics

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.common.Outcome
import dev.agentle.feature.settings.port.DiagnosticsPort
import dev.agentle.feature.settings.port.DiagnosticsSnapshot
import dev.agentle.feature.settings.port.UserTimeZonePort
import dev.agentle.feature.settings.ui.EffectViewModel
import dev.agentle.feature.settings.ui.Loadable
import dev.agentle.feature.settings.ui.SettingsEffect
import dev.agentle.feature.settings.ui.WhileUiSubscribed
import dev.agentle.feature.settings.ui.reloading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import javax.inject.Inject

internal data class DiagnosticsUiState(
    val content: Loadable<DiagnosticsSnapshot> = Loadable.Loading,
    /** The report is being written. */
    val exporting: Boolean = false,
    val zone: TimeZone = TimeZone.UTC,
)

internal sealed interface DiagnosticsAction {
    data object Retry : DiagnosticsAction

    data object Refresh : DiagnosticsAction

    data object Export : DiagnosticsAction
}

/** Diagnostics (spec §55): codes, states, counts and times only; the export goes to the share sheet. */
@HiltViewModel
internal class DiagnosticsViewModel @Inject constructor(
    private val port: DiagnosticsPort,
    private val zone: UserTimeZonePort,
) : EffectViewModel() {
    private val reloads = MutableStateFlow(0)
    private val exporting = MutableStateFlow(false)

    val state: StateFlow<DiagnosticsUiState> = combine(reloads.reloading { port.snapshot }, exporting) { content, isExporting ->
        DiagnosticsUiState(content, isExporting, zone.zone())
    }.stateIn(viewModelScope, WhileUiSubscribed, DiagnosticsUiState(zone = zone.zone()))

    fun onAction(action: DiagnosticsAction) {
        when (action) {
            DiagnosticsAction.Retry -> reloads.update { it + 1 }
            DiagnosticsAction.Refresh -> viewModelScope.launch { port.refresh() }
            DiagnosticsAction.Export -> export()
        }
    }

    private fun export() {
        if (exporting.value) return
        exporting.value = true
        viewModelScope.launch {
            when (val result = port.exportReport()) {
                is Outcome.Success -> send(SettingsEffect.Share(result.value.uri, result.value.mimeType))
                is Outcome.Failure -> report(result.error)
            }
            exporting.value = false
        }
    }
}
