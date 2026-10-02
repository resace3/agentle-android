package dev.agentle.analytics.features.daily

import com.google.common.truth.Truth.assertThat
import dev.agentle.analytics.features.MissingReason
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.SleepSessionPayload
import dev.agentle.core.model.SleepStage
import dev.agentle.core.model.SleepStageKind
import dev.agentle.core.model.SourceFamily
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Synced health features: steps, exercise, heart rate and sleep. */
class HealthFeaturesTest {
    private suspend fun compute(
        events: List<PersonalEvent>,
        now: Instant = LATER,
        day: LocalDate = D,
        policy: CanonicalSourcePolicy = CanonicalSourcePolicy.DEFAULT,
        timeline: ZoneTimeline = ZoneTimeline.fixed(UTC),
        claims: Map<MetricFamily, Map<dev.agentle.core.model.DataSourceId, List<dev.agentle.core.time.ClosedOpenRange>>> = emptyMap(),
    ): List<DailySummaryRow> {
        val inputs = InMemoryDailyInputs(events, timeline, policy = policy)
        claims.forEach { (family, bySource) -> bySource.forEach { (source, ranges) -> inputs.setSourceCoverage(family, source, ranges) } }
        if (claims.isEmpty()) {
            // Unless a test says otherwise, every source in it asserts the whole day (FINAL needs full coverage).
            for (e in events) {
                MetricFamily.entries.filter {
                    e.type in it.eventTypes
                }.forEach { inputs.setSourceCoverage(it, e.source, listOf(around(day))) }
            }
        }
        return DailyFeatureCalculator(inputs).compute(day, now)
    }

    @Test
    fun `step proration floors once over the whole day`() = runTest {
        val rows = compute(
            listOf(
                Ev.steps(Src.GH_STEPS, at(D, 0) - 30.seconds, at(D, 0) + 30.seconds, 5),
                Ev.steps(Src.GH_STEPS, at(D, 23, 59, second = 30), at(D.plusDays(1), 0) + 30.seconds, 5),
                Ev.steps(Src.GH_STEPS, at(D, 12), at(D, 12, 1), 10),
            ),
        )

        // 2.5 + 2.5 + 10 = 15 exactly; flooring each part would give 14.
        assertThat(rows.row("steps").value).isEqualTo(15.0)
        assertThat(rows.row("steps").source).isEqualTo(Src.GH_STEPS)
        assertThat(rows.row("steps").lineage.categories).containsExactly(DataCategory.ACTIVITY)
    }

    @Test
    fun `the canonical source's civil-date total wins over lower-priority samples`() = runTest {
        val events = listOf(Ev.dailySteps(Src.GH_DAILY, D, 10_412.0, TOKYO)) + Ev.stepMinutes(Src.PHONE_STEPS, at(D, 10), 50, 100)

        val rows = compute(events)
        val phoneFirst = compute(events, policy = CanonicalSourcePolicy(mapOf(MetricFamily.STEPS to listOf("android", "googlehealth"))))

        assertThat(rows.row("steps").value).isEqualTo(10_412.0)
        assertThat(rows.row("steps").source).isEqualTo(Src.GH_DAILY)
        assertThat(rows.row("active_minutes").value).isEqualTo(50.0)
        assertThat(phoneFirst.row("steps").value).isEqualTo(5_000.0)
        assertThat(phoneFirst.row("steps").lineage.sourceFamilies).containsExactly(SourceFamily.ON_DEVICE)
    }

    @Test
    fun `a civil-date total alone gives steps but no intraday features`() = runTest {
        val rows = compute(listOf(Ev.dailySteps(Src.GH_DAILY, D, 8_000.0)))

        assertThat(rows.row("steps").value).isEqualTo(8_000.0)
        assertThat(rows.row("active_minutes").status).isEqualTo(DailyRowStatus.MISSING)
        assertThat(rows.filter { it.featureId == "steps_by_hour" }).isEmpty()
        assertThat(compute(listOf(Ev.dailySteps(Src.GH_DAILY, D, -1.0))).row("steps").missingReason).isEqualTo(MissingReason.INVALID_VALUE)
    }

