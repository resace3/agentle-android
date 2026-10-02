package dev.agentle.feature.settings.background

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.common.Outcome
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.feature.settings.port.BackgroundBehaviorPort
import dev.agentle.feature.settings.port.BackgroundBehaviorState
import dev.agentle.feature.settings.port.CollectionProfile
import dev.agentle.feature.settings.port.SystemSettingsPort
import dev.agentle.feature.settings.port.SystemSettingsTarget
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
import javax.inject.Inject

internal data class BackgroundUiState(
    val content: Loadable<BackgroundBehaviorState> = Loadable.Loading,
    /** A profile being stored; shown as selected until the write finishes. */
    val savingProfile: CollectionProfile? = null,
)

internal sealed interface BackgroundAction {
    data object Retry : BackgroundAction

    data class SelectProfile(val profile: CollectionProfile) : BackgroundAction

    /** The app's own system page, where battery usage can be changed (the app never requests an exemption). */
    data object OpenAppSettings : BackgroundAction

    data object OpenDataSources : BackgroundAction
}

/** Background behavior: the collection profile, Battery Saver, battery optimization and the per-source summary. */
@HiltViewModel
internal class BackgroundBehaviorViewModel @Inject constructor(
    private val port: BackgroundBehaviorPort,
    private val systemSettings: SystemSettingsPort,
) : EffectViewModel() {
    private val reloads = MutableStateFlow(0)
    private val saving = MutableStateFlow<CollectionProfile?>(null)

    val state: StateFlow<BackgroundUiState> = combine(reloads.reloading { port.state }, saving) { content, savingProfile ->
        BackgroundUiState(content, savingProfile)
    }.stateIn(viewModelScope, WhileUiSubscribed, BackgroundUiState())

    fun onAction(action: BackgroundAction) {
        when (action) {
            BackgroundAction.Retry -> reloads.update { it + 1 }
            is BackgroundAction.SelectProfile -> select(action.profile)
            BackgroundAction.OpenAppSettings -> openSystemPage(systemSettings.intentFor(SystemSettingsTarget.AppDetails))
            BackgroundAction.OpenDataSources -> send(SettingsEffect.Navigate(AppRoute.DataSources))
        }
    }

    private fun select(profile: CollectionProfile) {
        val current = state.value.content.valueOrNull() ?: return
        if (saving.value != null || profile == current.profile) return
        saving.value = profile
        viewModelScope.launch {
            val result = port.setProfile(profile)
            saving.value = null
            if (result is Outcome.Failure) report(result.error)
        }
    }
}
