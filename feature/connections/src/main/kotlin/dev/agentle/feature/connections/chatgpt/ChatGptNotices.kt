package dev.agentle.feature.connections.chatgpt

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.agentle.feature.connections.R
import dev.agentle.feature.connections.port.ChatGptConnectResult
import dev.agentle.feature.connections.port.ChatGptDisconnectResult
import dev.agentle.feature.connections.ui.Tone
import dev.agentle.feature.connections.ui.errorText

/** The text and tone of a ChatGPT screen message. */
@Composable
internal fun chatGptNoticeText(notice: ChatGptNotice): Pair<String, Tone> = when (notice) {
    is ChatGptNotice.SignIn -> signInText(notice.result)
    is ChatGptNotice.Disconnect -> disconnectText(notice)
    is ChatGptNotice.ActionFailed ->
        stringResource(R.string.connections_chatgpt_notice_action_failed, errorText(notice.error)) to Tone.ERROR
}

@Composable
private fun signInText(result: ChatGptConnectResult): Pair<String, Tone> = when (result) {
    is ChatGptConnectResult.Failed -> stringResource(R.string.connections_chatgpt_notice_failed, errorText(result.error)) to Tone.ERROR
    is ChatGptConnectResult.Interrupted -> stringResource(
        if (result.firstRegistration) {
            R.string.connections_chatgpt_notice_interrupted_first
        } else {
            R.string.connections_chatgpt_notice_interrupted
        },
    ) to Tone.WARNING
    else -> stringResource(signInMessage(result)) to signInTone(result)
}

@StringRes
private fun signInMessage(result: ChatGptConnectResult): Int = when (result) {
    is ChatGptConnectResult.Connected -> R.string.connections_chatgpt_notice_connected
    ChatGptConnectResult.PlanUsageNotGranted -> R.string.connections_chatgpt_notice_plan_not_granted
    ChatGptConnectResult.Denied -> R.string.connections_chatgpt_notice_denied
    ChatGptConnectResult.NotCompleted -> R.string.connections_chatgpt_notice_not_completed
    ChatGptConnectResult.Cancelled -> R.string.connections_chatgpt_notice_cancelled
    ChatGptConnectResult.TimedOut -> R.string.connections_chatgpt_notice_timed_out
    ChatGptConnectResult.NoBrowser -> R.string.connections_chatgpt_notice_no_browser
    ChatGptConnectResult.AttemptRejected -> R.string.connections_chatgpt_notice_attempt_rejected
    ChatGptConnectResult.AccountMismatch -> R.string.connections_chatgpt_notice_account_mismatch
    ChatGptConnectResult.DeviceClockWrong -> R.string.connections_chatgpt_notice_clock_wrong
    ChatGptConnectResult.NetworkError -> R.string.connections_chatgpt_notice_network
    ChatGptConnectResult.ServiceUnavailable -> R.string.connections_chatgpt_notice_service_unavailable
    is ChatGptConnectResult.Interrupted -> R.string.connections_chatgpt_notice_interrupted
    is ChatGptConnectResult.Failed -> R.string.connections_error_unexpected
}

private fun signInTone(result: ChatGptConnectResult): Tone = when (result) {
    is ChatGptConnectResult.Connected -> Tone.POSITIVE
    ChatGptConnectResult.Denied, ChatGptConnectResult.NotCompleted, ChatGptConnectResult.Cancelled -> Tone.NEUTRAL
    ChatGptConnectResult.AttemptRejected, ChatGptConnectResult.NetworkError, is ChatGptConnectResult.Failed -> Tone.ERROR
    else -> Tone.WARNING
}

@Composable
private fun disconnectText(notice: ChatGptNotice.Disconnect): Pair<String, Tone> = when (val result = notice.result) {
    ChatGptDisconnectResult.Disconnected -> stringResource(
        if (notice.forgotRegistration) {
            R.string.connections_chatgpt_notice_disconnected_forgot
        } else {
            R.string.connections_chatgpt_notice_disconnected
        },
    ) to Tone.POSITIVE
    ChatGptDisconnectResult.RevocationUnconfirmed ->
        stringResource(R.string.connections_chatgpt_notice_revocation_unconfirmed) to Tone.WARNING
    is ChatGptDisconnectResult.Failed ->
        stringResource(R.string.connections_chatgpt_notice_disconnect_failed, errorText(result.error)) to Tone.ERROR
}
