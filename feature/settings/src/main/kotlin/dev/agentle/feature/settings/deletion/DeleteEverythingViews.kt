package dev.agentle.feature.settings.deletion

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.agentle.feature.settings.R
import dev.agentle.feature.settings.port.DeleteAllCheck
import dev.agentle.feature.settings.port.DeleteAllCheckItem
import dev.agentle.feature.settings.port.DeleteAllState
import dev.agentle.feature.settings.port.DeleteAllStep
import dev.agentle.feature.settings.port.RemoteDisconnection
import dev.agentle.feature.settings.port.RemoteDisconnectionResult
import dev.agentle.feature.settings.port.RemoteService
import dev.agentle.feature.settings.ui.BodyText
import dev.agentle.feature.settings.ui.BulletList
import dev.agentle.feature.settings.ui.ButtonRow
import dev.agentle.feature.settings.ui.ConfirmDialog
import dev.agentle.feature.settings.ui.NoticeCard
import dev.agentle.feature.settings.ui.RowPadding
import dev.agentle.feature.settings.ui.SectionHeader
import dev.agentle.feature.settings.ui.StatusKind
import dev.agentle.feature.settings.ui.StatusLine

/** True when [typed] is the confirmation word (surrounding spaces and letter case ignored). */
internal fun matchesConfirmationWord(typed: String, word: String): Boolean = typed.trim().equals(word, ignoreCase = true)

/** The "delete everything" entry shown while nothing is being deleted. */
@Composable
internal fun DeleteEverythingEntry(enabled: Boolean, starting: Boolean, onAction: (DeleteDataAction) -> Unit) {
    SectionHeader(stringResource(R.string.settings_delete_all_title))
    BodyText(stringResource(R.string.settings_delete_all_body))
    ButtonRow {
        DestructiveButton(
            text = stringResource(R.string.settings_delete_all_action),
            enabled = enabled,
            onClick = { onAction(DeleteDataAction.RequestDeleteEverything) },
        )
    }
    if (starting) StatusLine(StatusKind.IN_PROGRESS, stringResource(R.string.settings_delete_all_starting))
}

