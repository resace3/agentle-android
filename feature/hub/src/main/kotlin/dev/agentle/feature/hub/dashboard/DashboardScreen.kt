package dev.agentle.feature.hub.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentle.ai.api.AiProviderState
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.core.model.ConnectionStatus
import dev.agentle.core.ui.component.AgentleScaffold
import dev.agentle.core.ui.component.MetricTile
import dev.agentle.core.ui.component.SectionHeader
import dev.agentle.core.ui.component.SettingsRow
import dev.agentle.core.ui.format.dateTimeText
import dev.agentle.core.ui.format.durationText
import dev.agentle.core.ui.format.numberText
import dev.agentle.core.ui.format.relativeTimeText
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.core.ui.navigation.JitaiTab
import dev.agentle.core.ui.status.StatusBadge
import dev.agentle.core.ui.status.toStatusSpec
import dev.agentle.core.ui.theme.AgentleSpacing
import dev.agentle.feature.hub.LoadContent
import dev.agentle.feature.hub.R
import dev.agentle.feature.hub.permissions.ActionErrorDialog
import dev.agentle.feature.hub.port.DashboardData
import dev.agentle.feature.hub.port.PendingIntervention
import dev.agentle.feature.hub.port.TodayMetricKind
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@Composable
internal fun DashboardRoute(viewModel: DashboardViewModel, navigate: (AppRoute) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DashboardScreen(
        state = state,
        now = viewModel.clock.now(),
        zone = viewModel.clock.zone(),
        actions = DashboardActions(
            navigate,
            viewModel::retry,
            viewModel::snooze,
            viewModel::notNow,
            viewModel::stopJitai,
            viewModel::dismissError,
        ),
    )
}

internal data class DashboardActions(
    val navigate: (AppRoute) -> Unit = {},
    val retry: () -> Unit = {},
    val snooze: (PendingIntervention) -> Unit = {},
    val notNow: (PendingIntervention) -> Unit = {},
    val stopJitai: (PendingIntervention) -> Unit = {},
    val dismissError: () -> Unit = {},
)

@Composable
internal fun DashboardScreen(state: DashboardUiState, now: Instant, zone: TimeZone, actions: DashboardActions) {
    AgentleScaffold(title = stringResource(R.string.hub_dashboard_title)) { padding ->
        LoadContent(state.content, padding, actions.retry) { data ->
            LazyColumn(
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom =
                    padding.calculateBottomPadding() + AgentleSpacing.l,
                ),
                verticalArrangement = Arrangement.spacedBy(AgentleSpacing.s),
            ) {
                pending(data, now, zone, actions)
                today(data, zone)
                insights(data, actions)
                jitais(data, now, zone, actions)
                connections(data, actions)
                health(data, now, zone, actions)
                item {
                    SectionHeader(stringResource(R.string.hub_see_all))
                    Column {
                        SettingsRow(stringResource(R.string.hub_menu_sources), onClick = { actions.navigate(AppRoute.DataSources) })
                        SettingsRow(stringResource(R.string.hub_menu_permissions), onClick = {
                            actions.navigate(AppRoute.PermissionCenter())
                        })
                        SettingsRow(stringResource(R.string.hub_menu_timeline), onClick = { actions.navigate(AppRoute.Timeline()) })
                        SettingsRow(stringResource(R.string.hub_menu_settings), onClick = { actions.navigate(AppRoute.Settings) })
                    }
                }
            }
        }
    }
    state.actionError?.let { ActionErrorDialog(it, actions.dismissError) }
}

