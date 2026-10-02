package dev.agentle.jitai.engine.time

import dev.agentle.core.time.AgentleClock
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * One instant read from both device clocks (R10 §8.6; red team lifecycle-battery-20): the wall clock, which the user
 * or the network can move, and elapsed realtime, which is monotonic within one boot and includes deep sleep.
 *
 * @property wall wall-clock instant (stored timestamps, local-time logic, engine days).
 * @property elapsedMillis elapsed realtime in milliseconds since boot (Android `SystemClock.elapsedRealtime()`).
 * @property bootCount `Settings.Global.BOOT_COUNT`, or null when the platform does not report it. Elapsed values are only
 *   compared within one boot; a null boot count never matches, so such stamps fall back to the wall clock.
 */
@Serializable
public data class MonotonicStamp(val wall: Instant, val elapsedMillis: Long, val bootCount: Int?) {
    /** The stamp [duration] later on both clocks (used for deadlines such as leases and snoozes). */
    public operator fun plus(duration: Duration): MonotonicStamp =
        copy(wall = wall + duration, elapsedMillis = elapsedMillis + duration.inWholeMilliseconds)

    /** True when both stamps were taken in the same, known boot. */
    public fun sameBootAs(other: MonotonicStamp): Boolean = bootCount != null && bootCount == other.bootCount
}

/**
 * Elapsed time from [earlier] to [later] (R10 §8.6): the elapsed-realtime difference when both stamps come from the same
 * boot, otherwise the wall-clock difference; both clamped at zero. Moving the wall clock therefore never reopens or
 * shortens a cooldown within one boot.
 */
public fun elapsedBetween(earlier: MonotonicStamp, later: MonotonicStamp): Duration {
    val raw = if (earlier.sameBootAs(later)) (later.elapsedMillis - earlier.elapsedMillis).milliseconds else later.wall - earlier.wall
    return raw.coerceAtLeast(Duration.ZERO)
}

/** True while [now] is strictly before [deadline] (a stamp built as `start + duration`), with the same clock rule. */
public fun isBefore(now: MonotonicStamp, deadline: MonotonicStamp): Boolean =
    if (now.sameBootAs(deadline)) now.elapsedMillis < deadline.elapsedMillis else now.wall < deadline.wall

/** Reports `Settings.Global.BOOT_COUNT` (Android) or null when unknown. */
public fun interface BootCountSource {
    public fun bootCount(): Int?
}

/** The engine's view of [AgentleClock] plus the boot count, so every decision records wall, elapsed and boot together. */
public class EngineClock(private val clock: AgentleClock, private val boot: BootCountSource) {
    /** Both clocks now. */
    public fun now(): MonotonicStamp = MonotonicStamp(clock.now(), clock.elapsed().inWholeMilliseconds, boot.bootCount())

    /** The current zone, read on every call and never cached across passes (R10 §10.1). */
    public fun zone(): TimeZone = clock.zone()

    /** The stamp equivalent of a future wall instant [target] seen from [now] (same boot, elapsed shifted by the difference). */
    public fun stampAt(now: MonotonicStamp, target: Instant): MonotonicStamp = now + (target - now.wall)
}
