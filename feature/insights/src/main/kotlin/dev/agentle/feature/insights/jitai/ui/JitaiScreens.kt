@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.agentle.feature.insights.jitai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.core.ui.navigation.JitaiBuilderMode
import dev.agentle.core.ui.navigation.JitaiTab
import dev.agentle.feature.insights.R
import dev.agentle.feature.insights.jitai.HistoryContent
import dev.agentle.feature.insights.jitai.JitaiDetailUiState
import dev.agentle.feature.insights.jitai.JitaiDetailViewModel
import dev.agentle.feature.insights.jitai.JitaiListUiState
import dev.agentle.feature.insights.jitai.JitaiListViewModel
import dev.agentle.feature.insights.jitai.Load
import dev.agentle.feature.insights.jitai.RuleAction
import dev.agentle.feature.insights.jitai.RuleCard
import dev.agentle.feature.insights.port.DeliveryRecord
import dev.agentle.feature.insights.port.DeliveryResult
import dev.agentle.feature.insights.port.SuggestedJitai
import dev.agentle.feature.insights.ui.CollectEffects
import dev.agentle.feature.insights.ui.ErrorState
import dev.agentle.feature.insights.ui.Gutter
import dev.agentle.feature.insights.ui.InsightsScaffold
import dev.agentle.feature.insights.ui.LoadingState
import dev.agentle.feature.insights.ui.MessageState
import dev.agentle.feature.insights.ui.SectionTitle
import dev.agentle.feature.insights.ui.StatusLine
import dev.agentle.feature.insights.ui.formatDateTime
import dev.agentle.feature.insights.ui.label
import dev.agentle.jitai.dsl.model.JitaiStatus
import kotlinx.datetime.toLocalDateTime

/** Every action of the JITAI list. */
internal data class JitaiListActions(
    val selectTab: (JitaiTab) -> Unit = {},
    val retry: () -> Unit = {},
    val rule: (RuleAction, RuleCard) -> Unit = { _, _ -> },
    val open: (String) -> Unit = {},
    val openSuggestion: (String) -> Unit = {},
    val openDecision: (String) -> Unit = {},
    val create: (JitaiBuilderMode) -> Unit = {},
    val fixNotifications: () -> Unit = {},
    val confirmDisable: () -> Unit = {},
    val dismissDisable: () -> Unit = {},
)

@Composable
internal fun JitaiListRoute(viewModel: JitaiListViewModel, navigator: AppNavigator) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectEffects(viewModel.effects, navigator, snackbar)
    JitaiListScreen(
        state = state,
        snackbar = snackbar,
        actions = JitaiListActions(
            selectTab = viewModel::selectTab,
            retry = viewModel::retry,
            rule = { action, card ->
                when (action) {
                    RuleAction.PAUSE -> viewModel.pause(card.id)
                    RuleAction.RESUME -> viewModel.resume(card.id)
                    RuleAction.EDIT -> viewModel.edit(card.id)
                    RuleAction.DISABLE -> viewModel.requestDisable(card.id, card.name)
                }
            },
            open = viewModel::open,
            openSuggestion = viewModel::openSuggestion,
            openDecision = viewModel::openDecision,
            create = viewModel::create,
            fixNotifications = viewModel::fixNotifications,
            confirmDisable = viewModel::confirmDisable,
            dismissDisable = viewModel::dismissDisable,
        ),
        onBack = navigator::back,
    )
}

@Composable
internal fun JitaiListScreen(
    state: JitaiListUiState,
    actions: JitaiListActions,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onBack: (() -> Unit)? = null,
) {
    InsightsScaffold(stringResource(R.string.jitai_title), snackbar, onBack) { padding ->
        Column(Modifier.padding(padding)) {
            PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                JitaiTab.entries.forEach { tab ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { actions.selectTab(tab) },
                        text = { Text(stringResource(TAB_TITLES.getValue(tab))) },
                    )
                }
            }
            when (state.tab) {
                JitaiTab.ACTIVE, JitaiTab.PAUSED -> RulesTab(state, actions)
                JitaiTab.SUGGESTED -> SuggestedTab(state.suggestions, actions)
                JitaiTab.HISTORY -> HistoryTab(state.history, actions)
            }
        }
        state.pendingDisable?.let { pending ->
            AlertDialog(
                onDismissRequest = actions.dismissDisable,
                title = { Text(stringResource(R.string.jitai_disable_title)) },
                text = { Text(stringResource(R.string.jitai_disable_message, pending.name)) },
                confirmButton = { TextButton(onClick = actions.confirmDisable) { Text(stringResource(R.string.jitai_disable)) } },
                dismissButton = { TextButton(onClick = actions.dismissDisable) { Text(stringResource(R.string.insights_cancel)) } },
            )
        }
    }
}