/** The typed confirmation: the button stays disabled until the localized confirmation word is typed. */
@Composable
internal fun DeleteEverythingDialog(onAction: (DeleteDataAction) -> Unit) {
    val word = stringResource(R.string.settings_delete_all_confirm_word)
    var typed by rememberSaveable { mutableStateOf("") }
    ConfirmDialog(
        title = stringResource(R.string.settings_delete_all_confirm_title),
        confirmLabel = stringResource(R.string.settings_delete_all_confirm_action),
        onConfirm = { if (matchesConfirmationWord(typed, word)) onAction(DeleteDataAction.ConfirmDeleteEverything) },
        onDismiss = { onAction(DeleteDataAction.DismissDeleteEverything) },
        destructive = true,
        confirmEnabled = matchesConfirmationWord(typed, word),
    ) {
        Text(stringResource(R.string.settings_delete_all_confirm_body))
        Text(stringResource(R.string.settings_cannot_undo))
        Text(stringResource(R.string.settings_delete_all_confirm_prompt, word))
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text(stringResource(R.string.settings_delete_all_confirm_field)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Progress of "delete everything": the remote-disconnection warning first (it needs the user's action), then the
 * running, failed or verified state with the steps or the verified counts.
 */
@Composable
internal fun DeleteEverythingProgress(state: DeleteAllState, finishing: Boolean, starting: Boolean, onAction: (DeleteDataAction) -> Unit) {
    val remote = when (state) {
        is DeleteAllState.Running -> state.remote
        is DeleteAllState.Failed -> state.remote
        is DeleteAllState.Verified -> state.report.remote
        DeleteAllState.Idle -> emptyList()
    }
    RemoteWarning(remote)
    when (state) {
        is DeleteAllState.Running -> RunningPanel(state)
        is DeleteAllState.Failed -> FailedPanel(state, starting, onAction)
        is DeleteAllState.Verified -> VerifiedPanel(state, finishing, onAction)
        DeleteAllState.Idle -> Unit
    }
    RemoteResults(remote)
}

@Composable
private fun RunningPanel(state: DeleteAllState.Running) {
    val total = DeleteAllStep.entries.size
    NoticeCard(
        kind = StatusKind.IN_PROGRESS,
        title = stringResource(if (state.resumed) R.string.settings_delete_all_resuming else R.string.settings_delete_all_running),
        body = stringResource(if (state.resumed) R.string.settings_delete_all_resuming_body else R.string.settings_delete_all_running_body),
    ) {
        StepProgress(completed = state.completed.size, current = state.step.ordinal + 1, total = total)
    }
    StepList(current = state.step, completed = state.completed, failed = false)
}

@Composable
private fun FailedPanel(state: DeleteAllState.Failed, starting: Boolean, onAction: (DeleteDataAction) -> Unit) {
    NoticeCard(
        kind = StatusKind.ERROR,
        title = stringResource(R.string.settings_delete_all_failed),
        body = stringResource(R.string.settings_delete_all_failed_body, stepLabel(state.step), state.error.code),
    ) {
        ButtonRow {
            DestructiveButton(
                text = stringResource(R.string.settings_delete_all_retry),
                enabled = !starting,
                onClick = { onAction(DeleteDataAction.ConfirmDeleteEverything) },
            )
        }
    }
    StepList(current = state.step, completed = state.completed, failed = true)
}

@Composable
private fun VerifiedPanel(state: DeleteAllState.Verified, finishing: Boolean, onAction: (DeleteDataAction) -> Unit) {
    val report = state.report
    NoticeCard(
        kind = if (report.complete) StatusKind.DONE else StatusKind.WARNING,
        title = stringResource(if (report.complete) R.string.settings_delete_all_verified else R.string.settings_delete_all_left_over),
        body = stringResource(R.string.settings_delete_all_checked),
    ) {
        report.checks.forEach { CheckLine(it) }
    }
    SectionHeader(stringResource(R.string.settings_delete_all_close_title))
    BodyText(stringResource(if (report.complete) R.string.settings_delete_all_close_body else R.string.settings_delete_all_close_body_left_over))
    ButtonRow {
        DestructiveButton(
            text = stringResource(R.string.settings_delete_all_close_action),
            enabled = !finishing,
            onClick = { onAction(DeleteDataAction.FinishDeleteEverything) },
        )
    }
}

/** "Step 4 of 10" with a determinate bar (no indeterminate animation: the steps are the progress). */
@Composable
private fun StepProgress(completed: Int, current: Int, total: Int) {
    LinearProgressIndicator(
        progress = { completed.toFloat() / total },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RowPadding, vertical = 8.dp),
    )
    Text(
        text = stringResource(R.string.settings_delete_all_step_of, current.coerceAtMost(total), total),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = RowPadding, vertical = 4.dp),
    )
}

@Composable
private fun StepList(current: DeleteAllStep, completed: Set<DeleteAllStep>, failed: Boolean) {
    SectionHeader(stringResource(R.string.settings_delete_all_steps))
    DeleteAllStep.entries.forEach { step ->
        val kind = when {
            step in completed -> StatusKind.DONE
            step == current -> if (failed) StatusKind.ERROR else StatusKind.IN_PROGRESS
            else -> StatusKind.PENDING
        }
        StatusLine(kind, stepLabel(step))
    }
}

@Composable
private fun CheckLine(check: DeleteAllCheck) {
    val label = checkLabel(check.item)
    StatusLine(
        kind = if (check.remaining == 0L) StatusKind.DONE else StatusKind.WARNING,
        text = if (check.remaining == 0L) {
            stringResource(R.string.settings_delete_all_check_none_left, label)
        } else {
            stringResource(R.string.settings_delete_all_check_left, label, check.remaining)
        },
    )
}

/** "Remote disconnection could not be confirmed", with the manual steps for each service concerned. */
@Composable
private fun RemoteWarning(remote: List<RemoteDisconnection>) {
    val unconfirmed = remote.filter { it.result == RemoteDisconnectionResult.UNCONFIRMED }.map { it.service }.distinct()
    if (unconfirmed.isEmpty()) return
    NoticeCard(
        kind = StatusKind.WARNING,
        title = stringResource(R.string.settings_delete_all_remote_warning),
        body = stringResource(R.string.settings_delete_all_remote_warning_body),
    ) {
        BulletList(
            unconfirmed.map { service ->
                when (service) {
                    RemoteService.CHATGPT -> stringResource(R.string.settings_delete_all_remote_steps_chatgpt)
                    RemoteService.GOOGLE -> stringResource(R.string.settings_delete_all_remote_steps_google)
                }
            },
        )
    }
}

@Composable
private fun RemoteResults(remote: List<RemoteDisconnection>) {
    if (remote.isEmpty()) return
    SectionHeader(stringResource(R.string.settings_delete_all_remote_title))
    remote.forEach { result ->
        val service = remoteServiceLabel(result.service)
        when (result.result) {
            RemoteDisconnectionResult.DISCONNECTED ->
                StatusLine(StatusKind.DONE, stringResource(R.string.settings_delete_all_remote_disconnected, service))

            RemoteDisconnectionResult.NOT_CONNECTED ->
                StatusLine(StatusKind.OFF, stringResource(R.string.settings_delete_all_remote_not_connected, service))

            RemoteDisconnectionResult.UNCONFIRMED ->
                StatusLine(StatusKind.WARNING, stringResource(R.string.settings_delete_all_remote_unconfirmed, service))
        }
    }
}

@Composable
private fun DestructiveButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Text(text)
    }
}

