package dev.agentle.feature.settings.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.getOrNull
import dev.agentle.feature.settings.port.BackgroundBehaviorPort
import dev.agentle.feature.settings.port.CollectionProfile
import dev.agentle.feature.settings.port.DebugToolsPort
import dev.agentle.feature.settings.port.JitaiPause
import dev.agentle.feature.settings.port.NotificationSettingsPort
import dev.agentle.feature.settings.port.RetentionPeriod
import dev.agentle.feature.settings.port.RetentionPort
import dev.agentle.feature.settings.port.UserTimeZonePort
import dev.agentle.feature.settings.ui.WhileUiSubscribed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.TimeZone
import javax.inject.Inject

/**
 * The settings hub. Its links never depend on data; the summaries under them appear when their ports can be read
 * (a port that fails only removes its summary). The debug panel link exists only when [DebugToolsPort.available].
 */
internal data class SettingsHubUiState(
    val retention: RetentionPeriod? = null,
    val profile: CollectionProfile? = null,
    val pause: JitaiPause? = null,
    val dailyCap: Int? = null,
    val debugAvailable: Boolean = false,
    val zone: TimeZone = TimeZone.UTC,
)

@HiltViewModel
internal class SettingsHubViewModel @Inject constructor(
    retention: RetentionPort,
    background: BackgroundBehaviorPort,
    notifications: NotificationSettingsPort,
    debugTools: DebugToolsPort,
    @Suppress("UnusedPrivateProperty") private val zonePort: UserTimeZonePort,
) : ViewModel() {
    private val debugAvailable: Boolean = debugTools.available

    val state: StateFlow<SettingsHubUiState> = combine(
        retention.settings.summary { it.period },
        background.state.summary { it.profile },
        notifications.state.summary { it },
    ) { period, profile, notification ->
        SettingsHubUiState(
            retention = period,
            profile = profile,
            pause = notification?.pause,
            dailyCap = notification?.limits?.dailyCap,
            debugAvailable = debugAvailable,
            zone = zonePort.zone(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = WhileUiSubscribed,
        initialValue = SettingsHubUiState(debugAvailable = debugAvailable, zone = zonePort.zone()),
    )
}

/** A summary value: null until the port emits, and null when it fails (the link still shows). */
private fun <T, R> Flow<Outcome<T>>.summary(select: (T) -> R): Flow<R?> = map { outcome -> outcome.getOrNull()?.let(select) }
    .onStart { emit(null) }
    .catch { emit(null) }
