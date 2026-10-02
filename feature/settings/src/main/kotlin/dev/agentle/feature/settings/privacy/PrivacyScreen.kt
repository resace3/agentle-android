package dev.agentle.feature.settings.privacy

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.feature.settings.R
import dev.agentle.feature.settings.port.AiSharingSummary
import dev.agentle.feature.settings.port.PrivacyState
import dev.agentle.feature.settings.ui.BodyText
import dev.agentle.feature.settings.ui.BulletList
import dev.agentle.feature.settings.ui.DisplayFormats
import dev.agentle.feature.settings.ui.HandleSettingsEffects
import dev.agentle.feature.settings.ui.LoadableContent
import dev.agentle.feature.settings.ui.NavigationRow
import dev.agentle.feature.settings.ui.SectionHeader
import dev.agentle.feature.settings.ui.SettingsScaffold
import dev.agentle.feature.settings.ui.StatusKind
import dev.agentle.feature.settings.ui.StatusLine
import dev.agentle.feature.settings.ui.SwitchRow
import dev.agentle.feature.settings.ui.aiProviderLabel
import dev.agentle.feature.settings.ui.categoryLabel
import dev.agentle.feature.settings.ui.rememberDisplayFormats
import dev.agentle.feature.settings.ui.statusKind

@Composable
internal fun PrivacyRoute(navigator: AppNavigator, modifier: Modifier = Modifier, viewModel: PrivacyViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    HandleSettingsEffects(viewModel.effects, navigator, snackbarHostState)
    PrivacyScreen(state, viewModel::onAction, navigator::back, snackbarHostState, modifier)
}

/** Privacy (R04 s3.3, s3.10): storage, what stays on the phone, AI sharing, lock-screen and screenshot protection. */
@Composable
internal fun PrivacyScreen(
    state: PrivacyUiState,
    onAction: (PrivacyAction) -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val formats = rememberDisplayFormats(state.zone)
    SettingsScaffold(
        title = stringResource(R.string.settings_privacy_title),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    ) {
        LoadableContent(state.content, onRetry = { onAction(PrivacyAction.Retry) }) { content ->
            StorageSection(content.databaseEncrypted)
            NeverLeavesSection()
            AiSection(content.aiSharing, formats, onAction)
            LockScreenSection(content, onAction)
            ScreensSection(content, onAction)
        }
    }
}

@Composable
private fun StorageSection(databaseEncrypted: Boolean?) {
    SectionHeader(stringResource(R.string.settings_privacy_section_stored))
    when (databaseEncrypted) {
        true -> StatusLine(StatusKind.OK, stringResource(R.string.settings_privacy_db_encrypted))
        false -> StatusLine(StatusKind.WARNING, stringResource(R.string.settings_privacy_db_not_encrypted))
        null -> StatusLine(StatusKind.INFO, stringResource(R.string.settings_privacy_db_unknown))
    }
    BulletList(
        listOf(
            stringResource(R.string.settings_privacy_stored_local),
            stringResource(R.string.settings_privacy_stored_tokens),
            stringResource(R.string.settings_privacy_stored_media),
            stringResource(R.string.settings_privacy_stored_backup),
        ),
    )
}

@Composable
private fun NeverLeavesSection() {
    SectionHeader(stringResource(R.string.settings_privacy_section_never))
    BulletList(
        listOf(
            stringResource(R.string.settings_privacy_never_events),
            stringResource(R.string.settings_privacy_never_notifications),
            stringResource(R.string.settings_privacy_never_location),
            stringResource(R.string.settings_privacy_never_diagnostics),
        ),
    )
    BodyText(stringResource(R.string.settings_privacy_no_server))
}

@Composable
private fun AiSection(sharing: AiSharingSummary?, formats: DisplayFormats, onAction: (PrivacyAction) -> Unit) {
    SectionHeader(stringResource(R.string.settings_privacy_section_ai))
    if (sharing == null) {
        StatusLine(StatusKind.INFO, stringResource(R.string.settings_privacy_ai_unknown))
    } else {
        StatusLine(sharing.provider.statusKind(), stringResource(R.string.settings_privacy_ai_provider, aiProviderLabel(sharing.provider)))
        if (sharing.allowedCategories.isEmpty()) {
            StatusLine(StatusKind.OK, stringResource(R.string.settings_privacy_ai_none_allowed))
        } else {
            val allowed = sharing.allowedCategories.sortedBy { it.ordinal }.map { categoryLabel(it) }.joinToString(separator = ", ")
            StatusLine(StatusKind.INFO, stringResource(R.string.settings_privacy_ai_allowed, allowed))
        }
        BodyText(stringResource(R.string.settings_privacy_ai_summaries))
        BodyText(
            sharing.lastRequestAt?.let { stringResource(R.string.settings_privacy_ai_last_request, formats.dateTime(it)) }
                ?: stringResource(R.string.settings_privacy_ai_no_request),
        )
    }
    NavigationRow(
        title = stringResource(R.string.settings_privacy_open_ai_sharing),
        summary = stringResource(R.string.settings_privacy_open_ai_sharing_summary),
        onClick = { onAction(PrivacyAction.OpenAiDataSharing) },
    )
}

@Composable
private fun LockScreenSection(content: PrivacyState, onAction: (PrivacyAction) -> Unit) {
    SectionHeader(stringResource(R.string.settings_privacy_section_lock))
    if (content.detailedNotifications) {
        StatusLine(StatusKind.INFO, stringResource(R.string.settings_privacy_detailed_notifications))
    } else {
        StatusLine(StatusKind.OK, stringResource(R.string.settings_privacy_generic_notifications))
    }
    if (content.showOnWearables) {
        StatusLine(StatusKind.INFO, stringResource(R.string.settings_privacy_wearables))
    } else {
        StatusLine(StatusKind.OK, stringResource(R.string.settings_privacy_phone_only))
    }
    NavigationRow(
        title = stringResource(R.string.settings_privacy_open_notifications),
        summary = stringResource(R.string.settings_privacy_open_notifications_summary),
        onClick = { onAction(PrivacyAction.OpenNotificationSettings) },
    )
}

@Composable
private fun ScreensSection(content: PrivacyState, onAction: (PrivacyAction) -> Unit) {
    SectionHeader(stringResource(R.string.settings_privacy_section_screens))
    StatusLine(StatusKind.OK, stringResource(R.string.settings_privacy_sensitive_screens))
    SwitchRow(
        title = stringResource(R.string.settings_privacy_protect_all),
        summary = stringResource(R.string.settings_privacy_protect_all_summary),
        checked = content.protectAllScreens,
        onCheckedChange = { onAction(PrivacyAction.SetProtectAllScreens(it)) },
    )
    if (content.recentsPreviewHidden) {
        StatusLine(StatusKind.OK, stringResource(R.string.settings_privacy_recents_hidden))
    } else {
        StatusLine(StatusKind.INFO, stringResource(R.string.settings_privacy_recents_shown))
    }
}
