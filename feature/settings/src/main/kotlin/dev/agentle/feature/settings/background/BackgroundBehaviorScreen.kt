package dev.agentle.feature.settings.background

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentle.core.model.ConnectorMetadata
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.feature.settings.R
import dev.agentle.feature.settings.port.BackgroundBehaviorState
import dev.agentle.feature.settings.port.BatteryOptimization
import dev.agentle.feature.settings.port.CollectionProfile
import dev.agentle.feature.settings.port.StandbyBucket
import dev.agentle.feature.settings.ui.BodyText
import dev.agentle.feature.settings.ui.HandleSettingsEffects
import dev.agentle.feature.settings.ui.LoadableContent
import dev.agentle.feature.settings.ui.NavigationRow
import dev.agentle.feature.settings.ui.NoticeCard
import dev.agentle.feature.settings.ui.RadioRow
import dev.agentle.feature.settings.ui.SectionHeader
import dev.agentle.feature.settings.ui.SettingsScaffold
import dev.agentle.feature.settings.ui.StatusKind
import dev.agentle.feature.settings.ui.StatusLine
import dev.agentle.feature.settings.ui.permissionLabel

@Composable
internal fun BackgroundBehaviorRoute(
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
    viewModel: BackgroundBehaviorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    HandleSettingsEffects(viewModel.effects, navigator, snackbarHostState)
    BackgroundBehaviorScreen(state, viewModel::onAction, navigator::back, snackbarHostState, modifier)
}

/** Background behavior (R02 s2.3-2.5): profiles, Battery Saver, battery optimization and the per-source summary. */
@Composable
internal fun BackgroundBehaviorScreen(
    state: BackgroundUiState,
    onAction: (BackgroundAction) -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(
        title = stringResource(R.string.settings_background_title),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    ) {
        BodyText(stringResource(R.string.settings_background_intro))
        LoadableContent(state.content, onRetry = { onAction(BackgroundAction.Retry) }) { content ->
            ProfileSection(content, state.savingProfile, onAction)
            BatterySection(content, onAction)
            SourcesSection(content.sources, onAction)
        }
    }
}

@Composable
private fun ProfileSection(content: BackgroundBehaviorState, saving: CollectionProfile?, onAction: (BackgroundAction) -> Unit) {
    SectionHeader(stringResource(R.string.settings_background_section_profile))
    Column(Modifier.selectableGroup()) {
        CollectionProfile.entries.forEach { profile ->
            RadioRow(
                title = profileLabel(profile),
                summary = profileDescription(profile),
                selected = profile == (saving ?: content.profile),
                onClick = { onAction(BackgroundAction.SelectProfile(profile)) },
                enabled = saving == null,
            )
        }
    }
    if (content.effectiveProfile != content.profile) {
        StatusLine(StatusKind.INFO, stringResource(R.string.settings_background_adapted, profileLabel(content.effectiveProfile)))
    }
}

@Composable
private fun BatterySection(content: BackgroundBehaviorState, onAction: (BackgroundAction) -> Unit) {
    SectionHeader(stringResource(R.string.settings_background_section_saver))
    if (content.batterySaverOn) {
        StatusLine(StatusKind.INFO, stringResource(R.string.settings_background_saver_on))
    } else {
        StatusLine(StatusKind.OK, stringResource(R.string.settings_background_saver_off))
    }
    BodyText(stringResource(R.string.settings_background_saver_body))

    SectionHeader(stringResource(R.string.settings_background_section_optimization))
    when (content.batteryOptimization) {
        BatteryOptimization.OPTIMIZED -> StatusLine(StatusKind.OK, stringResource(R.string.settings_background_optimized))
        BatteryOptimization.UNRESTRICTED -> StatusLine(StatusKind.INFO, stringResource(R.string.settings_background_unrestricted))
        BatteryOptimization.UNKNOWN -> StatusLine(StatusKind.INFO, stringResource(R.string.settings_background_optimization_unknown))
    }
    BodyText(stringResource(R.string.settings_background_optimization_body))
    if (content.backgroundRestricted) {
        NoticeCard(
            kind = StatusKind.WARNING,
            title = stringResource(R.string.settings_background_restricted_title),
            body = stringResource(R.string.settings_background_restricted_body),
        )
    }
    content.standbyBucket?.let { bucket ->
        val low = bucket == StandbyBucket.RARE || bucket == StandbyBucket.RESTRICTED
        StatusLine(if (low) StatusKind.WARNING else StatusKind.OK, stringResource(R.string.settings_background_bucket, bucketLabel(bucket)))
        if (low) BodyText(stringResource(R.string.settings_background_bucket_low_body))
    }
    NavigationRow(
        title = stringResource(R.string.settings_background_open_app_settings),
        summary = stringResource(R.string.settings_background_open_app_settings_summary),
        onClick = { onAction(BackgroundAction.OpenAppSettings) },
    )
}

@Composable
private fun SourcesSection(sources: List<ConnectorMetadata>?, onAction: (BackgroundAction) -> Unit) {
    SectionHeader(stringResource(R.string.settings_background_section_sources))
    when {
        sources == null -> StatusLine(StatusKind.INFO, stringResource(R.string.settings_background_sources_unavailable))
        sources.isEmpty() -> BodyText(stringResource(R.string.settings_background_sources_empty))
        else -> sources.forEach { SourceLine(it) }
    }
    NavigationRow(
        title = stringResource(R.string.settings_background_open_sources),
        summary = stringResource(R.string.settings_background_open_sources_summary),
        onClick = { onAction(BackgroundAction.OpenDataSources) },
    )
}

@Composable
private fun SourceLine(source: ConnectorMetadata) {
    when {
        !source.enabled -> StatusLine(StatusKind.OFF, stringResource(R.string.settings_background_source_off, source.name))
        !source.permissionSummary.canCollect -> StatusLine(
            StatusKind.WARNING,
            stringResource(R.string.settings_background_source_blocked, source.name, permissionLabel(source.permissionSummary)),
        )
        else -> StatusLine(StatusKind.OK, stringResource(R.string.settings_background_source_on, source.name))
    }
}

/** "Balanced". */
@Composable
internal fun profileLabel(profile: CollectionProfile): String = stringResource(
    when (profile) {
        CollectionProfile.LOW -> R.string.settings_profile_low
        CollectionProfile.BALANCED -> R.string.settings_profile_balanced
        CollectionProfile.HIGH -> R.string.settings_profile_high
    },
)

/** What a profile changes (R02 s2.3; the daily minutes are the design targets). */
@Composable
private fun profileDescription(profile: CollectionProfile): String = stringResource(
    when (profile) {
        CollectionProfile.LOW -> R.string.settings_profile_low_summary
        CollectionProfile.BALANCED -> R.string.settings_profile_balanced_summary
        CollectionProfile.HIGH -> R.string.settings_profile_high_summary
    },
)

@Composable
private fun bucketLabel(bucket: StandbyBucket): String = stringResource(
    when (bucket) {
        StandbyBucket.ACTIVE -> R.string.settings_bucket_active
        StandbyBucket.WORKING_SET -> R.string.settings_bucket_working_set
        StandbyBucket.FREQUENT -> R.string.settings_bucket_frequent
        StandbyBucket.RARE -> R.string.settings_bucket_rare
        StandbyBucket.RESTRICTED -> R.string.settings_bucket_restricted
    },
)
