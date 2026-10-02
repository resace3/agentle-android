package dev.agentle.jitai.engine.time

import com.google.common.truth.Truth.assertThat
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.jitai.dsl.model.ActiveWindow
import dev.agentle.jitai.dsl.model.WeekDay
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.decision.DecisionKeys
import dev.agentle.jitai.engine.schedule.IntervalSlots
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** R10 §10 and §12.E / §12.F / §12.O (time model): windows, engine days, calendar features, DST and clocks. */
class TimeModelTest {
    private val friSat = LocalWindow.of(ActiveWindow("22:00", "02:00", listOf(WeekDay.FRI, WeekDay.SAT)))!!

    @ParameterizedTest(name = "{0} at {1}")
    @CsvSource(
        "E3, 2026-10-01T23:00, false, 2026-10-01",
        "E4, 2026-10-02T01:30, false, 2026-10-01",
        "E5, 2026-10-02T23:00, true, 2026-10-02",
        "E6, 2026-10-03T01:30, true, 2026-10-02",
        "E7, 2026-10-04T01:30, true, 2026-10-03",
        "E8, 2026-10-05T01:30, false, 2026-10-04",
    )
    fun `E3-E8 days filter by the start date of the instance`(id: String, local: String, open: Boolean, instanceDate: String) {
        val at = F0.local(local)

        val instance = friSat.instanceAt(at, F0.BERLIN)

        assertThat(instance != null).isEqualTo(open)
        if (instance != null) assertThat(instance.startDate).isEqualTo(LocalDate.parse(instanceDate))
        // Without the day filter the same instant belongs to an instance starting on [instanceDate].
        assertThat(friSat.copy(days = null).instanceAt(at, F0.BERLIN)!!.startDate).isEqualTo(LocalDate.parse(instanceDate))
    }

    @ParameterizedTest(name = "E10 quiet hours at {0}")
    @CsvSource("06:59, true", "07:00, false", "22:00, true", "21:59, false")
    fun `E10 quiet hours 22_00-07_00 are half-open`(time: String, inside: Boolean) {
        val quiet = LocalWindow.of("22:00", "07:00")!!

        assertThat(quiet.containsMinute(dev.agentle.jitai.dsl.rule.ClockTime.minuteOfDay(time)!!)).isEqualTo(inside)
    }

    @Test
    fun `E9 a window with start equal to end never opens (fails closed)`() {
        val window = LocalWindow.of(ActiveWindow("07:00", "07:00"))!!

        assertThat(window.isValid).isFalse()
        assertThat(window.containsMinute(7 * 60)).isFalse()
        assertThat(window.instanceAt(F0.local("2026-10-01T07:00"), F0.BERLIN)).isNull()
        assertThat(window.instanceStartingOn(LocalDate.parse("2026-10-01"), F0.BERLIN)).isNull()
        assertThat(LocalWindow.of(ActiveWindow("7:00", "08:00"))).isNull()
        assertThat(LocalWindow.of("07:00", "8:00")).isNull()
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "F1, 2026-10-01T12:00, THURSDAY, WEEKDAY, THURSDAY, SATURDAY;SUNDAY",
        "F2, 2026-10-02T23:30, FRIDAY, WEEKDAY, FRIDAY, SATURDAY;SUNDAY",
        "F3, 2026-10-03T01:00, SATURDAY, WEEKEND, FRIDAY, SATURDAY;SUNDAY",
        "F4, 2026-10-03T04:00, SATURDAY, WEEKEND, SATURDAY, SATURDAY;SUNDAY",
        "F5, 2026-10-02T12:00, FRIDAY, WEEKEND, FRIDAY, FRIDAY;SATURDAY",
        "F6, 2026-10-04T12:00, SUNDAY, WEEKDAY, SUNDAY, FRIDAY;SATURDAY",
    )
    fun `R10 12_F day_of_week, day_type and engine_day_of_week`(
        id: String,
        local: String,
        dayOfWeek: DayOfWeek,
        dayType: String,
        engineDay: DayOfWeek,
        weekend: String,
    ) {
        val at = F0.local(local)
        val days = weekend.split(';').map(DayOfWeek::valueOf).toSet()
        fun value(feature: String) = CalendarFeatures.value(feature, at, F0.BERLIN, weekend = days)!!.value

        assertThat(value(CalendarFeatures.DAY_OF_WEEK)).isEqualTo(FeatureScalar.DayOfWeekValue(dayOfWeek))
        assertThat(value(CalendarFeatures.DAY_TYPE)).isEqualTo(FeatureScalar.EnumValue(dayType))
        assertThat(value(CalendarFeatures.ENGINE_DAY_OF_WEEK)).isEqualTo(FeatureScalar.DayOfWeekValue(engineDay))
    }

