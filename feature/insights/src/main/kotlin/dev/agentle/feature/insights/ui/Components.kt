@file:OptIn(ExperimentalMaterial3Api::class)

package dev.agentle.feature.insights.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.feature.insights.R
import dev.agentle.feature.insights.common.LoadError
import dev.agentle.feature.insights.common.ScreenEffect
import dev.agentle.feature.insights.common.UserMessage
import dev.agentle.jitai.dsl.validation.ValidationIssue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

internal val Gutter = 16.dp

/** Screen frame: title (a heading), optional back button, snackbars, content padding. */
@Composable
internal fun InsightsScaffold(
    title: String,
    snackbar: SnackbarHostState,
    onBack: (() -> Unit)?,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_insights_back), contentDescription = stringResource(R.string.insights_back))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        content = content,
    )
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(top = 8.dp).semantics { heading() },
    )
}

@Composable
internal fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(Gutter), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** An empty or problem state: heading, explanation and the one action that fixes it. */
@Composable
internal fun MessageState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Column(modifier.fillMaxWidth().padding(Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(message, style = MaterialTheme.typography.bodyMedium)
        if (action != null) Button(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
    }
}

@Composable
internal fun ErrorState(error: LoadError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    if (error.code == LoadError.NOT_FOUND) {
        MessageState(stringResource(R.string.insights_not_found_title), stringResource(R.string.insights_not_found_message), modifier)
    } else {
        MessageState(
            title = stringResource(R.string.insights_error_title),
            message = stringResource(R.string.insights_error_message, error.code),
            modifier = modifier,
            action = stringResource(R.string.insights_retry).takeIf { error.retryable },
            onAction = onRetry,
        )
    }
}

/** Status shown as an icon plus text, never color alone. */
@Composable
internal fun StatusLine(@DrawableRes icon: Int, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A labelled choice from [options] in a dropdown menu. */
@Composable
internal fun <T> Picker(
    label: String,
    selected: T,
    options: List<T>,
    text: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("$label: ${text(selected)}", modifier = Modifier.fillMaxWidth())
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(text(option)) }, onClick = {
                    open = false
                    onSelect(option)
                })
            }
        }
    }
}

/** The localized message of a validator issue (template chosen by the params the issue carries). */
@Composable
internal fun issueText(code: dev.agentle.jitai.dsl.validation.IssueCode, params: Map<String, String>): String {
    val texts = ISSUE_TEXTS[code].orEmpty()
    val text = texts.firstOrNull { it.params.all(params::containsKey) } ?: texts.firstOrNull()
        ?: return code.name
    return stringResource(text.resId, *text.params.map { params[it].orEmpty() }.toTypedArray())
}

@Composable
internal fun issueText(issue: ValidationIssue): String = issueText(issue.code, issue.params)

@StringRes
internal fun UserMessage.text(): Int = when (this) {
    UserMessage.PAUSED -> R.string.insights_msg_paused
    UserMessage.RULE_EXPIRED -> R.string.insights_msg_rule_expired
    UserMessage.APPROVAL_REQUIRED -> R.string.insights_msg_approval_required
    UserMessage.RESUMED -> R.string.insights_msg_resumed
    UserMessage.DISABLED -> R.string.insights_msg_disabled
    UserMessage.ACTION_FAILED -> R.string.insights_msg_action_failed
    UserMessage.RESUME_BLOCKED -> R.string.insights_msg_resume_blocked
    UserMessage.SAVED_AS_DRAFT -> R.string.insights_msg_saved_draft
    UserMessage.ACTIVATED -> R.string.insights_msg_activated
    UserMessage.SAVE_FAILED -> R.string.insights_msg_save_failed
    UserMessage.SAVE_BLOCKED -> R.string.insights_msg_save_blocked
    UserMessage.FEEDBACK_SAVED -> R.string.insights_msg_feedback_saved
    UserMessage.FEEDBACK_FAILED -> R.string.insights_msg_feedback_failed
    UserMessage.PROPOSAL_REJECTED -> R.string.insights_msg_rejected
    UserMessage.PROPOSAL_ALREADY_HANDLED -> R.string.insights_msg_already_handled
}

/** Applies a ViewModel's one-off effects: navigation through [navigator], messages as snackbars. */
@Composable
internal fun CollectEffects(effects: Flow<ScreenEffect>, navigator: AppNavigator, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                is ScreenEffect.Navigate -> navigator.navigate(effect.route)
                ScreenEffect.Back -> navigator.back()
                is ScreenEffect.Message -> launch { snackbar.showSnackbar(context.getString(effect.message.text())) }
            }
        }
    }
}
