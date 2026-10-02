package dev.agentle.jitai.engine.testing

import dev.agentle.core.time.AgentleClock
import dev.agentle.jitai.engine.time.BootCountSource
import dev.agentle.jitai.engine.time.MonotonicStamp
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A virtual device: wall clock, elapsed realtime, zone and boot count, all moved explicitly by the test (R10 §8.6). The
 * zone is only ever the one set here, never the JVM default (testing-build-04).
 *
 * - [advanceBy] / [advanceTo]: time passes (both clocks move together);
 * - [setWallClock]: the user or the network moves the wall clock (elapsed does not move);
 * - [setZone]: travel or a manual zone change;
 * - [reboot]: the device restarts: the boot count increases and elapsed realtime starts again near zero.
 *
 * Reads are safe from any thread; the test moves the clock only while no pass runs.
 */
public class VirtualDeviceClock(start: Instant, zone: TimeZone, bootCount: Int? = 41, elapsedAtStart: Duration = 5.hours) :
    AgentleClock,
    BootCountSource {
    @Volatile
    private var wallNow: Instant = start

    @Volatile
    private var elapsedNow: Duration = elapsedAtStart

    @Volatile
    private var currentZone: TimeZone = zone

    @Volatile
    private var boot: Int? = bootCount

    override val wall: Clock = object : Clock {
        override fun now(): Instant = wallNow
    }

    override fun zone(): TimeZone = currentZone

    override fun elapsed(): Duration = elapsedNow

    override fun bootCount(): Int? = boot

    /** Both clocks now, as the engine reads them. */
    public fun stamp(): MonotonicStamp = MonotonicStamp(wallNow, elapsedNow.inWholeMilliseconds, boot)

    public fun advanceBy(duration: Duration) {
        require(!duration.isNegative()) { "time only moves forward; use setWallClock to move the wall clock back" }
        wallNow += duration
        elapsedNow += duration
    }

    /** Lets time pass until [instant] (wall clock); an instant in the past is a no-op. */
    public fun advanceTo(instant: Instant) {
        if (instant > wallNow) advanceBy(instant - wallNow)
    }

    public fun setWallClock(instant: Instant) {
        wallNow = instant
    }

    public fun setZone(zone: TimeZone) {
        currentZone = zone
    }

    /** Restarts the device after [downtime]: wall time moves on, elapsed realtime restarts, the boot count increases. */
    public fun reboot(downtime: Duration = 1.minutes) {
        wallNow += downtime
        elapsedNow = BOOT_ELAPSED
        boot = boot?.plus(1)
    }

    /** Makes the platform stop reporting a boot count (elapsed comparisons then fall back to the wall clock). */
    public fun loseBootCount() {
        boot = null
    }

    private companion object {
        val BOOT_ELAPSED: Duration = 30.seconds
    }
}
