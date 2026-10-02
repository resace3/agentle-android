package dev.agentle.fakes.synth

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours

/** The eight invariants of docs/research/08 §6.4, plus the golden values of §6.3. */
class SyntheticUserTest {
    @Test
    fun `same seed gives the same data and a different seed gives different data`() {
        assertThat(SyntheticUser.fingerprint(SyntheticUser.generate(TYPICAL))).isEqualTo(SyntheticUser.fingerprint(events))
        assertThat(SyntheticUser.fingerprint(SyntheticUser.generate(TYPICAL.copy(seed = 43))))
            .isNotEqualTo(SyntheticUser.fingerprint(events))
    }

    @Test
    fun `golden count and fingerprint of the typical user are pinned`() {
        // A change here is a reviewed change: every downstream golden (features, screenshots) must be re-recorded.
        assertThat(events.size).isEqualTo(GOLDEN_COUNT)
        assertThat(SyntheticUser.fingerprint(events)).isEqualTo(GOLDEN_SHA256)
        assertThat(SyntheticUser.fingerprint(SyntheticUser.sequence(TYPICAL))).isEqualTo(GOLDEN_SHA256)
    }

    @Test
    fun `testing-build-04 the golden fingerprint does not depend on the JVM default zone`() {
        val saved = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/St_Johns"))
            assertThat(SyntheticUser.fingerprint(SyntheticUser.generate(TYPICAL))).isEqualTo(GOLDEN_SHA256)
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }

    @Test
    fun `testing-build-19 a spec anchored to now ends on the local date of now and keeps the trip in place`() {
        // 2027-03-14 22:30 in New York.
        val now = kotlin.time.Instant.parse("2027-03-15T02:30:00Z")
        val spec = SynthSpec.endingAt(now, seed = 42)
        assertThat(spec.firstDay).isEqualTo(LocalDate(2026, 12, 15))
        assertThat(spec.days).isEqualTo(90)
        assertThat(spec.windowStart).isLessThan(now)
        assertThat(spec.windowEnd).isGreaterThan(now)
        assertThat(spec.trip!!.departure.toLocalDateTime(SynthSpec.NEW_YORK).date).isEqualTo(LocalDate(2027, 2, 24))
        assertThat(spec.trip!!.zone).isEqualTo(TimeZone.of("Europe/Berlin"))
        assertThat(SynthSpec.endingAt(now, seed = 42, days = 30).trip).isNull()
        val anchored = SyntheticUser.generate(spec)
        assertThat(anchored).isNotEmpty()
        assertThat(SyntheticUser.fingerprint(SyntheticUser.generate(SynthSpec.endingAt(now, seed = 42))))
            .isEqualTo(SyntheticUser.fingerprint(anchored))
    }

    @Test
    fun `DST fall-back days have 25 local hours in the zone the user is in`() {
        // 2026-10-25: EU DST ends while the user is in Berlin. 2026-11-01: US DST ends at home in New York.
        for (date in listOf(LocalDate(2026, 10, 25), LocalDate(2026, 11, 1))) {
            assertThat(wornMinutesOn(highVolume, date)).isEqualTo(25 * 60 - wearOffMinutes(highVolume, date))
        }
        val ordinary = LocalDate(2026, 10, 14)
        assertThat(wornMinutesOn(highVolume, ordinary)).isEqualTo(24 * 60 - wearOffMinutes(highVolume, ordinary))
    }

    @Test
    fun `spring-forward day has 23 local hours`() {
        val ny = SynthSpec.NEW_YORK
        val spec = SynthSpec(
            seed = 7,
            profile = SynthProfile.HIGH_VOLUME,
            firstDay = LocalDate(2027, 3, 1),
            days = 30,
            trip = null,
            wearableGapDays = null,
        )
        val hv = SyntheticUser.generate(spec)
        val shortDays = (0 until spec.days).map { spec.firstDay.plus(DatePeriod(days = it)) }.filter { localDayLength(it, ny) == 23.hours }
        assertThat(shortDays).containsExactly(LocalDate(2027, 3, 14))
        assertThat(wornMinutesOn(hv, LocalDate(2027, 3, 14))).isEqualTo(23 * 60 - wearOffMinutes(hv, LocalDate(2027, 3, 14)))
    }

