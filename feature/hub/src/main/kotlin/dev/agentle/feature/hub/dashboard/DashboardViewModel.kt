package dev.agentle.feature.hub.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.feature.hub.Load
import dev.agentle.feature.hub.loadOf
import dev.agentle.feature.hub.port.DashboardData
import dev.agentle.feature.hub.port.DashboardPort
import dev.agentle.feature.hub.port.HubClockPort
import dev.agentle.feature.hub.port.PendingIntervention
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

internal data class DashboardUiState(val content: Load<DashboardData> = Load.Loading, val actionError: AppError? = null)

@HiltViewModel
internal class DashboardViewModel @Inject constructor(private val port: DashboardPort, clockPort: HubClockPort) : ViewModel() {
    val clock = clockPort.clock
    private val retries = MutableStateFlow(0)
    private val actionError = MutableStateFlow<AppError?>(null)

    val state: StateFlow<DashboardUiState> =
        combine(loadOf(retries) { port.dashboard }, actionError) { load, error -> DashboardUiState(load, error) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun retry() {
        retries.value++
    }

    fun snooze(card: PendingIntervention) = launchAction { port.snooze(card.decisionKey) }

    fun notNow(card: PendingIntervention) = launchAction { port.notNow(card.decisionKey) }

    fun stopJitai(card: PendingIntervention) = launchAction { port.stopJitai(card.jitaiId) }

    fun dismissError() {
        actionError.value = null
    }

    private fun launchAction(action: suspend () -> Outcome<Unit>) {
        viewModelScope.launch {
            val outcome = action()
            if (outcome is Outcome.Failure) actionError.value = outcome.error
        }
    }
}
