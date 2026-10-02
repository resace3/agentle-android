package dev.agentle.analytics.features.daily

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.core.time.ClosedOpenRange
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class DailyWindowsTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("lengths")
    fun `window lengths follow local time across DST`(row: String, zone: TimeZone, day: String, window: DailyWindow, hours: Int) {
        val ranges = window.ranges(date(day), ZoneTimeline.fixed(zone))

        assertWithMessage(row).that(ranges).hasSize(1)
        assertThat(IntervalMath.windowMillis(ranges)).isEqualTo(hours.hours.inWholeMilliseconds)
        assertThat(ranges.single()).isEqualTo(window.bounds(date(day), zone))
    }

    @Test
    fun `consecutive engine days partition time on an eastbound trip and the travel day is shorter`() {
        val change = Instant.parse("2026-09-15T20:00:00Z")
        val timeline = ZoneTimeline(NEW_YORK, listOf(ZoneChange(change, BERLIN)))
        val days = (13..18).map { date("2026-09-$it") }
        val windows = days.map { DailyWindow.ENGINE_DAY.ranges(it, timeline) }

        windows.zipWithNext().forEach { (a, b) -> assertThat(a.last().end).isEqualTo(b.first().start) }
        assertThat(windows.map { IntervalMath.windowMillis(it) / 3_600_000 }).containsExactly(24L, 24L, 18L, 24L, 24L, 24L).inOrder()
        assertThat(windows[2]).containsExactly(range(Instant.parse("2026-09-15T08:00:00Z"), Instant.parse("2026-09-16T02:00:00Z")))
    }

    @Test
    fun `a westbound trip makes the travel day longer and never counts an hour twice`() {
        val change = Instant.parse("2026-09-15T12:00:00Z")
        val timeline = ZoneTimeline(BERLIN, listOf(ZoneChange(change, NEW_YORK)))
        val days = (14..17).map { date("2026-09-$it") }
        val windows = days.map { DailyWindow.CALENDAR_DAY.ranges(it, timeline) }

        windows.zipWithNext().forEach { (a, b) -> assertThat(a.last().end).isEqualTo(b.first().start) }
        assertThat(windows.map { IntervalMath.windowMillis(it) / 3_600_000 }).containsExactly(24L, 30L, 24L, 24L).inOrder()
    }

    @Test
    fun `a window split by a short trip keeps both pieces`() {
        val timeline = ZoneTimeline(
            UTC,
            listOf(ZoneChange(Instant.parse("2026-09-15T10:00:00Z"), TOKYO), ZoneChange(Instant.parse("2026-09-15T12:00:00Z"), UTC)),
        )

        val ranges = DailyWindow.DAYTIME_08_21.ranges(date("2026-09-15"), timeline)

        // UTC 08:00-10:00, Tokyo daytime ends 12:00Z (21:00 JST), then UTC again until 21:00.
        assertThat(ranges).containsExactly(range(Instant.parse("2026-09-15T08:00:00Z"), Instant.parse("2026-09-15T21:00:00Z")))
        assertThat(timeline.zoneAt(Instant.parse("2026-09-15T11:00:00Z"))).isEqualTo(TOKYO)
        assertThat(timeline.pieces(range(Instant.parse("2026-09-15T00:00:00Z"), Instant.parse("2026-09-16T00:00:00Z")))).hasSize(3)
    }

    @Test
    fun `timelines compare by zone history`() {
        val a = ZoneTimeline(UTC, listOf(ZoneChange(Instant.parse("2026-09-15T10:00:00Z"), TOKYO)))
        val b = ZoneTimeline(UTC, listOf(ZoneChange(Instant.parse("2026-09-15T10:00:00Z"), TOKYO)))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a).isNotEqualTo(ZoneTimeline.fixed(UTC))
        assertThat(a.toString()).contains("Asia/Tokyo")
    }

    @Test
    fun `merge and hull helpers`() {
        val t = Instant.parse("2026-09-15T00:00:00Z")
        val merged = mergeRanges(listOf(range(t + 2.hours, t + 3.hours), range(t, t + 1.hours), range(t + 1.hours, t + 2.hours)))

        assertThat(merged).containsExactly(range(t, t + 3.hours))
        assertThat(hull(emptyList())).isNull()
        assertThat(hull(listOf(range(t + 5.hours, t + 6.hours), range(t, t + 1.hours)))).isEqualTo(range(t, t + 6.hours))
        assertThat(mergeRanges(listOf(range(t, t + 1.hours)))).hasSize(1)
    }

    @Test
    fun `interval math clips merges gaps and floors minutes`() {
        val t = Instant.parse("2026-09-15T10:00:00Z")
        val window = listOf(range(t, t + 1.hours))
        val ms = { n: Long -> n.milliseconds }

        assertThat(IntervalMath.unionMinutes(listOf(range(t, t + ms(2_699_999))), window)).isEqualTo(44)
        assertThat(IntervalMath.mergeGaps(listOf(range(t, t + ms(1_000)), range(t + ms(3_000), t + ms(4_000))), 2_000))
            .containsExactly(range(t, t + ms(4_000)))
        assertThat(IntervalMath.mergeGaps(listOf(range(t, t + ms(1_000)), range(t + ms(3_001), t + ms(4_000))), 2_000)).hasSize(2)
        assertThat(IntervalMath.fraction(1, 0)).isEqualTo(0.0)
        assertThat(IntervalMath.fraction(3, 2)).isEqualTo(1.0)
        assertThat(IntervalMath.contains(window, t + 1.hours)).isFalse()
    }

    @Test
    fun `exact sums floor once at the end`() {
        val sum = ExactSum()
        sum.addProrated(5, 30_000, 60_000)
        sum.addProrated(5, 30_000, 60_000)
        sum.addProrated(1, 1, 3)
        sum.addProrated(7, 0, 10)
        sum.add(10)

        assertThat(sum.floor()).isEqualTo(15)
        assertThat(sum.hasFraction).isTrue()
        assertThat(sum.toDouble()).isWithin(1e-12).of(15.0 + 1.0 / 3)
        val t = Instant.parse("2026-09-15T10:00:00Z")
        val day = listOf(ClosedOpenRange(t - 10.hours, t + 14.hours))
        val point = ExactSum().apply { addInterval(4, range(t, t), day) }
        val half = ExactSum().apply { addInterval(10, range(t + 13.hours, t + 15.hours), day) }
        assertThat(point.floor()).isEqualTo(4)
        assertThat(half.floor()).isEqualTo(5)
        assertThat(ExactSum().apply { addInterval(3, range(t + 20.hours, t + 20.hours), day) }.floor()).isEqualTo(0)
    }

    companion object {
        @JvmStatic
        fun lengths(): List<Arguments> = listOf(
            Arguments.of("W1 UTC engine day", UTC, "2026-09-14", DailyWindow.ENGINE_DAY, 24),
            Arguments.of("W2 New York engine day before spring forward", NEW_YORK, "2026-03-07", DailyWindow.ENGINE_DAY, 23),
            Arguments.of("W3 New York spring-forward calendar day", NEW_YORK, "2026-03-08", DailyWindow.CALENDAR_DAY, 23),
            Arguments.of("W4 New York engine day before fall back", NEW_YORK, "2026-10-31", DailyWindow.ENGINE_DAY, 25),
            Arguments.of("W5 New York fall-back calendar day", NEW_YORK, "2026-11-01", DailyWindow.CALENDAR_DAY, 25),
            Arguments.of("W6 Berlin engine day before spring forward", BERLIN, "2026-03-28", DailyWindow.ENGINE_DAY, 23),
            Arguments.of("W7 Berlin engine day before fall back", BERLIN, "2026-10-24", DailyWindow.ENGINE_DAY, 25),
            Arguments.of("W8 New York late night with the gap", NEW_YORK, "2026-03-07", DailyWindow.LATE_NIGHT, 5),
            Arguments.of("W9 New York late night with the repeated hour", NEW_YORK, "2026-10-31", DailyWindow.LATE_NIGHT, 7),
            Arguments.of("W10 sleep night", UTC, "2026-09-14", DailyWindow.SLEEP_NIGHT, 20),
            Arguments.of("W11 daytime", UTC, "2026-09-14", DailyWindow.DAYTIME_08_21, 13),
            Arguments.of("W12 evening 21-24", UTC, "2026-09-14", DailyWindow.EVENING_21_24, 3),
            Arguments.of("W13 evening 22-24", UTC, "2026-09-14", DailyWindow.EVENING_22_24, 2),
        )
    }
}
