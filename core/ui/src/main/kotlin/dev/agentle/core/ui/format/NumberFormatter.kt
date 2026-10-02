package dev.agentle.core.ui.format

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.time.Duration

/** Locale-aware numbers: grouping and decimal separator follow [locale] ("1,234.5" in en-US, "1.234,5" in de-DE). */
public class NumberFormatter(public val locale: Locale) {
    /** A whole number with the locale's grouping. */
    public fun integer(value: Long): String = NumberFormat.getIntegerInstance(locale).format(value)

    /** A number with at most [maxFractionDigits] decimals (trailing zeros dropped). */
    public fun decimal(value: Double, maxFractionDigits: Int = 1): String = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = maxFractionDigits
        minimumFractionDigits = 0
    }.format(value)

    /** A percentage from a 0-100 value: "45%", "45 %" (fr), following the locale. */
    public fun percent(value: Int): String = NumberFormat.getPercentInstance(locale).format(value / PERCENT)

    private companion object {
        const val PERCENT = 100.0
    }
}

/** Hours and minutes of a duration for display, rounded to the nearest minute; never negative. */
public data class HoursMinutes(val hours: Long, val minutes: Long) {
    public companion object {
        public fun of(duration: Duration): HoursMinutes {
            val totalMinutes = (duration.inWholeSeconds.coerceAtLeast(0) / SECONDS_PER_MINUTE.toDouble()).roundToLong()
            return HoursMinutes(totalMinutes / MINUTES_PER_HOUR, totalMinutes % MINUTES_PER_HOUR)
        }

        private const val SECONDS_PER_MINUTE = 60L
        private const val MINUTES_PER_HOUR = 60L
    }
}
