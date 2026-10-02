package dev.agentle.jitai.engine.time

import dev.agentle.jitai.dsl.model.ActiveWindow
import dev.agentle.jitai.dsl.rule.ClockTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
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
 * A concrete occurrence of a recurring local-time window (R10 §10.3), identified by the local date it starts on.
 * [start] and [end] are instants: a DST change makes an instance longer or shorter than its nominal length.
 */
public data class WindowInstance(val startDate: LocalDate, val start: Instant, val end: Instant) {
    public operator fun contains(instant: Instant): Boolean = instant >= start && instant < end
}

/**
 * A half-open local-time window `[start, end)` in minutes of the day (R10 §10.3): it crosses midnight when
 * `end < start`; `start == end` is invalid (E025) and never contains anything, so a malformed stored rule fails closed.
 * [days] filters by the start date of the instance; null means every day.
 */
public data class LocalWindow(val startMinute: Int, val endMinute: Int, val days: Set<DayOfWeek>? = null) {
    public val crossesMidnight: Boolean get() = endMinute < startMinute
    public val isValid: Boolean get() = startMinute != endMinute

    /** Whether the minute of day [minute] lies in the window, ignoring [days]. */
    public fun containsMinute(minute: Int): Boolean = when {
        !isValid -> false
        crossesMidnight -> minute >= startMinute || minute < endMinute
        else -> minute in startMinute until endMinute
    }

    /**
     * The instance containing [t] in [zone], or null. Membership is tested on the local wall clock truncated to the
     * minute; the instance bounds are `ZonedDateTime.of(startDate, start, zone)`-style instants (a time in a DST gap is
     * shifted later by the gap length, an ambiguous time takes the earlier offset, R10 §10.6).
     */
    public fun instanceAt(t: Instant, zone: TimeZone): WindowInstance? {
        val local = t.toLocalDateTime(zone)
        val minute = local.hour * MINUTES_PER_HOUR + local.minute
        if (!containsMinute(minute)) return null
        val startDate = if (crossesMidnight && minute < endMinute) local.date.minus(1, DateTimeUnit.DAY) else local.date
        return instanceStartingOn(startDate, zone)
    }

    /** The instance that starts on [startDate], or null when [days] excludes that date or the window is invalid. */
    public fun instanceStartingOn(startDate: LocalDate, zone: TimeZone): WindowInstance? {
        if (!isValid) return null
        if (days != null && startDate.dayOfWeek !in days) return null
        val start = atMinute(startDate, startMinute, zone)
        val endDate = if (crossesMidnight) startDate.plus(1, DateTimeUnit.DAY) else startDate
        val end = atMinute(endDate, endMinute, zone)
        return if (end > start) WindowInstance(startDate, start, end) else null
    }

    /** The first instance whose start is strictly after [after] (looking at most [LOOKAHEAD_DAYS] dates ahead). */
    public fun nextInstanceAfter(after: Instant, zone: TimeZone): WindowInstance? {
        val today = after.toLocalDateTime(zone).date
        return (-1..LOOKAHEAD_DAYS).asSequence()
            .mapNotNull { instanceStartingOn(today.plus(it, DateTimeUnit.DAY), zone) }
            .firstOrNull { it.start > after }
    }

    /** The latest instance that ended at or before [t] (looking at most [LOOKAHEAD_DAYS] dates back). */
    public fun previousInstanceBefore(t: Instant, zone: TimeZone): WindowInstance? {
        val today = t.toLocalDateTime(zone).date
        return (0..LOOKAHEAD_DAYS).asSequence()
            .mapNotNull { instanceStartingOn(today.minus(it, DateTimeUnit.DAY), zone) }
            .firstOrNull { it.end <= t }
    }

    public companion object {
        private const val MINUTES_PER_HOUR = 60
        private const val LOOKAHEAD_DAYS = 8

        /** The whole local calendar day (`[00:00, 24:00)`), used by interval triggers without an active window. */
        public fun wholeDayInstance(t: Instant, zone: TimeZone): WindowInstance {
            val date = t.toLocalDateTime(zone).date
            return wholeDay(date, zone)
        }

        /** The local calendar day [date] as an instance (DST days are 23 or 25 hours long, R10 §10.5). */
        public fun wholeDay(date: LocalDate, zone: TimeZone): WindowInstance {
            val start = atMinute(date, 0, zone)
            val end = atMinute(date.plus(1, DateTimeUnit.DAY), 0, zone)
            return WindowInstance(date, start, end)
        }

        /** Parses an [ActiveWindow]; null when a time is not `HH:mm`. */
        public fun of(window: ActiveWindow): LocalWindow? {
            val start = ClockTime.minuteOfDay(window.start) ?: return null
            val end = ClockTime.minuteOfDay(window.end) ?: return null
            return LocalWindow(start, end, window.days?.map { it.dayOfWeek }?.toSet())
        }

        /** Parses `HH:mm` bounds; null when either is invalid. */
        public fun of(start: String, end: String): LocalWindow? {
            val s = ClockTime.minuteOfDay(start) ?: return null
            val e = ClockTime.minuteOfDay(end) ?: return null
            return LocalWindow(s, e)
        }

        /**
         * The instant of local [date] at [minuteOfDay] in [zone] with `ZonedDateTime.of` semantics: a nonexistent time
         * (gap) is shifted later by the gap length, an ambiguous time (overlap) takes the earlier offset (R10 §10.6).
         */
        public fun atMinute(date: LocalDate, minuteOfDay: Int, zone: TimeZone): Instant =
            LocalDateTime(date, LocalTime(minuteOfDay / MINUTES_PER_HOUR, minuteOfDay % MINUTES_PER_HOUR)).toInstant(zone)
    }
}
