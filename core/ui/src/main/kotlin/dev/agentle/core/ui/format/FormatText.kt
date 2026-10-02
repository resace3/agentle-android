package dev.agentle.core.ui.format

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.agentle.core.ui.R
import kotlinx.datetime.TimeZone
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Instant

/** The current locale of the composition (the first of the configuration's locales). */
@Composable
@ReadOnlyComposable
public fun currentLocale(): Locale = LocalConfiguration.current.locales.get(0) ?: Locale.ROOT

/** A [TimeFormatter] for the current locale and the user's 12/24-hour setting. */
@Composable
public fun rememberTimeFormatter(): TimeFormatter {
    val locale = currentLocale()
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    return remember(locale, use24Hour) { TimeFormatter(locale, use24Hour) }
}

/** A [NumberFormatter] for the current locale. */
@Composable
public fun rememberNumberFormatter(): NumberFormatter {
    val locale = currentLocale()
    return remember(locale) { NumberFormatter(locale) }
}

/** "Oct 25, 2026, 2:30 PM": the local date and time of [instant] in [zone]. */
@Composable
public fun dateTimeText(instant: Instant, zone: TimeZone): String {
    val formatter = rememberTimeFormatter()
    return stringResource(R.string.ui_date_time, formatter.date(instant, zone), formatter.time(instant, zone))
}

/**
 * [then] relative to [now] in the user's [zone]: "Just now", "5 min ago", "3 h ago", "Yesterday, 21:30", "Oct 12, 2026",
 * "In 20 min", "Today, 18:00", "Tomorrow, 08:00".
 */
@Composable
public fun relativeTimeText(then: Instant, now: Instant, zone: TimeZone): String {
    val formatter = rememberTimeFormatter()
    return when (val relative = RelativeTime.of(then, now, zone)) {
        RelativeTime.JustNow -> stringResource(R.string.ui_time_just_now)

        is RelativeTime.MinutesAgo ->
            pluralStringResource(R.plurals.ui_time_minutes_ago, relative.minutes.toPluralCount(), relative.minutes)

        is RelativeTime.HoursAgo -> pluralStringResource(R.plurals.ui_time_hours_ago, relative.hours.toPluralCount(), relative.hours)

        is RelativeTime.Yesterday -> stringResource(R.string.ui_time_yesterday_at, formatter.time(relative.at, zone))

        is RelativeTime.OnDate -> formatter.date(relative.date)

        is RelativeTime.InMinutes ->
            pluralStringResource(R.plurals.ui_time_in_minutes, relative.minutes.toPluralCount(), relative.minutes)

        is RelativeTime.LaterToday -> stringResource(R.string.ui_time_today_at, formatter.time(relative.at, zone))

        is RelativeTime.Tomorrow -> stringResource(R.string.ui_time_tomorrow_at, formatter.time(relative.at, zone))

        is RelativeTime.OnLaterDate ->
            stringResource(R.string.ui_date_time, formatter.date(relative.date), formatter.time(relative.at, zone))
    }
}

/** A duration such as "45 min", "7 h 12 min" or "2 h", rounded to the minute; under a minute: "0 min". */
@Composable
public fun durationText(duration: Duration): String {
    val parts = HoursMinutes.of(duration)
    val hours = pluralStringResource(R.plurals.ui_duration_hours, parts.hours.toPluralCount(), parts.hours)
    val minutes = pluralStringResource(R.plurals.ui_duration_minutes, parts.minutes.toPluralCount(), parts.minutes)
    return when {
        parts.hours == 0L -> minutes
        parts.minutes == 0L -> hours
        else -> stringResource(R.string.ui_duration_hours_minutes, hours, minutes)
    }
}

/** A whole number with the locale's grouping ("12,345"). */
@Composable
public fun numberText(value: Long): String = rememberNumberFormatter().integer(value)

private fun Long.toPluralCount(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
