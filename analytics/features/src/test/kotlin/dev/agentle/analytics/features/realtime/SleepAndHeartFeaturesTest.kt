package dev.agentle.analytics.features.realtime

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.Quality
import dev.agentle.core.common.AppError
import dev.agentle.core.model.SleepStageKind
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SleepAndHeartFeaturesTest {
    private val berlinSummer = UtcOffset.parse("+02:00")
    private val newYorkSummer = UtcOffset.parse("-04:00")

    private fun at(local: String, offset: UtcOffset = berlinSummer): Instant = LocalDateTime.parse(local).toInstant(offset)

    private fun session(
        start: String,
        end: String,
        offset: UtcOffset = berlinSummer,
        stages: List<SleepStageSpan> = emptyList(),
        minutesAsleep: Long? = null,
        mainSleep: Boolean? = true,
        nap: Boolean? = false,
        processed: Boolean? = true,
    ) = SleepSessionRecord(at(start, offset), at(end, offset), offset, offset, stages, minutesAsleep, mainSleep, nap, processed)

    /** R10 §12.J: evaluated 2026-10-02 08:00 in Berlin, so D = 2026-10-02; the sleep source synced at 08:00. */
    private fun j(start: String = "2026-10-02T08:00"): RealtimeFixture = RealtimeFixture(start = start).apply {
        inputs.sourceCoverage.set(inputs.sleep.source, HealthMetric.SLEEP, now)
    }

    private val sleepRefs = listOf(FeatureRef("sleep_minutes_last_night"), FeatureRef("bedtime_last_night"), FeatureRef("wake_time_today"))

    // ------------------------------------------------------------------ R10 §12.J

    @ParameterizedTest(name = "{0}: minutesAsleep {1}")
    @CsvSource("J1, 355", "J2, 360")
    fun `R10 12J the upstream summary is the minutes asleep`(id: String, minutes: Long) = runTest {
        val f = j()
        f.inputs.sleep.rows += session("2026-10-01T23:10", "2026-10-02T05:30", minutesAsleep = minutes)

        assertWithMessage(id).that(f.value("sleep_minutes_last_night")).isEqualTo(knownInt(minutes, f.now))
        assertThat(f.value("wake_time_today")).isEqualTo(FeatureValue.Known(FeatureScalar.LocalTimeValue(5 * 60 + 30), f.now))
        assertThat(f.value("bedtime_last_night")).isEqualTo(FeatureValue.Known(FeatureScalar.NightTimeValue(23 * 60 + 10), f.now))
    }

    @Test
    fun `R10 12J J3 without a summary the asleep stages count and RESTLESS does not`() = runTest {
        val f = j()
        val start = at("2026-10-01T23:00")
        val plan = listOf(
            SleepStageKind.AWAKE to 20,
            SleepStageKind.LIGHT to 200,
            SleepStageKind.DEEP to 60,
            SleepStageKind.RESTLESS to 10,
            SleepStageKind.REM to 90,
        )
        var cursor = start
        val stages = plan.map { (kind, minutes) ->
            SleepStageSpan(kind, cursor, cursor + minutes.minutes).also { cursor += minutes.minutes }
        }
        f.inputs.sleep.rows += SleepSessionRecord(start, cursor, berlinSummer, berlinSummer, stages, mainSleep = true, nap = false)

        assertThat(f.value("sleep_minutes_last_night")).isEqualTo(knownInt(350, f.now))
        // The first asleep stage starts after 20 minutes awake.
        assertThat(f.value("bedtime_last_night").knownScalar).isEqualTo(FeatureScalar.NightTimeValue(23 * 60 + 20))
    }

    @Test
    fun `R10 12J J4 only a nap is unknown`() = runTest {
        val f = j()
        f.inputs.sleep.rows += session("2026-10-02T06:30", "2026-10-02T07:10", minutesAsleep = 35, mainSleep = false, nap = true)

        for (ref in sleepRefs) assertThat(f.resolve(ref)).isEqualTo(missing(MissingReason.NO_DATA))
    }

    @Test
    fun `R10 12J J5 Health Connect sessions under 3 hours are naps`() = runTest {
        val f = j()
        f.inputs.sleep.source = "healthconnect.sleep"
        f.inputs.sourceCoverage.set("healthconnect.sleep", HealthMetric.SLEEP, f.now)
        f.inputs.sleep.rows += session("2026-10-01T22:30", "2026-10-02T01:00", mainSleep = null, nap = null)
        f.inputs.sleep.rows += session("2026-10-02T01:40", "2026-10-02T06:40", mainSleep = null, nap = null)

        assertThat(f.value("wake_time_today").knownScalar).isEqualTo(FeatureScalar.LocalTimeValue(6 * 60 + 40))
        assertThat(f.value("bedtime_last_night").knownScalar).isEqualTo(FeatureScalar.NightTimeValue(100))
        assertThat(f.value("sleep_minutes_last_night").knownLong).isEqualTo(300)
    }

    @Test
    fun `R10 12J J6 a session still processing is provisional and evaluates normally`() = runTest {
        val f = j()
        f.inputs.sleep.rows += session("2026-10-01T23:10", "2026-10-02T05:30", minutesAsleep = 355, processed = false)

        assertThat(f.value("sleep_minutes_last_night")).isEqualTo(knownInt(355, f.now, Quality.PROVISIONAL))
        assertThat(f.value("wake_time_today")).isEqualTo(FeatureValue.Known(FeatureScalar.LocalTimeValue(330), f.now, Quality.PROVISIONAL))
    }

    @Test
    fun `R10 12J J7 a night recorded in New York is read in its own offsets`() = runTest {
        val f = j(start = "2026-10-02T20:00")
        f.inputs.sleep.rows += session("2026-10-01T23:30", "2026-10-02T07:00", offset = newYorkSummer, minutesAsleep = 400)

        assertThat(f.value("sleep_minutes_last_night").knownLong).isEqualTo(400)
        assertThat(f.value("bedtime_last_night").knownScalar).isEqualTo(FeatureScalar.NightTimeValue(23 * 60 + 30))
        assertThat(f.value("wake_time_today").knownScalar).isEqualTo(FeatureScalar.LocalTimeValue(7 * 60))
    }

    @Test
    fun `R10 12J J7 a session whose own end date is yesterday is not last night, even if it is today in Berlin`() = runTest {
        val f = j(start = "2026-10-02T20:00")
        // Ends 2026-10-01 22:00-04:00, which is 2026-10-02 04:00 in Berlin.
        f.inputs.sleep.rows += session("2026-10-01T15:00", "2026-10-01T22:00", offset = newYorkSummer, minutesAsleep = 400)

        assertThat(f.value("sleep_minutes_last_night")).isEqualTo(missing(MissingReason.NO_DATA))
    }

    @Test
    fun `R10 12J J8 the first asleep stage at 23 42 is after 23 30 on the night clock`() = runTest {
        val f = j()
        val stages = listOf(
            SleepStageSpan(SleepStageKind.AWAKE, at("2026-10-01T23:20"), at("2026-10-01T23:42")),
            SleepStageSpan(SleepStageKind.LIGHT, at("2026-10-01T23:42"), at("2026-10-02T06:00")),
        )
        f.inputs.sleep.rows += session("2026-10-01T23:20", "2026-10-02T06:00", stages = stages)

        val bedtime = f.value("bedtime_last_night").knownScalar as FeatureScalar.NightTimeValue
        assertThat(bedtime).isEqualTo(FeatureScalar.NightTimeValue(23 * 60 + 42))
        assertThat(bedtime.nightOrder).isAtLeast(FeatureScalar.NightTimeValue(23 * 60 + 30).nightOrder)
    }

    @ParameterizedTest(name = "12B bedtime {0} gte 23:30 is {1}")
    @CsvSource("21:00, false", "23:29, false", "23:30, true", "00:15, true", "11:59, true", "12:00, false")
    fun `R10 12B bedtimes compare on the night clock`(bedtime: String, gte2330: Boolean) = runTest {
        val f = j(start = "2026-10-02T20:00")
        // A six-hour session that ends on D whatever its bedtime.
        val date = if (bedtime >= "18:00") "2026-10-01" else "2026-10-02"
        val start = LocalDateTime.parse("${date}T$bedtime").toInstant(berlinSummer)
        f.inputs.sleep.rows += SleepSessionRecord(start, start + 360.minutes, berlinSummer, berlinSummer, mainSleep = true, nap = false)

        val value = f.value("bedtime_last_night").knownScalar as FeatureScalar.NightTimeValue
        assertWithMessage(bedtime).that(value.nightOrder >= FeatureScalar.NightTimeValue(23 * 60 + 30).nightOrder).isEqualTo(gte2330)
    }

    @Test
    fun `a flagged main sleep wins over a longer unflagged session`() = runTest {
        val f = j()
        f.inputs.sleep.rows += session("2026-10-01T20:00", "2026-10-02T04:00", minutesAsleep = 420, mainSleep = false, nap = false)
        f.inputs.sleep.rows += session("2026-10-02T00:30", "2026-10-02T06:30", minutesAsleep = 330, mainSleep = true, nap = false)

        assertThat(f.value("sleep_minutes_last_night").knownLong).isEqualTo(330)
    }

    @Test
    fun `equal candidates go to the earliest start`() = runTest {
        val f = j()
        f.inputs.sleep.rows += session("2026-10-02T00:00", "2026-10-02T06:00", minutesAsleep = 300, mainSleep = null, nap = null)
        f.inputs.sleep.rows += session("2026-10-01T23:00", "2026-10-02T05:00", minutesAsleep = 310, mainSleep = null, nap = null)

        assertThat(f.value("sleep_minutes_last_night").knownLong).isEqualTo(310)
    }

    @Test
    fun `without a summary or stages out-of-bed segments are subtracted`() = runTest {
        val f = j()
        val outOfBed = SleepStageSpan(SleepStageKind.OUT_OF_BED, at("2026-10-02T03:00"), at("2026-10-02T03:25"))
        f.inputs.sleep.rows += session("2026-10-01T23:00", "2026-10-02T06:00", stages = listOf(outOfBed))

        assertThat(f.value("sleep_minutes_last_night").knownLong).isEqualTo(7 * 60 - 25)
        assertThat(f.value("bedtime_last_night").knownScalar).isEqualTo(FeatureScalar.NightTimeValue(23 * 60))
    }

    @Test
    fun `no session with a stale sleep source is NOT_SYNCED`() = runTest {
        val f = j()
        f.inputs.sourceCoverage.set(f.inputs.sleep.source, HealthMetric.SLEEP, f.now - 3.minutes * 60)

        for (ref in sleepRefs) assertThat(f.resolve(ref)).isEqualTo(missing(MissingReason.NOT_SYNCED))
    }

    @Test
    fun `no session from a source that never synced is NOT_SYNCED`() = runTest {
        val f = RealtimeFixture(start = "2026-10-02T08:00")

        assertThat(f.value("sleep_minutes_last_night")).isEqualTo(missing(MissingReason.NOT_SYNCED))
    }

    @Test
    fun `sleep without permission or with a failing coverage read is unknown`() = runTest {
        val f = j()
        f.inputs.sleep.unavailable = MissingReason.NO_PERMISSION
        assertThat(f.value("sleep_minutes_last_night")).isEqualTo(missing(MissingReason.NO_PERMISSION))

        f.inputs.sleep.unavailable = null
        f.inputs.sourceCoverage.failure = AppError.DatabaseError("SQLiteException")
        assertThat(f.value("sleep_minutes_last_night")).isEqualTo(missing(MissingReason.API_UNAVAILABLE))
    }

    // ------------------------------------------------------------------ R10 §12.K (F0: 2026-10-01)

    private fun k(start: String = "2026-10-01T12:00"): RealtimeFixture = RealtimeFixture(start = start).apply {
        inputs.sourceCoverage.set(inputs.dailySummaries.source, HealthMetric.RESTING_HEART_RATE, now)
    }

    private fun RealtimeFixture.rhr(date: LocalDate, bpm: Long) = inputs.dailySummaries.put(DailyMetric.RESTING_HEART_RATE, date, bpm)

    private val today = LocalDate(2026, 10, 1)

    private fun RealtimeFixture.baseline(vararg values: Long) {
        values.forEachIndexed { i, bpm -> rhr(today.minus(DatePeriod(days = i + 1)), bpm) }
    }

    @Test
    fun `R10 12K K1 resting_hr_today is today's daily value`() = runTest {
        val f = k()
        f.rhr(today, 58)
        f.rhr(today.minus(DatePeriod(days = 1)), 61)

        assertThat(f.value("resting_hr_today")).isEqualTo(knownInt(58, f.now))
    }

    @Test
    fun `R10 12K K2 no row for today yet at 07 30 is unknown`() = runTest {
        val f = k(start = "2026-10-01T07:30")
        f.rhr(today.minus(DatePeriod(days = 1)), 61)

        assertThat(f.value("resting_hr_today")).isEqualTo(missing(MissingReason.NO_DATA))
        assertThat(f.value("resting_hr_delta_vs_28d")).isEqualTo(missing(MissingReason.NO_DATA))

        f.inputs.sourceCoverage.set(f.inputs.dailySummaries.source, HealthMetric.RESTING_HEART_RATE, f.now - 120.minutes)
        assertThat(f.value("resting_hr_today")).isEqualTo(missing(MissingReason.NOT_SYNCED))
    }

    @Test
    fun `R10 12K K3 thirteen baseline values are too few`() = runTest {
        val f = k()
        f.rhr(today, 58)
        f.baseline(50, 51, 52, 52, 53, 53, 54, 54, 55, 55, 56, 56, 57)

        assertThat(f.value("resting_hr_delta_vs_28d")).isEqualTo(missing(MissingReason.NO_DATA))
    }

    @Test
    fun `R10 12K K4 the delta is against the lower median of the baseline`() = runTest {
        val f = k()
        f.rhr(today, 58)
        f.baseline(50, 51, 52, 52, 53, 53, 54, 54, 55, 55, 56, 56, 57, 58)

        assertThat(f.value("resting_hr_delta_vs_28d")).isEqualTo(knownInt(4, f.now))
    }

    @Test
    fun `Q3 a delta of 51 bpm during illness is a real value, not INVALID_VALUE`() = runTest {
        val f = k()
        f.rhr(today, 105)
        f.baseline(50, 51, 52, 52, 53, 53, 54, 54, 55, 55, 56, 56, 57, 58)

        assertThat(f.value("resting_hr_delta_vs_28d")).isEqualTo(knownInt(51, f.now))
    }

    @Test
    fun `R10 12K K5 an impossible resting heart rate is INVALID_VALUE`() = runTest {
        val f = k()
        f.rhr(today, 19)
        f.baseline(50, 51, 52, 52, 53, 53, 54, 54, 55, 55, 56, 56, 57, 58)

        assertThat(f.value("resting_hr_today")).isEqualTo(missing(MissingReason.INVALID_VALUE))
        assertThat(f.value("resting_hr_delta_vs_28d")).isEqualTo(missing(MissingReason.INVALID_VALUE))
    }

    @Test
    fun `the baseline uses only valid values of the previous 28 civil dates`() = runTest {
        val f = k()
        f.rhr(today, 60)
        f.baseline(55, 55, 55, 55, 55, 55, 55, 55, 55, 55, 55, 55, 55, 10)
        f.rhr(today.minus(DatePeriod(days = 29)), 30)

        // 13 valid values in range: too few, the invalid 10 and the date 29 days back do not count.
        assertThat(f.value("resting_hr_delta_vs_28d")).isEqualTo(missing(MissingReason.NO_DATA))

        f.rhr(today.minus(DatePeriod(days = 28)), 40)
        assertThat(f.value("resting_hr_delta_vs_28d")).isEqualTo(knownInt(5, f.now))
    }

    @Test
    fun `Q3 a delta beyond the 50 bpm literal range is still a known value`() = runTest {
        val f = k()
        f.rhr(today, 120)
        f.baseline(*LongArray(14) { 55 })

        assertThat(f.value("resting_hr_delta_vs_28d")).isEqualTo(knownInt(65, f.now))
    }

    @Test
    fun `resting_hr_today at 01 30 reads today's civil date, never yesterday's value`() = runTest {
        val f = k(start = "2026-10-02T01:30")
        f.rhr(LocalDate(2026, 10, 1), 58)

        assertThat(f.value("resting_hr_today")).isEqualTo(missing(MissingReason.NO_DATA))

        f.rhr(LocalDate(2026, 10, 2), 57)
        assertThat(f.value("resting_hr_today")).isEqualTo(knownInt(57, f.now))
    }

    @Test
    fun `a disconnected heart source is SOURCE_DISCONNECTED`() = runTest {
        val f = k()
        f.inputs.dailySummaries.failure = AppError.AuthenticationRequired("googlehealth")

        assertThat(f.value("resting_hr_today")).isEqualTo(missing(MissingReason.SOURCE_DISCONNECTED))
    }

    @Test
    fun `the lower median of an even count is the lower middle value`() {
        assertThat(HeartFeatures.lowerMedian(listOf(1, 2, 3, 4))).isEqualTo(2)
        assertThat(HeartFeatures.lowerMedian(listOf(1, 2, 3))).isEqualTo(2)
        assertThat(HeartFeatures.lowerMedian(listOf(7))).isEqualTo(7)
    }
}
