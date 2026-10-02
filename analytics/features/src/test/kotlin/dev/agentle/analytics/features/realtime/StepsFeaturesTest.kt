package dev.agentle.analytics.features.realtime

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.Quality
import dev.agentle.analytics.features.realtime.testing.HealthSources
import dev.agentle.analytics.features.realtime.testing.StepSourceSpec
import dev.agentle.core.common.AppError
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.toLocalDateTime
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class StepsFeaturesTest {
    private val watch = HealthSources.GOOGLE_HEALTH_STEPS
    private val healthConnect = HealthSources.HEALTH_CONNECT_STEPS
    private val phone = HealthSources.PHONE_STEPS

    /** R10 §12.D: R2 `steps_today lt 3000` at the 17:00 slot, one canonical source. */
    private fun d(): RealtimeFixture = RealtimeFixture(start = "2026-10-01T17:00").apply { onlyStepSource(watch) }

    private fun RealtimeFixture.steps(source: String, from: String, until: String, count: Long) {
        inputs.steps.add(source, StepInterval(local(from), local(until), count))
    }

    // ------------------------------------------------------------------ R10 §12.D freshness and the lower bound

    @Test
    fun `R10 12D D1 a lag of 29 minutes is fresh`() = runTest {
        val f = d()
        f.steps(watch, "2026-10-01T08:00", "2026-10-01T09:00", 2_999)
        f.stepsCoverage(watch, "2026-10-01T16:31")

        assertThat(f.value("steps_today")).isEqualTo(knownInt(2_999, f.local("2026-10-01T16:31")))
    }

    @Test
    fun `R10 12D D2 3000 steps fresh`() = runTest {
        val f = d()
        f.steps(watch, "2026-10-01T08:00", "2026-10-01T09:00", 3_000)
        f.stepsCoverage(watch, "2026-10-01T16:31")

        assertThat(f.value("steps_today")).isEqualTo(knownInt(3_000, f.local("2026-10-01T16:31")))
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource("D3, 3200", "D9, 3200", "D4, 2800", "D5, 2800")
    fun `R10 12D a lag of 45 minutes is Stale with today's value as the lower bound`(id: String, stale: Long) = runTest {
        val f = d()
        f.steps(watch, "2026-10-01T08:00", "2026-10-01T09:00", stale)
        f.stepsCoverage(watch, "2026-10-01T16:15")

        val value = f.value("steps_today")

        assertWithMessage(id).that(
            value,
        ).isEqualTo(FeatureValue.Stale(FeatureScalar.IntValue(stale), f.local("2026-10-01T16:15"), MissingReason.NOT_SYNCED))
        // The evaluator applies the monotone bound only to a stale value of the same local day (R10 §6.3).
        val asOf = (value as FeatureValue.Stale).asOf
        assertThat(asOf.toLocalDateTime(f.zone).date).isEqualTo(f.now.toLocalDateTime(f.zone).date)
    }

    @Test
    fun `R10 12D D4 a sync during the retries makes the 17 10 retry Known`() = runTest {
        val f = d()
        f.steps(watch, "2026-10-01T08:00", "2026-10-01T09:00", 2_800)
        f.stepsCoverage(watch, "2026-10-01T16:15")
        assertThat(f.value("steps_today")).isInstanceOf(FeatureValue.Stale::class.java)

        f.advanceTo("2026-10-01T17:08")
        f.steps(watch, "2026-10-01T16:20", "2026-10-01T17:05", 150)
        f.stepsCoverage(watch, "2026-10-01T17:05")
        f.advanceTo("2026-10-01T17:10")

        assertThat(f.value("steps_today")).isEqualTo(knownInt(2_950, f.local("2026-10-01T17:05")))
    }

    @Test
    fun `R10 12D D5 without a sync every retry stays Stale`() = runTest {
        val f = d()
        f.steps(watch, "2026-10-01T08:00", "2026-10-01T09:00", 2_800)
        f.stepsCoverage(watch, "2026-10-01T16:15")

        for (time in listOf("2026-10-01T17:00", "2026-10-01T17:10", "2026-10-01T17:20")) {
            f.advanceTo(time)
            assertThat(f.value("steps_today")).isInstanceOf(FeatureValue.Stale::class.java)
        }
    }

    @Test
    fun `R10 12D D6 a day without any step interval is NO_DATA, never 0`() = runTest {
        val f = d()
        f.steps(watch, "2026-09-30T08:00", "2026-09-30T09:00", 4_000)
        f.stepsCoverage(watch, "2026-10-01T17:00")

        assertThat(f.value("steps_today")).isEqualTo(missing(MissingReason.NO_DATA))
    }

    @Test
    fun `R10 12D D7 true zeros reported up to 16 59 are a known 0`() = runTest {
        val f = d()
        f.stepMinutes(watch, "2026-10-01T07:00", minutes = 599, count = 0)
        f.stepsCoverage(watch, "2026-10-01T16:59")

        assertThat(f.value("steps_today")).isEqualTo(knownInt(0, f.local("2026-10-01T16:59")))
    }

    @Test
    fun `R10 12D D8 just after midnight with coverage from yesterday nothing is known for the new day`() = runTest {
        val f = RealtimeFixture(start = "2026-10-02T00:20").apply { onlyStepSource(watch) }
        f.steps(watch, "2026-10-01T08:00", "2026-10-01T09:00", 5_000)
        f.stepsCoverage(watch, "2026-10-01T23:40")

        assertThat(f.value("steps_today")).isEqualTo(missing(MissingReason.NOT_SYNCED))
    }

    @Test
    fun `steps without any coverage assertion are NOT_SYNCED`() = runTest {
        val f = d()
        f.steps(watch, "2026-10-01T08:00", "2026-10-01T09:00", 1_000)

        assertThat(f.value("steps_today")).isEqualTo(missing(MissingReason.NOT_SYNCED))
        assertThat(f.value("steps_last_60m")).isEqualTo(missing(MissingReason.NOT_SYNCED))
        assertThat(f.value("activity_level_last_30m")).isEqualTo(missing(MissingReason.NOT_SYNCED))
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource("steps_last_60m, 16:41", "steps_last_30m, 16:41")
    fun `windowed step sums need a lag of at most 20 minutes and are not lower bounds`(id: String, through: String) = runTest {
        val f = d()
        f.steps(watch, "2026-10-01T16:30", "2026-10-01T16:40", 500)
        f.stepsCoverage(watch, "2026-10-01T$through")

        assertWithMessage(id).that(f.value(id)).isInstanceOf(FeatureValue.Known::class.java)

        f.stepsCoverage(watch, "2026-10-01T16:39")
        assertThat(f.value(id)).isEqualTo(
            FeatureValue.Stale(FeatureScalar.IntValue(500), f.local("2026-10-01T16:39"), MissingReason.NOT_SYNCED),
        )
    }

    @Test
    fun `a window without step data is NO_DATA when fresh and NOT_SYNCED when stale`() = runTest {
        val f = d()
        f.steps(watch, "2026-10-01T08:00", "2026-10-01T09:00", 1_000)
        f.stepsCoverage(watch, "2026-10-01T16:50")
        assertThat(f.value("steps_last_30m")).isEqualTo(missing(MissingReason.NO_DATA))

        f.stepsCoverage(watch, "2026-10-01T16:00")
        assertThat(f.value("steps_last_30m")).isEqualTo(missing(MissingReason.NOT_SYNCED))
    }

    @Test
    fun `R10 12C P values are 2500 and 3500 when fresh`() = runTest {
        for (count in listOf(2_500L, 3_500L)) {
            val f = d()
            f.steps(watch, "2026-10-01T08:00", "2026-10-01T09:00", count)
            f.stepsCoverage(watch, "2026-10-01T17:00")

            assertThat(f.value("steps_today").knownLong).isEqualTo(count)
        }
    }

    // ------------------------------------------------------------------ exact proration (R10 §5.4 F, database-sync-18)

    @Test
    fun `an interval spanning the window start is found and prorated`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T17:30").apply { onlyStepSource(watch) }
        f.steps(watch, "2026-10-01T16:50", "2026-10-01T17:10", 200)
        f.stepsCoverage(watch, "2026-10-01T17:30")

        assertThat(f.value("steps_last_30m").knownLong).isEqualTo(100)
        assertThat(f.value("steps_last_60m").knownLong).isEqualTo(200)
    }

    @Test
    fun `step proration is exact and floors once`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T17:30").apply { onlyStepSource(watch) }
        val windowStart = f.local("2026-10-01T17:00")
        // Ten intervals straddle the window start, each with 1/10 of its length inside: exactly 1 step in total.
        for (u in 1..10) f.inputs.steps.add(watch, StepInterval(windowStart - (9 * u).seconds, windowStart + u.seconds, 1))
        f.stepsCoverage(watch, "2026-10-01T17:30")

        assertThat(f.value("steps_last_30m").knownLong).isEqualTo(1)
        // Summing the shares as doubles would floor to 0.
        assertThat(kotlin.math.floor((1..10).fold(0.0) { acc, u -> acc + u.toDouble() / (10 * u) })).isEqualTo(0.0)
    }

    // ------------------------------------------------------------------ fusion (database-sync-02, jitai-correctness-03)

    @Test
    fun `a watch at 7800 and a phone at 7000 over the same minutes give 7800, not 14800`() = runTest {
        val f = d()
        f.inputs.steps.sources.clear()
        f.inputs.steps.sources += StepSourceSpec(watch, reportsTrueZeros = true)
        f.inputs.steps.sources += StepSourceSpec(phone, reportsTrueZeros = false)
        f.stepMinutes(watch, "2026-10-01T09:00", minutes = 78, count = 100)
        f.stepMinutes(phone, "2026-10-01T09:00", minutes = 70, count = 100)
        f.stepsCoverage(watch, "2026-10-01T17:00")
        f.stepsCoverage(phone, "2026-10-01T17:00")

        assertThat(f.value("steps_today")).isEqualTo(knownInt(7_800, f.now))
    }

    @Test
    fun `phone minutes fill only where the watch has no value`() = runTest {
        val f = d()
        f.inputs.steps.sources.clear()
        f.inputs.steps.sources += StepSourceSpec(watch, reportsTrueZeros = true)
        f.inputs.steps.sources += StepSourceSpec(phone, reportsTrueZeros = false)
        f.stepMinutes(watch, "2026-10-01T09:00", minutes = 30, count = 100) // worn 09:00-09:30
        f.stepMinutes(phone, "2026-10-01T09:00", minutes = 60, count = 90) // the phone counts throughout
        f.stepsCoverage(watch, "2026-10-01T17:00")
        f.stepsCoverage(phone, "2026-10-01T17:00")

        // Off-wrist 09:30-10:00 before the watch's coverage: the phone's minutes are the final value.
        assertThat(f.value("steps_today")).isEqualTo(knownInt(3_000 + 30 * 90, f.now))
    }

    private fun RealtimeFixture.dailyOnlyApiSync(phoneStepsPerMinute: Long, minutes: Int) {
        // The API source synced once, early today, through midnight; the phone is current.
        stepMinutes(watch, "2026-09-30T10:00", minutes = 60, count = 120)
        stepsCoverage(watch, "2026-10-01T00:00")
        stepMinutes(phone, "2026-10-01T08:00", minutes = minutes, count = phoneStepsPerMinute)
        stepsCoverage(phone, "2026-10-01T16:59")
    }

    @Test
    fun `jitai-correctness-03 with a daily-only API sync a 2000-step day makes steps_today lt 3000 true`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T17:00")
        f.dailyOnlyApiSync(phoneStepsPerMinute = 50, minutes = 40)

        val value = f.value("steps_today")

        assertThat(value).isEqualTo(knownInt(2_000, f.local("2026-10-01T16:59"), Quality.PROVISIONAL))
        assertThat(value.knownLong!! < 3_000).isTrue()
    }

    @Test
    fun `jitai-correctness-03 with a daily-only API sync a 9000-step day makes steps_today lt 3000 false`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T17:00")
        f.dailyOnlyApiSync(phoneStepsPerMinute = 100, minutes = 90)

        val value = f.value("steps_today")

        assertThat(value).isEqualTo(knownInt(9_000, f.local("2026-10-01T16:59"), Quality.PROVISIONAL))
        assertThat(value.knownLong!! < 3_000).isFalse()
    }

    @Test
    fun `jitai-correctness-03 once the API source covers the day its values win again`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T17:00")
        f.dailyOnlyApiSync(phoneStepsPerMinute = 50, minutes = 40)
        f.stepMinutes(watch, "2026-10-01T08:00", minutes = 40, count = 55)
        f.stepsCoverage(watch, "2026-10-01T17:00")

        assertThat(f.value("steps_today")).isEqualTo(knownInt(2_200, f.now))
    }

    @Test
    fun `jitai-correctness-03 Health Connect Fitbit-origin steps fill before the phone`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T17:00")
        f.stepsCoverage(watch, "2026-10-01T00:00")
        f.stepMinutes(healthConnect, "2026-10-01T08:00", minutes = 25, count = 100)
        f.stepsCoverage(healthConnect, "2026-10-01T16:50")
        f.stepMinutes(phone, "2026-10-01T08:00", minutes = 25, count = 92)
        f.stepMinutes(phone, "2026-10-01T16:52", minutes = 2, count = 10)
        f.stepsCoverage(phone, "2026-10-01T16:59")

        assertThat(f.value("steps_today")).isEqualTo(knownInt(2_520, f.local("2026-10-01T16:59"), Quality.PROVISIONAL))
    }

    @Test
    fun `no step source at all is NO_DATA`() = runTest {
        val f = d()
        f.inputs.steps.sources.clear()

        assertThat(f.value("steps_today")).isEqualTo(missing(MissingReason.NO_DATA))
    }

    @Test
    fun `a disconnected step source is SOURCE_DISCONNECTED`() = runTest {
        val f = d()
        f.inputs.steps.failure = AppError.TokenExpired("googlehealth")

        assertThat(f.value("steps_today")).isEqualTo(missing(MissingReason.SOURCE_DISCONNECTED))
        assertThat(f.value("activity_level_last_30m")).isEqualTo(missing(MissingReason.SOURCE_DISCONNECTED))
    }

    // ------------------------------------------------------------------ R10 §12.O O13 day bounds

    @Test
    fun `R10 12O O13 steps_today on Berlin 2026-10-25 covers 25 hours`() = runTest {
        val f = RealtimeFixture(start = "2026-10-25T23:59").apply { onlyStepSource(watch) }
        // One interval per real hour of the day: 25 of them.
        val dayStart = f.local("2026-10-25T00:00")
        repeat(25) { h -> f.inputs.steps.add(watch, StepInterval(dayStart + (h * 60).minutes, dayStart + (h * 60 + 1).minutes, 10)) }
        f.inputs.steps.add(watch, StepInterval(dayStart - 30.minutes, dayStart + 30.minutes, 60))
        f.stepsCoverage(watch, "2026-10-25T23:59")

        assertThat(f.value("steps_today").knownLong).isEqualTo(25 * 10 + 30)
    }

    @Test
    fun `R10 12O O13 steps_today on Santiago 2026-09-06 starts at 01 00-03 00`() = runTest {
        val f = RealtimeFixture(zone = Zones.SANTIAGO, start = "2026-09-06T12:00").apply { onlyStepSource(watch) }
        val dayStart = Instant.parse("2026-09-06T04:00:00Z")
        f.inputs.steps.add(watch, StepInterval(dayStart - 30.minutes, dayStart + 30.minutes, 60))
        f.stepsCoverage(watch, "2026-09-06T12:00")

        assertThat(LocalTimeRules.today(f.now, f.zone).start).isEqualTo(dayStart)
        assertThat(f.value("steps_today").knownLong).isEqualTo(30)
    }

    // ------------------------------------------------------------------ R10 §12.I activity level

    private fun stepIntervalsEveryMinute(f: RealtimeFixture, from: Instant, minutes: Int, count: Long) {
        repeat(minutes) { k -> f.inputs.steps.add(watch, StepInterval(from + k.minutes, from + (k + 1).minutes, count)) }
    }

    @ParameterizedTest(name = "{0}: {1}x{2} then {3}x{4}")
    @CsvSource(
        "I1, 10, 105, 20, 0, MODERATE_OR_VIGOROUS",
        "I2, 9, 120, 21, 0, LIGHT",
        "I3, 30, 10, 0, 0, LIGHT",
        "I4, 30, 9, 0, 0, SEDENTARY",
        "I5, 10, 100, 20, 0, MODERATE_OR_VIGOROUS",
        "I6, 10, 99, 20, 0, LIGHT",
    )
    fun `R10 12I activity_level_last_30m from minute cadence`(
        id: String,
        minutesA: Int,
        countA: Long,
        minutesB: Int,
        countB: Long,
        expected: String,
    ) = runTest {
        val f = RealtimeFixture(start = "2026-10-01T18:00").apply { onlyStepSource(watch) }
        val start = f.local("2026-10-01T17:30")
        stepIntervalsEveryMinute(f, start, minutesA, countA)
        stepIntervalsEveryMinute(f, start + minutesA.minutes, minutesB, countB)
        f.stepsCoverage(watch, "2026-10-01T18:00")

        assertWithMessage(id).that(f.value("activity_level_last_30m"))
            .isEqualTo(FeatureValue.Known(FeatureScalar.EnumValue(expected), f.now))
    }

    @Test
    fun `R10 12I I7 23 observed minutes are unknown`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T18:00").apply { onlyStepSource(watch) }
        stepIntervalsEveryMinute(f, f.local("2026-10-01T17:30"), 23, 50)
        f.stepsCoverage(watch, "2026-10-01T17:53")

        assertThat(f.value("activity_level_last_30m")).isEqualTo(missing(MissingReason.COVERAGE_GAP))
    }

    @Test
    fun `24 observed minutes are enough`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T18:00").apply { onlyStepSource(watch) }
        stepIntervalsEveryMinute(f, f.local("2026-10-01T17:30"), 24, 0)
        f.stepsCoverage(watch, "2026-10-01T17:54")

        assertThat(f.value("activity_level_last_30m").knownScalar).isEqualTo(FeatureScalar.EnumValue("SEDENTARY"))
    }

    @Test
    fun `a source that omits zero minutes observes every minute before its coverage`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T18:00").apply { onlyStepSource(phone, reportsTrueZeros = false) }
        f.stepsCoverage(phone, "2026-10-01T18:00")

        // An unworn tracker reads as SEDENTARY (R10 §5.4 F limitation).
        assertThat(f.value("activity_level_last_30m").knownScalar).isEqualTo(FeatureScalar.EnumValue("SEDENTARY"))
    }

    @Test
    fun `activity level coverage older than 20 minutes is NOT_SYNCED or Stale`() = runTest {
        val unobserved = RealtimeFixture(start = "2026-10-01T18:00").apply { onlyStepSource(phone, reportsTrueZeros = false) }
        unobserved.stepsCoverage(phone, "2026-10-01T17:30")
        assertThat(unobserved.value("activity_level_last_30m")).isEqualTo(missing(MissingReason.NOT_SYNCED))

        val stale = RealtimeFixture(start = "2026-10-01T18:00:30").apply { onlyStepSource(watch) }
        stepIntervalsEveryMinute(stale, stale.local("2026-10-01T17:30"), 30, 12)
        stale.stepsCoverage(watch, "2026-10-01T17:40")
        assertThat(stale.value("activity_level_last_30m"))
            .isEqualTo(FeatureValue.Stale(FeatureScalar.EnumValue("LIGHT"), stale.local("2026-10-01T17:40"), MissingReason.NOT_SYNCED))
    }

    @Test
    fun `the activity level window is aligned to whole minutes`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T18:00:45").apply { onlyStepSource(watch) }
        stepIntervalsEveryMinute(f, f.local("2026-10-01T17:30"), 10, 100)
        stepIntervalsEveryMinute(f, f.local("2026-10-01T17:40"), 20, 0)
        f.stepsCoverage(watch, "2026-10-01T18:00")

        assertThat(f.value("activity_level_last_30m").knownScalar).isEqualTo(FeatureScalar.EnumValue("MODERATE_OR_VIGOROUS"))
    }

    @Test
    fun `step values filled by a local copy are provisional for the activity level too`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T18:00")
        f.stepsCoverage(watch, "2026-10-01T00:00")
        f.stepsCoverage(phone, "2026-10-01T18:00")

        assertThat(f.value("activity_level_last_30m"))
            .isEqualTo(FeatureValue.Known(FeatureScalar.EnumValue("SEDENTARY"), f.now, Quality.PROVISIONAL))
        assertThat(f.snapshot(FeatureRef("steps_last_30m"))[FeatureRef("steps_last_30m")]).isEqualTo(missing(MissingReason.NO_DATA))
    }
}
