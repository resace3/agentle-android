package dev.agentle.core.ui.status

import dev.agentle.core.model.ConnectionStatus
import dev.agentle.core.model.PermissionState
import dev.agentle.core.model.SyncStatus
import dev.agentle.core.ui.R
import dev.agentle.core.ui.icon.AgentleIcons

/**
 * Tone, icon and label of each of the ten permission states (spec §6). Every state has its own icon and label, so the
 * ten states stay distinguishable without color.
 */
public fun PermissionState.toStatusSpec(): StatusSpec = when (this) {
    PermissionState.ALLOWED -> StatusSpec(StatusTone.POSITIVE, AgentleIcons.checkCircle, R.string.ui_permission_allowed)

    PermissionState.BACKGROUND_ALLOWED ->
        StatusSpec(StatusTone.POSITIVE, AgentleIcons.schedule, R.string.ui_permission_background_allowed)

    PermissionState.FOREGROUND_ONLY -> StatusSpec(StatusTone.CAUTION, AgentleIcons.visibility, R.string.ui_permission_foreground_only)

    PermissionState.PARTIALLY_ALLOWED ->
        StatusSpec(StatusTone.CAUTION, AgentleIcons.halfCircle, R.string.ui_permission_partially_allowed)

    PermissionState.UNAVAILABLE -> StatusSpec(StatusTone.CAUTION, AgentleIcons.pauseCircle, R.string.ui_permission_unavailable)

    PermissionState.DENIED -> StatusSpec(StatusTone.NEGATIVE, AgentleIcons.block, R.string.ui_permission_denied)

    PermissionState.DENIED_PERMANENTLY ->
        StatusSpec(StatusTone.NEGATIVE, AgentleIcons.error, R.string.ui_permission_denied_permanently)

    PermissionState.REQUIRES_SETTINGS -> StatusSpec(StatusTone.NEGATIVE, AgentleIcons.tune, R.string.ui_permission_requires_settings)

    PermissionState.RESTRICTED_BY_ANDROID ->
        StatusSpec(StatusTone.NEUTRAL, AgentleIcons.lock, R.string.ui_permission_restricted_by_android)

    PermissionState.UNSUPPORTED_ON_DEVICE ->
        StatusSpec(StatusTone.NEUTRAL, AgentleIcons.removeCircle, R.string.ui_permission_unsupported_on_device)
}

/** Tone, icon and label of a connector's connection state. */
public fun ConnectionStatus.toStatusSpec(): StatusSpec = when (this) {
    ConnectionStatus.CONNECTED -> StatusSpec(StatusTone.POSITIVE, AgentleIcons.checkCircle, R.string.ui_connection_connected)
    ConnectionStatus.CONNECTING -> StatusSpec(StatusTone.INFO, AgentleIcons.sync, R.string.ui_connection_connecting)
    ConnectionStatus.NOT_CONNECTED -> StatusSpec(StatusTone.NEUTRAL, AgentleIcons.circleOutline, R.string.ui_connection_not_connected)
    ConnectionStatus.NEEDS_REAUTH -> StatusSpec(StatusTone.NEGATIVE, AgentleIcons.warning, R.string.ui_connection_needs_reauth)
    ConnectionStatus.DISABLED -> StatusSpec(StatusTone.NEUTRAL, AgentleIcons.pauseCircle, R.string.ui_connection_disabled)
    ConnectionStatus.UNAVAILABLE -> StatusSpec(StatusTone.NEUTRAL, AgentleIcons.removeCircle, R.string.ui_connection_unavailable)
    ConnectionStatus.ERROR -> StatusSpec(StatusTone.NEGATIVE, AgentleIcons.error, R.string.ui_connection_error)
}

/** Tone, icon and label of a sync run's state. */
public fun SyncStatus.toStatusSpec(): StatusSpec = when (this) {
    SyncStatus.IDLE -> StatusSpec(StatusTone.NEUTRAL, AgentleIcons.schedule, R.string.ui_sync_idle)
    SyncStatus.RUNNING -> StatusSpec(StatusTone.INFO, AgentleIcons.sync, R.string.ui_sync_running)
    SyncStatus.SUCCEEDED -> StatusSpec(StatusTone.POSITIVE, AgentleIcons.checkCircle, R.string.ui_sync_succeeded)
    SyncStatus.PARTIAL -> StatusSpec(StatusTone.CAUTION, AgentleIcons.halfCircle, R.string.ui_sync_partial)
    SyncStatus.FAILED -> StatusSpec(StatusTone.NEGATIVE, AgentleIcons.error, R.string.ui_sync_failed)
}