    @Test
    fun `steps by hour, active minutes and sedentary periods`() = runTest {
        val rows = compute(
            listOf(
                Ev.steps(Src.GH_STEPS, at(D, 7, 10), at(D, 7, 11), 100),
                Ev.steps(Src.GH_STEPS, at(D, 7, 50), at(D, 7, 51), 50),
                Ev.steps(Src.GH_STEPS, at(D, 13), at(D, 13, 1), 30),
            ) + Ev.stepMinutes(Src.GH_STEPS, at(D, 10), 20, 120) + Ev.stepMinutes(Src.GH_STEPS, at(D, 15), 5, 99),
        )

        assertThat(rows.row("steps_by_hour{hour=7}").value).isEqualTo(150.0)
        assertThat(rows.row("steps_by_hour{hour=10}").value).isEqualTo(2_400.0)
        assertThat(rows.row("steps_by_hour{hour=13}").value).isEqualTo(30.0)
        // 07:10 (100 steps) and 10:00-10:20 (120 per minute) reach 100 steps per minute; 99 does not.
        assertThat(rows.row("active_minutes").value).isEqualTo(21.0)
        // Daytime 08:00-21:00 is 780 minutes; 10:00-10:20, 13:00 and 15:00-15:05 have steps.
        assertThat(rows.row("sedentary_minutes").value).isEqualTo(780.0 - 26)
        assertThat(rows.row("sedentary_bouts").value).isEqualTo(4.0)
        assertThat(rows.row("longest_sedentary_minutes").value).isEqualTo(355.0)
    }

    @Test
    fun `a true-zero step day is fully sedentary`() = runTest {
        val rows = compute(listOf(Ev.steps(Src.GH_STEPS, at(D, 0), at(D.plusDays(1), 0), 0)))

        assertThat(rows.row("steps").value).isEqualTo(0.0)
        assertThat(rows.row("sedentary_minutes").value).isEqualTo(780.0)
        assertThat(rows.row("sedentary_bouts").value).isEqualTo(1.0)
        assertThat(rows.row("active_minutes").value).isEqualTo(0.0)
    }

    @Test
    fun `hours follow local time on the spring-forward day`() = runTest {
        val day = date("2026-03-08")
        val rows = compute(
            listOf(
                Ev.steps(Src.GH_STEPS, at(day, 1, 30, NEW_YORK), at(day, 1, 31, NEW_YORK), 10, NEW_YORK),
                Ev.steps(Src.GH_STEPS, at(day, 3, 30, NEW_YORK), at(day, 3, 31, NEW_YORK), 20, NEW_YORK),
            ),
            day = day,
            now = at(day.plusDays(5), 0),
            timeline = ZoneTimeline.fixed(NEW_YORK),
        )

        assertThat(rows.filter { it.featureId == "steps_by_hour" }.map { it.metric })
            .containsExactly("steps_by_hour{hour=1}", "steps_by_hour{hour=3}")
        assertThat(rows.row("steps").value).isEqualTo(30.0)
    }

    @Test
    fun `step rows stay provisional while the day runs`() = runTest {
        val rows = compute(Ev.stepMinutes(Src.GH_STEPS, at(D, 10), 10, 50), now = at(D, 15))

        assertThat(rows.row("steps").status).isEqualTo(DailyRowStatus.PROVISIONAL)
        assertThat(rows.row("steps").value).isEqualTo(500.0)
        assertThat(rows.row("sedentary_minutes").status).isEqualTo(DailyRowStatus.PROVISIONAL)
        // Only minutes before now count: 08:00-15:00 is 420 minutes, 10 of them with steps.
        assertThat(rows.row("sedentary_minutes").value).isEqualTo(410.0)
    }

    @Test
    fun `overlapping exercise sessions of two sources count once`() = runTest {
        val rows = compute(
            listOf(Ev.exercise(Src.GH_EXERCISE, at(D, 10), at(D, 10, 40)), Ev.exercise(Src.HC_EXERCISE, at(D, 10, 5), at(D, 10, 45))),
        )

        assertThat(rows.row("exercise_minutes").value).isEqualTo(45.0)
        assertThat(rows.row("exercise_sessions").value).isEqualTo(1.0)
        assertThat(rows.row("exercise_day").value).isEqualTo(1.0)
        assertThat(rows.row("exercise_minutes").lineage.sourceFamilies).containsExactly(SourceFamily.GH_API, SourceFamily.HEALTH_CONNECT)
    }

    @Test
    fun `a session from the evening before adds minutes but no session`() = runTest {
        val rows = compute(listOf(Ev.exercise(Src.GH_EXERCISE, at(D.plusDays(-1), 23, 30), at(D, 0, 20))))

        assertThat(rows.row("exercise_minutes").value).isEqualTo(20.0)
        assertThat(rows.row("exercise_sessions").value).isEqualTo(0.0)
        assertThat(rows.row("exercise_day").value).isEqualTo(1.0)
    }

