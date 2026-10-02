package dev.agentle.core.time

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The single time source of Agentle (docs/ARCHITECTURE.md rule 8).
 *
 * - [wall]: wall-clock instants for stored timestamps and user-facing times.
 * - [zone]: the user's current time zone; a function because it changes at runtime (travel, manual change).
 * - [elapsed]: monotonic time since an arbitrary origin (Android: `SystemClock.elapsedRealtimeNanos()`), used for
 *   timeouts, leases and rate limits so that a user changing the wall clock cannot break them.
 * - [sleeper]: waits on the same time base as [elapsed]; code never pairs this clock with a raw `delay`.
 */
public interface AgentleClock {
    public val wall: Clock

    public fun zone(): TimeZone

    public fun elapsed(): Duration

    /**
     * Waits so that [elapsed] advances by the slept duration. The default, [Sleeper.Delay], fits clocks whose
     * [elapsed] follows the coroutine's time: real time in production, virtual time for a test clock driven by the
     * test dispatcher's scheduler. A clock with its own notion of time overrides it.
     */
    public val sleeper: Sleeper get() = Sleeper.Delay

    public fun now(): Instant = wall.now()

    public fun today(): LocalDate = now().toLocalDateTime(zone()).date
}

/** Start (inclusive) and end (exclusive) of a local calendar day; 23 h and 25 h DST days are handled. */
public fun dayBounds(date: LocalDate, zone: TimeZone): ClosedOpenRange =
    ClosedOpenRange(date.atStartOfDayIn(zone), date.plus(DatePeriod(days = 1)).atStartOfDayIn(zone))

public fun AgentleClock.dayBounds(date: LocalDate): ClosedOpenRange = dayBounds(date, zone())

/** A half-open instant range `[start, end)`. */
public data class ClosedOpenRange(val start: Instant, val end: Instant) {
    init {
        require(end >= start) { "end ($end) must not be before start ($start)" }
    }

    val duration: Duration get() = end - start

    public operator fun contains(instant: Instant): Boolean = instant >= start && instant < end

    public fun overlaps(other: ClosedOpenRange): Boolean = start < other.end && other.start < end

    public fun intersect(other: ClosedOpenRange): ClosedOpenRange? {
        val s = maxOf(start, other.start)
        val e = minOf(end, other.end)
        return if (s < e) ClosedOpenRange(s, e) else null
    }
}
