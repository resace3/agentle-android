package dev.agentle.core.testing

import dev.agentle.core.time.AgentleClock
import dev.agentle.core.time.Sleeper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Deterministic [AgentleClock] for tests. [advanceBy] moves wall and elapsed time together (a device that stays
 * awake); [setWallClock] simulates a user changing the time (elapsed does not move); [setZone] simulates travel.
 *
 * Without a [scheduler] the clock moves only when told to. Its [sleeper] waits with `delay` and then advances the
 * clock by the slept duration, so a loop that sleeps until [elapsed] has moved ends (for several coroutines sleeping
 * at once, give it the scheduler).
 *
 * With a [scheduler] (inside `runTest`: `testScheduler`) the scheduler's virtual time drives both clocks:
 * [elapsed] is the scheduler's current time, and [wall] is [start] + scheduler time + the offset that [setWallClock]
 * sets. [advanceBy] advances the scheduler and runs every task due by then, and a `delay` (or [sleeper]) in a
 * coroutine on a test dispatcher of that scheduler moves both clocks. Virtual time has millisecond resolution.
 *
 * The default zone is Australia/Adelaide (daylight saving time, UTC+9:30 and +10:30); test JVMs run in
 * America/St_Johns (build-logic), so code that mixes the JVM default zone with [zone] fails (red team testing-build-04).
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class TestAgentleClock(
    private val start: Instant = DEFAULT_START,
    zone: TimeZone = DEFAULT_ZONE,
    private val scheduler: TestCoroutineScheduler? = null,
) : AgentleClock {
    @Volatile private var manualWall: Instant = start

    @Volatile private var manualElapsed: Duration = Duration.ZERO

    @Volatile private var wallOffset: Duration = Duration.ZERO

    @Volatile private var tz: TimeZone = zone

    override val wall: Clock = object : Clock {
        override fun now(): Instant = scheduler?.let { start + virtualTime(it) + wallOffset } ?: manualWall
    }

    override fun zone(): TimeZone = tz

    override fun elapsed(): Duration = scheduler?.let(::virtualTime) ?: manualElapsed

    override val sleeper: Sleeper = if (scheduler != null) {
        Sleeper.Delay
    } else {
        Sleeper { duration ->
            delay(duration)
            if (duration.isPositive()) advanceBy(duration)
        }
    }

    /**
     * Moves wall and elapsed time forward by [duration]. With a scheduler it advances the scheduler's virtual time and
     * runs the tasks due up to and including the new time; [duration] must then be whole milliseconds.
     */
    public fun advanceBy(duration: Duration) {
        require(!duration.isNegative()) { "Use setWallClock() to move the wall clock backwards" }
        val virtual = scheduler
        if (virtual == null) {
            synchronized(this) {
                manualWall += duration
                manualElapsed += duration
            }
            return
        }
        require(duration == duration.inWholeMilliseconds.milliseconds) { "Virtual time has millisecond resolution" }
        virtual.advanceTimeBy(duration)
        virtual.runCurrent()
    }

    /** Sets the wall clock (forwards or backwards) without moving elapsed time, as a user changing the time does. */
    @Synchronized
    public fun setWallClock(instant: Instant) {
        val virtual = scheduler
        if (virtual == null) {
            manualWall = instant
        } else {
            wallOffset = instant - (start + virtualTime(virtual))
        }
    }

    @Synchronized
    public fun setZone(zone: TimeZone) {
        tz = zone
    }

    private fun virtualTime(scheduler: TestCoroutineScheduler): Duration = scheduler.currentTime.milliseconds

    public companion object {
        /** Wall time of a new clock (with a scheduler: at virtual time zero). */
        public val DEFAULT_START: Instant = Instant.parse("2026-10-01T12:00:00Z")

        /** A daylight-saving zone with a half-hour offset, unlike the test JVMs' default zone (America/St_Johns). */
        public val DEFAULT_ZONE: TimeZone = TimeZone.of("Australia/Adelaide")
    }
}
