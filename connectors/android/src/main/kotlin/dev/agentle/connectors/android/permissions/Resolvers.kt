package dev.agentle.connectors.android.permissions

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import dev.agentle.connectors.api.UnusedAppRestrictions
import dev.agentle.core.model.Blocker
import dev.agentle.core.model.DataCapability
import dev.agentle.core.model.PermissionState
import dev.agentle.core.model.PlannedStatus

private val AL = PermissionState.ALLOWED
private val D = PermissionState.DENIED
private val RS = PermissionState.REQUIRES_SETTINGS
private val RA = PermissionState.RESTRICTED_BY_ANDROID
private val UA = PermissionState.UNAVAILABLE
private val PA = PermissionState.PARTIALLY_ALLOWED
private val FO = PermissionState.FOREGROUND_ONLY
private val BA = PermissionState.BACKGROUND_ALLOWED
private val US = PermissionState.UNSUPPORTED_ON_DEVICE

/**
 * Mechanism A (§5.3 A), context-free part: a runtime permission that is not held is DENIED when it was never requested;
 * when it was requested it becomes a pending denial that only the UI-side [DenialRefinement] turns into DENIED (the
 * rationale may be shown) or DENIED_PERMANENTLY (the dialog is suppressed). Red team testing-build-15.
 */
internal fun StateBuilder.requireRuntime(context: ResolverContext, permission: String): Boolean {
    if (context.platform.isGranted(permission)) return true
    addRuntimeDenial(permission, requested = permission in context.requestedPermissions)
    return false
}

/** Mechanism E (§5.3 E): a special access still off after the user came back from its Settings page on a sideload. */
internal fun ResolverContext.likelyEcmGuarded(specialAccess: String): Boolean {
    if (platform.sdkInt < Build.VERSION_CODES.TIRAMISU || specialAccess !in settingsVisited) return false
    val source = platform.packageSource()
    return source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE || source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
}

/** DEFER and DOCUMENT_UNAVAILABLE capabilities, and debug-only ones in non-debuggable builds. */
public object NotInBuildResolver : CapabilityStateResolver {
    override suspend fun resolve(capability: DataCapability, context: ResolverContext): StateBuilder {
        val builder = StateBuilder().add(RA, Blocker.NOT_IN_THIS_BUILD)
        if (capability.id in TELEPHONY_ONLY && !hasTelephony(context)) builder.add(US, Blocker.NO_HARDWARE)
        return builder
    }

    private val TELEPHONY_ONLY = setOf("call_log_metadata", "sms_metadata")
}

internal fun hasTelephony(context: ResolverContext): Boolean = if (context.platform.sdkInt >= Build.VERSION_CODES.TIRAMISU) {
    context.platform.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING)
} else {
    context.platform.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
}

/** Mechanism A for capabilities that need every listed runtime permission (e.g. `calendar_events`). */
public class RuntimePermissionResolver(private val permissions: List<String>) : CapabilityStateResolver {
    override suspend fun resolve(capability: DataCapability, context: ResolverContext): StateBuilder {
        val builder = StateBuilder()
        permissions.forEach { builder.requireRuntime(context, it) }
        return builder
    }
}

/**
 * Mechanism B (§5.3 B), usage access. With [liveWithoutAccess] the capability still records live receiver events
 * without usage access (screen, unlock, boot: PARTIALLY_ALLOWED with USAGE_ACCESS_MISSING); otherwise missing access is
 * REQUIRES_SETTINGS, or RESTRICTED_BY_ANDROID when the ECM heuristic applies. [bestEffortLive] adds the
 * LIVE_EVENTS_BEST_EFFORT note (red team lifecycle-battery-07).
 */
public class UsageAccessResolver(private val liveWithoutAccess: Boolean = false, private val bestEffortLive: Boolean = false) :
    CapabilityStateResolver {
    override suspend fun resolve(capability: DataCapability, context: ResolverContext): StateBuilder {
        val builder = StateBuilder()
        if (bestEffortLive) builder.note(Blocker.LIVE_EVENTS_BEST_EFFORT)
        if (!context.platform.usageAccessGranted()) {
            when {
                liveWithoutAccess -> builder.add(PA, Blocker.USAGE_ACCESS_MISSING)

                !context.platform.canResolveActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                ) -> builder.add(US, Blocker.SETTINGS_SCREEN_MISSING)

                context.likelyEcmGuarded(SpecialAccess.USAGE_ACCESS) -> builder.add(RA, Blocker.ECM_RESTRICTED_SETTINGS)

                else -> builder.add(RS)
            }
        }
        if (!context.platform.isUserUnlocked()) builder.add(UA, Blocker.USER_LOCKED)
        return builder
    }
}

