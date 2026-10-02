package dev.agentle.core.ui.format

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Calendar words follow local days in the zone the caller passes, never the JVM default zone. */
class RelativeTimeTest {
    private val berlin = TimeZone.of("Europe/Berlin")
    private val stJohns = TimeZone.of("America/St_Johns")
    private val kolkata = TimeZone.of("Asia/Kolkata")
    private val chatham = TimeZone.of("Pacific/Chatham")

    @Test
    fun `less than a minute in either direction is just now`() {
        val now = Instant.parse("2026-10-02T12:00:00Z")
        assertThat(RelativeTime.of(now - 30.seconds, now, TimeZone.UTC)).isEqualTo(RelativeTime.JustNow)
        assertThat(RelativeTime.of(now + 59.seconds, now, TimeZone.UTC)).isEqualTo(RelativeTime.JustNow)
    }

    @Test
    fun `minutes and hours ago on the same local day`() {
        val now = Instant.parse("2026-10-02T12:00:00Z")
        assertThat(RelativeTime.of(now - 5.minutes, now, TimeZone.UTC)).isEqualTo(RelativeTime.MinutesAgo(5))
        assertThat(RelativeTime.of(now - 59.minutes, now, TimeZone.UTC)).isEqualTo(RelativeTime.MinutesAgo(59))
        assertThat(RelativeTime.of(now - 3.hours - 20.minutes, now, TimeZone.UTC)).isEqualTo(RelativeTime.HoursAgo(3))
    }

    @Test
    fun `the same instant pair is yesterday in one zone and today in another`() {
        val then = Instant.parse("2026-10-01T23:30:00Z")
        val now = Instant.parse("2026-10-02T08:00:00Z")
        assertThat(RelativeTime.of(then, now, TimeZone.UTC)).isEqualTo(RelativeTime.Yesterday(then))
        // Asia/Kolkata (+05:30): 05:00 and 13:30 on 2 October.
        assertThat(RelativeTime.of(then, now, kolkata)).isEqualTo(RelativeTime.HoursAgo(8))
        // America/St_Johns (-02:30 in October): 21:00 on 1 October and 05:30 on 2 October.
        assertThat(RelativeTime.of(then, now, stJohns)).isEqualTo(RelativeTime.Yesterday(then))
        // Pacific/Chatham (+13:45 in October): 13:15 and 21:45 on 2 October.
        assertThat(RelativeTime.of(then, now, chatham)).isEqualTo(RelativeTime.HoursAgo(8))
    }

    @Test
    fun `on the 25-hour Berlin day an event before the clock change is still today`() {
        // 2026-10-25: 03:00 CEST becomes 02:00 CET at 01:00 UTC. 00:15 local (22:15 UTC the day before) to 23:30 local.
        val then = Instant.parse("2026-10-24T22:15:00Z")
        val now = Instant.parse("2026-10-25T22:30:00Z")
        assertThat(RelativeTime.of(then, now, berlin)).isEqualTo(RelativeTime.HoursAgo(24))
        assertThat(RelativeTime.of(then, now, TimeZone.UTC)).isEqualTo(RelativeTime.Yesterday(then))
    }

    @Test
    fun `on the 23-hour Berlin day the hour after midnight is yesterday only before local midnight`() {
        // 2026-03-29: 02:00 CET becomes 03:00 CEST at 01:00 UTC.
        val now = Instant.parse("2026-03-29T21:30:00Z") // 23:30 CEST
        val justAfterMidnight = Instant.parse("2026-03-28T23:05:00Z") // 00:05 CET on the 29th
        val justBeforeMidnight = Instant.parse("2026-03-28T22:55:00Z") // 23:55 CET on the 28th
        assertThat(RelativeTime.of(justAfterMidnight, now, berlin)).isEqualTo(RelativeTime.HoursAgo(22))
        assertThat(RelativeTime.of(justBeforeMidnight, now, berlin)).isEqualTo(RelativeTime.Yesterday(justBeforeMidnight))
    }

    @Test
    fun `older moments show their local date`() {
        val now = Instant.parse("2026-10-02T12:00:00Z")
        val then = Instant.parse("2026-09-29T23:30:00Z")
        assertThat(RelativeTime.of(then, now, TimeZone.UTC)).isEqualTo(RelativeTime.OnDate(LocalDate(2026, 9, 29)))
        assertThat(RelativeTime.of(then, now, kolkata)).isEqualTo(RelativeTime.OnDate(LocalDate(2026, 9, 30)))
    }

    @Test
    fun `future moments are minutes, later today, tomorrow or a later date`() {
        val now = Instant.parse("2026-10-02T12:00:00Z")
        assertThat(RelativeTime.of(now + 20.minutes, now, TimeZone.UTC)).isEqualTo(RelativeTime.InMinutes(20))
        val evening = now + 6.hours
        assertThat(RelativeTime.of(evening, now, TimeZone.UTC)).isEqualTo(RelativeTime.LaterToday(evening))
        val tomorrow = Instant.parse("2026-10-03T08:00:00Z")
        assertThat(RelativeTime.of(tomorrow, now, TimeZone.UTC)).isEqualTo(RelativeTime.Tomorrow(tomorrow))
        val later = Instant.parse("2026-10-05T08:00:00Z")
        assertThat(RelativeTime.of(later, now, TimeZone.UTC)).isEqualTo(RelativeTime.OnLaterDate(later, LocalDate(2026, 10, 5)))
        // 08:00 UTC on 3 October is 13:30 (Kolkata) and 05:30 (St. John's) on 3 October: tomorrow in both zones.
        assertThat(RelativeTime.of(tomorrow, now, kolkata)).isEqualTo(RelativeTime.Tomorrow(tomorrow))
        assertThat(RelativeTime.of(tomorrow, now, stJohns)).isEqualTo(RelativeTime.Tomorrow(tomorrow))
    }
}
