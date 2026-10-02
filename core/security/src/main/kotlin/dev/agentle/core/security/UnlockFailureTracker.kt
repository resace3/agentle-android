package dev.agentle.core.security

import android.content.Context
import android.provider.Settings
import dev.agentle.core.time.AgentleClock
import java.io.File
import java.io.IOException
import java.util.Properties
import kotlin.time.Duration.Companion.minutes

/** The device boot counter (`Settings.Global.BOOT_COUNT`, API 24), or null when the platform does not provide it. */
fun interface BootCountSource {
    fun bootCount(): Int?
}

class AndroidBootCountSource(private val context: Context) : BootCountSource {
    override fun bootCount(): Int? = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (expected: Settings.SettingNotFoundException) {
        null
    } catch (expected: SecurityException) {
        null
    }
}

/**
 * Consecutive failures to open the local database, kept across process restarts (red team lifecycle-battery-08).
 * [resetAllowed] is true only for a permanent failure, or for failures seen in at least two different boots: a
 * transient Keystore error right after boot never offers to reset local data.
 */
data class UnlockFailureStatus(
    val consecutiveFailures: Int = 0,
    val distinctBoots: Int = 0,
    val lastReason: UnlockFailureReason? = null,
    val firstFailureAtMs: Long? = null,
    val lastFailureAtMs: Long? = null,
) {
    val failing: Boolean get() = consecutiveFailures > 0
    val permanent: Boolean get() = lastReason?.kind == FailureKind.PERMANENT
    val resetAllowed: Boolean get() = failing && (permanent || distinctBoots >= MIN_BOOTS_FOR_TRANSIENT_RESET)

    companion object {
        const val MIN_BOOTS_FOR_TRANSIENT_RESET: Int = 2
    }
}

/**
 * Stores [UnlockFailureStatus] in `noBackupFilesDir/state/unlock-failures.properties` (atomic writes). It only counts:
 * background components record failures but never delete anything; a reset runs only from a visible activity after
 * the user confirms it.
 */
class UnlockFailureTracker(private val paths: SecurityPaths, private val bootCounts: BootCountSource, private val clock: AgentleClock) {
    private val file: File get() = File(paths.stateDir, FILE_NAME)

    @Synchronized
    fun status(): UnlockFailureStatus = load()?.let(::statusOf) ?: UnlockFailureStatus()

    /** Records one failed open and returns the new status. Never throws. */
    @Synchronized
    fun recordFailure(reason: UnlockFailureReason): UnlockFailureStatus {
        val props = load() ?: Properties()
        val now = clock.now().toEpochMilliseconds()
        val count = (props.getProperty(KEY_COUNT)?.toIntOrNull() ?: 0) + 1
        val boots = props.getProperty(KEY_BOOTS).orEmpty().split(',').filter { it.isNotBlank() }.toMutableList()
        val boot = currentBootId()
        if (boot !in boots) boots += boot
        props.setProperty(KEY_COUNT, count.toString())
        props.setProperty(KEY_BOOTS, boots.takeLast(MAX_BOOTS).joinToString(","))
        props.setProperty(KEY_REASON, reason.name)
        if (props.getProperty(KEY_FIRST) == null) props.setProperty(KEY_FIRST, now.toString())
        props.setProperty(KEY_LAST, now.toString())
        try {
            AtomicFiles.write(file) { out -> props.store(out, null) }
        } catch (expected: IOException) {
            // Counting is best effort; the returned status still reflects this failure.
        }
        return statusOf(props)
    }

    /** Called after a successful open. */
    @Synchronized
    fun clear() {
        AtomicFiles.delete(file)
    }

    private fun statusOf(props: Properties): UnlockFailureStatus = UnlockFailureStatus(
        consecutiveFailures = props.getProperty(KEY_COUNT)?.toIntOrNull() ?: 0,
        distinctBoots = props.getProperty(KEY_BOOTS).orEmpty().split(',').count { it.isNotBlank() },
        lastReason = props.getProperty(KEY_REASON)?.let { name -> UnlockFailureReason.entries.firstOrNull { it.name == name } },
        firstFailureAtMs = props.getProperty(KEY_FIRST)?.toLongOrNull(),
        lastFailureAtMs = props.getProperty(KEY_LAST)?.toLongOrNull(),
    )

    private fun load(): Properties? {
        if (!file.exists()) return null
        return try {
            Properties().apply { file.inputStream().use(::load) }
        } catch (expected: IOException) {
            null
        } catch (expected: IllegalArgumentException) {
            null
        }
    }

    /**
     * `Settings.Global.BOOT_COUNT` when available; otherwise the boot instant (wall clock minus elapsed time since boot)
     * in 10-minute buckets, which stays stable within one boot unless the wall clock is changed.
     */
    private fun currentBootId(): String = bootCounts.bootCount()?.let { "b$it" } ?: run {
        val bootInstantMs = clock.now().toEpochMilliseconds() - clock.elapsed().inWholeMilliseconds
        "t${bootInstantMs / BOOT_BUCKET_MS}"
    }

    private companion object {
        const val FILE_NAME = "unlock-failures.properties"
        const val KEY_COUNT = "count"
        const val KEY_BOOTS = "boots"
        const val KEY_REASON = "reason"
        const val KEY_FIRST = "first_ms"
        const val KEY_LAST = "last_ms"
        const val MAX_BOOTS = 8
        val BOOT_BUCKET_MS = 10.minutes.inWholeMilliseconds
    }
}
