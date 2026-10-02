package dev.agentle.feature.settings.diagnostics

import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentle.core.model.CapabilityStatus
import dev.agentle.core.model.ConnectorMetadata
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.feature.settings.R
import dev.agentle.feature.settings.port.DiagnosticError
import dev.agentle.feature.settings.port.DiagnosticsSnapshot
import dev.agentle.feature.settings.port.RecordCounts
import dev.agentle.feature.settings.port.WorkerRunState
import dev.agentle.feature.settings.port.WorkerStatus
import dev.agentle.feature.settings.ui.BodyText
import dev.agentle.feature.settings.ui.ButtonRow
import dev.agentle.feature.settings.ui.DisplayFormats
import dev.agentle.feature.settings.ui.HandleSettingsEffects
import dev.agentle.feature.settings.ui.InfoRow
import dev.agentle.feature.settings.ui.LoadableContent
import dev.agentle.feature.settings.ui.SectionHeader
import dev.agentle.feature.settings.ui.SettingsScaffold
import dev.agentle.feature.settings.ui.StatusKind
import dev.agentle.feature.settings.ui.StatusLine
import dev.agentle.feature.settings.ui.aiProviderLabel
import dev.agentle.feature.settings.ui.connectionLabel
import dev.agentle.feature.settings.ui.permissionLabel
import dev.agentle.feature.settings.ui.rememberDisplayFormats
import dev.agentle.feature.settings.ui.severityLabel
import dev.agentle.feature.settings.ui.statusKind
import dev.agentle.feature.settings.ui.syncLabel
import kotlin.time.Instant

@Composable
internal fun DiagnosticsRoute(navigator: AppNavigator, modifier: Modifier = Modifier, viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    HandleSettingsEffects(viewModel.effects, navigator, snackbarHostState)
    DiagnosticsScreen(state, viewModel::onAction, navigator::back, snackbarHostState, modifier)
}

/**
 * Diagnostics (spec §55). Shows codes, states, counts and times only: never account labels, tokens, payloads,
 * notification text, `CapabilityStatus.detail` or `ErrorInfo.message`.
 */
@Composable
internal fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onAction: (DiagnosticsAction) -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val formats = rememberDisplayFormats(state.zone)
    SettingsScaffold(
        title = stringResource(R.string.settings_diagnostics_title),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
        actions = {
            IconButton(onClick = { onAction(DiagnosticsAction.Refresh) }) {
                Icon(painterResource(R.drawable.ic_settings_refresh), contentDescription = stringResource(R.string.settings_refresh))
            }
        },
    ) {
        LoadableContent(state.content, onRetry = { onAction(DiagnosticsAction.Retry) }) { snapshot ->
            AppSection(snapshot)
            PermissionsSection(snapshot.permissions)
            ConnectorsSection(snapshot, formats)
            WorkersSection(snapshot.workers, formats)
            CountsSection(snapshot.counts, formats)
            ErrorsSection(snapshot.recentErrors, formats)
        }
        SectionHeader(stringResource(R.string.settings_diagnostics_section_export))
        BodyText(stringResource(R.string.settings_diagnostics_export_body))
        ButtonRow {
            Button(onClick = { onAction(DiagnosticsAction.Export) }, enabled = !state.exporting) {
                Text(stringResource(R.string.settings_diagnostics_export))
            }
        }
    }
}

@Composable
private fun AppSection(snapshot: DiagnosticsSnapshot) {
    SectionHeader(stringResource(R.string.settings_diagnostics_section_app))
    val build = snapshot.build
    InfoRow(stringResource(R.string.settings_diagnostics_version), stringResource(R.string.settings_diagnostics_version_value, build.versionName, build.versionCode))
    InfoRow(stringResource(R.string.settings_diagnostics_build), stringResource(R.string.settings_diagnostics_build_value, build.flavor, build.buildType))
    InfoRow(
        stringResource(R.string.settings_diagnostics_database_version),
        snapshot.databaseVersion?.toString() ?: stringResource(R.string.settings_diagnostics_unavailable),
    )
    InfoRow(stringResource(R.string.settings_diagnostics_android_api), snapshot.androidApi.toString())
}

@Composable
private fun PermissionsSection(permissions: List<CapabilityStatus>) {
    SectionHeader(stringResource(R.string.settings_diagnostics_section_permissions))
    if (permissions.isEmpty()) BodyText(stringResource(R.string.settings_diagnostics_none))
    permissions.forEach { status ->
        val text = stringResource(R.string.settings_diagnostics_permission, status.capabilityId, permissionLabel(status.state))
        val blockers = status.blockers.joinToString(separator = ", ") { it.name }
        StatusLine(
            kind = status.state.statusKind(),
            text = if (blockers.isEmpty()) text else stringResource(R.string.settings_diagnostics_blockers, text, blockers),
        )
    }
}

