package dev.agentle.feature.settings.deletion

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.feature.settings.R
import dev.agentle.feature.settings.port.CollectionControl
import dev.agentle.feature.settings.port.DeleteAllState
import dev.agentle.feature.settings.port.DeletionItem
import dev.agentle.feature.settings.port.DeletionOverview
import dev.agentle.feature.settings.port.DeletionReport
import dev.agentle.feature.settings.port.DeletionTarget
import dev.agentle.feature.settings.ui.BodyText
import dev.agentle.feature.settings.ui.ConfirmDialog
import dev.agentle.feature.settings.ui.DisplayFormats
import dev.agentle.feature.settings.ui.HandleSettingsEffects
import dev.agentle.feature.settings.ui.LoadableContent
import dev.agentle.feature.settings.ui.NoticeCard
import dev.agentle.feature.settings.ui.RowPadding
import dev.agentle.feature.settings.ui.SectionHeader
import dev.agentle.feature.settings.ui.SettingsScaffold
import dev.agentle.feature.settings.ui.StatusKind
import dev.agentle.feature.settings.ui.StatusLine
import dev.agentle.feature.settings.ui.SwitchRow
import dev.agentle.feature.settings.ui.categoryLabel
import dev.agentle.feature.settings.ui.filesText
import dev.agentle.feature.settings.ui.recordsText
import dev.agentle.feature.settings.ui.rememberDisplayFormats
import dev.agentle.feature.settings.ui.toQuantity

@Composable
internal fun DeleteDataRoute(navigator: AppNavigator, modifier: Modifier = Modifier, viewModel: DeleteDataViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    HandleSettingsEffects(viewModel.effects, navigator, snackbarHostState)
    DeleteDataScreen(state, viewModel::onAction, navigator::back, snackbarHostState, modifier)
}

/**
 * Delete data (Journey 10). While "delete everything" runs, has failed or waits for "Close Agentle", only its progress
 * shows; otherwise the per-target deletes and the "delete everything" entry. There is no export in v1.
 */
@Composable
internal fun DeleteDataScreen(
    state: DeleteDataUiState,
    onAction: (DeleteDataAction) -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val formats = rememberDisplayFormats(state.zone)
    SettingsScaffold(
        title = stringResource(R.string.settings_delete_title),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    ) {
        if (state.deleteAll is DeleteAllState.Idle) {
            BodyText(stringResource(R.string.settings_delete_intro))
            state.report?.let { report -> DeletionReportCard(report, formats, onDismiss = { onAction(DeleteDataAction.DismissReport) }) }
            state.deleting?.let { target ->
                StatusLine(StatusKind.IN_PROGRESS, stringResource(R.string.settings_delete_running, targetLabel(target)))
            }
            LoadableContent(state.overview, onRetry = { onAction(DeleteDataAction.Retry) }) { overview ->
                DeletionTargets(overview, enabled = !state.busy, formats = formats, onAction = onAction)
            }
            DeleteEverythingEntry(enabled = !state.busy, starting = state.startingDeleteAll, onAction = onAction)
        } else {
            DeleteEverythingProgress(state.deleteAll, finishing = state.finishing, starting = state.startingDeleteAll, onAction = onAction)
        }
    }
    state.request?.let { request -> DeleteTargetDialog(request, formats, onAction) }
    if (state.confirmingDeleteAll) DeleteEverythingDialog(onAction)
}

@Composable
private fun DeletionTargets(overview: DeletionOverview, enabled: Boolean, formats: DisplayFormats, onAction: (DeleteDataAction) -> Unit) {
    val sources = overview.items.filter { it.target is DeletionTarget.WearableData || it.target is DeletionTarget.PhoneData }
    val categories = overview.items.filter { it.target is DeletionTarget.Category }
    val generated = overview.items.filter { it !in sources && it !in categories }
    if (overview.items.isEmpty()) BodyText(stringResource(R.string.settings_delete_nothing))
    if (sources.isNotEmpty()) {
        SectionHeader(stringResource(R.string.settings_delete_section_sources))
        sources.forEach { TargetRow(it, enabled, formats, onAction) }
    }
    if (categories.isNotEmpty()) {
        SectionHeader(stringResource(R.string.settings_delete_section_categories))
        BodyText(stringResource(R.string.settings_delete_categories_intro))
        categories.forEach { TargetRow(it, enabled, formats, onAction) }
    }
    if (generated.isNotEmpty()) {
        SectionHeader(stringResource(R.string.settings_delete_section_generated))
        generated.forEach { TargetRow(it, enabled, formats, onAction) }
    }
}