    @Test
    fun `a trip never duplicates or reorders instants and uses only the trip zone`() {
        val hv = SyntheticUser.generate(TYPICAL.copy(profile = SynthProfile.HIGH_VOLUME, wearableGapDays = null))
        val phone = hv.filter { it.type == SynthEventType.PHONE_STEPS_MINUTE }.map { it.start }
        assertThat(phone.toSet()).hasSize(phone.size)
        assertThat(phone).isInStrictOrder()
        val trip = TYPICAL.trip!!
        val during = hv.filter { it.start >= trip.departure && it.start < trip.returnAt }
        assertThat(during).isNotEmpty()
        assertThat(during.map { it.zone }.toSet()).containsExactly(trip.zone)
        assertThat(hv.filter { it.start < trip.departure }.map { it.zone }.toSet()).containsExactly(TYPICAL.homeZone)
        assertThat(hv.filter { it.start >= trip.returnAt }.map { it.zone }.toSet()).containsExactly(TYPICAL.homeZone)
    }

    @Test
    fun `wearable gap days have phone steps but no wearable data`() {
        val firstGap = TYPICAL.firstDay.plus(DatePeriod(days = 20))
        val onFirstGap = events.filter { it.start.toLocalDateTime(it.zone).date == firstGap }
        assertThat(onFirstGap.filter { it.source == SyntheticUser.WEARABLE }).isEmpty()
        assertThat(onFirstGap.filter { it.type == SynthEventType.PHONE_STEPS_MINUTE }).isNotEmpty()
        // On the second gap day only the next night's sleep (it ends on 09-01, a normal day) may start late.
        val secondGap = TYPICAL.firstDay.plus(DatePeriod(days = 21))
        val onSecondGap = events.filter { it.start.toLocalDateTime(it.zone).date == secondGap }
        assertThat(onSecondGap.filter { it.source == SyntheticUser.WEARABLE && it.type != SynthEventType.SLEEP_SESSION }).isEmpty()
        assertThat(onSecondGap.filter { it.type == SynthEventType.SLEEP_SESSION }.map { it.end.toLocalDateTime(it.zone).date })
            .containsExactly(LocalDate(2026, 9, 1))
        assertThat(onSecondGap.filter { it.type == SynthEventType.PHONE_STEPS_MINUTE }).isNotEmpty()
    }

    @Test
    fun `weekend wake-up is later and exercise happens only on Monday, Wednesday and Saturday`() {
        val sleeps = events.filter { it.type == SynthEventType.SLEEP_SESSION }
        fun wakeMinute(e: SynthEvent) = e.end.toLocalDateTime(e.zone).time.toSecondOfDay() / 60.0
        val (weekend, weekday) = sleeps.partition {
            it.end.toLocalDateTime(it.zone).dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        }
        assertThat(weekend.map(::wakeMinute).average() - weekday.map(::wakeMinute).average()).isAtLeast(60.0)
        val exerciseDays = events.filter { it.type == SynthEventType.EXERCISE_SESSION }
            .map { it.start.toLocalDateTime(it.zone).dayOfWeek }
            .toSet()
        assertThat(exerciseDays).containsExactly(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY)
    }

    @Test
    fun `profiles scale as designed`() {
        assertThat(SyntheticUser.generate(TYPICAL.copy(profile = SynthProfile.EMPTY))).isEmpty()
        val sparse = SyntheticUser.generate(TYPICAL.copy(profile = SynthProfile.SPARSE))
        val sparseDays = sparse.map { it.start.toLocalDateTime(it.zone).date }.toSet().size
        assertThat(sparseDays).isIn(3..25)
        assertThat(highVolume.size).isGreaterThan(events.size * 2)
    }

    @Test
    fun `profile goldens are pinned`() {
        val sparse = SyntheticUser.generate(TYPICAL.copy(profile = SynthProfile.SPARSE))
        assertThat(sparse).hasSize(SPARSE_COUNT)
        assertThat(sparse.map { it.start.toLocalDateTime(it.zone).date }.toSet()).hasSize(SPARSE_DAYS)
        assertThat(highVolume).hasSize(HIGH_VOLUME_COUNT)
    }

