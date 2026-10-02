package dev.agentle.feature.settings.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentle.core.model.EventType
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.feature.settings.R
import dev.agentle.feature.settings.port.DebugOption
import dev.agentle.feature.settings.port.DebugToolsState
import dev.agentle.feature.settings.port.FailureKind
import dev.agentle.feature.settings.ui.BodyText
import dev.agentle.feature.settings.ui.ButtonRow
import dev.agentle.feature.settings.ui.ConfirmDialog
import dev.agentle.feature.settings.ui.DisplayFormats
import dev.agentle.feature.settings.ui.HandleSettingsEffects
import dev.agentle.feature.settings.ui.InfoRow
import dev.agentle.feature.settings.ui.LoadableContent
import dev.agentle.feature.settings.ui.NavigationRow
import dev.agentle.feature.settings.ui.NoticeCard
import dev.agentle.feature.settings.ui.RadioRow
import dev.agentle.feature.settings.ui.SectionHeader
import dev.agentle.feature.settings.ui.SettingsScaffold
import dev.agentle.feature.settings.ui.StatusKind
import dev.agentle.feature.settings.ui.StatusLine
import dev.agentle.feature.settings.ui.StepperRow
import dev.agentle.feature.settings.ui.SwitchRow
import dev.agentle.feature.settings.ui.rememberDisplayFormats
import dev.agentle.feature.settings.ui.valueOrNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

@Composable
internal fun DebugPanelRoute(navigator: AppNavigator, modifier: Modifier = Modifier, viewModel: DebugPanelViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    HandleSettingsEffects(viewModel.effects, navigator, snackbarHostState)
    DebugPanelScreen(state, viewModel::onAction, navigator::back, snackbarHostState, modifier)
}

/** The debug panel (spec §56). Without debug tools in the build it only says so: no control is composed. */
@Composable
internal fun DebugPanelScreen(
    state: DebugPanelUiState,
    onAction: (DebugAction) -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(
        title = stringResource(R.string.settings_debug_title),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    ) {
        when (state) {
            DebugPanelUiState.NotAvailable -> NoticeCard(
                kind = StatusKind.INFO,
                title = stringResource(R.string.settings_debug_not_available),
                body = stringResource(R.string.settings_debug_not_available_body),
            )

            is DebugPanelUiState.Available -> {
                val formats = rememberDisplayFormats(state.zone)
                BodyText(stringResource(R.string.settings_debug_intro))
                if (state.running) StatusLine(StatusKind.IN_PROGRESS, stringResource(R.string.settings_debug_running))
                LoadableContent(state.tools, onRetry = { onAction(DebugAction.Retry) }) { tools ->
                    DebugTools(tools, state, formats, onAction)
                }
            }
        }
    }
    if (state is DebugPanelUiState.Available) {
        val tools = state.tools.valueOrNull()
        if (state.choosingEventType && tools != null) EventTypeDialog(tools.syntheticEventTypes, state.effectiveEventType, onAction)
        if (state.confirmingClear) ClearDatabaseDialog(onAction)
    }
}

