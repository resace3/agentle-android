package dev.agentle.feature.insights.review.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.feature.insights.R
import dev.agentle.feature.insights.jitai.Load
import dev.agentle.feature.insights.port.ProposalRejection
import dev.agentle.feature.insights.review.EvidenceSummary
import dev.agentle.feature.insights.review.ProposalReviewUiState
import dev.agentle.feature.insights.review.ProposalReviewViewModel
import dev.agentle.feature.insights.review.ReviewModel
import dev.agentle.feature.insights.review.ReviewOrigin
import dev.agentle.feature.insights.review.reviewKey
import dev.agentle.feature.insights.ui.CollectEffects
import dev.agentle.feature.insights.ui.ErrorState
import dev.agentle.feature.insights.ui.Gutter
import dev.agentle.feature.insights.ui.InsightsScaffold
import dev.agentle.feature.insights.ui.LoadingState
import dev.agentle.feature.insights.ui.SectionTitle
import dev.agentle.feature.insights.ui.StatusLine
import dev.agentle.feature.insights.ui.featureLabel
import dev.agentle.feature.insights.ui.issueText
import dev.agentle.feature.insights.ui.label
import dev.agentle.jitai.dsl.model.QuietHoursPolicy
import dev.agentle.jitai.dsl.validation.IssueCode
import kotlin.math.roundToInt

/** Everything the proposal review can do. */
internal data class ReviewActions(
    val chooseApp: (String, String) -> Unit = { _, _ -> },
    val acknowledge: (String, Boolean) -> Unit = { _, _ -> },
    val activate: () -> Unit = {},
    val reject: (ProposalRejection) -> Unit = {},
    val edit: () -> Unit = {},
    val fix: (AppRoute) -> Unit = {},
    val retry: () -> Unit = {},
)

@Composable
internal fun ProposalReviewRoute(viewModel: ProposalReviewViewModel, navigator: AppNavigator) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectEffects(viewModel.effects, navigator, snackbar)
    ProposalReviewScreen(
        state = state,
        snackbar = snackbar,
        actions = ReviewActions(
            chooseApp = viewModel::chooseApp,
            acknowledge = viewModel::setAcknowledged,
            activate = viewModel::activate,
            reject = viewModel::reject,
            edit = viewModel::edit,
            fix = viewModel::fix,
            retry = viewModel::retry,
        ),
        onBack = navigator::back,
    )
}

@Composable
internal fun ProposalReviewScreen(
    state: ProposalReviewUiState,
    actions: ReviewActions,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onBack: () -> Unit = {},
) {
    InsightsScaffold(stringResource(R.string.review_title), snackbar, onBack) { padding ->
        when (val load = state.load) {
            Load.Loading -> LoadingState(Modifier.padding(padding))

            is Load.Error -> ErrorState(load.error, actions.retry, Modifier.padding(padding))

            is Load.Loaded -> Column(
                Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(Gutter),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ReviewBody(load.value, state, actions)
            }
        }
    }
}

@Composable
private fun ReviewBody(model: ReviewModel, state: ProposalReviewUiState, actions: ReviewActions) {
    Text(model.name, style = MaterialTheme.typography.headlineSmall)
    StatusLine(
        R.drawable.ic_insights_ai,
        stringResource(if (model.origin == ReviewOrigin.DISCOVERED) R.string.review_origin_discovered else R.string.review_origin_nl),
    )
    if (!model.isPending) StatusLine(R.drawable.ic_insights_info, stringResource(R.string.review_not_pending, label(model.state)))
    // The rendering is the app's own sentence (deterministic renderer): it is what the user approves.
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.review_what_it_does), style = MaterialTheme.typography.titleSmall)
            Text(model.rendering, style = MaterialTheme.typography.bodyLarge)
            if (!model.renderingIsFinal) Text(stringResource(R.string.review_rendering_draft), style = MaterialTheme.typography.bodySmall)
        }
    }
    model.before?.let {
        SectionTitle(stringResource(R.string.review_before_after))
        Text(stringResource(R.string.review_before, it))
        Text(stringResource(R.string.review_after, model.rendering))
    }
    AiTexts(model)
    model.evidence?.let { EvidenceTable(it) }
    Settings(model)
    Problems(model, state, actions)
    Buttons(model, state, actions)
}

