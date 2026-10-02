package dev.agentle.core.ui.format

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Test
import java.util.Locale
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Dates and times are formatted in the zone the caller passes. The time pattern is fixed here ("HH:mm") so the
 * assertions do not depend on the platform's CLDR version; `BestTimePatternTest` covers the platform pattern.
 */
class TimeFormatterTest {
    private val us24 = TimeFormatter(Locale.US, use24HourClock = true, timePattern = "HH:mm")
    private val berlin = TimeZone.of("Europe/Berlin")
    private val kolkata = TimeZone.of("Asia/Kolkata")
    private val stJohns = TimeZone.of("America/St_Johns")
    private val chatham = TimeZone.of("Pacific/Chatham")
    private val newYork = TimeZone.of("America/New_York")

    @Test
    fun `one instant shows the local time of each zone`() {
        val instant = Instant.parse("2026-10-02T12:00:00Z")
        assertThat(us24.time(instant, TimeZone.UTC)).isEqualTo("12:00")
        assertThat(us24.time(instant, kolkata)).isEqualTo("17:30")
        assertThat(us24.time(instant, stJohns)).isEqualTo("09:30")
        assertThat(us24.time(instant, chatham)).isEqualTo("01:45")
        assertThat(us24.date(instant, chatham)).isEqualTo("Oct 3, 2026")
    }

    @Test
    fun `the repeated hour of the 25-hour Berlin day carries the UTC offset`() {
        val first = Instant.parse("2026-10-25T00:30:00Z") // 02:30 CEST
        val second = Instant.parse("2026-10-25T01:30:00Z") // 02:30 CET
        assertThat(isAmbiguousLocalTime(first, berlin)).isTrue()
        assertThat(isAmbiguousLocalTime(second, berlin)).isTrue()
        assertThat(us24.time(first, berlin)).isEqualTo("02:30 (GMT+2)")
        assertThat(us24.time(second, berlin)).isEqualTo("02:30 (GMT+1)")
        assertThat(us24.time(Instant.parse("2026-10-25T03:00:00Z"), berlin)).isEqualTo("04:00")
    }

    @Test
    fun `the skipped hour of the 23-hour Berlin day is never ambiguous`() {
        val afterGap = Instant.parse("2026-03-29T01:30:00Z") // 03:30 CEST, 02:00-03:00 did not exist
        assertThat(isAmbiguousLocalTime(afterGap, berlin)).isFalse()
        assertThat(us24.time(afterGap, berlin)).isEqualTo("03:30")
        assertThat(us24.time(Instant.parse("2026-03-29T00:59:00Z"), berlin)).isEqualTo("01:59")
    }

    @Test
    fun `day lengths follow the zone's clock changes`() {
        assertThat(dayLength(LocalDate(2026, 10, 25), berlin)).isEqualTo(25.hours)
        assertThat(dayLength(LocalDate(2026, 3, 29), berlin)).isEqualTo(23.hours)
        assertThat(dayLength(LocalDate(2026, 10, 2), berlin)).isEqualTo(24.hours)
        assertThat(dayLength(LocalDate(2026, 11, 1), stJohns)).isEqualTo(25.hours)
        assertThat(dayLength(LocalDate(2026, 3, 8), newYork)).isEqualTo(23.hours)
        assertThat(dayLength(LocalDate(2026, 10, 25), kolkata)).isEqualTo(24.hours)
        assertThat(isIrregularDay(LocalDate(2026, 10, 25), berlin)).isTrue()
        assertThat(isIrregularDay(LocalDate(2026, 10, 25), TimeZone.UTC)).isFalse()
    }

    @Test
    fun `local dates of an instant differ by zone`() {
        val lateEvening = Instant.parse("2026-10-01T23:30:00Z")
        assertThat(us24.date(lateEvening, TimeZone.UTC)).isEqualTo("Oct 1, 2026")
        assertThat(us24.date(lateEvening, kolkata)).isEqualTo("Oct 2, 2026")
        assertThat(us24.date(lateEvening, stJohns)).isEqualTo("Oct 1, 2026")
    }

    @Test
    fun `dates follow the locale`() {
        val date = LocalDate(2026, 10, 25)
        assertThat(us24.fullDate(date)).isEqualTo("Sunday, October 25, 2026")
        assertThat(TimeFormatter(Locale.GERMANY, use24HourClock = true, timePattern = "HH:mm").date(date)).isEqualTo("25.10.2026")
    }

    @Test
    fun `the UTC offset is localized per instant`() {
        assertThat(us24.utcOffset(Instant.parse("2026-10-02T12:00:00Z"), stJohns)).isEqualTo("GMT-2:30")
        assertThat(us24.utcOffset(Instant.parse("2026-12-02T12:00:00Z"), stJohns)).isEqualTo("GMT-3:30")
    }
}
