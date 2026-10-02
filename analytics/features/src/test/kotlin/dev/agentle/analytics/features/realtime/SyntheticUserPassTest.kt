package dev.agentle.analytics.features.realtime

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureSnapshot
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.analytics.features.realtime.testing.HealthSources
import dev.agentle.analytics.features.realtime.testing.InMemoryRealtimeInputs
import dev.agentle.core.model.EventType
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.StepsPayload
import dev.agentle.core.testing.TestAgentleClock
import dev.agentle.fakes.synth.SynthAdapter
import dev.agentle.fakes.synth.SynthEvent
import dev.agentle.fakes.synth.SynthEventType
import dev.agentle.fakes.synth.SynthHealth
import dev.agentle.fakes.synth.SynthSleepStageKind
import dev.agentle.fakes.synth.SynthSpec
import dev.agentle.fakes.synth.SyntheticUser
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * A pass over the 90-day synthetic user of `dev.agentle.fakes.synth` (seed 42: New York with a Berlin trip, a
 * two-day wearable gap, daily wear-off periods, both DST changes): the events go through the in-memory ports as the
 * connectors would store them, the clock follows the user's zone, and every health feature at 12:30, 18:45 and 21:00
 * local is checked against an oracle computed directly from the synthetic events (per minute the watch's count, else
 * the phone's; the session ending on the local date; the civil-date resting heart rate). Every other feature must
 * resolve to a value of its own type.
 */
class SyntheticUserPassTest {
    private val spec = SynthSpec(seed = 42)
    private val stepsToday = FeatureRef("steps_today")
    private val steps60 = FeatureRef("steps_last_60m")
    private val steps30 = FeatureRef("steps_last_30m")
    private val level = FeatureRef("activity_level_last_30m")
    private val sleepMinutes = FeatureRef("sleep_minutes_last_night")
    private val bedtime = FeatureRef("bedtime_last_night")
    private val wake = FeatureRef("wake_time_today")
    private val restingHr = FeatureRef("resting_hr_today")
    private val restingDelta = FeatureRef("resting_hr_delta_vs_28d")
    private val validHeartRates = checkNotNull(RealtimeFeatureCatalog["resting_hr_today"]?.validRange)
    private val seen = mutableMapOf<String, Int>()

    @Test
    fun `90 days of the synthetic user match an oracle computed from the synthetic events`() = runTest {
        val events = SynthAdapter(spec).all()
        val inputs = InMemoryRealtimeInputs.fromEvents(events)
        val clock = TestAgentleClock(spec.windowStart, spec.homeZone)
        val engine = RealtimeFeatureEngine(inputs.toInputs(), clock)
        val oracle = Oracle(events.filter { it.type == EventType.STEP_SAMPLE }.map { it.startTime to it })
        val refs = everyCatalogRef().toSet()

        assertThat(
            inputs.steps.sources.map {
                it.id
            },
        ).containsExactly(HealthSources.GOOGLE_HEALTH_STEPS, HealthSources.PHONE_STEPS).inOrder()
        var evaluations = 0
        for (date in SyntheticUser.days(spec)) {
            val zone = SyntheticUser.planZone(spec, date)
            for (time in listOf(LocalTime(12, 30), LocalTime(18, 45), LocalTime(21, 0))) {
                val t = LocalDateTime(date, time).toInstant(zone)
                if (t >= spec.windowEnd || SyntheticUser.zoneAt(spec, t) != zone) continue
                clock.setWallClock(t)
                clock.setZone(zone)
                inputs.healthSyncedThrough(t)

                val snapshot = engine.resolve(refs, t)

                val message = "$date $time ${zone.id}"
                assertThat(snapshot.zoneId).isEqualTo(zone.id)
                assertSteps(snapshot, oracle, date, zone, t, message)
                if (time == LocalTime(12, 30)) {
                    assertSleep(snapshot, date, t, message)
                    assertRestingHeartRate(snapshot, date, t, message)
                }
                assertEveryValueIsValid(snapshot, message)
                evaluations++
                seen.merge("${snapshot[stepsToday]?.javaClass?.simpleName} steps", 1, Int::plus)
                seen.merge("${snapshot[sleepMinutes]?.javaClass?.simpleName} sleep", 1, Int::plus)
                seen.merge("${(snapshot[level] as? FeatureValue.Known)?.value}", 1, Int::plus)
                if (zone != spec.homeZone) seen.merge("abroad", 1, Int::plus)
            }
        }
        assertThat(evaluations).isAtLeast(250)
        // The oracle is not vacuous: values, gaps, every activity level and the trip all occur.
        assertThat(seen["Known steps"]).isAtLeast(240)
        assertThat(seen["Known sleep"]).isAtLeast(200)
        assertThat(seen["Missing sleep"]).isAtLeast(2)
        assertThat(seen["abroad"]).isAtLeast(20)
        for (name in listOf("MODERATE_OR_VIGOROUS", "LIGHT", "SEDENTARY")) {
            assertWithMessage(name).that(seen["${FeatureScalar.EnumValue(name)}"] ?: 0).isAtLeast(5)
        }
    }

    private fun assertSteps(snapshot: FeatureSnapshot, oracle: Oracle, date: LocalDate, zone: TimeZone, t: Instant, message: String) {
        val windows = mapOf(stepsToday to date.atStartOfDayIn(zone), steps60 to t - 60.minutes, steps30 to t - 30.minutes)
        for ((ref, start) in windows) {
            val minutes = oracle.minutes(start, t)
            val expected = if (minutes.none { it.reported }) {
                FeatureValue.Missing(MissingReason.NO_DATA)
            } else {
                FeatureValue.Known(FeatureScalar.IntValue(minutes.sumOf { it.steps }), t)
            }
            assertWithMessage("$message ${ref.key}").that(snapshot[ref]).isEqualTo(expected)
        }
        val cadence = oracle.minutes(t - 30.minutes, t)
        val levelName = when {
            cadence.count { it.steps >= StepMath.CADENCE_STEPS_PER_MINUTE } >= StepMath.ACTIVE_MINUTES_FOR_MVPA -> "MODERATE_OR_VIGOROUS"
            cadence.sumOf { it.steps } >= StepMath.LIGHT_STEPS -> "LIGHT"
            else -> "SEDENTARY"
        }
        assertWithMessage("$message level").that(snapshot[level]).isEqualTo(FeatureValue.Known(FeatureScalar.EnumValue(levelName), t))
    }

    private fun assertSleep(snapshot: FeatureSnapshot, date: LocalDate, t: Instant, message: String) {
        val candidates = sleepSessions.filter { localOf(it.end, offsetAt(it.end)).date == date }
        val main = candidates.sortedWith(compareByDescending<SynthEvent> { it.end - it.start }.thenBy { it.start }).firstOrNull()
        if (main == null) {
            assertWithMessage("$message sleep").that(snapshot[sleepMinutes]).isEqualTo(FeatureValue.Missing(MissingReason.NO_DATA))
            assertWithMessage("$message bedtime").that(snapshot[bedtime]).isEqualTo(FeatureValue.Missing(MissingReason.NO_DATA))
            return
        }
        val stages = SynthHealth.sleepStages(spec, main)
        val awake = stages.filter { it.kind == SynthSleepStageKind.AWAKE }.sumOf { it.minutes }
        val asleep = (main.end - main.start).inWholeMinutes - awake
        val fellAsleep = stages.first { it.kind != SynthSleepStageKind.AWAKE }.start
        val bed = localOf(fellAsleep, offsetAt(main.start)).time
        val woke = localOf(main.end, offsetAt(main.end)).time

        assertWithMessage("$message sleep").that(snapshot[sleepMinutes]).isEqualTo(FeatureValue.Known(FeatureScalar.IntValue(asleep), t))
        assertWithMessage("$message bedtime").that(snapshot[bedtime])
            .isEqualTo(FeatureValue.Known(FeatureScalar.NightTimeValue(bed.hour * 60 + bed.minute), t))
        assertWithMessage("$message wake").that(snapshot[wake])
            .isEqualTo(FeatureValue.Known(FeatureScalar.LocalTimeValue(woke.hour * 60 + woke.minute), t))
    }

    private fun assertRestingHeartRate(snapshot: FeatureSnapshot, date: LocalDate, t: Instant, message: String) {
        val today = restingHeartRates[date]
        if (today == null) {
            assertWithMessage("$message rhr").that(snapshot[restingHr]).isEqualTo(FeatureValue.Missing(MissingReason.NO_DATA))
            assertWithMessage("$message delta").that(snapshot[restingDelta]).isEqualTo(FeatureValue.Missing(MissingReason.NO_DATA))
            return
        }
        assertWithMessage("$message rhr").that(snapshot[restingHr]).isEqualTo(FeatureValue.Known(FeatureScalar.IntValue(today), t))
        val baseline = (1..28).mapNotNull { restingHeartRates[date.minus(DatePeriod(days = it))] }.filter { it in validHeartRates }.sorted()
        val expected = if (baseline.size < 14) {
            FeatureValue.Missing(MissingReason.NO_DATA)
        } else {
            FeatureValue.Known(FeatureScalar.IntValue(today - baseline[(baseline.size - 1) / 2]), t)
        }
        assertWithMessage("$message delta").that(snapshot[restingDelta]).isEqualTo(expected)
    }

    private fun assertEveryValueIsValid(snapshot: FeatureSnapshot, message: String) {
        for ((key, value) in snapshot.values) {
            val definition = checkNotNull(RealtimeFeatureCatalog[key.substringBefore('{')])
            assertWithMessage("$message $key").that(RealtimeFeatureEngine.validate(definition, value)).isEqualTo(value)
        }
    }

    private val sleepSessions: List<SynthEvent> by lazy { SyntheticUser.generate(spec).filter { it.type == SynthEventType.SLEEP_SESSION } }

    private val restingHeartRates: Map<LocalDate, Long> by lazy {
        SynthHealth.restingHeartRates(spec).associate { it.date to it.bpm.toLong() }
    }

    private fun offsetAt(at: Instant): UtcOffset = SyntheticUser.zoneAt(spec, at).offsetAt(at)

    private fun localOf(at: Instant, offset: UtcOffset) = at.toLocalDateTime(offset.asTimeZone())

    private class Minute(val steps: Long, val reported: Boolean)

    /** Per minute: the watch's count when it reported the minute, else the phone's, else 0 (never a sum of both). */
    private class Oracle(stepEvents: List<Pair<Instant, PersonalEvent>>) {
        private val watch = HashMap<Instant, Long>()
        private val phone = HashMap<Instant, Long>()

        init {
            for ((at, event) in stepEvents) {
                val count = (event.payload as StepsPayload).count
                if (event.source.value == HealthSources.GOOGLE_HEALTH_STEPS) watch[at] = count else phone[at] = count
            }
        }

        fun minutes(start: Instant, end: Instant): List<Minute> {
            val out = mutableListOf<Minute>()
            var m = start
            while (m < end) {
                val value = watch[m] ?: phone[m]
                out += Minute(value ?: 0, value != null)
                m += 1.minutes
            }
            return out
        }
    }
}