/** One deletable target: what it is, what deleting it removes (except for categories) and how much there is. */
@Composable
private fun TargetRow(item: DeletionItem, enabled: Boolean, formats: DisplayFormats, onAction: (DeleteDataAction) -> Unit) {
    val description = if (item.target is DeletionTarget.Category) null else targetDescription(item.target)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                onClickLabel = stringResource(R.string.settings_delete_action),
                role = Role.Button,
                onClick = { onAction(DeleteDataAction.RequestDelete(item.target)) },
            )
            .heightIn(min = 56.dp)
            .padding(horizontal = RowPadding, vertical = 12.dp),
    ) {
        Text(targetLabel(item.target), style = MaterialTheme.typography.bodyLarge)
        if (description != null) {
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(itemCounts(item, formats), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** "1,234 records · 12 files · 3.4 MB", "No data" or "Count unavailable". */
@Composable
private fun itemCounts(item: DeletionItem, formats: DisplayFormats): String {
    val records = item.records ?: return stringResource(R.string.settings_delete_count_unknown)
    val files = item.files ?: 0
    if (records == 0L && files == 0) return stringResource(R.string.settings_delete_count_none)
    val context = LocalContext.current
    return buildList {
        add(recordsText(records, formats))
        if (files > 0) add(filesText(files, formats))
        item.bytes?.takeIf { it > 0 }?.let { add(Formatter.formatShortFileSize(context, it)) }
    }.joinToString(separator = " · ")
}

@Composable
private fun DeleteTargetDialog(request: DeleteRequest, formats: DisplayFormats, onAction: (DeleteDataAction) -> Unit) {
    val item = request.item
    val fromSources =
        item.target is DeletionTarget.WearableData || item.target is DeletionTarget.PhoneData || item.target is DeletionTarget.Category
    ConfirmDialog(
        title = stringResource(R.string.settings_delete_confirm_title, targetLabel(item.target)),
        confirmLabel = stringResource(R.string.settings_delete_action),
        onConfirm = { onAction(DeleteDataAction.ConfirmDelete) },
        onDismiss = { onAction(DeleteDataAction.DismissDelete) },
        destructive = true,
    ) {
        Text(targetDescription(item.target))
        Text(stringResource(R.string.settings_delete_confirm_amount, itemCounts(item, formats)))
        if (fromSources) Text(stringResource(R.string.settings_delete_confirm_floor))
        when (item.collection) {
            CollectionControl.ACTIVE -> SwitchRow(
                title = stringResource(R.string.settings_delete_stop_collecting),
                summary = stringResource(R.string.settings_delete_stop_collecting_summary),
                checked = request.stopCollecting,
                onCheckedChange = { onAction(DeleteDataAction.SetStopCollecting(it)) },
                horizontalPadding = 0.dp,
            )

            CollectionControl.STOPPED -> Text(stringResource(R.string.settings_delete_collection_already_off))

            CollectionControl.NOT_APPLICABLE -> Unit
        }
        Text(stringResource(R.string.settings_cannot_undo))
    }
}

/** The verified counts of the last per-target delete (a message is not proof: the port counted afterwards). */
@Composable
private fun DeletionReportCard(report: DeletionReport, formats: DisplayFormats, onDismiss: () -> Unit) {
    val label = targetLabel(report.target)
    NoticeCard(
        kind = if (report.complete) StatusKind.DONE else StatusKind.WARNING,
        title = stringResource(
            if (report.complete) R.string.settings_delete_report_done else R.string.settings_delete_report_incomplete,
            label,
        ),
    ) {
        BodyText(
            if (report.deletedFiles > 0) {
                stringResource(
                    R.string.settings_delete_report_deleted_with_files,
                    recordsText(report.deletedRecords, formats),
                    filesText(report.deletedFiles, formats),
                )
            } else {
                stringResource(R.string.settings_delete_report_deleted, recordsText(report.deletedRecords, formats))
            },
        )
        BodyText(
            if (report.complete) {
                stringResource(R.string.settings_delete_report_verified)
            } else {
                stringResource(
                    R.string.settings_delete_report_remaining,
                    recordsText(report.remainingRecords, formats),
                    filesText(report.remainingFiles, formats),
                )
            },
        )
        report.keptLedgerEntries?.takeIf { it > 0 }?.let { kept ->
            BodyText(ledgerText(kept, formats))
        }
        if (report.aiConsentRevoked) BodyText(stringResource(R.string.settings_delete_report_ai_revoked))
        if (report.collectionStopped) BodyText(stringResource(R.string.settings_delete_report_collection_stopped))
        report.importFloor?.let { BodyText(stringResource(R.string.settings_delete_report_floor, formats.date(it))) }
        TextButton(onClick = onDismiss, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.settings_done))
        }
    }
}

@Composable
private fun ledgerText(kept: Long, formats: DisplayFormats): String =
    pluralStringResource(R.plurals.settings_delete_report_ledger, kept.toQuantity(), formats.count(kept))

/** The name of a deletion target. */
@Composable
internal fun targetLabel(target: DeletionTarget): String = when (target) {
    DeletionTarget.WearableData -> stringResource(R.string.settings_delete_target_wearable)
    DeletionTarget.PhoneData -> stringResource(R.string.settings_delete_target_phone)
    is DeletionTarget.Category -> categoryLabel(target.category)
    DeletionTarget.Insights -> stringResource(R.string.settings_delete_target_insights)
    DeletionTarget.InterventionHistory -> stringResource(R.string.settings_delete_target_history)
    DeletionTarget.GeneratedMedia -> stringResource(R.string.settings_delete_target_media)
}

/** What deleting a target removes. */
@Composable
private fun targetDescription(target: DeletionTarget): String = when (target) {
    DeletionTarget.WearableData -> stringResource(R.string.settings_delete_target_wearable_summary)
    DeletionTarget.PhoneData -> stringResource(R.string.settings_delete_target_phone_summary)
    is DeletionTarget.Category -> stringResource(R.string.settings_delete_target_category_summary, categoryLabel(target.category))
    DeletionTarget.Insights -> stringResource(R.string.settings_delete_target_insights_summary)
    DeletionTarget.InterventionHistory -> stringResource(R.string.settings_delete_target_history_summary)
    DeletionTarget.GeneratedMedia -> stringResource(R.string.settings_delete_target_media_summary)
}