private fun LazyListScope.pending(data: DashboardData, now: Instant, zone: TimeZone, actions: DashboardActions) {
    if (data.pendingInterventions.isEmpty()) return
    item { SectionHeader(stringResource(R.string.hub_pending)) }
    item { Gutter { Text(stringResource(R.string.hub_pending_body), style = MaterialTheme.typography.bodyMedium) } }
    items(data.pendingInterventions, key = { it.decisionKey }) { card -> InterventionCard(card, now, zone, actions) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InterventionCard(card: PendingIntervention, now: Instant, zone: TimeZone, actions: DashboardActions) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = AgentleSpacing.screenGutter)) {
        Column(modifier = Modifier.padding(AgentleSpacing.l), verticalArrangement = Arrangement.spacedBy(AgentleSpacing.s)) {
            Text(card.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(card.text, style = MaterialTheme.typography.bodyMedium)
            Text(relativeTimeText(card.createdAt, now, zone), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AgentleSpacing.s)) {
                OutlinedButton(onClick = { actions.navigate(AppRoute.InterventionDetail(card.decisionKey)) }, modifier = touch) {
                    Text(stringResource(R.string.hub_open))
                }
                OutlinedButton(onClick = { actions.snooze(card) }, modifier = touch) { Text(stringResource(R.string.hub_snooze)) }
                OutlinedButton(onClick = { actions.notNow(card) }, modifier = touch) { Text(stringResource(R.string.hub_not_now)) }
                TextButton(onClick = { actions.stopJitai(card) }, modifier = touch) { Text(stringResource(R.string.hub_stop_jitai)) }
            }
        }
    }
}

private val touch = Modifier.heightIn(min = AgentleSpacing.minTouchTarget)

@Composable
private fun Gutter(content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = AgentleSpacing.screenGutter)) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.today(data: DashboardData, zone: TimeZone) {
    item { SectionHeader(stringResource(R.string.hub_today_summary)) }
    item {
        FlowRow(
            modifier = Modifier.padding(horizontal = AgentleSpacing.screenGutter),
            horizontalArrangement = Arrangement.spacedBy(AgentleSpacing.s),
            verticalArrangement = Arrangement.spacedBy(AgentleSpacing.s),
        ) {
            TodayMetricKind.entries.forEach { kind ->
                val value = data.today[kind] ?: FeatureValue.Missing(MissingReason.COVERAGE_GAP)
                val known = value !is FeatureValue.Missing
                MetricTile(
                    label = stringResource(kind.label()),
                    value = metricText(kind, value) ?: stringResource(R.string.hub_no_data_yet),
                    valueKnown = known,
                    supportingText = supporting(value, zone),
                    modifier = Modifier.widthIn(min = 150.dp),
                )
            }
        }
    }
}

private fun TodayMetricKind.label() = when (this) {
    TodayMetricKind.STEPS -> R.string.hub_metric_steps
    TodayMetricKind.ACTIVE_MINUTES -> R.string.hub_metric_active
    TodayMetricKind.SLEEP_MINUTES -> R.string.hub_metric_sleep
    TodayMetricKind.SCREEN_TIME_MINUTES -> R.string.hub_metric_screen
    TodayMetricKind.UNLOCKS -> R.string.hub_metric_unlocks
}

@Composable
private fun metricText(kind: TodayMetricKind, value: FeatureValue): String? {
    val scalar = when (value) {
        is FeatureValue.Known -> value.value
        is FeatureValue.Stale -> value.lastValue
        is FeatureValue.Missing -> return null
    }
    val number = (scalar as? FeatureScalar.IntValue)?.value ?: return null
    return when (kind) {
        TodayMetricKind.STEPS, TodayMetricKind.UNLOCKS -> numberText(number)
        else -> durationText(number.minutes)
    }
}

@Composable
private fun supporting(value: FeatureValue, zone: TimeZone): String? = when (value) {
    is FeatureValue.Known -> null

    is FeatureValue.Stale -> stringResource(R.string.hub_stale_as_of, dateTimeText(value.asOf, zone))

    is FeatureValue.Missing -> stringResource(
        when (value.reason) {
            MissingReason.NO_PERMISSION -> R.string.hub_missing_no_permission
            MissingReason.COLLECTOR_INACTIVE -> R.string.hub_missing_collector
            MissingReason.COVERAGE_GAP -> R.string.hub_missing_gap
            else -> R.string.hub_missing_other
        },
    )
}