    @Test
    fun `local_time is the local minute and other features are not calendar features`() {
        val at = F0.local("2026-10-01T22:07:59")

        assertThat(CalendarFeatures.value(CalendarFeatures.LOCAL_TIME, at, F0.BERLIN)!!.value)
            .isEqualTo(FeatureScalar.LocalTimeValue(22 * 60 + 7))
        assertThat(CalendarFeatures.value("steps_today", at, F0.BERLIN)).isNull()
        assertThat(CalendarFeatures.IDS).hasSize(4)
    }

    @ParameterizedTest(name = "O11 engine day at {0}")
    @CsvSource("2026-10-02T01:30, 2026-10-01", "2026-10-02T03:59, 2026-10-01", "2026-10-02T04:00, 2026-10-02")
    fun `O11 the engine day rolls over at 04_00`(local: String, engineDay: String) {
        assertThat(EngineDays.of(F0.local(local), F0.BERLIN)).isEqualTo(LocalDate.parse(engineDay))
    }

    @Test
    fun `the rollover setting is clamped to 00_00-06_00 and the next rollover is the next engine day start`() {
        val at = F0.local("2026-10-02T05:30")

        assertThat(EngineDays.of(at, F0.BERLIN, rolloverMinute = 9 * 60)).isEqualTo(LocalDate.parse("2026-10-01"))
        assertThat(EngineDays.clampRollover(-5)).isEqualTo(0)
        assertThat(EngineDays.nextRollover(F0.local("2026-10-01T22:05"), F0.BERLIN)).isEqualTo(F0.local("2026-10-02T04:00"))
        assertThat(
            EngineDays.window(LocalDate.parse("2026-10-07"), 7),
        ).isEqualTo(LocalDate.parse("2026-10-01")..LocalDate.parse("2026-10-07"))
    }

    @Test
    fun `O3 an interval-30 window 22_00-04_00 over the October DST night lasts 7 hours with slots 0-13`() {
        val window = LocalWindow.of("22:00", "04:00")!!
        val instance = window.instanceStartingOn(LocalDate.parse("2026-10-24"), F0.BERLIN)!!

        val slots = IntervalSlots.slots(instance, 30)

        assertThat(instance.end - instance.start).isEqualTo(7.hours)
        assertThat(slots.map { it.index }).isEqualTo((0..13).toList())
        assertThat(IntervalSlots.slotAt(instance, 30, Instant.parse("2026-10-25T00:10:00Z"))!!.index).isEqualTo(8)
        assertThat(IntervalSlots.slotAt(instance, 30, Instant.parse("2026-10-25T01:10:00Z"))!!.index).isEqualTo(10)
        assertThat(window.instanceAt(Instant.parse("2026-10-25T01:10:00Z"), F0.BERLIN)).isEqualTo(instance)
    }

    @Test
    fun `O4 R3 instance 2026-10-01 has 4 hours, slots 0-7, and 23_10 is slot 2`() {
        val instance = LocalWindow.of("22:00", "02:00")!!.instanceStartingOn(LocalDate.parse("2026-10-01"), F0.BERLIN)!!

        assertThat(IntervalSlots.slots(instance, 30).map { it.index }).isEqualTo((0..7).toList())
        assertThat(IntervalSlots.slotAt(instance, 30, F0.local("2026-10-01T23:10"))!!.index).isEqualTo(2)
        assertThat(IntervalSlots.slotAt(instance, 30, F0.local("2026-10-02T02:00"))).isNull()
        assertThat(IntervalSlots.slotAt(instance, 0, F0.local("2026-10-01T23:10"))).isNull()
        assertThat(IntervalSlots.slots(instance, 0)).isEmpty()
    }

    @ParameterizedTest(name = "O5 {0} in {1}")
    @CsvSource(
        "2026-10-01T22:07:30, Europe/Berlin, 1989872",
        "2026-10-01T22:14:59, Europe/Berlin, 1989872",
        "2026-10-01T22:15:00, Europe/Berlin, 1989873",
        "2026-10-02T01:37:30, Asia/Kolkata, 1989872",
    )
    fun `O5 event buckets are UTC 15-minute buckets, immune to the zone`(local: String, zone: String, bucket: Long) {
        assertThat(DecisionKeys.eventBucket(F0.local(local, TimeZone.of(zone)))).isEqualTo(bucket)
    }

