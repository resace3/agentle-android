package dev.agentle.connectors.android.permissions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.health.connect.client.HealthConnectClient
import dev.agentle.core.model.Blocker
import dev.agentle.core.model.CapabilityStatus
import dev.agentle.core.model.DataCapability
import dev.agentle.core.model.PermissionState

/** Settings screens the Permission Center can open (docs/research/01 §6). */
public enum class SettingsTarget {
    APP_DETAILS,
    USAGE_ACCESS,
    NOTIFICATION_LISTENER,
    APP_NOTIFICATIONS,
    BATTERY_OPTIMIZATION,
    UNUSED_APP_RESTRICTIONS,
    LOCATION_SOURCE,
    BLUETOOTH,
    WIFI,
    AIRPLANE_MODE,
    DATE,
    LOCALE,
    SOUND,
    HEALTH_CONNECT,
    DATA_USAGE,
    STORAGE,
}

/**
 * Builds the Settings intent for a capability or a [SettingsTarget] (docs/research/01 §6). Every candidate is checked
 * with `resolveActivity`; when it does not resolve, the fallbacks are `ACTION_APPLICATION_DETAILS_SETTINGS` for this
 * package and then `Settings.ACTION_SETTINGS`. Callers still catch `ActivityNotFoundException` when starting it.
 */
public class SettingsIntentFactory(
    private val context: Context,
    private val platform: PlatformState,
    private val listenerComponent: ComponentName,
) {
    /** The resolved intent for [target], or a fallback. */
    public fun forTarget(target: SettingsTarget): Intent = resolved(candidate(target))

    /**
     * The screen that fixes [status] of [capability], or null when Settings cannot help: the capability is allowed,
     * unsupported on this device, not in this build, or DENIED (ask in-app with the runtime dialog instead).
     */
    public fun forCapability(capability: DataCapability, status: CapabilityStatus): Intent? =
        targetFor(capability, status)?.let(::forTarget)

    /** The [SettingsTarget] for [status] of [capability] (see [forCapability]). */
    public fun targetFor(capability: DataCapability, status: CapabilityStatus): SettingsTarget? {
        val blockers = status.blockers
        val byBlocker = BLOCKER_TARGETS.firstOrNull { it.first in blockers }?.second
        return when {
            status.state == PermissionState.UNSUPPORTED_ON_DEVICE -> null
            Blocker.NOT_IN_THIS_BUILD in blockers -> null
            byBlocker != null -> byBlocker
            status.state == PermissionState.ALLOWED || status.state == PermissionState.BACKGROUND_ALLOWED -> null
            status.state == PermissionState.DENIED -> null
            capability.specialAccess == SpecialAccess.USAGE_ACCESS -> SettingsTarget.USAGE_ACCESS
            capability.specialAccess == SpecialAccess.NOTIFICATION_LISTENER -> SettingsTarget.NOTIFICATION_LISTENER
            capability.specialAccess == SpecialAccess.BATTERY_OPTIMIZATION -> SettingsTarget.BATTERY_OPTIMIZATION
            capability.specialAccess == SpecialAccess.HEALTH_CONNECT -> SettingsTarget.HEALTH_CONNECT
            status.state == PermissionState.DENIED_PERMANENTLY -> SettingsTarget.APP_DETAILS
            status.state == PermissionState.REQUIRES_SETTINGS -> SettingsTarget.APP_DETAILS
            else -> null
        }
    }

    /** App hibernation / unused-app restrictions (`IntentCompat.createManageUnusedAppRestrictionsIntent`). */
    public fun unusedAppRestrictions(): Intent = forTarget(SettingsTarget.UNUSED_APP_RESTRICTIONS)

    private fun candidate(target: SettingsTarget): Intent? = when (target) {
        SettingsTarget.APP_DETAILS -> appDetails()
        SettingsTarget.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        SettingsTarget.NOTIFICATION_LISTENER -> NotificationListenerResolver.listenerSettingsIntent(platform.sdkInt, listenerComponent)
        SettingsTarget.APP_NOTIFICATIONS ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        SettingsTarget.BATTERY_OPTIMIZATION -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        SettingsTarget.UNUSED_APP_RESTRICTIONS -> unusedRestrictionsIntent()
        SettingsTarget.LOCATION_SOURCE -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        SettingsTarget.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        SettingsTarget.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
        SettingsTarget.AIRPLANE_MODE -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        SettingsTarget.DATE -> Intent(Settings.ACTION_DATE_SETTINGS)
        SettingsTarget.LOCALE -> Intent(Settings.ACTION_LOCALE_SETTINGS)
        SettingsTarget.SOUND -> Intent(Settings.ACTION_SOUND_SETTINGS)
        SettingsTarget.HEALTH_CONNECT -> Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
        SettingsTarget.DATA_USAGE -> Intent(Settings.ACTION_DATA_USAGE_SETTINGS)
        SettingsTarget.STORAGE -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
    }

    /** `createManageUnusedAppRestrictionsIntent` throws when the device has no unused-app restrictions. */
    @Suppress("TooGenericExceptionCaught")
    private fun unusedRestrictionsIntent(): Intent? = try {
        IntentCompat.createManageUnusedAppRestrictionsIntent(context, context.packageName)
    } catch (ignored: RuntimeException) {
        null
    }

    private fun appDetails(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    private fun resolved(candidate: Intent?): Intent {
        val chain = listOfNotNull(candidate, appDetails())
        val first = chain.firstOrNull { platform.canResolveActivity(it) } ?: Intent(Settings.ACTION_SETTINGS)
        return Intent(first).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    public companion object {
        /** Blockers that point at one screen, in priority order. */
        private val BLOCKER_TARGETS: List<Pair<Blocker, SettingsTarget>> = listOf(
            Blocker.ECM_RESTRICTED_SETTINGS to SettingsTarget.APP_DETAILS,
            Blocker.LOCATION_SERVICES_OFF to SettingsTarget.LOCATION_SOURCE,
            Blocker.BLUETOOTH_OFF to SettingsTarget.BLUETOOTH,
            Blocker.NOTIFICATIONS_DISABLED to SettingsTarget.APP_NOTIFICATIONS,
            Blocker.CHANNEL_BLOCKED to SettingsTarget.APP_NOTIFICATIONS,
            Blocker.NOTIFICATIONS_PAUSED to SettingsTarget.APP_DETAILS,
            Blocker.HC_NOT_INSTALLED to SettingsTarget.HEALTH_CONNECT,
            Blocker.HC_UPDATE_REQUIRED to SettingsTarget.HEALTH_CONNECT,
            Blocker.HIBERNATION_ENABLED to SettingsTarget.UNUSED_APP_RESTRICTIONS,
            Blocker.BACKGROUND_RESTRICTED_BY_USER to SettingsTarget.APP_DETAILS,
        )
    }
}
