package dev.agentle.analytics.features.realtime

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.FeatureScalar
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

/**
 * The R10 §10.9 test vectors (executed on JDK 21.0.11, tzdb 2026a) through the realtime time rules: `since` resolution
 * (the `ZonedDateTime.of` rule of §10.4 and §10.6), today's bounds (§10.5) and window lengths. Each case runs with the
 * JVM default zone set to [HOSTILE_JVM_ZONE] (testing-build-04).
 */
class TimeZoneVectorsTest {
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "Europe/Berlin, 02:30, 2026-03-29T10:00:00Z, 2026-03-29T01:30:00Z, gap: 03:30+02:00",
        "Europe/Berlin, 02:30, 2026-10-25T11:00:00Z, 2026-10-25T00:30:00Z, overlap: 02:30+02:00",
        "America/New_York, 02:30, 2026-03-08T16:00:00Z, 2026-03-08T07:30:00Z, gap: 03:30-04:00",
        "America/New_York, 01:30, 2026-11-01T17:00:00Z, 2026-11-01T05:30:00Z, overlap: 01:30-04:00",
        "America/Santiago, 00:30, 2026-09-06T15:00:00Z, 2026-09-06T04:30:00Z, midnight gap: 01:30-03:00",
        "America/Santiago, 23:30, 2026-04-05T16:00:00Z, 2026-04-05T02:30:00Z, overlap before midnight: 2026-04-04T23:30-03:00",
        "Australia/Lord_Howe, 02:10, 2026-10-04T01:00:00Z, 2026-10-03T15:40:00Z, 30-minute gap: 02:40+11:00",
    )
    fun `R10 10_9 a local time in a gap moves later and one in an overlap takes the earlier offset`(
        zone: String,
        since: String,
        at: String,
        expected: String,
        vector: String,
    ) {
        withJvmDefaultZone(HOSTILE_JVM_ZONE) {
            val start = LocalTimeRules.sinceStart(Instant.parse(at), LocalTime.parse(since), TimeZone.of(zone))
            assertWithMessage(vector).that(start).isEqualTo(Instant.parse(expected))
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "Europe/Berlin, 2026-03-29T10:00:00Z, 2026-03-28T23:00:00Z, 1380",
        "Europe/Berlin, 2026-10-25T11:00:00Z, 2026-10-24T22:00:00Z, 1500",
        "America/New_York, 2026-03-08T16:00:00Z, 2026-03-08T05:00:00Z, 1380",
        "America/New_York, 2026-11-01T17:00:00Z, 2026-11-01T04:00:00Z, 1500",
        "America/Santiago, 2026-09-06T15:00:00Z, 2026-09-06T04:00:00Z, 1380",
        "Australia/Lord_Howe, 2026-10-04T01:00:00Z, 2026-10-03T13:30:00Z, 1410",
    )
    fun `R10 10_9 today starts at the local start of day and has the day's real length`(
        zone: String,
        at: String,
        start: String,
        minutes: Long,
    ) {
        withJvmDefaultZone(HOSTILE_JVM_ZONE) {
            val today = LocalTimeRules.today(Instant.parse(at), TimeZone.of(zone))
            assertWithMessage(zone).that(today.start).isEqualTo(Instant.parse(start))
            assertWithMessage(zone).that(today.end - today.start).isEqualTo(minutes.minutes)
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "Europe/Berlin, 22:00, 2026-10-25T03:00:00Z, 420",
        "America/New_York, 22:00, 2026-11-01T07:00:00Z, 300",
        "America/New_York, 22:00, 2026-03-08T08:00:00Z, 300",
    )
    fun `R10 10_9 a night window has its real length`(zone: String, since: String, end: String, minutes: Long) {
        withJvmDefaultZone(HOSTILE_JVM_ZONE) {
            val at = Instant.parse(end)
            assertWithMessage(zone).that(at - LocalTimeRules.sinceStart(at, LocalTime.parse(since), TimeZone.of(zone)))
                .isEqualTo(minutes.minutes)
        }
    }

    @Test
    fun `R10 10_9 02 30 occurs twice in the Berlin overlap and never in the gap`() {
        withJvmDefaultZone(HOSTILE_JVM_ZONE) {
            val berlin = Zones.BERLIN
            assertThat(LocalTimeRules.minuteOfDay(Instant.parse("2026-10-25T00:30:00Z"), berlin)).isEqualTo(150)
            assertThat(LocalTimeRules.minuteOfDay(Instant.parse("2026-10-25T01:30:00Z"), berlin)).isEqualTo(150)
            assertThat(LocalTimeRules.minuteOfDay(Instant.parse("2026-03-29T00:59:00Z"), berlin)).isEqualTo(119)
            assertThat(LocalTimeRules.minuteOfDay(Instant.parse("2026-03-29T01:00:00Z"), berlin)).isEqualTo(180)
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "Europe/Berlin, 2026-03-29T01:00:00Z",
        "Europe/Berlin, 2026-10-25T01:00:00Z",
        "America/New_York, 2026-03-08T07:00:00Z",
        "America/New_York, 2026-11-01T06:00:00Z",
        "America/Santiago, 2026-04-05T03:00:00Z",
        "America/Santiago, 2026-09-06T04:00:00Z",
        "Australia/Lord_Howe, 2026-04-04T15:00:00Z",
        "Australia/Lord_Howe, 2026-10-03T15:30:00Z",
    )
    fun `R10 10_9 the zone rules in use have the transitions the vectors assume`(zone: String, transition: String) {
        val tz = TimeZone.of(zone)
        val at = Instant.parse(transition)
        assertWithMessage(zone).that(tz.offsetAt(at - 1.nanoseconds)).isNotEqualTo(tz.offsetAt(at))
        assertWithMessage(zone).that(tz.offsetAt(at - 1.minutes)).isEqualTo(tz.offsetAt(at - 1.nanoseconds))
    }

    @Test
    fun `R10 10_9 since 02 10 on the Lord Howe gap morning resolves to 02 40 plus 11 00`() = runTest {
        withJvmDefaultZone(HOSTILE_JVM_ZONE) {
            val f = RealtimeFixture(zone = Zones.LORD_HOWE, start = "2026-10-04T04:00")

            assertThat(f.value("screen_minutes_since", "since" to "02:10")).isEqualTo(knownInt(80, f.now))
            assertThat(f.value("local_time").knownScalar).isEqualTo(FeatureScalar.LocalTimeValue(4 * 60))
        }
    }
}
