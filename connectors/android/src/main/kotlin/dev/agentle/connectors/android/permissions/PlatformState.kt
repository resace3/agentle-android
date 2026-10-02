package dev.agentle.connectors.android.permissions

import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.UserManager
import android.os.ext.SdkExtensions
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import dev.agentle.connectors.api.UnusedAppRestrictions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

/**
 * Every platform read the Permission Center makes (docs/research/01 §5.3), with public APIs only. Each read is safe: a
 * read that throws (a missing service, a `SecurityException`, a Robolectric gap) returns the conservative answer.
 * Tests use the real implementation with Robolectric shadows and override single reads where no shadow exists.
 */
@Suppress("TooManyFunctions")
public open class PlatformState(private val context: Context) {
    public open val sdkInt: Int get() = Build.VERSION.SDK_INT

    public val packageName: String get() = context.packageName

    public open fun isGranted(permission: String): Boolean =
        read(false) { ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED }

    public open fun shouldShowRationale(activity: Activity, permission: String): Boolean =
        read(false) { ActivityCompat.shouldShowRequestPermissionRationale(activity, permission) }

    /** Usage access, mirroring AOSP `UsageStatsService.hasQueryPermission()` (§5.3 B). */
    public open fun usageAccessGranted(): Boolean = read(false) {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return@read false
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    public open fun isUserUnlocked(): Boolean = read(true) { context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true }

    public open fun hasUserRestriction(restriction: String): Boolean =
        read(false) { context.getSystemService(UserManager::class.java)?.hasUserRestriction(restriction) ?: false }

    public open fun isManagedProfile(): Boolean = read(false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(UserManager::class.java)?.isManagedProfile ?: false
        } else {
            false
        }
    }

    public open fun isLowRamDevice(): Boolean = read(false) {
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice ?: false
    }

    public open fun canResolveActivity(intent: Intent): Boolean = read(false) { intent.resolveActivity(context.packageManager) != null }

    public open fun notificationListenerGranted(component: ComponentName): Boolean =
        read(false) { context.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(component) ?: false }

    public open fun notificationsEnabled(): Boolean =
        read(false) { context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: false }

    public open fun notificationsPaused(): Boolean =
        read(false) { context.getSystemService(NotificationManager::class.java)?.areNotificationsPaused() ?: false }

    /** Agentle's own channels that cannot alert: importance NONE, or in a blocked channel group. */
    public open fun blockedChannelIds(): Set<String> = read(emptySet()) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return@read emptySet()
        val blockedGroups = manager.notificationChannelGroups.filter { it.isBlocked }.map { it.id }.toSet()
        manager.notificationChannels
            .filter { channel ->
                channel.importance == NotificationManager.IMPORTANCE_NONE ||
                    channel.group?.let { it in blockedGroups } == true
            }
            .map { it.id }
            .toSet()
    }

    public open fun channelCount(): Int = read(0) {
        context.getSystemService(NotificationManager::class.java)?.notificationChannels?.size
            ?: 0
    }

    public open fun canScheduleExactAlarms(): Boolean = read(false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
        } else {
            true
        }
    }

    public open fun isIgnoringBatteryOptimizations(): Boolean =
        read(false) { context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: false }

    public open fun isBackgroundRestricted(): Boolean =
        read(false) { context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted ?: false }

    /** Agentle's own standby bucket (no permission needed), or null when unreadable. */
    public open fun appStandbyBucket(): Int? = read(null) { context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket }

    public open fun isLocationEnabled(): Boolean = read(false) {
        context.getSystemService(LocationManager::class.java)?.isLocationEnabled
            ?: false
    }

    /** Whether the device has a Bluetooth adapter. */
    public open fun bluetoothSupported(): Boolean = read(false) {
        hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) && context.getSystemService(BluetoothManager::class.java)?.adapter != null
    }

    public open fun bluetoothEnabled(): Boolean = read(false) {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
            ?: false
    }

    public open fun hasSystemFeature(feature: String): Boolean = read(false) { context.packageManager.hasSystemFeature(feature) }

    public open fun hasSensor(type: Int): Boolean = read(false) {
        context.getSystemService(SensorManager::class.java)?.getDefaultSensor(type) !=
            null
    }

    /** `InstallSourceInfo.packageSource` (API 33+), or null below 33 or when unreadable. */
    public open fun packageSource(): Int? = read(null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getInstallSourceInfo(context.packageName).packageSource
        } else {
            null
        }
    }

    public open fun isDebuggable(): Boolean = read(false) { context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }

    /** `SdkExtensions.getExtensionVersion(UPSIDE_DOWN_CAKE)` on API 34+, else 0 (no shadow: tests override it). */
    public open fun upsideDownCakeExtension(): Int = read(0) {
        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        } else {
            0
        }
    }

    /** `PackageManagerCompat.getUnusedAppRestrictionsStatus` mapped to [UnusedAppRestrictions]. */
    public open suspend fun unusedAppRestrictions(): UnusedAppRestrictions {
        val status = try {
            withTimeoutOrNull(UNUSED_RESTRICTIONS_TIMEOUT) { awaitUnusedAppRestrictionsStatus() }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (ignored: Exception) {
            null
        }
        return when (status) {
            UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE -> UnusedAppRestrictions.NOT_AVAILABLE
            UnusedAppRestrictionsConstants.DISABLED -> UnusedAppRestrictions.DISABLED
            UnusedAppRestrictionsConstants.API_30_BACKPORT -> UnusedAppRestrictions.PERMISSION_REVOCATION_BACKPORT
            UnusedAppRestrictionsConstants.API_30 -> UnusedAppRestrictions.PERMISSION_REVOCATION
            UnusedAppRestrictionsConstants.API_31 -> UnusedAppRestrictions.HIBERNATION
            else -> UnusedAppRestrictions.UNKNOWN
        }
    }

    private suspend fun awaitUnusedAppRestrictionsStatus(): Int? = suspendCancellableCoroutine { continuation ->
        val future = PackageManagerCompat.getUnusedAppRestrictionsStatus(context)
        future.addListener(
            {
                val value = try {
                    future.get()
                } catch (ignored: Exception) {
                    null
                }
                if (continuation.isActive) continuation.resume(value)
            },
            Runnable::run,
        )
        continuation.invokeOnCancellation { future.cancel(false) }
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> read(fallback: T, block: () -> T): T = try {
        block()
    } catch (ignored: RuntimeException) {
        fallback
    }

    public companion object {
        public const val PACKAGE_USAGE_STATS: String = "android.permission.PACKAGE_USAGE_STATS"
        private val UNUSED_RESTRICTIONS_TIMEOUT = 5.seconds
    }
}
