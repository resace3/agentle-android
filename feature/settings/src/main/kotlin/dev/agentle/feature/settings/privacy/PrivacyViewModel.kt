package dev.agentle.feature.settings.privacy

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.common.Outcome
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.feature.settings.port.PrivacyPort
import dev.agentle.feature.settings.port.PrivacyState
import dev.agentle.feature.settings.port.UserTimeZonePort
import dev.agentle.feature.settings.ui.EffectViewModel
import dev.agentle.feature.settings.ui.Loadable
import dev.agentle.feature.settings.ui.SettingsEffect
import dev.agentle.feature.settings.ui.WhileUiSubscribed
import dev.agentle.feature.settings.ui.reloading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import javax.inject.Inject

internal data class PrivacyUiState(val content: Loadable<PrivacyState> = Loadable.Loading, val zone: TimeZone = TimeZone.UTC)

internal sealed interface PrivacyAction {
    data object Retry : PrivacyAction

    data class SetProtectAllScreens(val enabled: Boolean) : PrivacyAction

    data object OpenAiDataSharing : PrivacyAction

    data object OpenNotificationSettings : PrivacyAction
}

/** Privacy: what is stored where, what never leaves the phone, AI sharing and the protections in force. */
@HiltViewModel
internal class PrivacyViewModel @Inject constructor(private val port: PrivacyPort, zonePort: UserTimeZonePort) : EffectViewModel() {
    private val reloads = MutableStateFlow(0)

    val state: StateFlow<PrivacyUiState> = reloads.reloading { port.state }
        .map { PrivacyUiState(it, zonePort.zone()) }
        .stateIn(viewModelScope, WhileUiSubscribed, PrivacyUiState(zone = zonePort.zone()))

    fun onAction(action: PrivacyAction) {
        when (action) {
            PrivacyAction.Retry -> reloads.update { it + 1 }

            is PrivacyAction.SetProtectAllScreens -> viewModelScope.launch {
                val result = port.setProtectAllScreens(action.enabled)
                if (result is Outcome.Failure) report(result.error)
            }

            PrivacyAction.OpenAiDataSharing -> send(SettingsEffect.Navigate(AppRoute.AiDataSharing))

            PrivacyAction.OpenNotificationSettings -> send(SettingsEffect.Navigate(AppRoute.NotificationSettings))
        }
    }
}