private val TAB_TITLES = mapOf(
    JitaiTab.ACTIVE to R.string.jitai_tab_active,
    JitaiTab.SUGGESTED to R.string.jitai_tab_suggested,
    JitaiTab.PAUSED to R.string.jitai_tab_paused,
    JitaiTab.HISTORY to R.string.jitai_tab_history,
)

@Composable
private fun RulesTab(state: JitaiListUiState, actions: JitaiListActions) {
    when (val load = state.rules) {
        Load.Loading -> LoadingState()

        is Load.Error -> ErrorState(load.error, actions.retry)

        is Load.Loaded -> {
            val lists = load.value
            val rules = if (state.tab == JitaiTab.ACTIVE) lists.active else lists.paused
            LazyColumn(contentPadding = PaddingValues(Gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!lists.notificationsAllowed) {
                    item {
                        MessageState(
                            stringResource(R.string.jitai_notifications_off_title),
                            stringResource(R.string.jitai_notifications_off_message),
                            action = stringResource(R.string.insights_fix),
                            onAction = actions.fixNotifications,
                        )
                    }
                }
                if (state.tab == JitaiTab.ACTIVE) {
                    item {
                        Text(
                            stringResource(R.string.jitai_today_total, lists.deliveredToday, lists.globalMaxPerDay),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    item { CreateButtons(actions) }
                }
                if (rules.isEmpty()) {
                    item {
                        MessageState(
                            stringResource(if (state.tab == JitaiTab.ACTIVE) R.string.jitai_empty_active else R.string.jitai_empty_paused),
                            stringResource(R.string.jitai_empty_message),
                        )
                    }
                }
                items(rules, key = { it.id }) { card ->
                    RuleCardView(card, busy = card.id in state.busyIds, actions = actions)
                }
            }
        }
    }
}

@Composable
private fun CreateButtons(actions: JitaiListActions) {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { actions.create(JitaiBuilderMode.MANUAL) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.jitai_create_manual))
        }
        OutlinedButton(onClick = { actions.create(JitaiBuilderMode.NATURAL_LANGUAGE) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.jitai_create_nl))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RuleCardView(card: RuleCard, busy: Boolean, actions: JitaiListActions, showOpen: Boolean = true) {
    Card(Modifier.fillMaxWidth().let { if (showOpen) it.clickable { actions.open(card.id) } else it }) {
        Column(Modifier.padding(Gutter), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(card.name, style = MaterialTheme.typography.titleMedium)
            StatusLine(statusIcon(card.status), label(card.status))
            card.pausedReason?.let { Text(label(it), style = MaterialTheme.typography.bodySmall) }
            if (card.needsAccess) StatusLine(R.drawable.ic_insights_warning, stringResource(R.string.jitai_needs_access))
            Text(card.rendering, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.jitai_channel, label(card.channel)), style = MaterialTheme.typography.bodySmall)
            card.maxPerDay?.let { cap ->
                Text(stringResource(R.string.jitai_today_of_cap, card.deliveredToday, cap), style = MaterialTheme.typography.bodySmall)
            } ?: Text(
                pluralStringResource(R.plurals.jitai_today, card.deliveredToday, card.deliveredToday),
                style = MaterialTheme.typography.bodySmall,
            )
            card.maxPerWeek?.let { cap ->
                Text(stringResource(R.string.jitai_week_of_cap, card.deliveredThisWeek, cap), style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                card.actions.sortedBy { it.ordinal }.forEach { action ->
                    OutlinedButton(
                        onClick = { actions.rule(action, card) },
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(ACTION_TITLES.getValue(action))) }
                }
            }
        }
    }
}

private val ACTION_TITLES = mapOf(
    RuleAction.PAUSE to R.string.jitai_pause,
    RuleAction.RESUME to R.string.jitai_resume,
    RuleAction.EDIT to R.string.jitai_edit,
    RuleAction.DISABLE to R.string.jitai_disable,
)

private fun statusIcon(status: JitaiStatus): Int = when (status) {
    JitaiStatus.ACTIVE -> R.drawable.ic_insights_check
    JitaiStatus.PAUSED -> R.drawable.ic_insights_pause
    else -> R.drawable.ic_insights_info
}