/** `app_standby_bucket`: Agentle's own bucket needs nothing; other apps' bucket changes need usage access. */
public object StandbyBucketResolver : CapabilityStateResolver {
    override suspend fun resolve(capability: DataCapability, context: ResolverContext): StateBuilder {
        val builder = StateBuilder()
        if (!context.platform.usageAccessGranted()) builder.add(PA, Blocker.USAGE_ACCESS_MISSING)
        return builder
    }
}

/**
 * Mechanism C (§5.3 C), the notification listener. UNSUPPORTED on low-RAM devices at API 29 and when no Settings screen
 * can grant it; RESTRICTED in a work profile or under the ECM heuristic; REQUIRES_SETTINGS when not granted;
 * UNAVAILABLE when granted but not connected. [content] adds the API 35+ OTP redaction (PARTIALLY_ALLOWED).
 */
public class NotificationListenerResolver(private val component: ComponentName, private val content: Boolean = false) :
    CapabilityStateResolver {
    override suspend fun resolve(capability: DataCapability, context: ResolverContext): StateBuilder {
        val builder = StateBuilder()
        val platform = context.platform
        if (platform.sdkInt <= Build.VERSION_CODES.Q && platform.isLowRamDevice()) builder.add(US, Blocker.NO_HARDWARE)
        if (!platform.canResolveActivity(
                listenerSettingsIntent(platform.sdkInt, component),
            )
        ) {
            builder.add(US, Blocker.SETTINGS_SCREEN_MISSING)
        }
        if (platform.isManagedProfile()) builder.add(RA, Blocker.MANAGED_PROFILE)
        if (!platform.notificationListenerGranted(component)) {
            if (context.likelyEcmGuarded(
                    SpecialAccess.NOTIFICATION_LISTENER,
                )
            ) {
                builder.add(RA, Blocker.ECM_RESTRICTED_SETTINGS)
            } else {
                builder.add(RS)
            }
        } else if (!context.listenerConnected) {
            builder.add(UA, Blocker.LISTENER_DISCONNECTED)
        }
        if (content && platform.sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM) builder.add(PA, Blocker.OTP_REDACTION)
        return builder
    }

    public companion object {
        public fun listenerSettingsIntent(sdkInt: Int, component: ComponentName): Intent = if (sdkInt >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
    }
}

/**
 * `post_notifications_jitai` (§3.36, red team jitai-correctness-13): POST_NOTIFICATIONS on 33+, the app-level switch,
 * the pause state and every Agentle channel. Delivery is possible only when the state is ALLOWED for the channel used
 * (see [dev.agentle.connectors.api.NotificationDeliveryGate]).
 */
public object PostNotificationsResolver : CapabilityStateResolver {
    override suspend fun resolve(capability: DataCapability, context: ResolverContext): StateBuilder {
        val builder = StateBuilder()
        val platform = context.platform
        val permissionHeld = platform.sdkInt < Build.VERSION_CODES.TIRAMISU ||
            builder.requireRuntime(context, Permissions.POST_NOTIFICATIONS)
        if (permissionHeld && !platform.notificationsEnabled()) builder.add(RS, Blocker.NOTIFICATIONS_DISABLED)
        if (!permissionHeld) builder.note(Blocker.NOTIFICATIONS_DISABLED)
        if (platform.notificationsPaused()) builder.add(UA, Blocker.NOTIFICATIONS_PAUSED)
        val blocked = platform.blockedChannelIds()
        if (blocked.isNotEmpty()) {
            if (blocked.size >=
                platform.channelCount()
            ) {
                builder.add(RS, Blocker.CHANNEL_BLOCKED)
            } else {
                builder.add(PA, Blocker.CHANNEL_BLOCKED)
            }
        }
        return builder
    }
}

/**
 * `exact_alarm_jitai_scheduling` (red team lifecycle-battery-15): Agentle never schedules exact alarms and declares
 * neither SCHEDULE_EXACT_ALARM nor USE_EXACT_ALARM. The state is reported for information: ALLOWED when the platform
 * would allow them (API 30 and lower, or granted), otherwise RESTRICTED_BY_ANDROID with NOT_IN_THIS_BUILD.
 */
public object ExactAlarmResolver : CapabilityStateResolver {
    override suspend fun resolve(capability: DataCapability, context: ResolverContext): StateBuilder {
        val builder = StateBuilder()
        if (context.platform.sdkInt >= Build.VERSION_CODES.S &&
            !context.platform.canScheduleExactAlarms()
        ) {
            builder.add(RA, Blocker.NOT_IN_THIS_BUILD)
        }
        return builder
    }
}

/**
 * `background_execution_exemption` (§5.3 D): not battery-optimization exempt is REQUIRES_SETTINGS (diagnostic, not a
 * hard block); user background restriction adds BACKGROUND_RESTRICTED_BY_USER; the restricted standby bucket and
 * active unused-app restrictions add their blockers.
 */
public object BackgroundExecutionResolver : CapabilityStateResolver {
    private const val STANDBY_BUCKET_RESTRICTED = 45

    override suspend fun resolve(capability: DataCapability, context: ResolverContext): StateBuilder {
        val builder = StateBuilder()
        val platform = context.platform
        if (platform.isBackgroundRestricted()) builder.add(RS, Blocker.BACKGROUND_RESTRICTED_BY_USER)
        if (!platform.isIgnoringBatteryOptimizations()) builder.add(RS)
        if (platform.appStandbyBucket() == STANDBY_BUCKET_RESTRICTED) builder.note(Blocker.STANDBY_BUCKET_RESTRICTED)
        if (context.unusedAppRestrictions().restrictsApp) builder.note(Blocker.HIBERNATION_ENABLED)
        return builder
    }
}

/** Mechanism F for device-state capabilities that need no grant: ALLOWED unless hardware or API level is missing. */
public class DeviceStateResolver(
    private val requiredFeature: String? = null,
    private val minSdk: Int = Build.VERSION_CODES.Q,
    private val bestEffortLive: Boolean = false,
) : CapabilityStateResolver {
    override suspend fun resolve(capability: DataCapability, context: ResolverContext): StateBuilder {
        val builder = StateBuilder()
        if (context.platform.sdkInt < minSdk) builder.add(US, Blocker.SDK_TOO_OLD)
        if (requiredFeature != null && !context.platform.hasSystemFeature(requiredFeature)) builder.add(US, Blocker.NO_HARDWARE)
        if (bestEffortLive) builder.note(Blocker.LIVE_EVENTS_BEST_EFFORT)
        return builder
    }
}

/** Composite location (§5.3 A): coarse is enough by default; precise is only required when the user chose it. */
public object LocationResolver : CapabilityStateResolver {
    override suspend fun resolve(capability: DataCapability, context: ResolverContext): StateBuilder {
        val builder = StateBuilder()
        val platform = context.platform
        if (!platform.hasSystemFeature(PackageManager.FEATURE_LOCATION)) builder.add(US, Blocker.NO_HARDWARE)
        if (platform.hasUserRestriction(UserManager.DISALLOW_SHARE_LOCATION) ||
            platform.hasUserRestriction(UserManager.DISALLOW_CONFIG_LOCATION)
        ) {
            builder.add(RA, Blocker.USER_RESTRICTION)
        }
        val fine = platform.isGranted(Permissions.ACCESS_FINE_LOCATION)
        val coarse = fine || builder.requireRuntime(context, Permissions.ACCESS_COARSE_LOCATION)
        if (coarse && !fine && context.settings.preciseLocation) builder.add(PA, Blocker.APPROXIMATE_ONLY)
        if (!platform.isLocationEnabled()) builder.add(UA, Blocker.LOCATION_SERVICES_OFF)
        return builder
    }
}

/** Mechanism F (+A on 31+ for connected devices): adapter presence, user restriction, adapter on, BLUETOOTH_CONNECT. */
public class BluetoothResolver(private val connectedDevices: Boolean) : CapabilityStateResolver {
    override suspend fun resolve(capability: DataCapability, context: ResolverContext): StateBuilder {
        val builder = StateBuilder()
        val platform = context.platform
        if (!platform.bluetoothSupported()) {
            builder.add(US, Blocker.NO_HARDWARE)
            return builder
        }
        if (platform.hasUserRestriction(DISALLOW_BLUETOOTH)) builder.add(RA, Blocker.USER_RESTRICTION)
        if (connectedDevices && platform.sdkInt >= Build.VERSION_CODES.S) builder.requireRuntime(context, Permissions.BLUETOOTH_CONNECT)
        if (!platform.bluetoothEnabled()) builder.add(UA, Blocker.BLUETOOTH_OFF)
        return builder
    }

    private companion object {
        const val DISALLOW_BLUETOOTH = "no_bluetooth"
    }
}

/** Activity Recognition transitions and the Recording API: ACTIVITY_RECOGNITION plus Google Play services. */
public class PlayServicesActivityResolver(private val requirement: PlayServicesRequirement) : CapabilityStateResolver {
    override suspend fun resolve(capability: DataCapability, context: ResolverContext): StateBuilder {
        val builder = StateBuilder()
        builder.requireRuntime(context, Permissions.ACTIVITY_RECOGNITION)
        if (!context.playServices.isAvailable(requirement)) builder.add(UA, Blocker.PLAY_SERVICES_MISSING)
        return builder
    }
}

/**
 * `call_state` (red team lifecycle-battery-19): opt-in, so until the user enables it the status carries
 * COLLECTION_DISABLED_BY_USER and READ_PHONE_STATE is never requested. UNSUPPORTED without telephony. API 29-30 are
 * handled explicitly: `PhoneStateListener.LISTEN_CALL_STATE` needs no permission there; from API 31
 * `TelephonyCallback.CallStateListener` needs READ_PHONE_STATE.
 */
public object CallStateResolver : CapabilityStateResolver {
    override suspend fun resolve(capability: DataCapability, context: ResolverContext): StateBuilder {
        val builder = StateBuilder()
        if (!hasTelephony(context)) builder.add(US, Blocker.NO_HARDWARE)
        if (CALL_CONNECTOR_ID !in context.settings.enabledOptInConnectors) builder.note(Blocker.COLLECTION_DISABLED_BY_USER)
        if (context.platform.sdkInt >= Build.VERSION_CODES.S) builder.requireRuntime(context, Permissions.READ_PHONE_STATE)
        return builder
    }

    /** The opt-in connector id (`android.call`). */
    public const val CALL_CONNECTOR_ID: String = "android.call"
}

/** Debug-only sensor capabilities: foreground snapshots only (no background events), UNSUPPORTED without the sensor. */
public class SensorDebugResolver(private val sensorTypes: List<Int>) : CapabilityStateResolver {
    override suspend fun resolve(capability: DataCapability, context: ResolverContext): StateBuilder {
        val builder = StateBuilder()
        if (sensorTypes.none { context.platform.hasSensor(it) }) builder.add(US, Blocker.NO_HARDWARE)
        builder.add(FO)
        return builder
    }

    public companion object {
        public val MOTION: List<Int> = listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE)
        public val AMBIENT: List<Int> = listOf(Sensor.TYPE_LIGHT, Sensor.TYPE_PROXIMITY, Sensor.TYPE_PRESSURE)
    }
}

