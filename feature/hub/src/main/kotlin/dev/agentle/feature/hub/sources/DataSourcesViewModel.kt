package dev.agentle.feature.hub.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.feature.hub.Load
import dev.agentle.feature.hub.loadOf
import dev.agentle.feature.hub.port.DataSourceItem
import dev.agentle.feature.hub.port.DataSourcesPort
import dev.agentle.feature.hub.port.HubClockPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

internal data class DataSourcesUiState(val content: Load<List<DataSourceItem>> = Load.Loading, val actionError: AppError? = null)

@HiltViewModel
internal class DataSourcesViewModel @Inject constructor(private val port: DataSourcesPort, clockPort: HubClockPort) : ViewModel() {
    val clock = clockPort.clock
    private val retries = MutableStateFlow(0)
    private val actionError = MutableStateFlow<AppError?>(null)

    val state: StateFlow<DataSourcesUiState> =
        combine(loadOf(retries) { port.sources }, actionError) { load, error -> DataSourcesUiState(load, error) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DataSourcesUiState())

    fun retry() {
        retries.value++
    }

    fun setEnabled(item: DataSourceItem, enabled: Boolean) = launchAction { port.setEnabled(item.metadata.connectorId, enabled) }

    fun syncNow(item: DataSourceItem) = launchAction { port.syncNow(item.metadata.connectorId) }

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
