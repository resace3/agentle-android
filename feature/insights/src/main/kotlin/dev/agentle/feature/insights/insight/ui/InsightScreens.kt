package dev.agentle.feature.insights.insight.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.sensitiveContent
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentle.core.model.InsightOrigin
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.feature.insights.R
import dev.agentle.feature.insights.insight.AiPanel
import dev.agentle.feature.insights.insight.DetailLoad
import dev.agentle.feature.insights.insight.InsightCard
import dev.agentle.feature.insights.insight.InsightDetailContent
import dev.agentle.feature.insights.insight.InsightDetailUiState
import dev.agentle.feature.insights.insight.InsightDetailViewModel
import dev.agentle.feature.insights.insight.InsightListUiState
import dev.agentle.feature.insights.insight.InsightListViewModel
import dev.agentle.feature.insights.insight.localDate
import dev.agentle.feature.insights.port.InsightChart
import dev.agentle.feature.insights.port.InterpretationPreview
import dev.agentle.feature.insights.ui.CollectEffects
import dev.agentle.feature.insights.ui.ErrorState
import dev.agentle.feature.insights.ui.Gutter
import dev.agentle.feature.insights.ui.InsightsScaffold
import dev.agentle.feature.insights.ui.LoadingState
import dev.agentle.feature.insights.ui.MessageState
import dev.agentle.feature.insights.ui.SectionTitle
import dev.agentle.feature.insights.ui.StatusLine
import dev.agentle.feature.insights.ui.formatDate
import dev.agentle.feature.insights.ui.label
import kotlinx.datetime.TimeZone
import java.util.Locale

@Composable
internal fun InsightListRoute(viewModel: InsightListViewModel, navigator: AppNavigator, onBack: (() -> Unit)?) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    InsightListScreen(
        state = state,
        onOpen = { navigator.navigate(AppRoute.InsightDetail(it)) },
        onFix = navigator::navigate,
        onRetry = viewModel::retry,
        onBack = onBack,
    )
}

@Composable
internal fun InsightListScreen(
    state: InsightListUiState,
    onOpen: (String) -> Unit,
    onFix: (AppRoute) -> Unit,
    onRetry: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    InsightsScaffold(stringResource(R.string.insights_title), snackbar, onBack) { padding ->
        when (state) {
            InsightListUiState.Loading -> LoadingState(Modifier.padding(padding))

            is InsightListUiState.Error -> ErrorState(state.error, onRetry, Modifier.padding(padding))

            is InsightListUiState.Content -> LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(Gutter),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.computing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                items(state.missingInputs) { missing ->
                    MessageState(
                        title = stringResource(R.string.insights_missing_title, label(missing.category)),
                        message = label(missing.reason),
                        action = stringResource(R.string.insights_fix),
                        onAction = { onFix(missing.fix) },
                    )
                }
                if (state.isEmpty) {
                    item { MessageState(stringResource(R.string.insights_empty_title), stringResource(R.string.insights_empty_message)) }
                }
                items(state.insights, key = { it.id }) { card -> InsightCardView(card, onClick = { onOpen(card.id) }) }
            }
        }
    }
}

@Composable
private fun InsightCardView(card: InsightCard, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(Gutter), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OriginLine(card.origin)
            Text(card.title, style = MaterialTheme.typography.titleMedium)
            Text(card.finding, style = MaterialTheme.typography.bodyMedium)
            card.supportingData.forEach { Text("${it.label}: ${it.value}", style = MaterialTheme.typography.bodySmall) }
            Text(
                stringResource(R.string.insights_period, formatDate(card.periodStart), formatDate(card.periodEnd)),
                style = MaterialTheme.typography.bodySmall,
            )
            StatusLine(R.drawable.ic_insights_info, label(card.strength))
            if (card.hasInterpretation) StatusLine(R.drawable.ic_insights_ai, stringResource(R.string.insights_has_ai))
        }
    }
}

@Composable
private fun OriginLine(origin: InsightOrigin) {
    if (origin == InsightOrigin.AI) {
        StatusLine(R.drawable.ic_insights_ai, stringResource(R.string.insights_origin_ai))
    } else {
        StatusLine(R.drawable.ic_insights_check, stringResource(R.string.insights_origin_local))
    }
}

@Composable
internal fun InsightDetailRoute(viewModel: InsightDetailViewModel, navigator: AppNavigator) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectEffects(viewModel.effects, navigator, snackbar)
    InsightDetailScreen(
        state = state,
        snackbar = snackbar,
        actions = InsightDetailActions(
            retry = viewModel::retry,
            request = viewModel::requestInterpretation,
            cancelPreview = viewModel::cancelPreview,
            send = viewModel::confirmSend,
            stop = viewModel::cancelStreaming,
            fix = viewModel::fix,
        ),
        onBack = navigator::back,
    )
}

internal data class InsightDetailActions(
    val retry: () -> Unit = {},
    val request: () -> Unit = {},
    val cancelPreview: () -> Unit = {},
    val send: () -> Unit = {},
    val stop: () -> Unit = {},
    val fix: (AppRoute) -> Unit = {},
)

@Composable
internal fun InsightDetailScreen(
    state: InsightDetailUiState,
    actions: InsightDetailActions,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onBack: () -> Unit = {},
) {
    InsightsScaffold(stringResource(R.string.insights_detail_title), snackbar, onBack) { padding ->
        when (val load = state.load) {
            DetailLoad.Loading -> LoadingState(Modifier.padding(padding))

            DetailLoad.NotFound -> ErrorState(
                dev.agentle.feature.insights.common.LoadError(dev.agentle.feature.insights.common.LoadError.NOT_FOUND),
                actions.retry,
                Modifier.padding(padding),
            )

            is DetailLoad.Error -> ErrorState(load.error, actions.retry, Modifier.padding(padding))

            is DetailLoad.Loaded -> Column(
                Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(Gutter),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InsightBody(load.content)
                AiSection(state, load.content.zone, actions)
            }
        }
    }
}

