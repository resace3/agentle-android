package dev.agentle.feature.settings.ui

import android.icu.text.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Instant
import android.icu.util.TimeZone as IcuTimeZone
import android.text.format.DateFormat as AndroidDateFormat

/**
 * Locale-aware formatting in the user's zone (from `UserTimeZonePort`, never the JVM default) that follows the
 * device's 12/24-hour setting. ICU formats are not thread-safe: use one instance from the main thread only.
 */
internal class DisplayFormats(locale: Locale, is24Hour: Boolean, zone: TimeZone) {
    private val icuZone: IcuTimeZone = IcuTimeZone.getTimeZone(zone.id)
    private val date: DateFormat = skeleton("yMMMd", locale, icuZone)
    private val dateTime: DateFormat = skeleton(if (is24Hour) "yMMMdHm" else "yMMMdhma", locale, icuZone)
    private val dayTime: DateFormat = skeleton(if (is24Hour) "EEEHm" else "EEEhma", locale, icuZone)
    private val timeOfDay: DateFormat = skeleton(if (is24Hour) "Hm" else "hma", locale, IcuTimeZone.getTimeZone("UTC"))
    private val numbers: NumberFormat = NumberFormat.getIntegerInstance(locale)

    /** "Oct 2, 2026". */
    fun date(instant: Instant): String = date.format(Date(instant.toEpochMilliseconds()))

    /** "Oct 2, 2026, 14:03". */
    fun dateTime(instant: Instant): String = dateTime.format(Date(instant.toEpochMilliseconds()))

    /** "Fri 04:00": for instants within the coming week. */
    fun dayTime(instant: Instant): String = dayTime.format(Date(instant.toEpochMilliseconds()))

    /** "22:00" or "10:00 PM" for a wall-clock time. */
    fun time(time: LocalTime): String = timeOfDay.format(Date(time.toSecondOfDay() * MILLIS_PER_SECOND))

    /** "12,345". */
    fun count(value: Long): String = numbers.format(value)

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L

        fun skeleton(skeleton: String, locale: Locale, zone: IcuTimeZone): DateFormat =
            DateFormat.getInstanceForSkeleton(skeleton, locale).apply { timeZone = zone }
    }
}

/** [DisplayFormats] for the current locale, 12/24-hour setting and [zone]. */
@Composable
internal fun rememberDisplayFormats(zone: TimeZone): DisplayFormats {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = AndroidDateFormat.is24HourFormat(context)
    return remember(locale, is24Hour, zone) { DisplayFormats(locale, is24Hour, zone) }
}
