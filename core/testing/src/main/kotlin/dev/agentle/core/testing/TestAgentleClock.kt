package dev.agentle.core.testing

import dev.agentle.core.time.AgentleClock
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Deterministic [AgentleClock] for tests. [advanceBy] moves wall and elapsed time together (a device that stays
 * awake); [setWallClock] simulates a user changing the time (elapsed does not move); [setZone] simulates travel.
 */
public class TestAgentleClock(start: Instant = Instant.parse("2026-10-01T12:00:00Z"), zone: TimeZone = TimeZone.UTC) : AgentleClock {
    @Volatile private var current: Instant = start

    @Volatile private var elapsedTotal: Duration = Duration.ZERO

    @Volatile private var tz: TimeZone = zone

    override val wall: Clock = object : Clock {
        override fun now(): Instant = current
    }

    override fun zone(): TimeZone = tz

    override fun elapsed(): Duration = elapsedTotal

    @Synchronized
    public fun advanceBy(duration: Duration) {
        require(!duration.isNegative()) { "Use setWallClock() to move the wall clock backwards" }
        current += duration
        elapsedTotal += duration
    }

    @Synchronized
    public fun setWallClock(instant: Instant) {
        current = instant
    }

    @Synchronized
    public fun setZone(zone: TimeZone) {
        tz = zone
    }
}