@Composable
private fun InsightBody(content: InsightDetailContent) {
    val card = content.card
    OriginLine(card.origin)
    Text(card.title, style = MaterialTheme.typography.headlineSmall)
    Text(card.finding, style = MaterialTheme.typography.bodyLarge)
    Text(stringResource(R.string.insights_not_causal), style = MaterialTheme.typography.bodySmall)
    StatusLine(R.drawable.ic_insights_info, label(card.strength))
    Text(
        stringResource(R.string.insights_period, formatDate(card.periodStart), formatDate(card.periodEnd)),
        style = MaterialTheme.typography.bodySmall,
    )
    content.chart?.let { ChartView(it) }
    if (card.supportingData.isNotEmpty()) {
        SectionTitle(stringResource(R.string.insights_supporting))
        card.supportingData.forEach { Text("${it.label}: ${it.value}") }
    }
    content.method?.let { method ->
        SectionTitle(stringResource(R.string.insights_method))
        val parts = listOfNotNull(
            label(method.test),
            method.qValue?.let { "q = " + String.format(Locale.ROOT, "%.2g", it) },
            method.pValue?.let { "p = " + String.format(Locale.ROOT, "%.2g", it) },
            method.sampleSize?.let { stringResource(R.string.insights_sample_size, it) },
        )
        Text(parts.joinToString(", "))
    }
}

/** A simple bar chart (comparison) or daily bars drawn with Canvas; the values are also given as text for readers. */
@Composable
private fun ChartView(chart: InsightChart) {
    val values: List<Pair<String, Double?>> = when (chart) {
        is InsightChart.Comparison -> chart.bars.map { it.label to it.value }
        is InsightChart.DailySeries -> chart.points.map { formatDate(it.date) to it.value }
    }
    val unit = label(chart.unit)
    val summary = values.joinToString("; ") { (name, value) -> "$name: ${value?.let { formatValue(it) } ?: "-"} $unit" }
    val max = values.mapNotNull { it.second }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val barColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.outline
    SectionTitle(stringResource(R.string.insights_chart))
    Canvas(
        Modifier.fillMaxWidth().height(160.dp).semantics { contentDescription = summary },
    ) {
        val slot = size.width / values.size.coerceAtLeast(1)
        values.forEachIndexed { index, (_, value) ->
            if (value != null) {
                val h = (value / max).toFloat() * size.height
                drawRect(barColor, topLeft = Offset(index * slot + slot * 0.15f, size.height - h), size = Size(slot * 0.7f, h))
            }
        }
        drawLine(axisColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
    }
    if (chart is InsightChart.Comparison) {
        chart.bars.forEach { Text("${it.label}: ${formatValue(it.value)} $unit", style = MaterialTheme.typography.bodySmall) }
    }
}

private fun formatValue(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.getDefault(), "%.1f", value)

@Composable
private fun AiSection(state: InsightDetailUiState, zone: TimeZone, actions: InsightDetailActions) {
    SectionTitle(stringResource(R.string.insights_ai_title))
    state.interpretation?.takeIf { state.ai !is AiPanel.Streaming }?.let { saved ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Gutter), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusLine(R.drawable.ic_insights_ai, stringResource(R.string.insights_ai_label))
                // Model output: plain text only.
                Text(saved.text, modifier = Modifier.sensitiveContent())
                Text(
                    stringResource(R.string.insights_ai_made_from, saved.categories.map { label(it) }.sorted().joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    when (val panel = state.ai) {
        AiPanel.Idle, is AiPanel.Completed -> OutlinedButton(onClick = actions.request, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.insights_ai_interpret))
        }

        AiPanel.Preparing -> LinearProgressIndicator(Modifier.fillMaxWidth())

        is AiPanel.Preview -> PreviewCard(panel.preview, zone, actions)

        is AiPanel.Streaming -> {
            Text(stringResource(R.string.insights_ai_streaming), style = MaterialTheme.typography.labelLarge)
            Text(panel.text, modifier = Modifier.sensitiveContent())
            TextButton(
                onClick = actions.stop,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.insights_ai_stop)) }
        }

        is AiPanel.Problem -> {
            StatusLine(R.drawable.ic_insights_warning, label(panel.problem))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                panel.problem.fix?.let { route ->
                    Button(onClick = { actions.fix(route) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.insights_fix))
                    }
                }
                if (panel.problem.retryable) {
                    OutlinedButton(onClick = actions.request, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.insights_retry))
                    }
                }
                TextButton(onClick = actions.cancelPreview, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.insights_close))
                }
            }
        }
    }
}

/** Exactly what would be sent: purpose, data categories, date range and the port's preview text. */
@Composable
private fun PreviewCard(preview: InterpretationPreview, zone: TimeZone, actions: InsightDetailActions) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Gutter), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.insights_ai_preview_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.insights_ai_purpose, preview.purpose.name.lowercase(Locale.ROOT).replace('_', ' ')))
            Text(stringResource(R.string.insights_ai_categories, preview.categories.map { label(it) }.sorted().joinToString(", ")))
            Text(
                stringResource(
                    R.string.insights_ai_range,
                    formatDate(preview.rangeStart.localDate(zone)),
                    formatDate(dev.agentle.feature.insights.insight.lastDay(preview.rangeStart, preview.rangeEnd, zone)),
                ),
            )
            Text(preview.previewText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.sensitiveContent())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = actions.send,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.insights_ai_send)) }
                TextButton(onClick = actions.cancelPreview, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.insights_cancel))
                }
            }
        }
    }
}