/** Debug-only media metadata: granular media permissions on 33+, selected photos on 34+ (PARTIALLY_ALLOWED). */
public object MediaDebugResolver : CapabilityStateResolver {
    override suspend fun resolve(capability: DataCapability, context: ResolverContext): StateBuilder {
        val builder = StateBuilder()
        val platform = context.platform
        if (platform.sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            val full = platform.isGranted(Permissions.READ_MEDIA_IMAGES) && platform.isGranted(Permissions.READ_MEDIA_VIDEO)
            val partial =
                platform.sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && platform.isGranted(Permissions.READ_MEDIA_VISUAL_USER_SELECTED)
            when {
                full -> Unit
                partial -> builder.add(PA)
                else -> builder.requireRuntime(context, Permissions.READ_MEDIA_IMAGES)
            }
        } else {
            builder.requireRuntime(context, Permissions.READ_EXTERNAL_STORAGE)
        }
        return builder
    }
}

/**
 * Health Connect (§5.3 D): SDK status, the granted set, background and history features. [kind] selects the registry
 * capability. A repeated empty grant result is REQUIRES_SETTINGS (Health Connect's own "don't ask again").
 */
public class HealthConnectResolver(private val kind: Kind) : CapabilityStateResolver {
    public enum class Kind { RECORDS, BACKGROUND_READ, HISTORY_READ, ON_DEVICE_STEPS }