    @Test
    fun `events carry the offset of their zone and stay inside the window`() {
        val first = events.first()
        assertThat(first.utcOffsetSeconds).isEqualTo(-4 * 3600)
        val minutes = events.filter { it.type != SynthEventType.SLEEP_SESSION }
        assertThat(minutes.all { it.start >= TYPICAL.windowStart && it.start < TYPICAL.windowEnd }).isTrue()
        // The first night's sleep (it ends on the first day) starts the evening before the window.
        val firstSleep = events.first { it.type == SynthEventType.SLEEP_SESSION }
        assertThat(firstSleep.start).isLessThan(TYPICAL.windowStart)
        assertThat(firstSleep.end).isGreaterThan(TYPICAL.windowStart)
        assertThat(events.all { it.end >= it.start }).isTrue()
        val berlin = events.first { it.zone == SynthSpec.BERLIN }
        assertThat(berlin.utcOffsetSeconds).isEqualTo(2 * 3600)
    }

    @Test
    fun `days lists every local date the walk enters, including the trip dates`() {
        val days = SyntheticUser.days(TYPICAL)
        assertThat(days.first()).isEqualTo(LocalDate(2026, 8, 10))
        assertThat(days.last()).isEqualTo(LocalDate(2026, 11, 7))
        assertThat(days).hasSize(90)
        assertThat(days).isInStrictOrder()
        assertThat(SyntheticUser.days(TYPICAL.copy(profile = SynthProfile.EMPTY))).isEmpty()
    }

    @Test
    fun `plans are planned in the zone the user is in at local noon`() {
        assertThat(SyntheticUser.planZone(TYPICAL, LocalDate(2026, 10, 20))).isEqualTo(SynthSpec.NEW_YORK)
        assertThat(SyntheticUser.planZone(TYPICAL, LocalDate(2026, 10, 21))).isEqualTo(SynthSpec.BERLIN)
        assertThat(SyntheticUser.planZone(TYPICAL, LocalDate(2026, 10, 28))).isEqualTo(SynthSpec.BERLIN)
        assertThat(SyntheticUser.planZone(TYPICAL, LocalDate(2026, 10, 29))).isEqualTo(SynthSpec.NEW_YORK)
        assertThat(SyntheticUser.planZone(TYPICAL.copy(trip = null), LocalDate(2026, 10, 21))).isEqualTo(SynthSpec.NEW_YORK)
        val plan = SyntheticUser.dayPlan(TYPICAL, LocalDate(2026, 8, 30))
        assertThat(plan.wearableMissing).isTrue()
        assertThat(plan.dayIndex).isEqualTo(20)
        val again = SyntheticUser.dayPlan(TYPICAL, LocalDate(2026, 8, 30))
        assertThat(listOf(again.sleepStart, again.sleepEnd, again.offStart, again.exerciseEnd))
            .containsExactly(plan.sleepStart, plan.sleepEnd, plan.offStart, plan.exerciseEnd)
            .inOrder()
        assertThat(again.minuteRng.nextLong()).isEqualTo(plan.minuteRng.nextLong())
    }

    private fun wornMinutesOn(hv: List<SynthEvent>, date: LocalDate) =
        hv.count { it.type == SynthEventType.HEART_RATE_SAMPLE && it.start.toLocalDateTime(it.zone).date == date }

    private fun wearOffMinutes(hv: List<SynthEvent>, date: LocalDate) = hv
        .filter { it.type == SynthEventType.WEAR_OFF && it.start.toLocalDateTime(it.zone).date == date }
        .sumOf { (it.end - it.start).inWholeMinutes.toInt() }

    private fun localDayLength(date: LocalDate, zone: TimeZone) =
        date.plus(DatePeriod(days = 1)).atStartOfDayIn(zone) - date.atStartOfDayIn(zone)

    companion object {
        val TYPICAL = SynthSpec(seed = 42)
        val events: List<SynthEvent> by lazy { SyntheticUser.generate(TYPICAL) }
        val highVolume: List<SynthEvent> by lazy { SyntheticUser.generate(TYPICAL.copy(profile = SynthProfile.HIGH_VOLUME)) }

        // Golden values of docs/research/08 §6.3 (prototype pinned 2026-10-01 with Kotlin 2.4.20 on JDK 21).
        const val GOLDEN_COUNT = 57_519
        const val GOLDEN_SHA256 = "cc78bf369e8bed969dd426ff0f364bfa350a3a1fead9d4c686c9b501bda124f2"
        const val SPARSE_COUNT = 1_556
        const val SPARSE_DAYS = 6
        const val HIGH_VOLUME_COUNT = 153_505
    }
}
