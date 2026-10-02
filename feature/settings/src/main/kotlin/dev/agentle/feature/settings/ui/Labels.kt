package dev.agentle.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.agentle.ai.api.AiProviderState
import dev.agentle.core.common.Severity
import dev.agentle.core.model.ConnectionStatus
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.PermissionState
import dev.agentle.core.model.SyncStatus
import dev.agentle.feature.settings.R

// Shared enums are looked up in maps with a fallback to the code, so an additive enum change elsewhere never breaks
// this module; the fallback shows the stable code until a label is added.

private val CategoryLabels: Map<DataCategory, Int> = mapOf(
    DataCategory.APP_USAGE to R.string.settings_category_app_usage,
    DataCategory.SCREEN to R.string.settings_category_screen,
    DataCategory.NOTIFICATIONS to R.string.settings_category_notifications,
    DataCategory.NOTIFICATION_CONTENT to R.string.settings_category_notification_content,
    DataCategory.LOCATION to R.string.settings_category_location,
    DataCategory.ACTIVITY to R.string.settings_category_activity,
    DataCategory.HEART to R.string.settings_category_heart,
    DataCategory.SLEEP to R.string.settings_category_sleep,
    DataCategory.BODY to R.string.settings_category_body,
    DataCategory.DEVICE_STATE to R.string.settings_category_device_state,
    DataCategory.CALENDAR to R.string.settings_category_calendar,
    DataCategory.COMMUNICATION to R.string.settings_category_communication,
    DataCategory.MEDIA to R.string.settings_category_media,
    DataCategory.USER_LOGS to R.string.settings_category_user_logs,
    DataCategory.GOALS to R.string.settings_category_goals,
    DataCategory.INTERVENTIONS to R.string.settings_category_interventions,
    DataCategory.INSIGHTS to R.string.settings_category_insights,
    DataCategory.GENERATED_MEDIA to R.string.settings_category_generated_media,
)

private val PermissionLabels: Map<PermissionState, Int> = mapOf(
    PermissionState.ALLOWED to R.string.settings_permission_allowed,
    PermissionState.DENIED to R.string.settings_permission_denied,
    PermissionState.DENIED_PERMANENTLY to R.string.settings_permission_denied_permanently,
    PermissionState.REQUIRES_SETTINGS to R.string.settings_permission_requires_settings,
    PermissionState.RESTRICTED_BY_ANDROID to R.string.settings_permission_restricted,
    PermissionState.UNAVAILABLE to R.string.settings_permission_unavailable,
    PermissionState.PARTIALLY_ALLOWED to R.string.settings_permission_partial,
    PermissionState.FOREGROUND_ONLY to R.string.settings_permission_foreground_only,
    PermissionState.BACKGROUND_ALLOWED to R.string.settings_permission_background_allowed,
    PermissionState.UNSUPPORTED_ON_DEVICE to R.string.settings_permission_unsupported,
)

private val PermissionKinds: Map<PermissionState, StatusKind> = mapOf(
    PermissionState.ALLOWED to StatusKind.OK,
    PermissionState.BACKGROUND_ALLOWED to StatusKind.OK,
    PermissionState.PARTIALLY_ALLOWED to StatusKind.INFO,
    PermissionState.FOREGROUND_ONLY to StatusKind.INFO,
    PermissionState.DENIED to StatusKind.WARNING,
    PermissionState.DENIED_PERMANENTLY to StatusKind.WARNING,
    PermissionState.REQUIRES_SETTINGS to StatusKind.WARNING,
    PermissionState.RESTRICTED_BY_ANDROID to StatusKind.WARNING,
    PermissionState.UNAVAILABLE to StatusKind.OFF,
    PermissionState.UNSUPPORTED_ON_DEVICE to StatusKind.OFF,
)

private val ConnectionLabels: Map<ConnectionStatus, Int> = mapOf(
    ConnectionStatus.NOT_CONNECTED to R.string.settings_connection_not_connected,
    ConnectionStatus.CONNECTING to R.string.settings_connection_connecting,
    ConnectionStatus.CONNECTED to R.string.settings_connection_connected,
    ConnectionStatus.NEEDS_REAUTH to R.string.settings_connection_needs_reauth,
    ConnectionStatus.DISABLED to R.string.settings_connection_disabled,
    ConnectionStatus.UNAVAILABLE to R.string.settings_connection_unavailable,
    ConnectionStatus.ERROR to R.string.settings_connection_error,
)

