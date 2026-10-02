package dev.agentle.feature.connections.chatgpt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentle.ai.api.CapabilitySupport
import dev.agentle.feature.connections.R
import dev.agentle.feature.connections.port.ChatGptConnectResult
import dev.agentle.feature.connections.port.PlanUsageAvailability
import dev.agentle.feature.connections.ui.AiRequestSummary
import dev.agentle.feature.connections.ui.BusyRow
import dev.agentle.feature.connections.ui.CheckRow
import dev.agentle.feature.connections.ui.ConnectionsScaffold
import dev.agentle.feature.connections.ui.InstantFormatter
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
import dev.agentle.feature.connections.ui.capabilityLabel
import dev.agentle.feature.connections.ui.rememberInstantFormatter
import dev.agentle.feature.connections.ui.supportLabel

/** OpenAI's page where users see and manage their plan usage (docs/research/06 §6). */
internal const val MANAGE_USAGE_URL: String = "https://chatgpt.com/settings/usage"

@Composable
internal fun ChatGptRoute(onBack: () -> Unit, onOpenAiDataSharing: () -> Unit, viewModel: ChatGptViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ChatGptScreen(state = state, onAction = viewModel::onAction, onBack = onBack, onOpenAiDataSharing = onOpenAiDataSharing)
}

/** The stateless ChatGPT screen (Sign in with ChatGPT). */
@Composable
internal fun ChatGptScreen(state: ChatGptUiState, onAction: (ChatGptAction) -> Unit, onBack: () -> Unit, onOpenAiDataSharing: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val openUsage = { openLink(uriHandler, MANAGE_USAGE_URL) }
    val formatter = rememberInstantFormatter(state.zone)
    ConnectionsScaffold(title = stringResource(R.string.connections_chatgpt_title), onBack = onBack) {
        state.notice?.let { ChatGptNoticeCard(it, onAction) }
        when (state.phase) {
            ChatGptPhase.LOADING -> LoadingContent()
            ChatGptPhase.LOAD_FAILED -> LoadFailedContent(onRetry = { onAction(ChatGptAction.Retry) })
            else -> ChatGptStatusCard(state, formatter, onAction, openUsage)
        }
        if (state.bound) CapabilitiesSection(state, formatter)
        if (state.phase !in HIDDEN_DETAIL_PHASES) {
            LastRequestSection(state, formatter)
            AiDataSharingLink(onOpenAiDataSharing)
        }
        if (state.bound) {
            SecondaryAction(
                text = stringResource(R.string.connections_chatgpt_disconnect),
                onClick = { onAction(ChatGptAction.RequestDisconnect) },
                enabled = state.canDisconnect,
            )
        }
    }
    if (state.showPlanNotice) PlanNoticeDialog(onAction, openUsage)
    state.disconnectDialog?.let { DisconnectDialog(it, onAction) }
}

private val HIDDEN_DETAIL_PHASES = setOf(ChatGptPhase.LOADING, ChatGptPhase.LOAD_FAILED, ChatGptPhase.NOT_AVAILABLE)

private fun openLink(uriHandler: UriHandler, url: String) {
    try {
        uriHandler.openUri(url)
    } catch (expected: IllegalArgumentException) {
        // No app can open the link (no browser); nothing else to do from here.
    }
}