/** Texts written by the AI: shown as plain, labelled, untrusted text. */
@Composable
private fun AiTexts(model: ReviewModel) {
    model.request?.let { Text(stringResource(R.string.review_you_asked, it)) }
    val aiTexts = listOfNotNull(
        model.interpretation?.let { R.string.review_ai_understood to it },
        model.whyProposed?.let { R.string.review_why to it },
        model.expectedOutcome?.let { R.string.review_expected to it },
        model.description.takeIf { it.isNotBlank() }?.let { R.string.review_description to it },
    )
    if (aiTexts.isEmpty()) return
    SectionTitle(stringResource(R.string.review_ai_texts))
    Text(stringResource(R.string.review_ai_untrusted), style = MaterialTheme.typography.bodySmall)
    aiTexts.forEach { (title, text) ->
        Text(stringResource(title), style = MaterialTheme.typography.labelLarge)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EvidenceTable(evidence: EvidenceSummary) {
    SectionTitle(stringResource(R.string.review_evidence))
    Text(stringResource(R.string.review_evidence_with, evidence.exposedWithOutcome, evidence.exposedNights, percent(evidence.rateExposed)))
    Text(
        stringResource(
            R.string.review_evidence_without,
            evidence.unexposedWithOutcome,
            evidence.unexposedNights,
            percent(evidence.rateUnexposed),
        ),
    )
    if (evidence.rangeLow != null && evidence.rangeHigh != null) {
        Text(stringResource(R.string.review_evidence_range, percent(evidence.rangeLow), percent(evidence.rangeHigh)))
    }
    Text(stringResource(R.string.review_evidence_note), style = MaterialTheme.typography.bodySmall)
}

private fun percent(rate: Double): Int = (rate * 100).roundToInt()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Settings(model: ReviewModel) {
    SectionTitle(stringResource(R.string.review_details))
    model.tier?.let { Text(label(it)) }
    model.kind?.let { Text(stringResource(R.string.review_kind, label(it))) }
    model.channel?.let { Text(stringResource(R.string.review_channel, label(it))) }
    model.quietHours?.let {
        Text(stringResource(if (it == QuietHoursPolicy.RESPECT) R.string.review_quiet_respect else R.string.review_quiet_interactive))
    }
    model.cooldownMinutes?.let { Text(pluralStringResource(R.plurals.review_cooldown, it, it)) }
    model.maxPerDay?.let { Text(pluralStringResource(R.plurals.review_max_day, it, it)) }
    model.maxPerWeek?.let { Text(pluralStringResource(R.plurals.review_max_week, it, it)) }
    model.trialDays?.let { Text(pluralStringResource(R.plurals.review_trial, it, it)) }
    if (model.features.isNotEmpty()) {
        Text(stringResource(R.string.review_features), style = MaterialTheme.typography.labelLarge)
        model.features.forEach { Text("• " + featureLabel(it)) }
    }
    if (model.readCategories.isNotEmpty()) {
        Text(stringResource(R.string.review_reads), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { model.readCategories.forEach { Text(label(it)) } }
    }
    if (model.sourceCategories.isNotEmpty()) {
        Text(stringResource(R.string.review_sources), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { model.sourceCategories.forEach { Text(label(it)) } }
    }
    if (model.content.isNotEmpty()) {
        Text(stringResource(R.string.review_content), style = MaterialTheme.typography.labelLarge)
        model.content.forEach { Text("“${it.text}”") }
        if (model.content.any { it.isPersonal }) {
            Text(stringResource(R.string.review_content_personal), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Problems(model: ReviewModel, state: ProposalReviewUiState, actions: ReviewActions) {
    if (model.errors.isNotEmpty()) {
        SectionTitle(stringResource(R.string.review_errors))
        StatusLine(R.drawable.ic_insights_error, pluralStringResource(R.plurals.review_error_count, model.errorCount, model.errorCount))
        model.errors.forEach { Text("• " + issueText(it)) }
    }
    model.appChoices.forEach { choice ->
        SectionTitle(issueText(IssueCode.C01, mapOf("appLabel" to choice.appLabel)))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choice.candidates.forEach { app ->
                FilterChip(
                    selected = false,
                    onClick = { actions.chooseApp(choice.appLabel, app.packageName) },
                    label = { Text(app.label) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
    if (model.acknowledgeable.isNotEmpty()) {
        SectionTitle(stringResource(R.string.review_confirm))
        model.acknowledgeable.forEach { issue ->
            val checked = issue.reviewKey in state.acknowledged
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                    value = checked,
                    enabled = model.isPending,
                    role = Role.Checkbox,
                    onValueChange = { actions.acknowledge(issue.reviewKey, it) },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Text(issueText(issue), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    if (model.warnings.isNotEmpty()) {
        SectionTitle(stringResource(R.string.review_warnings))
        model.warnings.forEach { issue ->
            StatusLine(R.drawable.ic_insights_warning, issueText(issue))
            if (issue.code == IssueCode.W03) {
                OutlinedButton(onClick = { actions.fix(AppRoute.PermissionCenter()) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.review_allow_access))
                }
            }
        }
    }
}

@Composable
private fun Buttons(model: ReviewModel, state: ProposalReviewUiState, actions: ReviewActions) {
    if (!model.isPending) return
    val trial = model.trialDays
    Button(onClick = actions.activate, enabled = state.canActivate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(if (trial != null) pluralStringResource(R.plurals.review_try_for, trial, trial) else stringResource(R.string.review_turn_on))
    }
    OutlinedButton(onClick = actions.edit, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.review_edit))
    }
    if (model.origin == ReviewOrigin.DISCOVERED) {
        TextButton(
            onClick = { actions.reject(ProposalRejection.NOT_NOW) },
            enabled = !state.busy,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.review_not_now))
        }
        TextButton(
            onClick = { actions.reject(ProposalRejection.NEVER) },
            enabled = !state.busy,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.review_never))
        }
    } else {
        TextButton(
            onClick = { actions.reject(ProposalRejection.DISCARD) },
            enabled = !state.busy,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.review_discard))
        }
    }
}
