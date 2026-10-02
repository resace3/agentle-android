package dev.agentle.feature.hub

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.agentle.core.common.AppError
import dev.agentle.core.common.AppException
import dev.agentle.core.model.CapabilityCategory
import dev.agentle.core.model.DataCapability
import dev.agentle.core.model.PermissionState
import dev.agentle.core.model.PlannedStatus
import dev.agentle.core.ui.component.ErrorState
import dev.agentle.core.ui.component.LoadingState
import dev.agentle.feature.hub.port.CapabilityItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Loading, content or a port error, for every hub screen. */
internal sealed interface Load<out T> {
    data object Loading : Load<Nothing>

    data class Ready<T>(val value: T) : Load<T>

    data class Failed(val error: AppError) : Load<Nothing>
}

/** Re-subscribes to [source] whenever [retries] emits; an [AppException] from the port becomes [Load.Failed]. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> loadOf(retries: Flow<Int>, source: () -> Flow<T>): Flow<Load<T>> = retries.flatMapLatest {
    source()
        .map<T, Load<T>> { Load.Ready(it) }
        .onStart { emit(Load.Loading) }
        .catch { error -> if (error is AppException) emit(Load.Failed(error.error)) else throw error }
}

/** Shows loading and error states; [content] gets the ready value. */
@Composable
internal fun <T> LoadContent(load: Load<T>, padding: PaddingValues, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    val modifier = Modifier.padding(padding).fillMaxSize()
    when (load) {
        Load.Loading -> LoadingState(modifier)
        is Load.Failed -> ErrorState(error = load.error, modifier = modifier, onRetry = onRetry)
        is Load.Ready -> content(load.value)
    }
}

/** What the main button of a capability does. */
internal enum class CapabilityAction { REQUEST, OPEN_SETTINGS, NONE }

private const val EXACT_ALARM_ID = "exact_alarm_jitai_scheduling"
private const val HEALTH_CONNECT = "health_connect"
private val BEST_EFFORT_IDS = setOf("screen_interactive_events", "unlock_keyguard_events", "battery_state")

/** The one action that moves [item] forward (R01 §6). Health permissions go through Health Connect's own screen. */
internal fun primaryAction(item: CapabilityItem): CapabilityAction {
    val capability = item.capability
    if (capability.id == EXACT_ALARM_ID || !capability.isPlannedForUse()) return CapabilityAction.NONE
    val canRequest = item.missingPermissions.isNotEmpty() && capability.specialAccess != HEALTH_CONNECT
    return when (item.status.state) {
        PermissionState.ALLOWED, PermissionState.BACKGROUND_ALLOWED -> CapabilityAction.NONE

        PermissionState.RESTRICTED_BY_ANDROID, PermissionState.UNSUPPORTED_ON_DEVICE -> CapabilityAction.NONE

        PermissionState.DENIED, PermissionState.PARTIALLY_ALLOWED, PermissionState.FOREGROUND_ONLY ->
            if (canRequest) CapabilityAction.REQUEST else CapabilityAction.OPEN_SETTINGS

        PermissionState.DENIED_PERMANENTLY, PermissionState.REQUIRES_SETTINGS, PermissionState.UNAVAILABLE ->
            CapabilityAction.OPEN_SETTINGS
    }
}

internal fun DataCapability.isPlannedForUse(): Boolean =
    plannedStatus == PlannedStatus.IMPLEMENT || plannedStatus == PlannedStatus.IMPLEMENT_DEBUG_ONLY

internal fun CapabilityItem.isBestEffort(): Boolean = capability.id in BEST_EFFORT_IDS

internal fun CapabilityItem.isInfoOnly(): Boolean = capability.id == EXACT_ALARM_ID

internal fun CapabilityCategory.whyRes(): Int = when (this) {
    CapabilityCategory.ACTIVITY -> R.string.hub_why_activity
    CapabilityCategory.LOCATION -> R.string.hub_why_location
    CapabilityCategory.NOTIFICATIONS -> R.string.hub_why_notifications
    CapabilityCategory.APPS -> R.string.hub_why_apps
    CapabilityCategory.BLUETOOTH -> R.string.hub_why_bluetooth
    CapabilityCategory.MEDIA -> R.string.hub_why_media
    CapabilityCategory.CALENDAR -> R.string.hub_why_calendar
    CapabilityCategory.HEALTH -> R.string.hub_why_health
    CapabilityCategory.COMMUNICATION -> R.string.hub_why_communication
    CapabilityCategory.DEVICE_STATE -> R.string.hub_why_device
    CapabilityCategory.SENSORS -> R.string.hub_why_sensors
}

internal fun specialAccessRes(specialAccess: String): Int = when (specialAccess) {
    "usage_access" -> R.string.hub_special_usage_access
    "notification_listener" -> R.string.hub_special_notification_listener
    "exact_alarm" -> R.string.hub_special_exact_alarm
    "battery_optimization" -> R.string.hub_special_battery
    HEALTH_CONNECT -> R.string.hub_special_health_connect
    else -> R.string.hub_special_other
}

/** "android.permission.ACTIVITY_RECOGNITION" -> "Activity recognition". */
internal fun permissionLabel(permission: String): String =
    permission.substringAfterLast('.').lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
