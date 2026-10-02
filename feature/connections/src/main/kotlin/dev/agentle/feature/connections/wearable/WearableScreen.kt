package dev.agentle.feature.connections.wearable

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.agentle.core.model.Blocker
import dev.agentle.core.model.PermissionState
import dev.agentle.core.model.SyncStatus
import dev.agentle.feature.connections.R
import dev.agentle.feature.connections.port.WearableAccountProblem
import dev.agentle.feature.connections.port.WearableDataAccess
import dev.agentle.feature.connections.ui.BusyRow
import dev.agentle.feature.connections.ui.ChoiceRow
import dev.agentle.feature.connections.ui.ConnectionsScaffold
import dev.agentle.feature.connections.ui.LabelValueRow
import dev.agentle.feature.connections.ui.LoadFailedContent
import dev.agentle.feature.connections.ui.LoadingContent
import dev.agentle.feature.connections.ui.NoticeCard
import dev.agentle.feature.connections.ui.PrimaryAction
import dev.agentle.feature.connections.ui.SecondaryAction
import dev.agentle.feature.connections.ui.SectionHeader
import dev.agentle.feature.connections.ui.StatusCard
import dev.agentle.feature.connections.ui.StatusRow
import dev.agentle.feature.connections.ui.Tone
import dev.agentle.feature.connections.ui.errorCodeText
import dev.agentle.feature.connections.ui.rememberInstantFormatter
import dev.agentle.feature.connections.ui.wearableTypeLabel

/**
 * The Wearable route: collects the state, launches Google's consent screen while the screen is visible and hands the
 * result back (also after the process was recreated: the launcher's registration is saved with the screen).
 */
@Composable
internal fun WearableRoute(onBack: () -> Unit, viewModel: WearableViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onAction(WearableAction.ConsentResult(result.resultCode, result.data))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is WearableEffect.LaunchConsent -> launchConsent(launcher, effect.pendingIntent, viewModel::onAction)
                }
            }
        }
    }
    WearableScreen(state = state, onAction = viewModel::onAction, onBack = onBack)
}

private fun launchConsent(
    launcher: ActivityResultLauncher<IntentSenderRequest>,
    pendingIntent: PendingIntent,
    onAction: (WearableAction) -> Unit,
) {
    try {
        launcher.launch(IntentSenderRequest.Builder(pendingIntent).build())
    } catch (expected: IllegalStateException) {
        onAction(WearableAction.ConsentLaunchFailed)
    } catch (expected: ActivityNotFoundException) {
        onAction(WearableAction.ConsentLaunchFailed)
    }
}