@Composable
private fun stepLabel(step: DeleteAllStep): String = stringResource(
    when (step) {
        DeleteAllStep.WRITE_MARKER -> R.string.settings_delete_step_marker
        DeleteAllStep.STOP_PRODUCERS -> R.string.settings_delete_step_stop
        DeleteAllStep.REVOKE_REMOTE -> R.string.settings_delete_step_revoke
        DeleteAllStep.DRAIN_WRITERS -> R.string.settings_delete_step_drain
        DeleteAllStep.CLOSE_DATABASE -> R.string.settings_delete_step_close_database
        DeleteAllStep.DELETE_DATABASE -> R.string.settings_delete_step_delete_database
        DeleteAllStep.DELETE_KEYS -> R.string.settings_delete_step_keys
        DeleteAllStep.DELETE_FILES -> R.string.settings_delete_step_files
        DeleteAllStep.VERIFY -> R.string.settings_delete_step_verify
        DeleteAllStep.CLEAR_APP_DATA -> R.string.settings_delete_step_clear
    },
)

@Composable
private fun checkLabel(item: DeleteAllCheckItem): String = stringResource(
    when (item) {
        DeleteAllCheckItem.DATABASE_FILES -> R.string.settings_delete_check_database
        DeleteAllCheckItem.ENCRYPTION_KEYS -> R.string.settings_delete_check_keys
        DeleteAllCheckItem.SIGN_IN_CREDENTIALS -> R.string.settings_delete_check_credentials
        DeleteAllCheckItem.MEDIA_FILES -> R.string.settings_delete_check_media
        DeleteAllCheckItem.SETTINGS_FILES -> R.string.settings_delete_check_settings
        DeleteAllCheckItem.SCHEDULED_WORK -> R.string.settings_delete_check_work
    },
)

@Composable
private fun remoteServiceLabel(service: RemoteService): String = stringResource(
    when (service) {
        RemoteService.CHATGPT -> R.string.settings_remote_chatgpt
        RemoteService.GOOGLE -> R.string.settings_remote_google
    },
)