    override suspend fun resolve(capability: DataCapability, context: ResolverContext): StateBuilder {
        val base = if (kind == Kind.BACKGROUND_READ || kind == Kind.ON_DEVICE_STEPS) BA else AL
        val builder = StateBuilder(base)
        val probe = context.healthConnect
        if (kind == Kind.ON_DEVICE_STEPS &&
            (
                context.platform.sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                    context.platform.upsideDownCakeExtension() < ON_DEVICE_STEPS_EXTENSION
                )
        ) {
            builder.add(US, Blocker.SDK_TOO_OLD)
            return builder
        }
        when (probe.sdkStatus()) {
            SDK_UNAVAILABLE -> return builder.add(US, Blocker.HC_NOT_INSTALLED)
            SDK_UPDATE_REQUIRED -> return builder.add(UA, Blocker.HC_UPDATE_REQUIRED)
        }
        val granted = probe.grantedPermissions()
        when (kind) {
            Kind.RECORDS -> {
                val held = HealthPermissions.RECORDS.count { it in granted }
                when {
                    held == HealthPermissions.RECORDS.size -> Unit
                    held > 0 -> builder.add(PA)
                    HealthPermissions.READ_STEPS in context.requestedPermissions -> builder.add(RS)
                    else -> builder.add(D)
                }
            }

            Kind.BACKGROUND_READ -> {
                if (!probe.featureAvailable(FEATURE_READ_IN_BACKGROUND)) builder.add(US, Blocker.SDK_TOO_OLD)
                if (HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND !in granted) builder.add(FO)
            }

            Kind.HISTORY_READ -> if (!probe.featureAvailable(FEATURE_READ_HISTORY) ||
                HealthPermissions.READ_HEALTH_DATA_HISTORY !in granted
            ) {
                builder.add(PA)
            }

            Kind.ON_DEVICE_STEPS -> when {
                HealthPermissions.READ_STEPS !in granted -> builder.add(D)
                HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND !in granted -> builder.add(FO)
            }
        }
        return builder
    }

