package dev.agentle.analytics.insights

import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.daily.InMemoryDailyFeatureStore
import dev.agentle.core.common.Outcome
import dev.agentle.core.testing.TestAgentleClock
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.time.Instant

/**
 * The weekly run and the insight periods across user zones and DST transitions (integrator correction 3b). Everything is
 * read in the clock's zone: each row is computed under two different JVM default zones and must not change. 2026
 * transitions (JDK tzdb): Los Angeles and St. John's fall back on 11-01, Chatham springs forward on 09-27 (02:45 ->
 * 03:45), Adelaide on 10-04 (02:00 -> 03:00); Kolkata and UTC have none.
 */
class InsightZoneTest {
    @ParameterizedTest(name = "{0} {1} at {2}")
    @CsvSource(
        "Z1, UTC, 2026-10-05T03:59:00Z, 2026-10-03",
        "Z2, UTC, 2026-10-05T04:00:00Z, 2026-10-04",
        "Z3, America/Los_Angeles, 2026-10-05T10:59:00Z, 2026-10-03",
        "Z4, America/Los_Angeles, 2026-10-05T11:00:00Z, 2026-10-04",
        "Z5, America/Los_Angeles, 2026-11-01T11:59:00Z, 2026-10-30",
        "Z6, America/Los_Angeles, 2026-11-01T12:00:00Z, 2026-10-31",
        "Z7, Asia/Kolkata, 2026-10-04T22:29:00Z, 2026-10-03",
        "Z8, Asia/Kolkata, 2026-10-04T22:30:00Z, 2026-10-04",
        "Z9, Pacific/Chatham, 2026-10-04T14:14:00Z, 2026-10-03",
        "Z10, Pacific/Chatham, 2026-10-04T14:15:00Z, 2026-10-04",
        "Z11, Pacific/Chatham, 2026-09-26T14:14:00Z, 2026-09-25",
        "Z12, Pacific/Chatham, 2026-09-26T14:15:00Z, 2026-09-26",
        "Z13, Australia/Adelaide, 2026-10-03T17:29:00Z, 2026-10-02",
        "Z14, Australia/Adelaide, 2026-10-03T17:30:00Z, 2026-10-03",
        "Z15, Australia/Adelaide, 2026-10-04T17:30:00Z, 2026-10-04",
        "Z16, America/St_Johns, 2026-10-05T06:29:00Z, 2026-10-03",
        "Z17, America/St_Johns, 2026-10-05T06:30:00Z, 2026-10-04",
        "Z18, America/St_Johns, 2026-11-01T06:30:00Z, 2026-10-30",
        "Z19, America/St_Johns, 2026-11-01T07:30:00Z, 2026-10-31",
    )
    fun `the last night of the weekly run is the engine day before today in the clock's zone`(
        row: String,
        zone: String,
        now: String,
        lastNight: String,
    ) = runTest {
        for (jvmZone in JVM_ZONES) {
            val clock = TestAgentleClock(Instant.parse(now), TimeZone.of(zone))
            val discovery = WeeklyDiscovery(InMemoryDailyFeatureStore(), InMemoryDiscoveryStore(), { emptySet() }, clock)

            val report = withJvmDefaultZone(jvmZone) { (discovery.run() as Outcome.Success).value }

            assertWithMessage("$row under $jvmZone").that(report.run.lastNight).isEqualTo(date(lastNight))
            assertWithMessage("$row under $jvmZone").that(report.run.firstNight).isEqualTo(date(lastNight).plusDays(-89))
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "I1, UTC, 2026-08-06T04:00:00Z, 2026-10-05T04:00:00Z",
        "I2, America/Los_Angeles, 2026-08-06T11:00:00Z, 2026-10-05T11:00:00Z",
        "I3, Asia/Kolkata, 2026-08-05T22:30:00Z, 2026-10-04T22:30:00Z",
        "I4, Pacific/Chatham, 2026-08-05T15:15:00Z, 2026-10-04T14:15:00Z",
        "I5, Australia/Adelaide, 2026-08-05T18:30:00Z, 2026-10-04T17:30:00Z",
        "I6, America/St_Johns, 2026-08-06T06:30:00Z, 2026-10-05T06:30:00Z",
    )
    fun `an insight covers the engine days of its nights in the user's zone`(row: String, zone: String, start: String, end: String) {
        val run = PatternAnalyzer.analyze(Controls.p1(), T0)

        for (jvmZone in JVM_ZONES) {
            val insight = withJvmDefaultZone(jvmZone) { PatternInsights.of(run, TimeZone.of(zone), T0).single() }

            assertWithMessage("$row under $jvmZone").that(insight.periodStart).isEqualTo(Instant.parse(start))
            assertWithMessage("$row under $jvmZone").that(insight.periodEnd).isEqualTo(Instant.parse(end))
        }
    }

    private companion object {
        /** Test JVMs will default to St. John's; Kiritimati (UTC+14) is as far from it as a zone gets. */
        val JVM_ZONES = listOf("America/St_Johns", "Pacific/Kiritimati")
    }
}