    @Test
    fun `heart rate mean and max use valid samples and rollup bounds`() = runTest {
        val rows = compute(
            listOf(
                Ev.heartRate(Src.GH_HR, at(D, 8), 60.0),
                Ev.heartRate(Src.GH_HR, at(D, 9), 70.0),
                Ev.heartRate(Src.GH_HR, at(D, 9, 30), 300.0),
                Ev.heartRate(Src.GH_HR, at(D, 10), 75.0, end = at(D, 10, 1), maxBpm = 90.0),
            ),
        )

        assertThat(rows.row("hr_mean").value!!).isWithin(1e-9).of(205.0 / 3)
        assertThat(rows.row("hr_max").value).isEqualTo(90.0)
        assertThat(rows.row("hr_max").lineage.categories).containsExactly(DataCategory.HEART)
        val invalid = compute(listOf(Ev.heartRate(Src.GH_HR, at(D, 8), 400.0)))
        assertThat(invalid.row("hr_mean").missingReason).isEqualTo(MissingReason.INVALID_VALUE)
        assertThat(invalid.row("hr_max").missingReason).isEqualTo(MissingReason.INVALID_VALUE)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("restingCases")
    fun `resting heart rate is the canonical value of the civil date`(row: String, events: List<PersonalEvent>, value: Double?) = runTest {
        val result = compute(events).row("resting_hr")

        assertThat(result.value).isEqualTo(value)
    }

    @Test
    fun `sleep uses asleep stages, the first asleep stage as bedtime and own offsets`() = runTest {
        val night = Ev.sleepSession(
            Src.GH_SLEEP,
            at(D, 23, 10),
            at(D.plusDays(1), 7),
            SleepSessionPayload(
                stages = listOf(
                    stage(SleepStageKind.AWAKE, at(D, 23, 10), at(D, 23, 30)),
                    stage(SleepStageKind.LIGHT, at(D, 23, 30), at(D.plusDays(1), 3)),
                    stage(SleepStageKind.DEEP, at(D.plusDays(1), 3), at(D.plusDays(1), 5)),
                    stage(SleepStageKind.REM, at(D.plusDays(1), 5), at(D.plusDays(1), 6, 30)),
                    stage(SleepStageKind.AWAKE, at(D.plusDays(1), 6, 30), at(D.plusDays(1), 7)),
                ),
                processed = true,
            ),
        )

        val rows = compute(listOf(night))

        assertThat(rows.row("sleep_minutes").value).isEqualTo(420.0)
        assertThat(rows.row("bedtime").value).isEqualTo(690.0)
        assertThat(rows.row("wake_time").value).isEqualTo(420.0)
        assertThat(rows.row("sleep_midpoint").value).isEqualTo(915.0)
        assertThat(rows.row("sleep_minutes").lineage.categories).containsExactly(DataCategory.SLEEP)
    }

    @Test
    fun `sleep across the fall-back night reads each end in its own offset`() = runTest {
        val day = date("2026-10-31")
        val night = Ev.sleepSession(
            Src.GH_SLEEP,
            Instant.parse("2026-11-01T03:00:00Z"),
            Instant.parse("2026-11-01T12:00:00Z"),
            SleepSessionPayload(processed = true, startUtcOffsetSeconds = -14_400, endUtcOffsetSeconds = -18_000),
            NEW_YORK,
        )

        val rows = compute(listOf(night), day = day, now = at(day.plusDays(5), 0), timeline = ZoneTimeline.fixed(NEW_YORK))

        assertThat(rows.row("sleep_minutes").value).isEqualTo(540.0)
        assertThat(rows.row("bedtime").value).isEqualTo(660.0)
        assertThat(rows.row("wake_time").value).isEqualTo(420.0)
        assertThat(rows.row("sleep_midpoint").value).isEqualTo(870.0)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sleepCases")
    fun `the night's main sleep`(row: String, events: List<PersonalEvent>, minutes: Double?) = runTest {
        assertThat(compute(events).row("sleep_minutes").value).isEqualTo(minutes)
    }

    @Test
    fun `sleep being processed stays provisional only within the grace period`() = runTest {
        val night = listOf(Ev.sleep(Src.GH_SLEEP, at(D, 23), at(D.plusDays(1), 7), processed = false))

        assertThat(compute(night, now = at(D.plusDays(1), 20)).row("bedtime").status).isEqualTo(DailyRowStatus.PROVISIONAL)
        assertThat(compute(night).row("bedtime").status).isEqualTo(DailyRowStatus.FINAL)
    }

    private fun stage(kind: SleepStageKind, start: Instant, end: Instant) =
        SleepStage(kind, start.toEpochMilliseconds(), end.toEpochMilliseconds())

    companion object {
        private val D = date("2026-09-14")
        private val LATER = at(D.plusDays(4), 12)

        @JvmStatic
        fun restingCases(): List<Arguments> = listOf(
            Arguments.of(
                "R1 the wearable API wins over Health Connect",
                listOf(Ev.restingHr(Src.GH_RHR, D, 55.0, TOKYO), Ev.restingHr(Src.HC_RHR, D, 57.0)),
                55.0,
            ),
            Arguments.of("R2 Health Connect alone", listOf(Ev.restingHr(Src.HC_RHR, D, 57.0)), 57.0),
            Arguments.of(
                "R3 the next date's value is not shifted onto this date",
                listOf(Ev.restingHr(Src.GH_RHR, D.plusDays(1), 52.0)),
                null,
            ),
            Arguments.of(
                "R4 an unlisted source is ignored",
                listOf(Ev.restingHr(dev.agentle.core.model.DataSourceId("other.rhr"), D, 50.0)),
                null,
            ),
        )

        @JvmStatic
        fun sleepCases(): List<Arguments> {
            val wake = D.plusDays(1)
            return listOf(
                Arguments.of(
                    "S1 the upstream minutes asleep win",
                    listOf(Ev.sleep(Src.GH_SLEEP, at(D, 23), at(wake, 7), minutesAsleep = 400)),
                    400.0,
                ),
                Arguments.of(
                    "S2 without stages, out-of-bed time is removed",
                    listOf(
                        Ev.sleepSession(
                            Src.GH_SLEEP,
                            at(D, 23),
                            at(wake, 7),
                            SleepSessionPayload(
                                processed = true,
                                outOfBedSegments = listOf(
                                    SleepStage(
                                        SleepStageKind.OUT_OF_BED,
                                        at(wake, 2).toEpochMilliseconds(),
                                        at(wake, 2, 30).toEpochMilliseconds(),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    450.0,
                ),
                Arguments.of(
                    "S3 naps are excluded",
                    listOf(Ev.sleep(Src.GH_SLEEP, at(wake, 13), at(wake, 14), isNap = true)),
                    null,
                ),
                Arguments.of(
                    "S4 a short Health Connect session is a nap",
                    listOf(Ev.sleep(Src.HC_SLEEP, at(wake, 1), at(wake, 3))),
                    null,
                ),
                Arguments.of(
                    "S5 the session marked main wins over a longer one",
                    listOf(
                        Ev.sleep(Src.GH_SLEEP, at(D, 22), at(wake, 6)),
                        Ev.sleep(Src.GH_SLEEP, at(wake, 6, 30), at(wake, 16, 30), isMainSleep = false),
                    ),
                    480.0,
                ),
                Arguments.of(
                    "S6 the wearable API wins over Health Connect for the night",
                    listOf(Ev.sleep(Src.HC_SLEEP, at(D, 22), at(wake, 8)), Ev.sleep(Src.GH_SLEEP, at(D, 23), at(wake, 6))),
                    420.0,
                ),
                Arguments.of(
                    "S7 the wake date is read in the session's own offset",
                    listOf(
                        Ev.sleepSession(
                            Src.GH_SLEEP,
                            Instant.parse("2026-09-14T15:00:00Z"),
                            Instant.parse("2026-09-14T22:00:00Z"),
                            SleepSessionPayload(processed = true, endUtcOffsetSeconds = 9 * 3_600),
                        ),
                        Ev.sleepSession(
                            Src.GH_SLEEP,
                            Instant.parse("2026-09-15T15:00:00Z"),
                            Instant.parse("2026-09-15T23:00:00Z"),
                            SleepSessionPayload(processed = true, endUtcOffsetSeconds = 9 * 3_600),
                        ),
                    ),
                    420.0,
                ),
                Arguments.of(
                    "S8 the longest of the unmarked sessions, earliest first on ties",
                    listOf(
                        Ev.sleep(Src.GH_SLEEP, at(D, 22), at(wake, 4), isMainSleep = false),
                        Ev.sleep(Src.GH_SLEEP, at(wake, 5), at(wake, 11), isMainSleep = false),
                    ),
                    360.0,
                ),
            )
        }
    }
}