/** The stateless Wearable screen (the spec's "Fitbit" screen, backed by the Google Health API). */
@Composable
internal fun WearableScreen(state: WearableUiState, onAction: (WearableAction) -> Unit, onBack: () -> Unit) {
    ConnectionsScaffold(title = stringResource(R.string.connections_wearable_title), onBack = onBack) {
        Text(
            text = stringResource(R.string.connections_wearable_source),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.notice?.let { notice ->
            val (text, tone) = wearableNoticeText(notice)
            NoticeCard(text = text, tone = tone, onDismiss = { onAction(WearableAction.DismissNotice) })
        }
        when (state.phase) {
            WearablePhase.LOADING -> LoadingContent()
            WearablePhase.LOAD_FAILED -> LoadFailedContent(onRetry = { onAction(WearableAction.Retry) })
            WearablePhase.ACCOUNT_PROBLEM -> AccountProblemCard(state, onAction)
            else -> WearableStatusCard(state, onAction)
        }
        if (state.bound) {
            DataAccessSection(state, onAction)
            SyncSection(state, onAction)
            SecondaryAction(
                text = stringResource(R.string.connections_wearable_disconnect),
                onClick = { onAction(WearableAction.RequestDisconnect) },
                enabled = state.canDisconnect,
            )
        }
    }
    state.disconnectDialog?.let { DisconnectDialog(it, onAction) }
}

@Composable
private fun WearableStatusCard(state: WearableUiState, onAction: (WearableAction) -> Unit) {
    when (state.phase) {
        WearablePhase.NOT_AVAILABLE -> StatusCard(
            icon = R.drawable.connections_ic_block,
            tone = Tone.NEUTRAL,
            title = stringResource(R.string.connections_wearable_unavailable_title),
            body = stringResource(unavailableBody(state.unavailableReason)),
        )
        WearablePhase.NOT_CONNECTED -> StatusCard(
            icon = R.drawable.connections_ic_info,
            tone = Tone.NEUTRAL,
            title = stringResource(R.string.connections_wearable_not_connected_title),
            body = stringResource(R.string.connections_wearable_not_connected_body),
        ) {
            PrimaryAction(
                text = stringResource(R.string.connections_wearable_connect),
                onClick = { onAction(WearableAction.Connect) },
                enabled = state.busy == null,
            )
        }
        WearablePhase.CONNECTING -> StatusCard(
            icon = R.drawable.connections_ic_hourglass,
            tone = Tone.NEUTRAL,
            title = stringResource(R.string.connections_wearable_connecting_title),
        ) {
            val waitingForConsent = state.busy == WearableBusy.AWAITING_CONSENT
            BusyRow(
                text = stringResource(
                    if (waitingForConsent) {
                        R.string.connections_wearable_awaiting_consent_body
                    } else {
                        R.string.connections_wearable_connecting_body
                    },
                ),
            )
        }
        WearablePhase.CONNECTED -> StatusCard(
            icon = R.drawable.connections_ic_check_circle,
            tone = Tone.POSITIVE,
            title = stringResource(R.string.connections_wearable_connected_title),
        ) { AccountLine(state.accountLabel) }
        WearablePhase.NEEDS_REAUTH -> ReconnectCard(
            icon = R.drawable.connections_ic_warning,
            tone = Tone.WARNING,
            title = stringResource(R.string.connections_wearable_needs_reauth_title),
            body = stringResource(R.string.connections_wearable_needs_reauth_body),
            state = state,
            onAction = onAction,
        )
        WearablePhase.CONNECTION_ERROR -> ReconnectCard(
            icon = R.drawable.connections_ic_error,
            tone = Tone.ERROR,
            title = stringResource(R.string.connections_wearable_error_title),
            body = state.sync?.errorCode?.let { stringResource(R.string.connections_wearable_error_body, errorCodeText(it)) }
                ?: stringResource(R.string.connections_wearable_error_body_no_code),
            state = state,
            onAction = onAction,
        )
        WearablePhase.PAUSED -> StatusCard(
            icon = R.drawable.connections_ic_info,
            tone = Tone.NEUTRAL,
            title = stringResource(R.string.connections_wearable_paused_title),
            body = stringResource(R.string.connections_wearable_paused_body),
        ) { AccountLine(state.accountLabel) }
        WearablePhase.LOADING, WearablePhase.LOAD_FAILED, WearablePhase.ACCOUNT_PROBLEM -> Unit
    }
}

private fun unavailableBody(reason: Blocker?): Int =
    if (reason == Blocker.PLAY_SERVICES_MISSING) {
        R.string.connections_wearable_unavailable_play_services_body
    } else {
        R.string.connections_wearable_unavailable_flag_body
    }

@Composable
private fun AccountLine(accountLabel: String?) {
    if (accountLabel != null) {
        Text(text = stringResource(R.string.connections_wearable_account, accountLabel), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ReconnectCard(
    icon: Int,
    tone: Tone,
    title: String,
    body: String,
    state: WearableUiState,
    onAction: (WearableAction) -> Unit,
) {
    StatusCard(icon = icon, tone = tone, title = title, body = body) {
        AccountLine(state.accountLabel)
        PrimaryAction(
            text = stringResource(R.string.connections_wearable_reconnect),
            onClick = { onAction(WearableAction.Reconnect) },
            enabled = state.busy == null,
        )
    }
}

@Composable
private fun AccountProblemCard(state: WearableUiState, onAction: (WearableAction) -> Unit) {
    val problem = state.accountProblem ?: return
    val (title, body) = when (problem) {
        is WearableAccountProblem.NotLinked ->
            R.string.connections_wearable_problem_not_linked_title to R.string.connections_wearable_problem_not_linked_body
        WearableAccountProblem.ProfileNotReady ->
            R.string.connections_wearable_problem_profile_title to R.string.connections_wearable_problem_profile_body
        WearableAccountProblem.LegacyFitbitAccount ->
            R.string.connections_wearable_problem_legacy_title to R.string.connections_wearable_problem_legacy_body
        WearableAccountProblem.AccountChanged ->
            R.string.connections_wearable_problem_account_changed_title to R.string.connections_wearable_problem_account_changed_body
    }
    val uriHandler = LocalUriHandler.current
    StatusCard(
        icon = R.drawable.connections_ic_warning,
        tone = Tone.WARNING,
        title = stringResource(title),
        body = stringResource(body),
    ) {
        AccountLine(state.accountLabel)
        val link = (problem as? WearableAccountProblem.NotLinked)?.actionUrl
        if (link != null) {
            SecondaryAction(
                text = stringResource(R.string.connections_wearable_problem_open_link),
                onClick = {
                    try {
                        uriHandler.openUri(link)
                    } catch (expected: IllegalArgumentException) {
                        // No app can open the link; the text above still says what to do.
                    }
                },
            )
        }
        PrimaryAction(
            text = stringResource(R.string.connections_wearable_reconnect),
            onClick = { onAction(WearableAction.Reconnect) },
            enabled = state.busy == null,
        )
    }
}

@Composable
private fun DataAccessSection(state: WearableUiState, onAction: (WearableAction) -> Unit) {
    SectionHeader(text = stringResource(R.string.connections_wearable_access_header))
    Text(
        text = pluralStringResource(
            R.plurals.connections_wearable_access_summary,
            state.dataAccess.size,
            state.sharedCount,
            state.dataAccess.size,
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    Column {
        state.dataAccess.forEach { access -> DataAccessRow(access) }
    }
    if (state.sharedCount < state.dataAccess.size) {
        Text(text = stringResource(R.string.connections_wearable_access_partial_body), style = MaterialTheme.typography.bodyMedium)
        SecondaryAction(
            text = stringResource(R.string.connections_wearable_grant_more),
            onClick = { onAction(WearableAction.GrantMore) },
            enabled = state.busy == null && state.phase == WearablePhase.CONNECTED,
        )
    }
}

@Composable
private fun DataAccessRow(access: WearableDataAccess) {
    val shared = access.permission == PermissionState.ALLOWED
    LabelValueRow(
        icon = if (shared) R.drawable.connections_ic_check_circle else R.drawable.connections_ic_remove_circle,
        label = stringResource(wearableTypeLabel(access.type)),
        value = stringResource(
            if (shared) R.string.connections_wearable_access_shared else R.string.connections_wearable_access_not_shared,
        ),
        tone = if (shared) Tone.POSITIVE else Tone.NEUTRAL,
    )
}

@Composable
private fun SyncSection(state: WearableUiState, onAction: (WearableAction) -> Unit) {
    val sync = state.sync ?: return
    val formatter = rememberInstantFormatter(state.zone)
    SectionHeader(text = stringResource(R.string.connections_wearable_sync_header))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.syncRunning) {
            BusyRow(text = stringResource(R.string.connections_wearable_sync_running))
        } else {
            SyncStatusRow(sync)
        }
        Text(
            text = sync.lastSuccess?.let { stringResource(R.string.connections_wearable_last_sync, formatter.format(it)) }
                ?: stringResource(R.string.connections_wearable_last_sync_never),
            style = MaterialTheme.typography.bodyMedium,
        )
        val lastAttempt = sync.lastAttempt
        if (lastAttempt != null && lastAttempt != sync.lastSuccess) {
            Text(
                text = stringResource(R.string.connections_wearable_last_attempt, formatter.format(lastAttempt)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        state.rateLimitedUntil?.let { until ->
            StatusRow(
                icon = R.drawable.connections_ic_schedule,
                text = stringResource(R.string.connections_wearable_rate_limited, formatter.format(until)),
                tone = Tone.WARNING,
            )
        }
        if (state.offline) {
            StatusRow(
                icon = R.drawable.connections_ic_cloud_off,
                text = stringResource(R.string.connections_wearable_offline),
                tone = Tone.WARNING,
            )
        }
    }
    PrimaryAction(
        text = stringResource(R.string.connections_wearable_sync_now),
        onClick = { onAction(WearableAction.SyncNow) },
        enabled = state.canSync,
    )
}

@Composable
private fun SyncStatusRow(sync: WearableSyncUi) {
    when (sync.state) {
        SyncStatus.RUNNING -> BusyRow(text = stringResource(R.string.connections_wearable_sync_running))
        SyncStatus.SUCCEEDED -> StatusRow(
            icon = R.drawable.connections_ic_check_circle,
            text = stringResource(R.string.connections_wearable_sync_succeeded),
            tone = Tone.POSITIVE,
        )
        SyncStatus.PARTIAL -> StatusRow(
            icon = R.drawable.connections_ic_warning,
            text = stringResource(R.string.connections_wearable_sync_partial),
            tone = Tone.WARNING,
        )
        SyncStatus.FAILED -> StatusRow(
            icon = R.drawable.connections_ic_error,
            text = sync.errorCode?.let { stringResource(R.string.connections_wearable_sync_failed, errorCodeText(it)) }
                ?: stringResource(R.string.connections_wearable_sync_failed_no_code),
            tone = Tone.ERROR,
        )
        SyncStatus.IDLE -> StatusRow(
            icon = R.drawable.connections_ic_sync,
            text = stringResource(R.string.connections_wearable_sync_idle),
            tone = Tone.NEUTRAL,
        )
    }
}

@Composable
private fun DisconnectDialog(dialog: WearableDisconnectDialog, onAction: (WearableAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(WearableAction.DismissDisconnect) },
        title = { Text(text = stringResource(R.string.connections_wearable_disconnect_title)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = stringResource(R.string.connections_wearable_disconnect_body))
                ChoiceRow(
                    selected = !dialog.deleteData,
                    text = stringResource(R.string.connections_wearable_disconnect_keep),
                    onClick = { onAction(WearableAction.SetDeleteData(false)) },
                )
                ChoiceRow(
                    selected = dialog.deleteData,
                    text = stringResource(R.string.connections_wearable_disconnect_delete),
                    onClick = { onAction(WearableAction.SetDeleteData(true)) },
                )
                Text(
                    text = stringResource(R.string.connections_wearable_disconnect_delete_note),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(WearableAction.ConfirmDisconnect) }) {
                Text(text = stringResource(R.string.connections_wearable_disconnect_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(WearableAction.DismissDisconnect) }) {
                Text(text = stringResource(R.string.connections_cancel))
            }
        },
    )
}
