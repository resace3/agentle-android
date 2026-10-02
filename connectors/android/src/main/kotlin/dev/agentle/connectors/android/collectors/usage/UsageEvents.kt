package dev.agentle.connectors.android.collectors.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One `UsageEvents.Event`, reduced to the fields Agentle reads. */
public data class RawUsageEvent(
    val timestampMs: Long,
    val type: Int,
    val packageName: String,
    val className: String? = null,
    /** `getAppStandbyBucket()` for STANDBY_BUCKET_CHANGED events. */
    val standbyBucket: Int? = null,
)

/** `UsageStatsManager.queryEvents` behind a seam (tests revoke access mid-run with a fake). */
public fun interface UsageEventSource {
    /**
     * Events with `beginMs <= timestamp < endMs`, or null while the data is unavailable (the user is locked, R+).
     * Throws `SecurityException` when usage access is gone. Never called with `beginMs >= endMs`.
     */
    public fun query(beginMs: Long, endMs: Long): List<RawUsageEvent>?
}

public class PlatformUsageEventSource(private val context: Context) : UsageEventSource {
    override fun query(beginMs: Long, endMs: Long): List<RawUsageEvent>? {
        require(beginMs < endMs) { "queryEvents needs begin < end" }
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val events = manager.queryEvents(beginMs, endMs) ?: return null
        val out = ArrayList<RawUsageEvent>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(event)) break
            val bucket = if (event.eventType == UsageEvents.Event.STANDBY_BUCKET_CHANGED) event.appStandbyBucket else null
            out += RawUsageEvent(event.timeStamp, event.eventType, event.packageName.orEmpty(), event.className, bucket)
        }
        return out
    }
}

/** Device boot count (`Settings.Global.BOOT_COUNT`, API 24), part of the usage high-water mark. */
public fun interface BootCountSource {
    public fun bootCount(): Int
}

public class PlatformBootCountSource(private val context: Context) : BootCountSource {
    @Suppress("TooGenericExceptionCaught")
    override fun bootCount(): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (ignored: RuntimeException) {
        -1
    }
}

/** `ApplicationInfo.category` as a stable lowercase name (package visibility may hide other apps: then null). */
public fun interface AppCategorySource {
    public fun category(packageName: String): String?
}

public class PlatformAppCategorySource(private val context: Context) : AppCategorySource {
    private val cache = HashMap<String, String?>()

    @Synchronized
    override fun category(packageName: String): String? = cache.getOrPut(packageName) {
        try {
            nameOf(context.packageManager.getApplicationInfo(packageName, 0).category)
        } catch (ignored: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun nameOf(category: Int): String? = when (category) {
        ApplicationInfo.CATEGORY_GAME -> "game"
        ApplicationInfo.CATEGORY_AUDIO -> "audio"
        ApplicationInfo.CATEGORY_VIDEO -> "video"
        ApplicationInfo.CATEGORY_IMAGE -> "image"
        ApplicationInfo.CATEGORY_SOCIAL -> "social"
        ApplicationInfo.CATEGORY_NEWS -> "news"
        ApplicationInfo.CATEGORY_MAPS -> "maps"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productivity"
        CATEGORY_ACCESSIBILITY -> "accessibility"
        else -> null
    }

    private companion object {
        /** `ApplicationInfo.CATEGORY_ACCESSIBILITY` (API 31). */
        const val CATEGORY_ACCESSIBILITY = 8
    }
}

/**
 * The usage high-water mark (red team database-sync-13): `(wall_ms, elapsed_ms, boot_count)` of the end of the last
 * sweep, plus the foreground sessions and the screen-on interval still open at that point, so sessions that span two
 * sweeps close correctly. Stored as JSON in the `("android", "usage")` cursor.
 */
@Serializable
public data class UsageMark(
    val wallMs: Long,
    val elapsedMs: Long,
    val bootCount: Int,
    val open: List<OpenSession> = emptyList(),
    val screenOnMs: Long? = null,
    val v: Int = 1,
) {
    public fun encode(): String = json.encodeToString(serializer(), this)

    public companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Null for an unreadable cursor (the next sweep then starts over from the initial backfill). */
        @Suppress("TooGenericExceptionCaught")
        public fun decode(text: String?): UsageMark? = try {
            text?.let { json.decodeFromString(serializer(), it) }
        } catch (ignored: RuntimeException) {
            null
        }
    }
}

/** A foreground activity session opened by ACTIVITY_RESUMED and not yet closed. */
@Serializable
public data class OpenSession(val pkg: String, val cls: String? = null, val startMs: Long, val index: Int = 0)
