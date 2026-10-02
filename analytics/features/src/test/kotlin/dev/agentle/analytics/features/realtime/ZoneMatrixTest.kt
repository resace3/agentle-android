package dev.agentle.analytics.features.realtime

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureSnapshot
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.realtime.testing.HealthSources
import dev.agentle.core.model.ActivityKind
import dev.agentle.core.model.TransitionKind
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.offsetAt
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The feature rules in several device zones, across their 2026 DST transitions (testing-build-04): UTC,
 * America/Los_Angeles, Asia/Kolkata (+05:30), Pacific/Chatham (+12:45/+13:45), Australia/Adelaide (+09:30/+10:30) and
 * America/St_Johns (-03:30/-02:30, the coming default zone of the test JVMs). Every case runs with the JVM default zone
 * set to [HOSTILE_JVM_ZONE], so a rule that read the JVM default instead of the clock's zone would fail. The transition
 * instants and day lengths below were checked with Python zoneinfo (tzdata of the 2026a generation).
 */
class ZoneMatrixTest {
    private val jitai = "11111111-1111-4111-8111-111111111111"
    private val appSince = FeatureRef("app_minutes_since", mapOf("package" to INSTAGRAM, "since" to "22:00"))
    private val opens = FeatureRef("app_opens_last_60m", mapOf("package" to INSTAGRAM))
    private val screenSince = FeatureRef("screen_minutes_since", mapOf("since" to "22:00"))
    private val deliveriesToday = FeatureRef("deliveries_today", mapOf("jitai" to "any"))
    private val deliveries7d = FeatureRef("deliveries_last_7d", mapOf("jitai" to "any"))
    private val minutesSince = FeatureRef("minutes_since_last_delivery", mapOf("jitai" to jitai))

    private fun fixture(zone: String, start: String) = RealtimeFixture(zone = TimeZone.of(zone), start = start)

