package dev.agentle.core.time

import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * JVM production clock. The Android app binds a variant whose [elapsed] reads `SystemClock.elapsedRealtimeNanos()`
 * and whose zone reads `TimeZone.getDefault()` after `TIMEZONE_CHANGED` resets it.
 */
public class SystemAgentleClock(
    private val zoneProvider: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val elapsedProvider: (() -> Duration)? = null,
) : AgentleClock {
    private val origin = TimeSource.Monotonic.markNow()

    override val wall: Clock = Clock.System

    override fun zone(): TimeZone = zoneProvider()

    override fun elapsed(): Duration = elapsedProvider?.invoke() ?: origin.elapsedNow()
}
