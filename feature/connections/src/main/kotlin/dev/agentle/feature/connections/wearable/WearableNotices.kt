package dev.agentle.feature.connections.wearable

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.agentle.connectors.api.SyncResult
import dev.agentle.core.model.Blocker
import dev.agentle.feature.connections.R
import dev.agentle.feature.connections.port.WearableDisconnectReport
import dev.agentle.feature.connections.ui.Tone
import dev.agentle.feature.connections.ui.errorText

/** The text and tone of a Wearable screen message. */
@Composable
internal fun wearableNoticeText(notice: WearableNotice): Pair<String, Tone> = when (notice) {
    is WearableNotice.Connected -> connectedText(notice)

    WearableNotice.ConsentDenied -> stringResource(R.string.connections_wearable_notice_denied) to Tone.NEUTRAL

    WearableNotice.ConsentCancelled -> stringResource(R.string.connections_wearable_notice_cancelled) to Tone.NEUTRAL

    WearableNotice.NothingShared -> stringResource(R.string.connections_wearable_notice_nothing_shared) to Tone.WARNING

    WearableNotice.ConsentScreenFailed -> stringResource(R.string.connections_wearable_notice_consent_failed) to Tone.ERROR

    is WearableNotice.Unavailable -> stringResource(
        if (notice.blocker == Blocker.PLAY_SERVICES_MISSING) {
            R.string.connections_wearable_unavailable_play_services_body
        } else {
            R.string.connections_wearable_unavailable_flag_body
        },
    ) to Tone.WARNING

    is WearableNotice.AuthorizationFailed ->
        stringResource(R.string.connections_wearable_notice_auth_failed, errorText(notice.error)) to Tone.ERROR

    is WearableNotice.SyncFinished -> syncText(notice)

    is WearableNotice.Disconnected -> disconnectedText(notice.report)

    is WearableNotice.DisconnectFailed ->
        stringResource(R.string.connections_wearable_notice_disconnect_failed, errorText(notice.error)) to Tone.ERROR
}

@Composable
private fun connectedText(notice: WearableNotice.Connected): Pair<String, Tone> = when {
    notice.accountChanged -> stringResource(R.string.connections_wearable_notice_account_changed) to Tone.WARNING

    notice.missingCount > 0 -> pluralStringResource(
        R.plurals.connections_wearable_notice_connected_partial,
        notice.missingCount,
        notice.missingCount,
    ) to Tone.WARNING

    else -> stringResource(R.string.connections_wearable_notice_connected) to Tone.POSITIVE
}

@Composable
private fun syncText(notice: WearableNotice.SyncFinished): Pair<String, Tone> = when (notice.status) {
    SyncResult.Status.SUCCESS ->
        pluralStringResource(R.plurals.connections_wearable_notice_sync_done, notice.committed, notice.committed) to Tone.POSITIVE

    SyncResult.Status.PARTIAL ->
        pluralStringResource(R.plurals.connections_wearable_notice_sync_partial, notice.committed, notice.committed) to Tone.WARNING

    SyncResult.Status.FAILED -> stringResource(
        R.string.connections_wearable_notice_sync_failed,
        notice.error?.let { errorText(it) } ?: stringResource(R.string.connections_error_unexpected),
    ) to Tone.ERROR

    SyncResult.Status.SKIPPED_NOT_CONNECTED ->
        stringResource(R.string.connections_wearable_notice_sync_not_connected) to Tone.NEUTRAL

    SyncResult.Status.SKIPPED_NO_PERMISSION ->
        stringResource(R.string.connections_wearable_notice_sync_no_permission) to Tone.WARNING

    SyncResult.Status.SKIPPED_DISABLED -> stringResource(R.string.connections_wearable_notice_sync_disabled) to Tone.NEUTRAL

    SyncResult.Status.ACCOUNT_CHANGED ->
        stringResource(R.string.connections_wearable_notice_sync_account_changed) to Tone.WARNING
}

@Composable
private fun disconnectedText(report: WearableDisconnectReport): Pair<String, Tone> {
    val done = stringResource(
        if (report.dataDeleted) {
            R.string.connections_wearable_notice_disconnected_deleted
        } else {
            R.string.connections_wearable_notice_disconnected_kept
        },
    )
    return if (report.accessRevoked) {
        done to Tone.POSITIVE
    } else {
        "$done ${stringResource(R.string.connections_wearable_notice_revoke_unconfirmed)}" to Tone.WARNING
    }
}
