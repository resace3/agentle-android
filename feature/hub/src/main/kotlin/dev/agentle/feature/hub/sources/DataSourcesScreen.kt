package dev.agentle.feature.hub.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentle.core.model.ConnectionStatus
import dev.agentle.core.ui.component.AgentleScaffold
import dev.agentle.core.ui.component.EmptyState
import dev.agentle.core.ui.component.SettingsSwitchRow
import dev.agentle.core.ui.component.errorMessageRes
import dev.agentle.core.ui.format.dateTimeText
import dev.agentle.core.ui.format.relativeTimeText
import dev.agentle.core.ui.status.StatusBadge
import dev.agentle.core.ui.status.toStatusSpec
import dev.agentle.core.ui.theme.AgentleSpacing
import dev.agentle.feature.hub.LoadContent
import dev.agentle.feature.hub.R
import dev.agentle.feature.hub.permissions.ActionErrorDialog
import dev.agentle.feature.hub.port.DataSourceItem
import dev.agentle.feature.hub.port.DataSourceKind
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

@Composable
internal fun DataSourcesRoute(viewModel: DataSourcesViewModel, onBack: () -> Unit, onOpen: (DataSourceItem) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DataSourcesScreen(
        state = state,
        now = viewModel.clock.now(),
        zone = viewModel.clock.zone(),
        actions = DataSourcesActions(onBack, viewModel::retry, viewModel::setEnabled, viewModel::syncNow, onOpen, viewModel::dismissError),
    )
}

internal data class DataSourcesActions(
    val back: () -> Unit = {},
    val retry: () -> Unit = {},
    val setEnabled: (DataSourceItem, Boolean) -> Unit = { _, _ -> },
    val syncNow: (DataSourceItem) -> Unit = {},
    val open: (DataSourceItem) -> Unit = {},
    val dismissError: () -> Unit = {},
)

@Composable
internal fun DataSourcesScreen(state: DataSourcesUiState, now: Instant, zone: TimeZone, actions: DataSourcesActions) {
    AgentleScaffold(title = stringResource(R.string.hub_sources_title), onBack = actions.back) { padding ->
        LoadContent(state.content, padding, actions.retry) { items ->
            if (items.isEmpty()) {
                EmptyState(title = stringResource(R.string.hub_sources_empty), modifier = Modifier.padding(padding))
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding() + AgentleSpacing.s,
                        bottom = padding.calculateBottomPadding() + AgentleSpacing.l,
                    ),
                    verticalArrangement = Arrangement.spacedBy(AgentleSpacing.s),
                ) {
                    items(items, key = { it.metadata.connectorId }) { item -> SourceCard(item, now, zone, actions) }
                }
            }
        }
    }
    state.actionError?.let { ActionErrorDialog(it, actions.dismissError) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceCard(item: DataSourceItem, now: Instant, zone: TimeZone, actions: DataSourcesActions) {
    val meta = item.metadata
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = AgentleSpacing.screenGutter)) {
        Column(modifier = Modifier.padding(AgentleSpacing.l), verticalArrangement = Arrangement.spacedBy(AgentleSpacing.s)) {
            Text(meta.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AgentleSpacing.s),
                verticalArrangement = Arrangement.spacedBy(AgentleSpacing.xs),
            ) {
                StatusBadge(meta.connection.toStatusSpec())
                StatusBadge(meta.permissionSummary.toStatusSpec())
                StatusBadge(meta.syncState.toStatusSpec())
            }
            Text(
                meta.lastSuccessfulCollection?.let { stringResource(R.string.hub_last_collected, relativeTimeText(it, now, zone)) }
                    ?: stringResource(R.string.hub_never_collected),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                item.coverageThrough?.let { stringResource(R.string.hub_coverage_through, dateTimeText(it, zone)) }
                    ?: stringResource(R.string.hub_no_coverage),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (item.gapDays.isNotEmpty()) {
                Text(
                    pluralStringResource(R.plurals.hub_gap_days, item.gapDays.size, item.gapDays.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            meta.lastError?.let { error ->
                Text(
                    stringResource(R.string.hub_last_error, stringResource(errorMessageRes(error.code))),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            SettingsSwitchRow(
                title = stringResource(R.string.hub_collect_switch),
                checked = meta.enabled,
                onCheckedChange = { actions.setEnabled(item, it) },
                enabled = meta.connection != ConnectionStatus.UNAVAILABLE,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AgentleSpacing.s)) {
                if (item.kind == DataSourceKind.WEARABLE) {
                    OutlinedButton(
                        onClick = { actions.syncNow(item) },
                        enabled = meta.enabled && meta.connection == ConnectionStatus.CONNECTED,
                        modifier = Modifier.heightIn(min = AgentleSpacing.minTouchTarget),
                    ) { Text(stringResource(R.string.hub_sync_now)) }
                }
                OutlinedButton(onClick = { actions.open(item) }, modifier = Modifier.heightIn(min = AgentleSpacing.minTouchTarget)) {
                    Text(
                        stringResource(
                            if (item.kind == DataSourceKind.WEARABLE) R.string.hub_manage_wearable else R.string.hub_manage_permissions,
                        ),
                    )
                }
            }
        }
    }
}
