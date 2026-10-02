package dev.agentle.core.time

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class EngineDayTest {
    private val berlin = TimeZone.of("Europe/Berlin")
    private val newYork = TimeZone.of("America/New_York")

    @Test
    fun `before 04_00 belongs to the previous engine day`() {
        val instant = Instant.parse("2026-10-02T01:30:00Z") // 03:30 in Berlin (CEST, +2)
        assertThat(EngineDay.of(instant, berlin)).isEqualTo(LocalDate(2026, 10, 1))
    }

    @Test
    fun `at 04_00 a new engine day starts`() {
        val instant = Instant.parse("2026-10-02T02:00:00Z") // 04:00 in Berlin
        assertThat(EngineDay.of(instant, berlin)).isEqualTo(LocalDate(2026, 10, 2))
    }

    @Test
    fun `engine day is 25 hours on the EU DST end night`() {
        // DST ends 2026-10-25 at 03:00 CEST -> 02:00 CET, inside engine day 2026-10-24.
        val bounds = EngineDay.bounds(LocalDate(2026, 10, 24), berlin)
        assertThat(bounds.duration).isEqualTo(25.hours)
    }

    @Test
    fun `engine day is 23 hours on the US DST start night`() {
        // DST starts 2027-03-14 at 02:00 EST -> 03:00 EDT.
        val bounds = EngineDay.bounds(LocalDate(2027, 3, 13), newYork)
        assertThat(bounds.duration).isEqualTo(23.hours)
    }

    @Test
    fun `calendar day bounds handle 25 hour days`() {
        val bounds = dayBounds(LocalDate(2026, 10, 25), berlin)
        assertThat(bounds.duration).isEqualTo(25.hours)
        assertThat(bounds.start).isEqualTo(Instant.parse("2026-10-24T22:00:00Z"))
    }
}

class LocalTimeWindowTest {
    private val utc = TimeZone.UTC

    @Test
    fun `window crossing midnight contains late evening and early morning`() {
        val w = LocalTimeWindow.parse("22:00-07:00")
        assertThat(w.crossesMidnight).isTrue()
        assertThat(w.contains(Instant.parse("2026-10-01T23:30:00Z"), utc)).isTrue()
        assertThat(w.contains(Instant.parse("2026-10-02T06:59:00Z"), utc)).isTrue()
        assertThat(w.contains(Instant.parse("2026-10-02T07:00:00Z"), utc)).isFalse()
        assertThat(w.contains(Instant.parse("2026-10-01T21:59:59Z"), utc)).isFalse()
    }

    @Test
    fun `occurrence of a midnight-crossing window starts on the previous day after midnight`() {
        val w = LocalTimeWindow(LocalTime(22, 0), LocalTime(7, 0))
        val occ = w.occurrenceContaining(Instant.parse("2026-10-02T03:00:00Z"), utc)!!
        assertThat(occ.start).isEqualTo(Instant.parse("2026-10-01T22:00:00Z"))
        assertThat(occ.end).isEqualTo(Instant.parse("2026-10-02T07:00:00Z"))
    }

    @Test
    fun `same day window`() {
        val w = LocalTimeWindow.parse("09:00-17:00")
        assertThat(w.contains(Instant.parse("2026-10-01T09:00:00Z"), utc)).isTrue()
        assertThat(w.contains(Instant.parse("2026-10-01T17:00:00Z"), utc)).isFalse()
        assertThat(w.occurrenceContaining(Instant.parse("2026-10-01T18:00:00Z"), utc)).isNull()
    }

    @Test
    fun `all day window`() {
        val w = LocalTimeWindow.parse("00:00-00:00")
        assertThat(w.isAllDay).isTrue()
        assertThat(w.contains(Instant.parse("2026-10-01T13:00:00Z"), utc)).isTrue()
    }

    @Test
    fun `an all day window that starts after midnight contains the early hours in the previous occurrence`() {
        val w = LocalTimeWindow.parse("04:00-04:00")
        val early = Instant.parse("2026-10-02T02:00:00Z")
        val occ = w.occurrenceContaining(early, utc)!!
        assertThat(early in occ).isTrue()
        assertThat(occ.start).isEqualTo(Instant.parse("2026-10-01T04:00:00Z"))
        assertThat(occ.end).isEqualTo(Instant.parse("2026-10-02T04:00:00Z"))
        val later = w.occurrenceContaining(Instant.parse("2026-10-02T04:00:00Z"), utc)!!
        assertThat(later.start).isEqualTo(Instant.parse("2026-10-02T04:00:00Z"))
    }

    @Test
    fun `every occurrence contains the instant it was found for`() {
        val windows = listOf("22:00-07:00", "09:00-17:00", "00:00-00:00", "04:00-04:00", "23:59-00:01").map(LocalTimeWindow::parse)
        val zone = TimeZone.of("America/New_York")
        var t = Instant.parse("2026-11-01T00:00:00Z") // spans the US fall-back transition
        repeat(48 * 4) {
            windows.forEach { w -> w.occurrenceContaining(t, zone)?.let { assertThat(t in it).isTrue() } }
            t += 15.minutes
        }
    }

    @Test
    fun `range intersection and containment`() {
        val a = ClosedOpenRange(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-01T10:00:00Z"))
        val b = ClosedOpenRange(Instant.parse("2026-10-01T05:00:00Z"), Instant.parse("2026-10-01T15:00:00Z"))
        assertThat(a.overlaps(b)).isTrue()
        assertThat(a.intersect(b)!!.duration).isEqualTo(5.hours)
        assertThat(Instant.parse("2026-10-01T10:00:00Z") in a).isFalse()
    }
}
