package dev.agentle.app

import android.os.SystemClock
import java.util.Date

/** PROBE (BUILD-INFRA item B): detektTest must report both calls in Android test sources. The next commit removes it. */
internal object ClockRuleTestProbe {
    fun uptime(): Long = SystemClock.uptimeMillis()

    fun wallDate(): Date = Date()
}