private val ConnectionKinds: Map<ConnectionStatus, StatusKind> = mapOf(
    ConnectionStatus.CONNECTED to StatusKind.OK,
    ConnectionStatus.CONNECTING to StatusKind.IN_PROGRESS,
    ConnectionStatus.NOT_CONNECTED to StatusKind.OFF,
    ConnectionStatus.NEEDS_REAUTH to StatusKind.WARNING,
    ConnectionStatus.DISABLED to StatusKind.OFF,
    ConnectionStatus.UNAVAILABLE to StatusKind.OFF,
    ConnectionStatus.ERROR to StatusKind.ERROR,
)

private val SyncLabels: Map<SyncStatus, Int> = mapOf(
    SyncStatus.IDLE to R.string.settings_sync_idle,
    SyncStatus.RUNNING to R.string.settings_sync_running,
    SyncStatus.SUCCEEDED to R.string.settings_sync_succeeded,
    SyncStatus.PARTIAL to R.string.settings_sync_partial,
    SyncStatus.FAILED to R.string.settings_sync_failed,
)

private val SeverityLabels: Map<Severity, Int> = mapOf(
    Severity.VERBOSE to R.string.settings_severity_verbose,
    Severity.DEBUG to R.string.settings_severity_debug,
    Severity.INFO to R.string.settings_severity_info,
    Severity.WARN to R.string.settings_severity_warn,
    Severity.ERROR to R.string.settings_severity_error,
)

/** The user-facing name of a data category. */
@Composable
internal fun categoryLabel(category: DataCategory): String = CategoryLabels[category]?.let { stringResource(it) } ?: category.name

@Composable
internal fun permissionLabel(state: PermissionState): String = PermissionLabels[state]?.let { stringResource(it) } ?: state.name

internal fun PermissionState.statusKind(): StatusKind = PermissionKinds[this] ?: StatusKind.INFO

@Composable
internal fun connectionLabel(status: ConnectionStatus): String = ConnectionLabels[status]?.let { stringResource(it) } ?: status.name

internal fun ConnectionStatus.statusKind(): StatusKind = ConnectionKinds[this] ?: StatusKind.INFO

@Composable
internal fun syncLabel(status: SyncStatus): String = SyncLabels[status]?.let { stringResource(it) } ?: status.name

@Composable
internal fun severityLabel(severity: Severity): String = SeverityLabels[severity]?.let { stringResource(it) } ?: severity.name

/** The kind of a ChatGPT connection, never its account label (diagnostics and privacy show only the kind). */
@Composable
internal fun aiProviderLabel(state: AiProviderState): String = stringResource(
    when (state) {
        AiProviderState.Disconnected -> R.string.settings_ai_disconnected
        AiProviderState.Connecting -> R.string.settings_ai_connecting
        is AiProviderState.Connected -> R.string.settings_ai_connected
        AiProviderState.NeedsReauth -> R.string.settings_ai_needs_reauth
        is AiProviderState.NotEligible -> R.string.settings_ai_not_eligible
        is AiProviderState.UsageLimited -> R.string.settings_ai_usage_limited
        is AiProviderState.Unavailable -> R.string.settings_ai_unavailable
        else -> R.string.settings_ai_unknown
    },
)

internal fun AiProviderState.statusKind(): StatusKind = when (this) {
    is AiProviderState.Connected -> StatusKind.OK
    AiProviderState.Connecting -> StatusKind.IN_PROGRESS
    AiProviderState.NeedsReauth, is AiProviderState.NotEligible, is AiProviderState.UsageLimited -> StatusKind.WARNING
    else -> StatusKind.OFF
}

/** "1,234 records". */
@Composable
internal fun recordsText(count: Long, formats: DisplayFormats): String =
    pluralStringResource(R.plurals.settings_records, count.toQuantity(), formats.count(count))

/** "12 files". */
@Composable
internal fun filesText(count: Int, formats: DisplayFormats): String =
    pluralStringResource(R.plurals.settings_files, count, formats.count(count.toLong()))

/** Plural quantity of a count that may exceed an Int. */
internal fun Long.toQuantity(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
