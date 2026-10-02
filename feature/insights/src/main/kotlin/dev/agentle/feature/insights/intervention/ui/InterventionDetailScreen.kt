package dev.agentle.feature.insights.intervention.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.sensitiveContent
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.feature.insights.R
import dev.agentle.feature.insights.intervention.InterventionContent
import dev.agentle.feature.insights.intervention.InterventionDetailUiState
import dev.agentle.feature.insights.intervention.InterventionDetailViewModel
import dev.agentle.feature.insights.intervention.TraceLine
import dev.agentle.feature.insights.jitai.Load
import dev.agentle.feature.insights.jitai.ui.resultIcon
import dev.agentle.feature.insights.port.Feedback
import dev.agentle.feature.insights.port.TraceResult
import dev.agentle.feature.insights.ui.CollectEffects
import dev.agentle.feature.insights.ui.ErrorState
import dev.agentle.feature.insights.ui.Gutter
import dev.agentle.feature.insights.ui.InsightsScaffold
import dev.agentle.feature.insights.ui.LoadingState
import dev.agentle.feature.insights.ui.SectionTitle
import dev.agentle.feature.insights.ui.StatusLine
import dev.agentle.feature.insights.ui.featureLabel
import dev.agentle.feature.insights.ui.formatDateTime
import dev.agentle.feature.insights.ui.label
import java.util.Locale

/** Everything the intervention detail can do. */
internal data class InterventionActions(
    val sendFeedback: (Boolean) -> Unit = {},
    val openRule: () -> Unit = {},
    val retry: () -> Unit = {},
)

@Composable
internal fun InterventionDetailRoute(viewModel: InterventionDetailViewModel, navigator: AppNavigator) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectEffects(viewModel.effects, navigator, snackbar)
    InterventionDetailScreen(
        state = state,
        snackbar = snackbar,
        actions = InterventionActions(viewModel::sendFeedback, viewModel::openRule, viewModel::retry),
        onBack = navigator::back,
    )
}

@Composable
internal fun InterventionDetailScreen(
    state: InterventionDetailUiState,
    actions: InterventionActions,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onBack: () -> Unit = {},
) {
    InsightsScaffold(stringResource(R.string.intervention_title), snackbar, onBack) { padding ->
        when (val load = state.load) {
            Load.Loading -> LoadingState(Modifier.padding(padding))

            is Load.Error -> ErrorState(load.error, actions.retry, Modifier.padding(padding))

            is Load.Loaded -> Column(
                Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(Gutter),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Body(load.value, state.sendingFeedback, actions)
            }
        }
    }
}

@Composable
private fun Body(item: InterventionContent, sending: Boolean, actions: InterventionActions) {
    Text(item.jitaiName, style = MaterialTheme.typography.headlineSmall)
    StatusLine(resultIcon(item.result), label(item.result))
    item.reason?.let { Text(stringResource(R.string.intervention_reason, label(it))) }

    SectionTitle(stringResource(R.string.intervention_when))
    Text(stringResource(R.string.intervention_decided_at, formatDateTime(item.decidedAt, item.use24HourClock)))
    item.deliveredAt?.let { Text(stringResource(R.string.intervention_delivered_at, formatDateTime(it, item.use24HourClock))) }
    Text(stringResource(R.string.intervention_channel, label(item.channel)))

    SectionTitle(stringResource(R.string.intervention_what))
    val content = item.content
    if (content == null) {
        Text(stringResource(R.string.intervention_no_content))
    } else {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp).sensitiveContent(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (content.title.isNotBlank()) Text(content.title, style = MaterialTheme.typography.titleSmall)
                Text(content.body)
            }
        }
        // The notification may have shown a general text (detailed notifications are off by default).
        Text(stringResource(R.string.intervention_content_note), style = MaterialTheme.typography.bodySmall)
    }

    SectionTitle(stringResource(R.string.intervention_why))
    if (item.conditions.isEmpty()) Text(stringResource(R.string.intervention_no_trace))
    item.conditions.forEach { TraceRow(it, item.appLabels) }

    if (item.canGiveFeedback) {
        SectionTitle(stringResource(R.string.intervention_feedback))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Feedback.HELPFUL to true, Feedback.NOT_HELPFUL to false).forEach { (value, helpful) ->
                FilterChip(
                    selected = item.feedback == value,
                    enabled = !sending,
                    onClick = { actions.sendFeedback(helpful) },
                    label = { Text(label(value)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
    if (item.jitaiId != null) {
        OutlinedButton(onClick = actions.openRule, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.intervention_open_rule))
        }
    }
}

@Composable
private fun TraceRow(line: TraceLine, appLabels: Map<String, String>) {
    val icon = when (line.result) {
        TraceResult.TRUE -> R.drawable.ic_insights_check
        TraceResult.FALSE -> R.drawable.ic_insights_error
        TraceResult.UNKNOWN -> R.drawable.ic_insights_info
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        StatusLine(icon, line.text + " — " + label(line.result))
        val observed = line.observed
        if (line.featureId != null && observed != null) {
            Text(
                stringResource(R.string.intervention_observed, featureLabel(line.featureId), observedText(observed, appLabels)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (line.overridden) Text(stringResource(R.string.intervention_overridden), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun observedText(value: FeatureValue, appLabels: Map<String, String>): String = when (value) {
    is FeatureValue.Known -> scalarText(value.value, appLabels)
    is FeatureValue.Stale -> stringResource(R.string.intervention_stale, scalarText(value.lastValue, appLabels))
    is FeatureValue.Missing -> stringResource(R.string.intervention_unknown_value)
}

@Composable
private fun scalarText(scalar: FeatureScalar, appLabels: Map<String, String>): String = when (scalar) {
    is FeatureScalar.IntValue -> scalar.value.toString()
    is FeatureScalar.BoolValue -> stringResource(if (scalar.value) R.string.intervention_yes else R.string.intervention_no)
    is FeatureScalar.EnumValue -> scalar.value.lowercase(Locale.ROOT).replace('_', ' ')
    is FeatureScalar.DayOfWeekValue -> scalar.value.name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
    is FeatureScalar.LocalTimeValue -> clock(scalar.minuteOfDay)
    is FeatureScalar.NightTimeValue -> clock(scalar.minuteOfDay)
    is FeatureScalar.PackageValue -> appLabels[scalar.packageName] ?: stringResource(R.string.intervention_other_app)
    FeatureScalar.NoPackage -> stringResource(R.string.intervention_no_app)
    FeatureScalar.Never -> stringResource(R.string.intervention_never)
}

private fun clock(minuteOfDay: Int): String = String.format(Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)
