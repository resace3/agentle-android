package dev.agentle.connectors.android.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import dev.agentle.connectors.api.CoverageEndCause
import kotlin.time.Instant

/** The last process exit of this app (API 30+ `ApplicationExitInfo`), behind a seam for tests. */
public fun interface ExitInfoSource {
    /** The most recent exit: its reason code and when it happened; null below API 30 or when unknown. */
    public fun lastExit(): LastExit?

    public data class LastExit(val reason: Int, val at: Instant)
}

/** Reads `ActivityManager.getHistoricalProcessExitReasons` on API 30+. */
public class PlatformExitInfoSource(private val context: Context) : ExitInfoSource {
    @Suppress("TooGenericExceptionCaught")
    override fun lastExit(): ExitInfoSource.LastExit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return null
            val info = manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
            ExitInfoSource.LastExit(info.reason, Instant.fromEpochMilliseconds(info.timestamp))
        } catch (ignored: RuntimeException) {
            null
        }
    }
}

/**
 * Process-start coverage repair (red team lifecycle-battery-01): every interval still open belongs to a process that
 * died without closing it. Each is closed at its last heartbeat with the cause of the last `ApplicationExitInfo`
 * (API 30+) when that exit happened after the heartbeat, otherwise UNKNOWN. Run it once per process, before any
 * collector opens an interval.
 */
public class CoverageBootstrap(private val coverage: SafeCoverage, private val exits: ExitInfoSource) {
    /** Returns the number of intervals closed. */
    public suspend fun closeStaleIntervals(): Int {
        val open = coverage.openIntervals()
        if (open.isEmpty()) return 0
        val exit = exits.lastExit()
        open.forEach { (collector, lastHeartbeat) ->
            val cause = if (exit != null && exit.at >= lastHeartbeat) causeOf(exit.reason) else CoverageEndCause.UNKNOWN
            coverage.close(collector, lastHeartbeat, cause)
        }
        return open.size
    }

    public companion object {
        /** Maps `ApplicationExitInfo.REASON_*` to a coverage end cause. */
        public fun causeOf(reason: Int): CoverageEndCause = when (reason) {
            ApplicationExitInfo.REASON_EXIT_SELF -> CoverageEndCause.PROCESS_EXITED

            ApplicationExitInfo.REASON_LOW_MEMORY -> CoverageEndCause.PROCESS_LOW_MEMORY

            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE ->
                CoverageEndCause.PROCESS_CRASHED

            ApplicationExitInfo.REASON_ANR -> CoverageEndCause.PROCESS_ANR

            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> CoverageEndCause.PROCESS_KILLED_BY_USER

            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> CoverageEndCause.PROCESS_PERMISSION_CHANGE

            ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
            -> CoverageEndCause.PROCESS_PACKAGE_UPDATED

            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            ApplicationExitInfo.REASON_OTHER,
            ApplicationExitInfo.REASON_FREEZER,
            -> CoverageEndCause.PROCESS_KILLED_BY_SYSTEM

            else -> CoverageEndCause.UNKNOWN
        }
    }
}