private fun LazyListScope.insights(data: DashboardData, actions: DashboardActions) {
    item { SectionHeader(stringResource(R.string.hub_insights)) }
    if (data.recentInsights.isEmpty()) {
        item { Gutter { Text(stringResource(R.string.hub_no_insights), style = MaterialTheme.typography.bodyMedium) } }
    }
    items(data.recentInsights, key = { "i-${it.id}" }) { insight ->
        SettingsRow(title = insight.title, subtitle = insight.finding, onClick = { actions.navigate(AppRoute.InsightDetail(insight.id)) })
    }
}

private fun LazyListScope.jitais(data: DashboardData, now: Instant, zone: TimeZone, actions: DashboardActions) {
    item { SectionHeader(stringResource(R.string.hub_jitais)) }
    if (data.activeJitais.isEmpty()) {
        item {
            SettingsRow(
                title = stringResource(R.string.hub_no_jitais),
                subtitle = stringResource(R.string.hub_create_jitai),
                onClick = { actions.navigate(AppRoute.JitaiBuilder()) },
            )
        }
    }
    items(data.activeJitais, key = { "j-${it.id}" }) { jitai ->
        SettingsRow(
            title = jitai.name,
            subtitle = jitai.nextPossibleDelivery?.let { stringResource(R.string.hub_next_delivery, relativeTimeText(it, now, zone)) }
                ?: jitai.blockedReason
                ?: stringResource(R.string.hub_no_delivery_possible),
            onClick = { actions.navigate(AppRoute.JitaiDetail(jitai.id)) },
        )
    }
    if (data.activeJitais.isNotEmpty()) {
        item { SettingsRow(stringResource(R.string.hub_see_all), onClick = { actions.navigate(AppRoute.Jitais(JitaiTab.ACTIVE)) }) }
    }
}

private fun LazyListScope.connections(data: DashboardData, actions: DashboardActions) {
    item { SectionHeader(stringResource(R.string.hub_connections)) }
    item {
        SettingsRow(
            title = stringResource(R.string.hub_wearable),
            trailing = { StatusBadge((data.wearable?.connection ?: ConnectionStatus.UNAVAILABLE).toStatusSpec()) },
            onClick = { actions.navigate(AppRoute.Wearable) },
        )
    }
    item {
        SettingsRow(
            title = stringResource(R.string.hub_chatgpt),
            subtitle = when (val ai = data.chatGpt) {
                is AiProviderState.Connected -> ai.accountLabel
                is AiProviderState.NotEligible -> stringResource(R.string.hub_ai_not_eligible)
                is AiProviderState.UsageLimited -> stringResource(R.string.hub_ai_limited)
                else -> null
            },
            trailing = { StatusBadge(data.chatGpt.toConnectionStatus().toStatusSpec()) },
            onClick = { actions.navigate(AppRoute.ChatGpt) },
        )
    }
}

internal fun AiProviderState.toConnectionStatus(): ConnectionStatus = when (this) {
    AiProviderState.Disconnected -> ConnectionStatus.NOT_CONNECTED
    AiProviderState.Connecting -> ConnectionStatus.CONNECTING
    is AiProviderState.Connected -> ConnectionStatus.CONNECTED
    AiProviderState.NeedsReauth -> ConnectionStatus.NEEDS_REAUTH
    is AiProviderState.UsageLimited -> ConnectionStatus.ERROR
    is AiProviderState.NotEligible, is AiProviderState.Unavailable -> ConnectionStatus.UNAVAILABLE
}

private fun LazyListScope.health(data: DashboardData, now: Instant, zone: TimeZone, actions: DashboardActions) {
    item { SectionHeader(stringResource(R.string.hub_collection_health)) }
    if (data.collectors.isEmpty()) {
        item { SettingsRow(stringResource(R.string.hub_no_collectors), onClick = { actions.navigate(AppRoute.DataSources) }) }
    }
    items(data.collectors, key = { "c-${it.connectorId}" }) { meta ->
        SettingsRow(
            title = meta.name,
            subtitle = meta.lastSuccessfulCollection?.let { stringResource(R.string.hub_last_collected, relativeTimeText(it, now, zone)) }
                ?: stringResource(R.string.hub_never_collected),
            trailing = { StatusBadge(meta.permissionSummary.toStatusSpec()) },
            onClick = { actions.navigate(AppRoute.DataSources) },
        )
    }
}