@Composable
private fun ConnectorsSection(snapshot: DiagnosticsSnapshot, formats: DisplayFormats) {
    SectionHeader(stringResource(R.string.settings_diagnostics_section_connectors))
    if (snapshot.connectors.isEmpty()) BodyText(stringResource(R.string.settings_diagnostics_none))
    snapshot.connectors.forEach { ConnectorLine(it) }
    InfoRow(stringResource(R.string.settings_diagnostics_last_sync), timeOrNever(snapshot.lastSuccessfulSync, formats))
    InfoRow(stringResource(R.string.settings_diagnostics_last_wearable_sync), timeOrNever(snapshot.lastWearableSync, formats))
    StatusLine(snapshot.chatGpt.statusKind(), stringResource(R.string.settings_diagnostics_chatgpt, aiProviderLabel(snapshot.chatGpt)))
}

@Composable
private fun ConnectorLine(connector: ConnectorMetadata) {
    val text = stringResource(
        R.string.settings_diagnostics_connector,
        connector.name,
        connectionLabel(connector.connection),
        syncLabel(connector.syncState),
    )
    // Only the error code: ErrorInfo.message is never shown.
    val error = connector.lastError?.code
    StatusLine(
        kind = connector.connection.statusKind(),
        text = if (error == null) text else stringResource(R.string.settings_diagnostics_last_error, text, error),
    )
}

@Composable
private fun WorkersSection(workers: List<WorkerStatus>?, formats: DisplayFormats) {
    SectionHeader(stringResource(R.string.settings_diagnostics_section_workers))
    when {
        workers == null -> StatusLine(StatusKind.INFO, stringResource(R.string.settings_diagnostics_unavailable))
        workers.isEmpty() -> BodyText(stringResource(R.string.settings_diagnostics_none))
        else -> workers.forEach { WorkerLine(it, formats) }
    }
}

@Composable
private fun WorkerLine(worker: WorkerStatus, formats: DisplayFormats) {
    val parts = buildList {
        add(stringResource(R.string.settings_diagnostics_worker, worker.uniqueName, workerStateLabel(worker.state)))
        worker.nextRunAt?.let { add(stringResource(R.string.settings_diagnostics_worker_next, formats.dateTime(it))) }
        if (worker.runAttemptCount > 0) {
            add(pluralStringResource(R.plurals.settings_diagnostics_worker_attempts, worker.runAttemptCount, worker.runAttemptCount))
        }
    }
    StatusLine(workerStateKind(worker.state), parts.joinToString(separator = " · "))
}

@Composable
private fun CountsSection(counts: RecordCounts?, formats: DisplayFormats) {
    SectionHeader(stringResource(R.string.settings_diagnostics_section_counts))
    if (counts == null) {
        StatusLine(StatusKind.INFO, stringResource(R.string.settings_diagnostics_unavailable))
    } else {
        InfoRow(stringResource(R.string.settings_diagnostics_events), formats.count(counts.events))
        InfoRow(stringResource(R.string.settings_diagnostics_insights), formats.count(counts.insights))
        InfoRow(stringResource(R.string.settings_diagnostics_jitais), formats.count(counts.jitais))
    }
}

@Composable
private fun ErrorsSection(errors: List<DiagnosticError>, formats: DisplayFormats) {
    SectionHeader(stringResource(R.string.settings_diagnostics_section_errors))
    if (errors.isEmpty()) {
        StatusLine(StatusKind.OK, stringResource(R.string.settings_diagnostics_no_errors))
    }
    errors.forEach { error ->
        val text = stringResource(
            R.string.settings_diagnostics_error,
            formats.dateTime(error.at),
            severityLabel(error.severity),
            error.component,
            error.code,
        )
        StatusLine(
            kind = StatusKind.ERROR,
            text = error.httpStatus?.let { stringResource(R.string.settings_diagnostics_error_http, text, it) } ?: text,
        )
    }
}

@Composable
private fun timeOrNever(instant: Instant?, formats: DisplayFormats): String =
    instant?.let { formats.dateTime(it) } ?: stringResource(R.string.settings_diagnostics_never)

@Composable
private fun workerStateLabel(state: WorkerRunState): String = stringResource(
    when (state) {
        WorkerRunState.ENQUEUED -> R.string.settings_worker_enqueued
        WorkerRunState.RUNNING -> R.string.settings_worker_running
        WorkerRunState.SUCCEEDED -> R.string.settings_worker_succeeded
        WorkerRunState.FAILED -> R.string.settings_worker_failed
        WorkerRunState.BLOCKED -> R.string.settings_worker_blocked
        WorkerRunState.CANCELLED -> R.string.settings_worker_cancelled
    },
)

private fun workerStateKind(state: WorkerRunState): StatusKind = when (state) {
    WorkerRunState.SUCCEEDED -> StatusKind.DONE
    WorkerRunState.RUNNING -> StatusKind.IN_PROGRESS
    WorkerRunState.ENQUEUED, WorkerRunState.BLOCKED -> StatusKind.PENDING
    WorkerRunState.FAILED -> StatusKind.ERROR
    WorkerRunState.CANCELLED -> StatusKind.OFF
}
