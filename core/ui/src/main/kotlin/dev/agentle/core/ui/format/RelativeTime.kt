package dev.agentle.core.ui.format

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * A moment relative to "now", decided in the user's zone: calendar words ("yesterday", "tomorrow") follow local days
 * in that zone, so a 23 h or 25 h day and travel across zones are handled. The text comes from `relativeTimeText`.
 */
public sealed interface RelativeTime {
    /** Less than a minute away, in either direction. */
    public data object JustNow : RelativeTime

    /** 1-59 minutes ago. */
    public data class MinutesAgo(val minutes: Long) : RelativeTime

    /** Earlier on the same local day, an hour or more ago. */
    public data class HoursAgo(val hours: Long) : RelativeTime

    /** On the previous local day. */
    public data class Yesterday(val at: Instant) : RelativeTime

    /** Two or more local days ago. */
    public data class OnDate(val date: LocalDate) : RelativeTime

    /** 1-59 minutes from now. */
    public data class InMinutes(val minutes: Long) : RelativeTime

    /** Later on the same local day, an hour or more from now. */
    public data class LaterToday(val at: Instant) : RelativeTime

    /** On the next local day. */
    public data class Tomorrow(val at: Instant) : RelativeTime

    /** Two or more local days from now. */
    public data class OnLaterDate(val at: Instant, val date: LocalDate) : RelativeTime

    public companion object {
        /** Classifies [then] relative to [now], with local days taken in [zone]. */
        public fun of(then: Instant, now: Instant, zone: TimeZone): RelativeTime {
            val delta = now - then
            val thenDate = then.toLocalDateTime(zone).date
            val today = now.toLocalDateTime(zone).date
            return when {
                delta.absoluteValue < 1.minutes -> JustNow
                delta.isPositive() && delta < 60.minutes -> MinutesAgo(delta.inWholeMinutes)
                delta.isPositive() -> past(then, thenDate, today, delta.inWholeHours)
                -delta < 60.minutes -> InMinutes((-delta).inWholeMinutes.coerceAtLeast(1))
                else -> future(then, thenDate, today)
            }
        }

        private fun past(then: Instant, thenDate: LocalDate, today: LocalDate, hours: Long): RelativeTime = when (thenDate) {
            today -> HoursAgo(hours)
            today.minus(DatePeriod(days = 1)) -> Yesterday(then)
            else -> OnDate(thenDate)
        }

        private fun future(then: Instant, thenDate: LocalDate, today: LocalDate): RelativeTime = when (thenDate) {
            today -> LaterToday(then)
            today.plus(DatePeriod(days = 1)) -> Tomorrow(then)
            else -> OnLaterDate(then, thenDate)
        }
    }
}
