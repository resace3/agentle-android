package dev.agentle.background

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import dev.agentle.background.port.ProcessExit

/** Platform state the scheduler reads; a seam so tests can script it (R02 §7.0 production seams). */
public interface DeviceSignals {
    public fun isPowerSaveMode(): Boolean

    /** `UsageStatsManager.getAppStandbyBucket()` (API 28+), or null if unavailable. */
    public fun standbyBucket(): Int?

    /** `ActivityManager.getHistoricalProcessExitReasons` on API 30+ (empty below). */
    public fun processExits(): List<ProcessExit>

    /** `Settings.Global.BOOT_COUNT`, or -1. */
    public fun bootCount(): Int

    public fun appVersionCode(): Long
}

public class AndroidDeviceSignals(private val context: Context) : DeviceSignals {
    override fun isPowerSaveMode(): Boolean = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

    override fun standbyBucket(): Int? = context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket

    override fun processExits(): List<ProcessExit> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
            .map { ProcessExit(it.timestamp, it.reason) }
    }

    override fun bootCount(): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    override fun appVersionCode(): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    } catch (@Suppress("SwallowedException") e: android.content.pm.PackageManager.NameNotFoundException) {
        -1
    }

    private companion object {
        const val MAX_EXITS = 16
    }
}
