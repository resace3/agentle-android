package dev.agentle.analytics.features.realtime

import dev.agentle.core.time.ClosedOpenRange
import dev.agentle.core.time.dayBounds
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The time-zone rules of R10 §10. The zone is the device's current zone, read once per pass. Rolling windows are
 * instant-based; `since`, "today" and the engine day are local. Local times that fall in a DST gap move later by the
 * gap's length and times in an overlap take the earlier offset, which is what `LocalDateTime.toInstant(zone)` does
 * (java.time `ZonedDateTime.of`, R10 §10.6 and the §10.9 vectors).
 */
internal object LocalTimeRules {
    /** Minute of day 0..1439 of [at] on the local wall clock, truncated (R10 §5.4 A). */
    fun minuteOfDay(at: Instant, zone: TimeZone): Int {
        val time = at.toLocalDateTime(zone).time
        return time.hour * MINUTES_PER_HOUR + time.minute
    }

    /**
     * `since*`: the latest local occurrence of [since] at or before [at] (R10 §10.4): today's occurrence, or the
     * previous date's when today's lies after [at]. A gap shifts it later, an overlap takes the earlier offset.
     */
    fun sinceStart(at: Instant, since: LocalTime, zone: TimeZone): Instant {
        val today = at.toLocalDateTime(zone).date
        val candidate = LocalDateTime(today, since).toInstant(zone)
        return if (candidate <= at) candidate else LocalDateTime(today.minus(ONE_DAY), since).toInstant(zone)
    }

    /** Today's local calendar day `[startOfDay, next startOfDay)` (R10 §10.5); handles midnight gaps (Santiago). */
    fun today(at: Instant, zone: TimeZone): ClosedOpenRange = dayBounds(at.toLocalDateTime(zone).date, zone)

    /** The engine day of [at]: the local date, minus one day before [rollover] (R10 §10.2). */
    fun engineDay(at: Instant, zone: TimeZone, rollover: LocalTime): LocalDate {
        val local = at.toLocalDateTime(zone)
        return if (local.time < rollover) local.date.minus(ONE_DAY) else local.date
    }

    private val ONE_DAY = DatePeriod(days = 1)
    private const val MINUTES_PER_HOUR = 60
}