@Composable
private fun ChatGptNoticeCard(notice: ChatGptNotice, onAction: (ChatGptAction) -> Unit) {
    val (text, tone) = chatGptNoticeText(notice)
    val mismatch = (notice as? ChatGptNotice.SignIn)?.result == ChatGptConnectResult.AccountMismatch
    NoticeCard(
        text = text,
        tone = tone,
        onDismiss = { onAction(ChatGptAction.DismissNotice) },
        action = if (mismatch) {
            {
                TextButton(onClick = { onAction(ChatGptAction.UseDifferentAccount) }) {
                    Text(text = stringResource(R.string.connections_chatgpt_use_new_account))
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun ChatGptStatusCard(
    state: ChatGptUiState,
    formatter: InstantFormatter,
    onAction: (ChatGptAction) -> Unit,
    openUsage: () -> Unit,
) {
    when (state.phase) {
        ChatGptPhase.NOT_AVAILABLE -> StatusCard(
            icon = R.drawable.connections_ic_block,
            tone = Tone.NEUTRAL,
            title = stringResource(R.string.connections_chatgpt_not_available_title),
            body = stringResource(R.string.connections_chatgpt_not_available_body),
        )

        ChatGptPhase.DISCONNECTED -> StatusCard(
            icon = R.drawable.connections_ic_info,
            tone = Tone.NEUTRAL,
            title = stringResource(R.string.connections_chatgpt_disconnected_title),
            body = stringResource(R.string.connections_chatgpt_disconnected_body),
        ) {
            StatusRow(
                icon = R.drawable.connections_ic_remove_circle,
                text = stringResource(R.string.connections_chatgpt_not_connected),
                tone = Tone.NEUTRAL,
            )
            PrimaryAction(
                text = stringResource(R.string.connections_chatgpt_connect),
                onClick = { onAction(ChatGptAction.Connect) },
                enabled = state.busy == null,
            )
        }

        ChatGptPhase.WAITING_FOR_BROWSER -> WaitingCard(onAction)

        ChatGptPhase.CONNECTED -> ConnectedCard(state, openUsage)

        ChatGptPhase.NEEDS_REAUTH -> StatusCard(
            icon = R.drawable.connections_ic_warning,
            tone = Tone.WARNING,
            title = stringResource(R.string.connections_chatgpt_needs_reauth_title),
            body = stringResource(R.string.connections_chatgpt_needs_reauth_body),
        ) {
            PrimaryAction(
                text = stringResource(R.string.connections_chatgpt_reconnect),
                onClick = { onAction(ChatGptAction.Connect) },
                enabled = state.busy == null,
            )
        }

        ChatGptPhase.NOT_ELIGIBLE -> NotEligibleCard(state, onAction, openUsage)

        ChatGptPhase.USAGE_LIMITED -> StatusCard(
            icon = R.drawable.connections_ic_schedule,
            tone = Tone.WARNING,
            title = stringResource(R.string.connections_chatgpt_usage_limited_title),
            body = state.usageLimitedUntil?.let { stringResource(R.string.connections_chatgpt_usage_limited_until, formatter.format(it)) }
                ?: stringResource(R.string.connections_chatgpt_usage_limited_unknown),
        ) {
            PrimaryAction(text = stringResource(R.string.connections_chatgpt_manage_usage), onClick = openUsage)
        }

        ChatGptPhase.PROVIDER_UNAVAILABLE -> StatusCard(
            icon = R.drawable.connections_ic_cloud_off,
            tone = Tone.WARNING,
            title = stringResource(R.string.connections_chatgpt_unavailable_title),
            body = stringResource(R.string.connections_chatgpt_unavailable_body, state.providerReason.orEmpty()),
        )

        ChatGptPhase.LOADING, ChatGptPhase.LOAD_FAILED -> Unit
    }
}

@Composable
private fun WaitingCard(onAction: (ChatGptAction) -> Unit) {
    StatusCard(
        icon = R.drawable.connections_ic_hourglass,
        tone = Tone.NEUTRAL,
        title = stringResource(R.string.connections_chatgpt_waiting_title),
    ) {
        BusyRow(text = stringResource(R.string.connections_chatgpt_waiting_body))
        SecondaryAction(text = stringResource(R.string.connections_chatgpt_reopen), onClick = { onAction(ChatGptAction.Connect) })
        SecondaryAction(
            text = stringResource(R.string.connections_chatgpt_cancel_sign_in),
            onClick = { onAction(ChatGptAction.CancelSignIn) },
        )
    }
}

@Composable
private fun ConnectedCard(state: ChatGptUiState, openUsage: () -> Unit) {
    StatusCard(
        icon = R.drawable.connections_ic_check_circle,
        tone = Tone.POSITIVE,
        title = stringResource(R.string.connections_chatgpt_connected_title),
    ) {
        state.accountLabel?.let { Text(text = stringResource(R.string.connections_chatgpt_account, it)) }
        state.model?.let { Text(text = stringResource(R.string.connections_chatgpt_model, it)) }
        if (state.planUsage == PlanUsageAvailability.Available) {
            StatusRow(
                icon = R.drawable.connections_ic_check_circle,
                text = stringResource(R.string.connections_chatgpt_using_plan),
                tone = Tone.POSITIVE,
            )
        }
        ManageUsageLink(openUsage)
    }
}

@Composable
private fun NotEligibleCard(state: ChatGptUiState, onAction: (ChatGptAction) -> Unit, openUsage: () -> Unit) {
    if (state.planUsageDeclined) {
        StatusCard(
            icon = R.drawable.connections_ic_warning,
            tone = Tone.WARNING,
            title = stringResource(R.string.connections_chatgpt_plan_declined_title),
            body = stringResource(R.string.connections_chatgpt_plan_declined_body),
        ) {
            PrimaryAction(
                text = stringResource(R.string.connections_chatgpt_use_plan),
                onClick = { onAction(ChatGptAction.UsePlan) },
                enabled = state.busy == null,
            )
        }
    } else {
        StatusCard(
            icon = R.drawable.connections_ic_block,
            tone = Tone.WARNING,
            title = stringResource(R.string.connections_chatgpt_not_eligible_title),
            body = stringResource(R.string.connections_chatgpt_not_eligible_body, state.providerReason.orEmpty()),
        ) { ManageUsageLink(openUsage) }
    }
}

@Composable
private fun ManageUsageLink(openUsage: () -> Unit) {
    TextButton(onClick = openUsage, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(text = stringResource(R.string.connections_chatgpt_manage_usage))
        Icon(
            painter = painterResource(R.drawable.connections_ic_open_in_new),
            contentDescription = null,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun CapabilitiesSection(state: ChatGptUiState, formatter: InstantFormatter) {
    SectionHeader(text = stringResource(R.string.connections_chatgpt_capabilities_header))
    Column {
        val rows = state.capabilities
        if (rows == null) {
            Text(text = stringResource(R.string.connections_chatgpt_capabilities_unknown), style = MaterialTheme.typography.bodyMedium)
        } else {
            rows.forEach { row ->
                val available = row.support != CapabilitySupport.UNSUPPORTED
                LabelValueRow(
                    icon = if (available) R.drawable.connections_ic_check_circle else R.drawable.connections_ic_remove_circle,
                    label = stringResource(capabilityLabel(row.capability)),
                    value = stringResource(supportLabel(row.support)),
                    tone = if (available) Tone.POSITIVE else Tone.NEUTRAL,
                )
            }
        }
        val (planIcon, planTone) = planUsageLook(state.planUsage)
        LabelValueRow(
            icon = planIcon,
            label = stringResource(R.string.connections_chatgpt_plan_usage_label),
            value = planUsageText(state.planUsage, formatter),
            tone = planTone,
        )
    }
}

private fun planUsageLook(planUsage: PlanUsageAvailability): Pair<Int, Tone> = when (planUsage) {
    PlanUsageAvailability.Unknown -> R.drawable.connections_ic_help to Tone.NEUTRAL
    PlanUsageAvailability.Available -> R.drawable.connections_ic_check_circle to Tone.POSITIVE
    PlanUsageAvailability.NotGranted, is PlanUsageAvailability.NotEligible -> R.drawable.connections_ic_block to Tone.WARNING
    is PlanUsageAvailability.LimitReached -> R.drawable.connections_ic_schedule to Tone.WARNING
    PlanUsageAvailability.TemporarilyUnavailable -> R.drawable.connections_ic_cloud_off to Tone.WARNING
}

@Composable
private fun planUsageText(planUsage: PlanUsageAvailability, formatter: InstantFormatter): String = when (planUsage) {
    PlanUsageAvailability.Unknown -> stringResource(R.string.connections_chatgpt_plan_usage_unknown)

    PlanUsageAvailability.Available -> stringResource(R.string.connections_chatgpt_plan_usage_available)

    PlanUsageAvailability.NotGranted -> stringResource(R.string.connections_chatgpt_plan_usage_not_granted)

    is PlanUsageAvailability.NotEligible -> stringResource(R.string.connections_chatgpt_plan_usage_not_eligible, planUsage.reason)

    is PlanUsageAvailability.LimitReached -> planUsage.until?.let {
        stringResource(R.string.connections_chatgpt_plan_usage_limit_until, formatter.format(it))
    } ?: stringResource(R.string.connections_chatgpt_plan_usage_limit)

    PlanUsageAvailability.TemporarilyUnavailable -> stringResource(R.string.connections_chatgpt_plan_usage_temporarily_unavailable)
}

@Composable
private fun LastRequestSection(state: ChatGptUiState, formatter: InstantFormatter) {
    SectionHeader(text = stringResource(R.string.connections_chatgpt_last_request_header))
    val request = state.lastRequest
    if (request == null) {
        Text(text = stringResource(R.string.connections_chatgpt_last_request_none), style = MaterialTheme.typography.bodyMedium)
    } else {
        AiRequestSummary(record = request, formatter = formatter)
        Text(
            text = stringResource(R.string.connections_chatgpt_last_request_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AiDataSharingLink(onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = stringResource(R.string.connections_chatgpt_ai_sharing), style = MaterialTheme.typography.titleMedium)
                Text(text = stringResource(R.string.connections_chatgpt_ai_sharing_body), style = MaterialTheme.typography.bodyMedium)
            }
            Icon(painter = painterResource(R.drawable.connections_ic_chevron_right), contentDescription = null)
        }
    }
}

@Composable
private fun PlanNoticeDialog(onAction: (ChatGptAction) -> Unit, openUsage: () -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(ChatGptAction.AcknowledgePlanNotice) },
        title = { Text(text = stringResource(R.string.connections_chatgpt_plan_notice_title)) },
        text = { Text(text = stringResource(R.string.connections_chatgpt_plan_notice_body)) },
        confirmButton = {
            TextButton(onClick = { onAction(ChatGptAction.AcknowledgePlanNotice) }) {
                Text(text = stringResource(R.string.connections_chatgpt_plan_notice_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = openUsage) { Text(text = stringResource(R.string.connections_chatgpt_manage_usage)) }
        },
    )
}

@Composable
private fun DisconnectDialog(dialog: ChatGptDisconnectDialog, onAction: (ChatGptAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(ChatGptAction.DismissDisconnect) },
        title = { Text(text = stringResource(R.string.connections_chatgpt_disconnect_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(R.string.connections_chatgpt_disconnect_body))
                CheckRow(
                    checked = dialog.forgetRegistration,
                    text = stringResource(R.string.connections_chatgpt_disconnect_forget),
                    onCheckedChange = { onAction(ChatGptAction.SetForgetRegistration(it)) },
                )
                Text(text = stringResource(R.string.connections_chatgpt_disconnect_forget_note), style = MaterialTheme.typography.bodySmall)
                Text(text = stringResource(R.string.connections_chatgpt_disconnect_help), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(ChatGptAction.ConfirmDisconnect) }) {
                Text(text = stringResource(R.string.connections_chatgpt_disconnect_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(ChatGptAction.DismissDisconnect) }) {
                Text(text = stringResource(R.string.connections_cancel))
            }
        },
    )
}