@Composable
private fun SuggestedTab(load: Load<List<SuggestedJitai>>, actions: JitaiListActions) {
    when (load) {
        Load.Loading -> LoadingState()

        is Load.Error -> ErrorState(load.error, actions.retry)

        is Load.Loaded -> LazyColumn(contentPadding = PaddingValues(Gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (load.value.isEmpty()) {
                item {
                    MessageState(
                        stringResource(R.string.jitai_empty_suggested),
                        stringResource(R.string.jitai_empty_suggested_message),
                    )
                }
            }
            items(load.value, key = { it.proposalId }) { suggestion ->
                Card(Modifier.fillMaxWidth().clickable { actions.openSuggestion(suggestion.proposalId) }) {
                    Column(Modifier.padding(Gutter), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusLine(R.drawable.ic_insights_ai, stringResource(R.string.jitai_suggested_label))
                        Text(suggestion.name, style = MaterialTheme.typography.titleMedium)
                        Text(suggestion.rendering, style = MaterialTheme.typography.bodyMedium)
                        Text(label(suggestion.tier), style = MaterialTheme.typography.bodySmall)
                        Text(pluralStringResource(R.plurals.jitai_trial_days, suggestion.trialDays, suggestion.trialDays))
                        TextButton(onClick = { actions.openSuggestion(suggestion.proposalId) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.jitai_review))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryTab(load: Load<HistoryContent>, actions: JitaiListActions) {
    when (load) {
        Load.Loading -> LoadingState()

        is Load.Error -> ErrorState(load.error, actions.retry)

        is Load.Loaded -> LazyColumn(contentPadding = PaddingValues(Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            historyItems(load.value, actions.openDecision)
        }
    }
}

internal fun LazyListScope.historyItems(history: HistoryContent, onOpen: (String) -> Unit) {
    if (history.records.isEmpty()) {
        item { MessageState(stringResource(R.string.jitai_empty_history), stringResource(R.string.jitai_empty_history_message)) }
    }
    items(history.records, key = { it.decisionKey }) { record -> HistoryRow(record, history, onOpen) }
}

@Composable
internal fun HistoryRow(record: DeliveryRecord, history: HistoryContent, onOpen: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpen(record.decisionKey) }) {
        Column(Modifier.padding(Gutter), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(record.jitaiName, style = MaterialTheme.typography.titleSmall)
            Text(
                formatDateTime(record.at.toLocalDateTime(history.zone), history.use24HourClock),
                style = MaterialTheme.typography.bodySmall,
            )
            StatusLine(resultIcon(record.result), label(record.result))
            record.reason?.let { Text(label(it), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

internal fun resultIcon(result: DeliveryResult): Int = when (result) {
    DeliveryResult.DELIVERED, DeliveryResult.OPENED, DeliveryResult.SENDING -> R.drawable.ic_insights_check
    DeliveryResult.FAILED, DeliveryResult.DELIVERY_UNCERTAIN -> R.drawable.ic_insights_error
    DeliveryResult.SUPPRESSED, DeliveryResult.EXPIRED, DeliveryResult.MISSED, DeliveryResult.CANCELLED -> R.drawable.ic_insights_warning
    else -> R.drawable.ic_insights_info
}

@Composable
internal fun JitaiDetailRoute(viewModel: JitaiDetailViewModel, navigator: AppNavigator) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectEffects(viewModel.effects, navigator, snackbar)
    JitaiDetailScreen(
        state = state,
        snackbar = snackbar,
        actions = JitaiListActions(
            retry = viewModel::retry,
            rule = { action, _ ->
                when (action) {
                    RuleAction.PAUSE -> viewModel.pause()
                    RuleAction.RESUME -> viewModel.resume()
                    RuleAction.EDIT -> viewModel.edit()
                    RuleAction.DISABLE -> viewModel.requestDisable()
                }
            },
            openDecision = viewModel::openDecision,
            confirmDisable = viewModel::confirmDisable,
            dismissDisable = viewModel::dismissDisable,
        ),
        onBack = navigator::back,
    )
}

@Composable
internal fun JitaiDetailScreen(
    state: JitaiDetailUiState,
    actions: JitaiListActions,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onBack: () -> Unit = {},
) {
    InsightsScaffold(stringResource(R.string.jitai_detail_title), snackbar, onBack) { padding ->
        when (val load = state.load) {
            Load.Loading -> LoadingState(Modifier.padding(padding))

            is Load.Error -> ErrorState(load.error, actions.retry, Modifier.padding(padding))

            is Load.Loaded -> {
                val content = load.value
                LazyColumn(
                    Modifier.padding(padding),
                    contentPadding = PaddingValues(Gutter),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { RuleCardView(content.card, busy = state.busy, actions = actions, showOpen = false) }
                    if (content.description.isNotBlank()) item { Text(content.description) }
                    if (content.content.isNotEmpty()) {
                        item { SectionTitle(stringResource(R.string.jitai_content)) }
                        items(content.content) { Text(it) }
                    }
                    item { SectionTitle(stringResource(R.string.jitai_tab_history)) }
                    historyItems(content.history, actions.openDecision)
                }
            }
        }
        if (state.confirmingDisable) {
            AlertDialog(
                onDismissRequest = actions.dismissDisable,
                title = { Text(stringResource(R.string.jitai_disable_title)) },
                text = { Text(stringResource(R.string.jitai_disable_message_short)) },
                confirmButton = { TextButton(onClick = actions.confirmDisable) { Text(stringResource(R.string.jitai_disable)) } },
                dismissButton = { TextButton(onClick = actions.dismissDisable) { Text(stringResource(R.string.insights_cancel)) } },
            )
        }
    }
}
