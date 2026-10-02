package dev.agentle.core.time

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The "engine day" used for daily caps, nightly behaviour and daily features: a local day that rolls over at
 * [ROLLOVER] (04:00) instead of midnight, so "once per night" covers 22:00-02:00 as one day (docs/research/10 §10.2).
 */
public object EngineDay {
    public val ROLLOVER: LocalTime = LocalTime(4, 0)

    /** The engine day containing [instant] in [zone]. */
    public fun of(instant: Instant, zone: TimeZone): LocalDate {
        val local = instant.toLocalDateTime(zone)
        return if (local.time < ROLLOVER) local.date.minus(DatePeriod(days = 1)) else local.date
    }

    /**
     * Bounds of an engine day `[date 04:00, date+1 04:00)` in [zone]. If 04:00 does not exist on a DST-gap day,
     * kotlinx-datetime resolves it to the first valid instant after the gap.
     */
    public fun bounds(date: LocalDate, zone: TimeZone): ClosedOpenRange {
        val start = LocalDateTime(date, ROLLOVER).toInstant(zone)
        val end = LocalDateTime(date.plus(DatePeriod(days = 1)), ROLLOVER).toInstant(zone)
        return ClosedOpenRange(start, end)
    }
}

/** The engine day of "now". */
public fun AgentleClock.engineDay(): LocalDate = EngineDay.of(now(), zone())