    private companion object {
        const val SDK_UNAVAILABLE = 1
        const val SDK_UPDATE_REQUIRED = 2
        const val FEATURE_READ_IN_BACKGROUND = 1
        const val FEATURE_READ_HISTORY = 4
        const val ON_DEVICE_STEPS_EXTENSION = 20
    }
}

/** Whether the restriction level revokes permissions or hibernates Agentle. */
public val UnusedAppRestrictions.restrictsApp: Boolean
    get() = this == UnusedAppRestrictions.PERMISSION_REVOCATION_BACKPORT ||
        this == UnusedAppRestrictions.PERMISSION_REVOCATION ||
        this == UnusedAppRestrictions.HIBERNATION

/** The resolver of every registry capability (docs/research/01 §5.5). */
public object CapabilityResolvers {
    public fun forCapability(capability: DataCapability, listener: ComponentName, debuggable: Boolean): CapabilityStateResolver {
        when (capability.plannedStatus) {
            PlannedStatus.DEFER, PlannedStatus.DOCUMENT_UNAVAILABLE -> return NotInBuildResolver
            PlannedStatus.IMPLEMENT_DEBUG_ONLY -> if (!debuggable) return NotInBuildResolver
            PlannedStatus.IMPLEMENT -> Unit
        }
        return when (capability.id) {
            "app_usage_events", "app_usage_aggregates", "network_data_usage" -> UsageAccessResolver()
            "screen_interactive_events", "unlock_keyguard_events" -> UsageAccessResolver(liveWithoutAccess = true, bestEffortLive = true)
            "boot_shutdown_events" -> UsageAccessResolver(liveWithoutAccess = true)
            "app_standby_bucket" -> StandbyBucketResolver
            "battery_state" -> DeviceStateResolver(bestEffortLive = true)
            "wifi_connection_metadata" -> DeviceStateResolver(requiredFeature = PackageManager.FEATURE_WIFI)
            "notification_events_metadata" -> NotificationListenerResolver(listener)
            "notification_content" -> NotificationListenerResolver(listener, content = true)
            "post_notifications_jitai" -> PostNotificationsResolver
            "exact_alarm_jitai_scheduling" -> ExactAlarmResolver
            "background_execution_exemption" -> BackgroundExecutionResolver
            "location_foreground" -> LocationResolver
            "bluetooth_adapter_state" -> BluetoothResolver(connectedDevices = false)
            "bluetooth_connected_devices" -> BluetoothResolver(connectedDevices = true)
            "activity_recognition_transitions" -> PlayServicesActivityResolver(PlayServicesRequirement.ANY)
            "step_count_recording_api" -> PlayServicesActivityResolver(PlayServicesRequirement.RECORDING_API)
            "calendar_events" -> RuntimePermissionResolver(listOf(Permissions.READ_CALENDAR))
            "health_connect_records" -> HealthConnectResolver(HealthConnectResolver.Kind.RECORDS)
            "health_connect_background_read" -> HealthConnectResolver(HealthConnectResolver.Kind.BACKGROUND_READ)
            "health_connect_history_read" -> HealthConnectResolver(HealthConnectResolver.Kind.HISTORY_READ)
            "health_connect_on_device_steps" -> HealthConnectResolver(HealthConnectResolver.Kind.ON_DEVICE_STEPS)
            "call_state" -> CallStateResolver
            "motion_sensors" -> SensorDebugResolver(SensorDebugResolver.MOTION)
            "ambient_proximity_sensors" -> SensorDebugResolver(SensorDebugResolver.AMBIENT)
            "media_images_video_metadata" -> MediaDebugResolver
            else -> DeviceStateResolver()
        }
    }
}
