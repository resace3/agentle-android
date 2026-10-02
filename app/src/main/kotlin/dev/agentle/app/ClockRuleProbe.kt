package dev.agentle.app

import android.os.SystemClock

/** PROBE (BUILD-INFRA item B): type-resolved detekt must report all three calls. The next commit removes this file. */
internal object ClockRuleProbe {
    fun uptime(): Long = SystemClock.uptimeMillis()

    fun realtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()

    fun realtime(): Long = SystemClock.elapsedRealtime()
}
