package dev.agentle.feature.settings.hub

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.feature.settings.R
import dev.agentle.feature.settings.background.profileLabel
import dev.agentle.feature.settings.notifications.notificationsSummary
import dev.agentle.feature.settings.retention.retentionLabel
import dev.agentle.feature.settings.ui.NavigationRow
import dev.agentle.feature.settings.ui.SectionHeader
import dev.agentle.feature.settings.ui.SettingsScaffold
import dev.agentle.feature.settings.ui.rememberDisplayFormats

@Composable
internal fun SettingsHubRoute(navigator: AppNavigator, modifier: Modifier = Modifier, viewModel: SettingsHubViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsHubScreen(state = state, onNavigate = navigator::navigate, onBack = navigator::back, modifier = modifier)
}

/** Settings (spec §22): one link per section; AI settings link to the connections feature's screens. */
@Composable
internal fun SettingsHubScreen(state: SettingsHubUiState, onNavigate: (AppRoute) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val formats = rememberDisplayFormats(state.zone)
    val snackbarHostState = remember { SnackbarHostState() }
    SettingsScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    ) {
        SectionHeader(stringResource(R.string.settings_hub_section_data))
        NavigationRow(
            title = stringResource(R.string.settings_retention_title),
            summary = state.retention?.let { retentionLabel(it) } ?: stringResource(R.string.settings_hub_retention_summary),
            onClick = { onNavigate(AppRoute.DataRetention) },
        )
        NavigationRow(
            title = stringResource(R.string.settings_delete_title),
            summary = stringResource(R.string.settings_hub_delete_summary),
            onClick = { onNavigate(AppRoute.DeleteData) },
        )

        SectionHeader(stringResource(R.string.settings_hub_section_background))
        NavigationRow(
            title = stringResource(R.string.settings_background_title),
            summary = state.profile?.let { stringResource(R.string.settings_hub_profile_summary, profileLabel(it)) }
                ?: stringResource(R.string.settings_hub_background_summary),
            onClick = { onNavigate(AppRoute.BackgroundBehavior) },
        )
        NavigationRow(
            title = stringResource(R.string.settings_notifications_title),
            summary = notificationsSummary(state.pause, state.dailyCap, formats)
                ?: stringResource(R.string.settings_hub_notifications_summary),
            onClick = { onNavigate(AppRoute.NotificationSettings) },
        )

        SectionHeader(stringResource(R.string.settings_hub_section_ai))
        NavigationRow(
            title = stringResource(R.string.settings_hub_chatgpt_title),
            summary = stringResource(R.string.settings_hub_chatgpt_summary),
            onClick = { onNavigate(AppRoute.ChatGpt) },
        )
        NavigationRow(
            title = stringResource(R.string.settings_hub_ai_sharing_title),
            summary = stringResource(R.string.settings_hub_ai_sharing_summary),
            onClick = { onNavigate(AppRoute.AiDataSharing) },
        )

        SectionHeader(stringResource(R.string.settings_hub_section_privacy))
        NavigationRow(
            title = stringResource(R.string.settings_privacy_title),
            summary = stringResource(R.string.settings_hub_privacy_summary),
            onClick = { onNavigate(AppRoute.Privacy) },
        )

        SectionHeader(stringResource(R.string.settings_hub_section_help))
        NavigationRow(
            title = stringResource(R.string.settings_diagnostics_title),
            summary = stringResource(R.string.settings_hub_diagnostics_summary),
            onClick = { onNavigate(AppRoute.Diagnostics) },
        )
        if (state.debugAvailable) {
            NavigationRow(
                title = stringResource(R.string.settings_debug_title),
                summary = stringResource(R.string.settings_hub_debug_summary),
                onClick = { onNavigate(AppRoute.DebugPanel) },
            )
        }
    }
}
