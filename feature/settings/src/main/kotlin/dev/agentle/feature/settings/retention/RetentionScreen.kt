package dev.agentle.feature.settings.retention

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.feature.settings.R
import dev.agentle.feature.settings.port.RetentionImpact
import dev.agentle.feature.settings.port.RetentionPeriod
import dev.agentle.feature.settings.ui.BodyText
import dev.agentle.feature.settings.ui.BulletList
import dev.agentle.feature.settings.ui.ConfirmDialog
import dev.agentle.feature.settings.ui.DisplayFormats
import dev.agentle.feature.settings.ui.HandleSettingsEffects
import dev.agentle.feature.settings.ui.LoadableContent
import dev.agentle.feature.settings.ui.RadioRow
import dev.agentle.feature.settings.ui.SectionHeader
import dev.agentle.feature.settings.ui.SettingsScaffold
import dev.agentle.feature.settings.ui.StatusKind
import dev.agentle.feature.settings.ui.StatusLine
import dev.agentle.feature.settings.ui.recordsText
import dev.agentle.feature.settings.ui.rememberDisplayFormats

@Composable
internal fun RetentionRoute(navigator: AppNavigator, modifier: Modifier = Modifier, viewModel: RetentionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    HandleSettingsEffects(viewModel.effects, navigator, snackbarHostState)
    RetentionScreen(state, viewModel::onAction, navigator::back, snackbarHostState, modifier)
}

/** Data retention: the choice, what each choice deletes and when, and what is always kept. */
@Composable
internal fun RetentionScreen(
    state: RetentionUiState,
    onAction: (RetentionAction) -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val formats = rememberDisplayFormats(state.zone)
    SettingsScaffold(
        title = stringResource(R.string.settings_retention_title),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    ) {
        BodyText(stringResource(R.string.settings_retention_intro))
        LoadableContent(state.settings, onRetry = { onAction(RetentionAction.Retry) }) { settings ->
            SectionHeader(stringResource(R.string.settings_retention_section_keep))
            Column(Modifier.selectableGroup()) {
                RetentionPeriod.entries.forEach { period ->
                    RadioRow(
                        title = retentionLabel(period),
                        summary = retentionDescription(period),
                        selected = period == state.selected,
                        onClick = { onAction(RetentionAction.Select(period)) },
                        enabled = !state.busy,
                    )
                }
            }
            if (state.counting != null) {
                StatusLine(StatusKind.IN_PROGRESS, stringResource(R.string.settings_retention_counting))
            }

            SectionHeader(stringResource(R.string.settings_retention_section_when))
            BodyText(stringResource(R.string.settings_retention_when_body))
            StatusLine(
                kind = StatusKind.INFO,
                text = settings.lastCleanupAt?.let { stringResource(R.string.settings_retention_last_cleanup, formats.dateTime(it)) }
                    ?: stringResource(R.string.settings_retention_never_cleaned),
            )

            SectionHeader(stringResource(R.string.settings_retention_section_exempt))
            BodyText(stringResource(R.string.settings_retention_exempt_intro))
            BulletList(
                listOf(
                    stringResource(R.string.settings_retention_exempt_jitais),
                    stringResource(R.string.settings_retention_exempt_goals),
                    stringResource(R.string.settings_retention_exempt_decisions),
                    stringResource(R.string.settings_retention_exempt_ledger),
                ),
            )
        }
    }
    state.confirmation?.let { impact ->
        ShorterPeriodDialog(impact, formats, onAction)
    }
}

@Composable
private fun ShorterPeriodDialog(impact: RetentionImpact, formats: DisplayFormats, onAction: (RetentionAction) -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.settings_retention_confirm_title),
        confirmLabel = stringResource(R.string.settings_retention_confirm_action),
        onConfirm = { onAction(RetentionAction.ConfirmChange) },
        onDismiss = { onAction(RetentionAction.DismissChange) },
        destructive = true,
    ) {
        Text(
            stringResource(
                R.string.settings_retention_confirm_body,
                retentionLabel(impact.period),
                recordsText(impact.recordsToDelete, formats),
                formats.date(impact.cutoff),
            ),
        )
        Text(stringResource(R.string.settings_cannot_undo))
    }
}

/** "Keep for 90 days". */
@Composable
internal fun retentionLabel(period: RetentionPeriod): String = stringResource(
    when (period) {
        RetentionPeriod.KEEP_INDEFINITELY -> R.string.settings_retention_keep_indefinitely
        RetentionPeriod.ONE_YEAR -> R.string.settings_retention_one_year
        RetentionPeriod.DAYS_90 -> R.string.settings_retention_90_days
        RetentionPeriod.DAYS_30 -> R.string.settings_retention_30_days
    },
)

@Composable
private fun retentionDescription(period: RetentionPeriod): String = stringResource(
    when (period) {
        RetentionPeriod.KEEP_INDEFINITELY -> R.string.settings_retention_keep_indefinitely_summary
        RetentionPeriod.ONE_YEAR -> R.string.settings_retention_one_year_summary
        RetentionPeriod.DAYS_90 -> R.string.settings_retention_90_days_summary
        RetentionPeriod.DAYS_30 -> R.string.settings_retention_30_days_summary
    },
)