    @ParameterizedTest(name = "{0}")
    @MethodSource("zones")
    fun `clock features use the clock's zone and the engine day rolls over at 04 00 local`(zone: String) = runTest {
        withJvmDefaultZone(HOSTILE_JVM_ZONE) {
            val thu = FeatureScalar.DayOfWeekValue(DayOfWeek.THURSDAY)
            val fri = FeatureScalar.DayOfWeekValue(DayOfWeek.FRIDAY)
            val f = fixture(zone, "2026-10-01T22:30")
            assertWithMessage(zone).that(f.value("local_time").knownScalar).isEqualTo(FeatureScalar.LocalTimeValue(22 * 60 + 30))
            assertWithMessage(zone).that(f.value("day_of_week").knownScalar).isEqualTo(thu)
            assertWithMessage(zone).that(f.value("engine_day_of_week").knownScalar).isEqualTo(thu)

            f.advanceTo("2026-10-02T03:59")
            assertWithMessage(zone).that(f.value("local_time").knownScalar).isEqualTo(FeatureScalar.LocalTimeValue(3 * 60 + 59))
            assertWithMessage(zone).that(f.value("day_of_week").knownScalar).isEqualTo(fri)
            assertWithMessage(zone).that(f.value("engine_day_of_week").knownScalar).isEqualTo(thu)

            f.advanceTo("2026-10-02T04:00")
            assertWithMessage(zone).that(f.value("engine_day_of_week").knownScalar).isEqualTo(fri)
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "America/Los_Angeles, 2026-03-08T10:00:00Z, 119, 180",
        "America/Los_Angeles, 2026-11-01T09:00:00Z, 119, 60",
        "Pacific/Chatham, 2026-09-26T14:00:00Z, 164, 225",
        "Pacific/Chatham, 2026-04-04T14:00:00Z, 224, 165",
        "Australia/Adelaide, 2026-10-03T16:30:00Z, 119, 180",
        "Australia/Adelaide, 2026-04-04T16:30:00Z, 179, 120",
        "America/St_Johns, 2026-03-08T05:30:00Z, 119, 180",
        "America/St_Johns, 2026-11-01T04:30:00Z, 119, 60",
    )
    fun `local_time follows the wall clock through each DST transition`(zone: String, transition: String, before: Int, after: Int) =
        runTest {
            withJvmDefaultZone(HOSTILE_JVM_ZONE) {
                val f = fixture(zone, "2026-01-01T12:00")
                f.clock.setWallClock(Instant.parse(transition) - 1.minutes)
                assertWithMessage(zone).that(f.value("local_time").knownScalar).isEqualTo(FeatureScalar.LocalTimeValue(before))

                f.clock.setWallClock(Instant.parse(transition))
                assertWithMessage(zone).that(f.value("local_time").knownScalar).isEqualTo(FeatureScalar.LocalTimeValue(after))
            }
        }

    /** Each zone's 2026 transition dates (an ordinary date for zones without DST): day length, 22:00 -> 04:00 window. */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "UTC, 2026-03-29, 1440, 360",
        "UTC, 2026-10-25, 1440, 360",
        "America/Los_Angeles, 2026-03-08, 1380, 300",
        "America/Los_Angeles, 2026-11-01, 1500, 420",
        "Asia/Kolkata, 2026-03-29, 1440, 360",
        "Pacific/Chatham, 2026-04-05, 1500, 420",
        "Pacific/Chatham, 2026-09-27, 1380, 300",
        "Australia/Adelaide, 2026-04-05, 1500, 420",
        "Australia/Adelaide, 2026-10-04, 1380, 300",
        "America/St_Johns, 2026-03-08, 1380, 300",
        "America/St_Johns, 2026-11-01, 1500, 420",
    )
    fun `today and since windows have their real length across each zone's DST transitions`(
        zone: String,
        date: String,
        dayMinutes: Long,
        nightMinutes: Long,
    ) = runTest {
        withJvmDefaultZone(HOSTILE_JVM_ZONE) {
            val night = fixture(zone, "${date}T04:00")
            assertWithMessage(
                zone,
            ).that(night.value("screen_minutes_since", "since" to "22:00")).isEqualTo(knownInt(nightMinutes, night.now))

            // 60 steps in every minute of the local day; one second before its end steps_today is 60 * minutes - 1.
            val day = fixture(zone, "${date}T00:00")
            val start = LocalDate.parse(date).atStartOfDayIn(day.zone)
            day.onlyStepSource()
            repeat(dayMinutes.toInt()) { k ->
                day.inputs.steps.add(HealthSources.GOOGLE_HEALTH_STEPS, StepInterval(start + k.minutes, start + (k + 1).minutes, 60))
            }
            val t = start + dayMinutes.minutes - 1.seconds
            day.clock.advanceBy(t - day.now)
            day.inputs.sourceCoverage.set(HealthSources.GOOGLE_HEALTH_STEPS, HealthMetric.STEPS, t)
            assertWithMessage(zone).that(day.value("steps_today")).isEqualTo(knownInt(60 * dayMinutes - 1, t))
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("zones")
    fun `R10 12H H1 usage windows are local in every zone`(zone: String) = runTest {
        withJvmDefaultZone(HOSTILE_JVM_ZONE) {
            val f = fixture(zone, "2026-10-01T23:00")
            f.resumed(INSTAGRAM, "2026-10-01T22:05")
            f.paused(INSTAGRAM, "2026-10-01T22:20")
            f.resumed(INSTAGRAM, "2026-10-01T22:40")
            f.paused(INSTAGRAM, "2026-10-01T22:55")

            val snapshot = f.snapshot(appSince, opens, screenSince)

            assertWithMessage(zone).that(snapshot[appSince]).isEqualTo(knownInt(30, f.now))
            assertWithMessage(zone).that(snapshot[opens]).isEqualTo(knownInt(2, f.now))
            assertWithMessage(zone).that(snapshot[screenSince]).isEqualTo(knownInt(60, f.now))
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("zones")
    fun `R10 12D D1 steps_today counts the local day only, in every zone`(zone: String) = runTest {
        withJvmDefaultZone(HOSTILE_JVM_ZONE) {
            val f = fixture(zone, "2026-10-01T17:00")
            f.onlyStepSource()
            f.stepMinutes(HealthSources.GOOGLE_HEALTH_STEPS, "2026-09-30T23:30", minutes = 1, count = 5_000)
            f.stepMinutes(HealthSources.GOOGLE_HEALTH_STEPS, "2026-10-01T08:00", minutes = 1, count = 2_999)
            f.stepsCoverage(HealthSources.GOOGLE_HEALTH_STEPS, "2026-10-01T16:31")

            assertWithMessage(zone).that(f.value("steps_today")).isEqualTo(knownInt(2_999, f.local("2026-10-01T16:31")))
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("zones")
    fun `R10 12M M10 deliveries count by the local engine day in every zone`(zone: String) = runTest {
        withJvmDefaultZone(HOSTILE_JVM_ZONE) {
            val f = fixture(zone, "2026-10-01T23:30")
            f.inputs.history.rows += DeliveryRecord(
                "v1|$jitai|1",
                jitai,
                "DIGITAL_WELLBEING",
                DeliveryState.DELIVERED,
                f.now,
                LocalDate(2026, 10, 1),
                f.clock.elapsed(),
                f.inputs.live.state.bootCount,
            )

            f.advanceTo("2026-10-02T03:59")
            assertWithMessage(zone).that(f.resolve(deliveriesToday)).isEqualTo(knownInt(1, f.now))
            assertWithMessage(zone).that(f.resolve(minutesSince)).isEqualTo(knownInt(4 * 60 + 29, f.now))

            f.advanceTo("2026-10-02T04:00")
            assertWithMessage(zone).that(f.resolve(deliveriesToday)).isEqualTo(knownInt(0, f.now))
            assertWithMessage(zone).that(f.resolve(deliveries7d)).isEqualTo(knownInt(1, f.now))
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("zones")
    fun `R10 12J sleep is read in the session's own offsets in every zone`(zone: String) = runTest {
        withJvmDefaultZone(HOSTILE_JVM_ZONE) {
            val f = fixture(zone, "2026-10-02T08:00")
            val start = f.local("2026-10-01T23:10")
            val end = f.local("2026-10-02T06:30")
            f.inputs.sleep.rows += SleepSessionRecord(
                start,
                end,
                f.zone.offsetAt(start),
                f.zone.offsetAt(end),
                emptyList(),
                minutesAsleep = 355,
                mainSleep = true,
                nap = false,
                processed = true,
            )
            f.inputs.sourceCoverage.set(f.inputs.sleep.source, HealthMetric.SLEEP, f.now)

            assertWithMessage(zone).that(f.value("sleep_minutes_last_night")).isEqualTo(knownInt(355, f.now))
            assertWithMessage(zone).that(f.value("bedtime_last_night").knownScalar).isEqualTo(FeatureScalar.NightTimeValue(23 * 60 + 10))
            assertWithMessage(zone).that(f.value("wake_time_today").knownScalar).isEqualTo(FeatureScalar.LocalTimeValue(6 * 60 + 30))
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("zones")
    fun `R10 12K resting_hr_today is the local civil date at 01 30 in every zone`(zone: String) = runTest {
        withJvmDefaultZone(HOSTILE_JVM_ZONE) {
            val f = fixture(zone, "2026-10-02T01:30")
            f.inputs.sourceCoverage.set(f.inputs.dailySummaries.source, HealthMetric.RESTING_HEART_RATE, f.now)
            f.inputs.dailySummaries.put(DailyMetric.RESTING_HEART_RATE, LocalDate(2026, 10, 1), 58)
            assertWithMessage(zone).that(f.value("resting_hr_today")).isEqualTo(missing(MissingReason.NO_DATA))

            f.inputs.dailySummaries.put(DailyMetric.RESTING_HEART_RATE, LocalDate(2026, 10, 2), 61)
            assertWithMessage(zone).that(f.value("resting_hr_today")).isEqualTo(knownInt(61, f.now))
        }
    }

    @ParameterizedTest(name = "JVM default {0}")
    @ValueSource(
        strings = ["America/St_Johns", "Pacific/Chatham", "Asia/Kolkata", "Australia/Adelaide", "Pacific/Kiritimati", "Etc/GMT+12"],
    )
    fun `the JVM default zone never changes a snapshot of every feature`(jvmZone: String) = runTest {
        val baseline = withJvmDefaultZone("UTC") { richSnapshot() }
        val other = withJvmDefaultZone(jvmZone) { richSnapshot() }

        assertThat(other).isEqualTo(baseline)
        assertThat(baseline.values.values.count { it is FeatureValue.Known }).isAtLeast(30)
    }

    /** Every catalog feature in Los Angeles at 2026-10-01 23:00, with data for nearly all of them. */
    private suspend fun richSnapshot(): FeatureSnapshot {
        val f = fixture("America/Los_Angeles", "2026-10-01T23:00")
        f.resumed(INSTAGRAM, "2026-10-01T22:05")
        f.paused(INSTAGRAM, "2026-10-01T22:20")
        f.resumed(CHAT, "2026-10-01T22:30")
        f.paused(CHAT, "2026-10-01T22:50")
        f.screenOff("2026-10-01T22:52")
        f.screenOn("2026-10-01T22:54")
        f.inputs.usage.categories[INSTAGRAM] = "SOCIAL"
        f.inputs.usage.categories[CHAT] = "SOCIAL"
        f.inputs.notifications.rows += NotificationPost(f.local("2026-10-01T22:15"), CHAT, "k1")
        f.inputs.notifications.rows += NotificationPost(f.local("2026-10-01T22:45"), MAPS, "k2")
        f.inputs.activity.rows += ActivityTransition(f.local("2026-10-01T22:10"), ActivityKind.WALKING, TransitionKind.ENTER)
        f.onlyStepSource()
        f.stepMinutes(HealthSources.GOOGLE_HEALTH_STEPS, "2026-10-01T22:00", minutes = 60, count = 110)
        f.inputs.healthSyncedThrough(f.now)
        val sleepStart = f.local("2026-09-30T23:20")
        val sleepEnd = f.local("2026-10-01T06:50")
        f.inputs.sleep.rows += SleepSessionRecord(
            sleepStart,
            sleepEnd,
            f.zone.offsetAt(sleepStart),
            f.zone.offsetAt(sleepEnd),
            emptyList(),
            minutesAsleep = 410,
        )
        val today = LocalDate(2026, 10, 1)
        for (k in 1..20) f.inputs.dailySummaries.put(DailyMetric.RESTING_HEART_RATE, today.minus(DatePeriod(days = k)), 55L + k % 5)
        f.inputs.dailySummaries.put(DailyMetric.RESTING_HEART_RATE, today, 60)
        f.inputs.history.rows += DeliveryRecord(
            "v1|$jitai|1",
            jitai,
            "DIGITAL_WELLBEING",
            DeliveryState.DELIVERED,
            f.local("2026-10-01T21:00"),
            today,
            response = InterventionResponse.IGNORED,
        )
        return f.engine.resolve(everyCatalogRef(jitai).toSet(), f.now)
    }

    companion object {
        @JvmStatic
        fun zones(): List<String> =
            listOf("UTC", "America/Los_Angeles", "Asia/Kolkata", "Pacific/Chatham", "Australia/Adelaide", "America/St_Johns")
    }
}
