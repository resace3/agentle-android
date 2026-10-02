package dev.agentle.core.ui.format

import android.icu.text.DateTimePatternGenerator
import dev.agentle.core.time.dayBounds
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaZoneId
import kotlinx.datetime.toLocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Formats dates and times for display in an explicit zone and [locale]. It never reads the JVM or system default zone:
 * callers pass the user's zone (`AgentleClock.zone()`, through their port).
 *
 * Times use the platform's localized pattern for [use24HourClock] (ICU `DateTimePatternGenerator`, the API behind
 * `DateFormat.getBestDateTimePattern`). A local time that occurs twice on a day when clocks go back (for example 02:30
 * on 2026-10-25 in Europe/Berlin) carries its UTC offset, so the two moments read differently.
 */
public class TimeFormatter(
    public val locale: Locale,
    public val use24HourClock: Boolean,
    timePattern: String = bestTimePattern(locale, use24HourClock),
) {
    private val mediumDateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    private val fullDateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    private val timeFormat: DateTimeFormatter = try {
        DateTimeFormatter.ofPattern(timePattern, locale)
    } catch (ignored: IllegalArgumentException) {
        // A pattern letter this platform's java.time does not know (for example a newer day-period letter).
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    }
    private val offsetFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("O", locale)

    /** A medium date, for example "Oct 25, 2026" (en-US) or "25.10.2026" (de-DE). */
    public fun date(date: LocalDate): String = mediumDateFormat.format(date.toJavaLocalDate())

    /** A full date with the weekday, for day headers: "Sunday, October 25, 2026". */
    public fun fullDate(date: LocalDate): String = fullDateFormat.format(date.toJavaLocalDate())

    /** The local date of [instant] in [zone]. */
    public fun date(instant: Instant, zone: TimeZone): String = date(instant.toLocalDateTime(zone).date)

    /** The local time of [instant] in [zone]; ambiguous local times (clocks going back) include the UTC offset. */
    public fun time(instant: Instant, zone: TimeZone): String {
        val zoned = instant.toZoned(zone)
        val text = timeFormat.format(zoned)
        return if (isAmbiguousLocalTime(instant, zone)) "$text (${offsetFormat.format(zoned)})" else text
    }

    /** The UTC offset of [zone] at [instant], localized ("GMT+2", "GMT-2:30"). */
    public fun utcOffset(instant: Instant, zone: TimeZone): String = offsetFormat.format(instant.toZoned(zone))

    public companion object {
        /** The platform's best localized time pattern ("h:mm a", "HH:mm", ...) for [locale]. */
        public fun bestTimePattern(locale: Locale, use24HourClock: Boolean): String =
            DateTimePatternGenerator.getInstance(locale).getBestPattern(if (use24HourClock) "Hm" else "hm")
    }
}

/** Converts to a `java.time` zoned date-time in [zone] (no default zone involved). */
internal fun Instant.toZoned(zone: TimeZone): ZonedDateTime =
    ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong()), zone.toJavaZoneId())

/** True if the local wall time of [instant] in [zone] also occurs at another instant (a fall-back overlap). */
public fun isAmbiguousLocalTime(instant: Instant, zone: TimeZone): Boolean {
    val zoned = instant.toZoned(zone)
    return zoned.zone.rules.getValidOffsets(zoned.toLocalDateTime()).size > 1
}

/** Length of the local day [date] in [zone]: 24 h, or 23 h / 25 h on days when clocks change. */
public fun dayLength(date: LocalDate, zone: TimeZone): Duration = dayBounds(date, zone).duration

/** Whether [date] in [zone] is not a 24-hour day. */
public fun isIrregularDay(date: LocalDate, zone: TimeZone): Boolean = dayLength(date, zone) != 24.hours
