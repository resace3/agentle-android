package dev.agentle.feature.insights.builder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.feature.insights.R
import dev.agentle.feature.insights.builder.NlBuilderUiState
import dev.agentle.feature.insights.builder.NlBuilderViewModel
import dev.agentle.feature.insights.builder.NlPhase
import dev.agentle.feature.insights.ui.CollectEffects
import dev.agentle.feature.insights.ui.Gutter
import dev.agentle.feature.insights.ui.InsightsScaffold
import dev.agentle.feature.insights.ui.SectionTitle
import dev.agentle.feature.insights.ui.StatusLine
import dev.agentle.feature.insights.ui.issueText
import dev.agentle.feature.insights.ui.label
import dev.agentle.jitai.dsl.nl.QuestionId

/** Everything the natural-language builder can do. */
internal data class NlActions(
    val updateText: (String) -> Unit = {},
    val submit: () -> Unit = {},
    val answer: (QuestionId, String) -> Unit = { _, _ -> },
    val submitAnswers: () -> Unit = {},
    val editManually: () -> Unit = {},
    val startOver: () -> Unit = {},
    val fix: (AppRoute) -> Unit = {},
)

@Composable
internal fun NlBuilderRoute(viewModel: NlBuilderViewModel, navigator: AppNavigator) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectEffects(viewModel.effects, navigator, snackbar)
    NlBuilderScreen(
        state = state,
        snackbar = snackbar,
        actions = NlActions(
            updateText = viewModel::updateText,
            submit = viewModel::submit,
            answer = viewModel::answer,
            submitAnswers = viewModel::submitAnswers,
            editManually = viewModel::editManually,
            startOver = viewModel::startOver,
            fix = viewModel::fix,
        ),
        onBack = navigator::back,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NlBuilderScreen(
    state: NlBuilderUiState,
    actions: NlActions,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onBack: () -> Unit = {},
) {
    InsightsScaffold(stringResource(R.string.nl_title), snackbar, onBack) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(Gutter),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.nl_intro), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.nl_privacy), style = MaterialTheme.typography.bodySmall)
            val editable = state.phase is NlPhase.Editing || state.phase is NlPhase.Problem
            OutlinedTextField(
                value = state.text,
                onValueChange = actions.updateText,
                label = { Text(stringResource(R.string.nl_request_label)) },
                placeholder = { Text(stringResource(R.string.nl_request_hint)) },
                enabled = editable,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            if (editable) {
                Button(onClick = actions.submit, enabled = state.canSubmit, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.nl_submit))
                }
            }
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PhaseContent(state.phase, actions)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhaseContent(phase: NlPhase, actions: NlActions) {
    when (phase) {
        NlPhase.Editing -> Unit

        NlPhase.Converting -> {
            CircularProgressIndicator()
            Text(stringResource(R.string.nl_converting))
        }

        is NlPhase.Clarifying -> {
            SectionTitle(stringResource(R.string.nl_questions))
            phase.questions.forEach { question ->
                // Question text comes from the model: shown as plain text only.
                Text(question.text, style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    question.options.forEach { option ->
                        FilterChip(
                            selected = phase.answers[question.id] == option,
                            onClick = { actions.answer(question.id, option) },
                            label = { Text(option) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = phase.answers[question.id].orEmpty(),
                    onValueChange = { actions.answer(question.id, it) },
                    label = { Text(stringResource(R.string.nl_answer_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(onClick = actions.submitAnswers, enabled = phase.complete, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.nl_send_answers))
            }
            OutlinedButton(
                onClick = actions.startOver,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.nl_start_over)) }
        }

        is NlPhase.Refused -> {
            StatusLine(R.drawable.ic_insights_info, stringResource(R.string.nl_refused_title))
            Text(label(phase.reason))
            phase.detail?.let { Text(stringResource(R.string.nl_ai_said, it), style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(
                onClick = actions.startOver,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.nl_start_over)) }
        }

        is NlPhase.Invalid -> {
            StatusLine(R.drawable.ic_insights_error, stringResource(R.string.nl_invalid_title))
            phase.problems.forEach { Text("• " + issueText(it)) }
            if (phase.draftId != null) {
                Button(
                    onClick = actions.editManually,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.nl_edit_manually)) }
            }
            OutlinedButton(
                onClick = actions.startOver,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.nl_start_over)) }
        }

        is NlPhase.Problem -> {
            StatusLine(R.drawable.ic_insights_warning, label(phase.problem))
            phase.problem.fix?.let { route ->
                Button(onClick = { actions.fix(route) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.insights_fix))
                }
            }
        }
    }
}