    @Test
    fun `O13 day bounds - Berlin 2026-10-25 lasts 25 h, Santiago 2026-09-06 starts at 01_00 and lasts 23 h`() {
        val berlin = LocalWindow.wholeDay(LocalDate.parse("2026-10-25"), F0.BERLIN)
        val santiagoZone = TimeZone.of("America/Santiago")
        val santiago = LocalWindow.wholeDay(LocalDate.parse("2026-09-06"), santiagoZone)

        assertThat(berlin.end - berlin.start).isEqualTo(25.hours)
        assertThat(santiago.end - santiago.start).isEqualTo(23.hours)
        assertThat(santiago.start).isEqualTo(F0.local("2026-09-06T01:00", santiagoZone))
    }

    @Test
    fun `O1 a local time in the spring gap is shifted later by the gap length`() {
        val slot = LocalWindow.atMinute(LocalDate.parse("2026-03-29"), 2 * 60 + 30, F0.BERLIN)

        assertThat(slot).isEqualTo(Instant.parse("2026-03-29T01:30:00Z"))
    }

    @Test
    fun `O2 an ambiguous local time in the autumn overlap takes the earlier offset`() {
        val slot = LocalWindow.atMinute(LocalDate.parse("2026-10-25"), 2 * 60 + 30, F0.BERLIN)

        assertThat(slot).isEqualTo(Instant.parse("2026-10-25T00:30:00Z"))
    }

    @Test
    fun `next and previous instances skip excluded days`() {
        val after = F0.local("2026-10-01T12:00")

        assertThat(friSat.nextInstanceAfter(after, F0.BERLIN)!!.startDate).isEqualTo(LocalDate.parse("2026-10-02"))
        assertThat(friSat.previousInstanceBefore(F0.local("2026-10-05T12:00"), F0.BERLIN)!!.startDate)
            .isEqualTo(LocalDate.parse("2026-10-03"))
        val sameDay = LocalWindow.of("09:00", "17:00")!!
        assertThat(sameDay.crossesMidnight).isFalse()
        assertThat(sameDay.instanceAt(F0.local("2026-10-01T09:00"), F0.BERLIN)!!.end).isEqualTo(F0.local("2026-10-01T17:00"))
    }

    // -- R10 §8.6 clocks --------------------------------------------------------------------------------------------------

    @Test
    fun `elapsed time uses elapsed realtime within one boot and ignores wall clock changes`() {
        val start = MonotonicStamp(F0.local("2026-10-01T22:00"), elapsedMillis = 1_000_000, bootCount = 41)
        val clockSetBack =
            MonotonicStamp(F0.local("2026-10-01T22:05"), elapsedMillis = 1_000_000 + 65.minutes.inWholeMilliseconds, bootCount = 41)

        assertThat(elapsedBetween(start, clockSetBack)).isEqualTo(65.minutes)
        assertThat(isBefore(clockSetBack, start + 70.minutes)).isTrue()
        assertThat(isBefore(clockSetBack, start + 65.minutes)).isFalse()
    }

    @Test
    fun `across boots or without a boot count the wall clock is used, clamped at zero`() {
        val start = MonotonicStamp(F0.local("2026-10-01T22:00"), elapsedMillis = 1_000_000, bootCount = 41)
        val rebooted = MonotonicStamp(F0.local("2026-10-01T22:30"), elapsedMillis = 30_000, bootCount = 42)
        val unknownBoot = MonotonicStamp(F0.local("2026-10-01T21:00"), elapsedMillis = 9_000_000, bootCount = null)

        assertThat(elapsedBetween(start, rebooted)).isEqualTo(30.minutes)
        assertThat(elapsedBetween(start, unknownBoot)).isEqualTo(kotlin.time.Duration.ZERO)
        assertThat(unknownBoot.sameBootAs(unknownBoot)).isFalse()
        assertThat(isBefore(rebooted, start + 60.minutes)).isTrue()
        assertThat(isBefore(rebooted, start + 10.minutes)).isFalse()
    }

    @Test
    fun `the engine clock reads both clocks and shifts future targets within the boot`() {
        val device = dev.agentle.jitai.engine.testing.VirtualDeviceClock(F0.local("2026-10-01T22:00"), F0.BERLIN)
        val clock = EngineClock(device, device)

        val now = clock.now()
        val target = clock.stampAt(now, now.wall + 90.seconds)

        assertThat(now.bootCount).isEqualTo(41)
        assertThat(target.elapsedMillis - now.elapsedMillis).isEqualTo(90_000)
        assertThat(clock.zone()).isEqualTo(F0.BERLIN)
    }
}
