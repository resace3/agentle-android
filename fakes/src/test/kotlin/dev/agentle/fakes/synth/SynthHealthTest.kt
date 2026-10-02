package dev.agentle.fakes.synth

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SynthHealthTest {
    private val spec = SynthSpec(seed = 42)
    private val events = SyntheticUserTest.events

    @Test
    fun `sleep stages cover every session exactly, contiguously and deterministically`() {
        val sessions = events.filter { it.type == SynthEventType.SLEEP_SESSION }
        assertThat(sessions).isNotEmpty()
        for (session in sessions) {
            val stages = SynthHealth.sleepStages(spec, session)
            assertThat(stages.first().start).isEqualTo(session.start)
            assertThat(stages.last().end).isEqualTo(session.end)
            stages.zipWithNext().forEach { (a, b) -> assertThat(b.start).isEqualTo(a.end) }
            assertThat(stages.all { it.end > it.start }).isTrue()
            assertThat(stages.sumOf { it.minutes }).isEqualTo(session.value.toLong())
            assertThat(stages.first().kind).isEqualTo(SynthSleepStageKind.AWAKE)
            assertThat(SynthHealth.sleepStages(spec, session)).isEqualTo(stages)
        }
        val kinds = sessions.flatMap { SynthHealth.sleepStages(spec, it) }.map { it.kind }.toSet()
        assertThat(kinds).containsExactlyElementsIn(SynthSleepStageKind.entries)
        assertThrows<IllegalArgumentException> { SynthHealth.sleepStages(spec, events.first { it.type == SynthEventType.WEAR_OFF }) }
    }

    @Test
    fun `exercise kind follows the weekday and metrics come from the minutes inside the session`() {
        val sessions = events.filter { it.type == SynthEventType.EXERCISE_SESSION }
        for (session in sessions) {
            val weekday = session.start.toLocalDateTime(session.zone).dayOfWeek
            val expected = if (weekday == DayOfWeek.SATURDAY) SynthExerciseKind.WALKING else SynthExerciseKind.RUNNING
            assertThat(SynthHealth.exerciseKind(session)).isEqualTo(expected)
            val metrics = SynthHealth.exerciseMetrics(session, events)
            val steps = events.filter {
                it.type == SynthEventType.STEPS_MINUTE && it.start >= session.start && it.start < session.end
            }.sumOf { it.value.toLong() }
            assertThat(metrics.steps).isEqualTo(steps)
            assertThat(metrics.distanceMillimeters).isEqualTo(steps * SynthHealth.STRIDE_MILLIMETERS)
            assertThat(metrics.activeKcal).isWithin(1e-9).of(steps * SynthHealth.ACTIVE_KCAL_PER_STEP)
            assertThat(metrics.averageHeartRate).isNotNull()
            assertThat(metrics.averageHeartRate!!).isIn(100L..180L)
        }
        assertThat(SynthHealth.exerciseMetrics(sessions.first(), emptyList()).averageHeartRate).isNull()
        assertThrows<IllegalArgumentException> { SynthHealth.exerciseKind(events.first { it.type == SynthEventType.SLEEP_SESSION }) }
        assertThrows<IllegalArgumentException> { SynthHealth.exerciseMetrics(events.first(), events) }
    }

    @Test
    fun `resting heart rate exists for every recorded day and never on gap days`() {
        val rhr = SynthHealth.restingHeartRates(spec)
        assertThat(rhr).hasSize(88)
        assertThat(rhr.map { it.date }).containsNoneOf(LocalDate(2026, 8, 30), LocalDate(2026, 8, 31))
        assertThat(rhr.all { it.bpm in 50..70 }).isTrue()
        assertThat(rhr.first { it.date == LocalDate(2026, 10, 25) }.zone).isEqualTo(SynthSpec.BERLIN)
        assertThat(SynthHealth.restingHeartRates(spec.copy(profile = SynthProfile.EMPTY))).isEmpty()
        val sparse = spec.copy(profile = SynthProfile.SPARSE)
        val keptDates = SyntheticUser.days(sparse).filter { d -> SyntheticUser.dayPlan(sparse, d).keep }
        assertThat(SynthHealth.restingHeartRates(sparse).map { it.date }).containsExactlyElementsIn(keptDates).inOrder()
        assertThat(keptDates).hasSize(4)
    }

    @Test
    fun `body measurements happen on Monday and Thursday mornings with plausible values`() {
        val readings = SynthHealth.bodyMeasurements(spec)
        assertThat(readings).hasSize(26)
        for (r in readings) {
            val local = r.time.toLocalDateTime(r.zone)
            assertThat(local.dayOfWeek).isAnyOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)
            assertThat(local.hour).isIn(5..10)
            assertThat(r.weightGrams % 50).isEqualTo(0)
            assertThat(r.weightGrams).isIn(74_000L..80_000L)
            assertThat(r.bodyFatPercent).isWithin(2.0).of(21.5)
        }
        assertThat(SynthHealth.bodyMeasurements(spec)).isEqualTo(readings)
    }

    @Test
    fun `derived formulas are fixed`() {
        assertThat(SynthHealth.distanceMillimeters(100)).isEqualTo(76_200)
        assertThat(SynthHealth.activeKcal(100)).isWithin(1e-12).of(4.0)
        assertThat(SynthHealth.floors(119)).isEqualTo(0)
        assertThat(SynthHealth.floors(120)).isEqualTo(1)
    }
}
