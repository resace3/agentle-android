package dev.agentle.app.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.connectors.android.collectors.sensors.SensorGateway
import dev.agentle.connectors.api.sensors.SensorCheck
import dev.agentle.connectors.api.sensors.SensorStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * The Phone sensors tab: lists every sensor the phone reports and checks live that each one can be read. Listening runs
 * only while the tab is on screen and starts over (re-listing the sensors) on [checkAgain], for example after a
 * permission was granted.
 */
@HiltViewModel
class SensorsViewModel @Inject constructor(gateway: SensorGateway) : ViewModel() {
    private val restarts = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val statuses: StateFlow<List<SensorStatus>> = restarts
        .flatMapLatest { _ ->
            val sensors = gateway.sensors()
            gateway.live(sensors).map { snapshot -> SensorCheck.statuses(sensors, snapshot) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    fun checkAgain() {
        restarts.update { it + 1 }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