@Composable
private fun DebugTools(
    tools: DebugToolsState,
    state: DebugPanelUiState.Available,
    formats: DisplayFormats,
    onAction: (DebugAction) -> Unit,
) {
    val enabled = !state.running
    ScenarioSection(
        title = stringResource(R.string.settings_debug_section_wearable),
        options = tools.wearableScenarios,
        selected = tools.selectedWearableScenario,
        enabled = enabled,
        onSelect = { onAction(DebugAction.SelectWearableScenario(it)) },
    )
    ScenarioSection(
        title = stringResource(R.string.settings_debug_section_chatgpt),
        options = tools.chatGptScenarios,
        selected = tools.selectedChatGptScenario,
        enabled = enabled,
        onSelect = { onAction(DebugAction.SelectChatGptScenario(it)) },
    )

    SectionHeader(stringResource(R.string.settings_debug_section_events))
    NavigationRow(
        title = stringResource(R.string.settings_debug_event_type),
        summary = state.effectiveEventType?.name ?: stringResource(R.string.settings_debug_none),
        onClick = { onAction(DebugAction.ChooseEventType) },
        enabled = enabled && tools.syntheticEventTypes.isNotEmpty(),
    )
    StepperRow(
        title = stringResource(R.string.settings_debug_event_count),
        valueText = formats.count(state.eventCount.toLong()),
        canDecrement = enabled && state.eventCount > DebugPanelViewModel.EVENT_COUNTS.first(),
        canIncrement = enabled && state.eventCount < DebugPanelViewModel.EVENT_COUNTS.last(),
        onDecrement = { onAction(DebugAction.StepEventCount(up = false)) },
        onIncrement = { onAction(DebugAction.StepEventCount(up = true)) },
    )
    ButtonRow {
        Button(onClick = { onAction(DebugAction.GenerateEvents) }, enabled = enabled && state.effectiveEventType != null) {
            Text(stringResource(R.string.settings_debug_generate_events))
        }
        OutlinedButton(onClick = { onAction(DebugAction.GenerateDataset) }, enabled = enabled) {
            Text(stringResource(R.string.settings_debug_generate_dataset))
        }
    }

    OptionActions(
        title = stringResource(R.string.settings_debug_section_jitai),
        options = tools.jitais,
        actionLabel = stringResource(R.string.settings_debug_simulate_trigger),
        enabled = enabled,
        onClick = { onAction(DebugAction.SimulateJitai(it)) },
    )

    SectionHeader(stringResource(R.string.settings_debug_section_time))
    InfoRow(stringResource(R.string.settings_debug_app_time), formats.dateTime(tools.appTime))
    InfoRow(
        stringResource(R.string.settings_debug_time_offset),
        tools.timeOffset?.toString() ?: stringResource(R.string.settings_debug_none),
    )
    ButtonRow {
        OutlinedButton(onClick = { onAction(DebugAction.ShiftTime(1.hours)) }, enabled = enabled) {
            Text(stringResource(R.string.settings_debug_plus_hour))
        }
        OutlinedButton(onClick = { onAction(DebugAction.ShiftTime(1.days)) }, enabled = enabled) {
            Text(stringResource(R.string.settings_debug_plus_day))
        }
        OutlinedButton(onClick = { onAction(DebugAction.ClearTimeOffset) }, enabled = enabled && tools.timeOffset != null) {
            Text(stringResource(R.string.settings_debug_reset_time))
        }
    }

    OptionActions(
        title = stringResource(R.string.settings_debug_section_sync),
        options = tools.syncTargets,
        actionLabel = stringResource(R.string.settings_debug_sync_now),
        enabled = enabled,
        onClick = { onAction(DebugAction.ForceSync(it)) },
    )
    OptionActions(
        title = stringResource(R.string.settings_debug_section_workers),
        options = tools.workers,
        actionLabel = stringResource(R.string.settings_debug_run_now),
        enabled = enabled,
        onClick = { onAction(DebugAction.ForceWorker(it)) },
    )

    SectionHeader(stringResource(R.string.settings_debug_section_failures))
    FailureKind.entries.forEach { kind ->
        SwitchRow(
            title = failureLabel(kind),
            summary = null,
            checked = kind in tools.activeFailures,
            onCheckedChange = { onAction(DebugAction.SetFailure(kind, it)) },
            enabled = enabled,
        )
    }

    SectionHeader(stringResource(R.string.settings_debug_section_database))
    BodyText(stringResource(R.string.settings_debug_clear_database_body))
    ButtonRow {
        Button(
            onClick = { onAction(DebugAction.RequestClearDatabase) },
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Text(stringResource(R.string.settings_debug_clear_database))
        }
    }
}

@Composable
private fun ScenarioSection(title: String, options: List<DebugOption>, selected: String?, enabled: Boolean, onSelect: (String) -> Unit) {
    SectionHeader(title)
    if (options.isEmpty()) {
        BodyText(stringResource(R.string.settings_debug_none))
        return
    }
    Column(Modifier.selectableGroup()) {
        options.forEach { option ->
            RadioRow(
                title = option.label,
                summary = null,
                selected = option.id == selected,
                onClick = { onSelect(option.id) },
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun OptionActions(title: String, options: List<DebugOption>, actionLabel: String, enabled: Boolean, onClick: (String) -> Unit) {
    SectionHeader(title)
    if (options.isEmpty()) BodyText(stringResource(R.string.settings_debug_none))
    options.forEach { option ->
        NavigationRow(title = option.label, summary = actionLabel, onClick = { onClick(option.id) }, enabled = enabled)
    }
}

@Composable
private fun EventTypeDialog(types: List<EventType>, selected: EventType?, onAction: (DebugAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(DebugAction.DismissEventTypes) },
        title = { Text(stringResource(R.string.settings_debug_event_type)) },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
            ) {
                types.forEach { type ->
                    RadioRow(
                        title = type.name,
                        summary = null,
                        selected = type == selected,
                        onClick = { onAction(DebugAction.SelectEventType(type)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(DebugAction.DismissEventTypes) }) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@Composable
private fun ClearDatabaseDialog(onAction: (DebugAction) -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.settings_debug_clear_database_confirm),
        confirmLabel = stringResource(R.string.settings_debug_clear_database),
        onConfirm = { onAction(DebugAction.ConfirmClearDatabase) },
        onDismiss = { onAction(DebugAction.DismissClearDatabase) },
        destructive = true,
    ) {
        Text(stringResource(R.string.settings_debug_clear_database_body))
        Text(stringResource(R.string.settings_cannot_undo))
    }
}

@Composable
private fun failureLabel(kind: FailureKind): String = stringResource(
    when (kind) {
        FailureKind.DATABASE_EXCEPTION -> R.string.settings_debug_failure_database
        FailureKind.GOOGLE_HEALTH_HTTP_ERROR -> R.string.settings_debug_failure_google_health
        FailureKind.OPENAI_HTTP_ERROR -> R.string.settings_debug_failure_openai
        FailureKind.EXPIRED_TOKEN -> R.string.settings_debug_failure_expired_token
        FailureKind.NETWORK_LOSS -> R.string.settings_debug_failure_network
        FailureKind.INVALID_AI_OUTPUT -> R.string.settings_debug_failure_invalid_ai
        FailureKind.PERMISSION_LOSS -> R.string.settings_debug_failure_permission
    },
)
