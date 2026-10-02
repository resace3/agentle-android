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
 * A recurring local-time window such as 22:00-07:00 (quiet hours, active windows). [start] == [end] means the
 * whole day. A window that crosses midnight belongs to the day it starts on.
 */
public data class LocalTimeWindow(val start: LocalTime, val end: LocalTime) {
    val crossesMidnight: Boolean get() = end < start
    val isAllDay: Boolean get() = start == end

    /** True if the local wall time of [instant] in [zone] falls inside the window (start inclusive, end exclusive). */
    public fun contains(instant: Instant, zone: TimeZone): Boolean {
        if (isAllDay) return true
        val t = instant.toLocalDateTime(zone).time
        return if (crossesMidnight) t >= start || t < end else t >= start && t < end
    }

    /**
     * The concrete occurrence of this window that contains [instant], or null. Times that do not exist because of
     * a DST gap resolve to the first valid instant after the gap.
     */
    public fun occurrenceContaining(instant: Instant, zone: TimeZone): ClosedOpenRange? {
        if (!contains(instant, zone)) return null
        val local = instant.toLocalDateTime(zone)
        val startDate: LocalDate = when {
            isAllDay -> local.date
            crossesMidnight && local.time < end -> local.date.minus(DatePeriod(days = 1))
            else -> local.date
        }
        val endDate = if (crossesMidnight || isAllDay) startDate.plus(DatePeriod(days = 1)) else startDate
        val s = LocalDateTime(startDate, start).toInstant(zone)
        val e = LocalDateTime(endDate, end).toInstant(zone)
        return if (e > s) ClosedOpenRange(s, e) else null
    }

    override fun toString(): String = "$start-$end"

    public companion object {
        /** Parses "HH:mm-HH:mm". */
        public fun parse(text: String): LocalTimeWindow {
            val parts = text.split('-')
            require(parts.size == 2) { "Expected HH:mm-HH:mm but was '$text'" }
            return LocalTimeWindow(LocalTime.parse(parts[0].trim()), LocalTime.parse(parts[1].trim()))
        }
    }
}
