package dev.agentle.analytics.features.daily

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.core.common.getOrThrow
import dev.agentle.core.testing.TestAgentleClock
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The daily features in several user zones and across their 2026 DST transitions (integrator correction 3b,
 * testing-build-04): UTC, Los Angeles, Kolkata (+05:30), Chatham (+12:45, DST at 02:45), Adelaide (+09:30) and
 * St John's (-03:30, the zone test JVMs will default to). Transition instants were read from the JDK's tzdb. Every
 * computation takes its zone from the user's zone timeline, never from the JVM default, which the last test varies.
 */
class ZoneMatrixTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("windowLengths")
    fun `windows follow the user's zone across its DST transitions`(
        row: String,
        zone: String,
        day: String,
        window: DailyWindow,
        hours: Int,
    ) {
        val ranges = window.ranges(date(day), ZoneTimeline.fixed(TimeZone.of(zone)))

        assertWithMessage(row).that(IntervalMath.windowMillis(ranges)).isEqualTo(hours.hours.inWholeMilliseconds)
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "Y1 UTC has no DST, UTC, false",
        "Y2 Los Angeles, America/Los_Angeles, true",
        "Y3 Kolkata has no DST, Asia/Kolkata, false",
        "Y4 Chatham, Pacific/Chatham, true",
        "Y5 Adelaide, Australia/Adelaide, true",
        "Y6 St John's, America/St_Johns, true",
    )
    fun `the engine days of a year partition time with one short and one long day where DST applies`(
        row: String,
        zone: String,
        dst: Boolean,
    ) {
        val timeline = ZoneTimeline.fixed(TimeZone.of(zone))
        val days = (0 until 365).map { DailyWindow.ENGINE_DAY.ranges(date("2026-01-01").plusDays(it), timeline).single() }

        days.zipWithNext().forEach { (a, b) -> assertWithMessage(row).that(a.end).isEqualTo(b.start) }
        val lengths = days.groupingBy { it.duration.inWholeHours }.eachCount()
        assertThat(lengths).isEqualTo(if (dst) mapOf(23L to 1, 24L to 363, 25L to 1) else mapOf(24L to 365))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nights")
    fun `screen time and sleep of a DST night are measured in elapsed time and read on the user's clock`(
        row: String,
        zone: String,
        day: String,
        screenMinutes: Double,
        sleepMinutes: Double,
    ) = runTest {
        val rows = DailyFeatureCalculator(night(TimeZone.of(zone), date(day))).compute(date(day), at(date(day).plusDays(5), 0))

        assertWithMessage(row).that(rows.row("screen_minutes").value).isEqualTo(screenMinutes)
        assertThat(rows.row("screen_minutes").status).isEqualTo(DailyRowStatus.FINAL)
        assertThat(rows.row("sleep_minutes").value).isEqualTo(sleepMinutes)
        assertThat(rows.row("bedtime").value).isEqualTo(BEDTIME)
        assertThat(rows.row("wake_time").value).isEqualTo(WAKE_TIME)
        assertThat(rows.row("bedtime").status).isEqualTo(DailyRowStatus.FINAL)
    }

    @Test
    fun `no result depends on the JVM default zone`() = runTest(timeout = 5.minutes) {
        val user = SyntheticDailyUser()
        val baseline = withJvmDefaultZone("UTC") { fullRecompute(user) }

        for (jvmZone in JVM_ZONES) {
            val rows = withJvmDefaultZone(jvmZone) { fullRecompute(user) }
            assertWithMessage(jvmZone).that(rows).isEqualTo(baseline)
        }
        assertThat(baseline.size).isGreaterThan(5_000)
    }

    private suspend fun fullRecompute(user: SyntheticDailyUser): List<Any> {
        val inputs = InMemoryDailyInputs(user.arrivals.mapNotNull { it.event }, user.timeline)
        user.arrivals.mapNotNull { it.coverage }.forEach { (collector, range) -> inputs.addCoverage(collector, listOf(range)) }
        user.claimingSources.forEach { (family, source) -> inputs.setSourceCoverage(family, source, listOf(around(user.first, LAST))) }
        val store = InMemoryDailyFeatureStore()
        val clock = TestAgentleClock(user.ingestionTime(user.days - 1), NEW_YORK)
        DailyFeatureEngine(inputs, store, clock).recompute(DirtyDays.datesBetween(user.first, LAST)).getOrThrow()
        return store.allDailyRows.sortedWith(compareBy({ it.date }, { it.metric })) +
            store.allDerivedRows.sortedWith(compareBy({ it.anchorDate }, { it.featureId }, { it.windowDays }))
    }

    companion object {
        /** 23:00 on the night clock (minutes after noon). */
        private const val BEDTIME = 11 * 60.0
        private const val WAKE_TIME = 7 * 60.0
        private val LAST = date("2026-11-29")
        private val JVM_ZONES = listOf("America/St_Johns", "America/Los_Angeles", "Asia/Kolkata", "Pacific/Chatham", "Australia/Adelaide")

        /** An hour of screen time at noon, a session from 01:00 to 05:00 the next morning, and a 23:00-07:00 sleep. */
        private fun night(zone: TimeZone, d: LocalDate): InMemoryDailyInputs {
            val next = d.plusDays(1)
            val inputs = InMemoryDailyInputs(
                listOf(
                    Ev.screen(at(d, 12, zone = zone), at(d, 13, zone = zone), zone),
                    Ev.screen(at(next, 1, zone = zone), at(next, 5, zone = zone), zone),
                    Ev.sleep(Src.GH_SLEEP, at(d, 23, zone = zone), at(next, 7, zone = zone), zone),
                ),
                ZoneTimeline.fixed(zone),
                collectorCoverage = mapOf(Col.USAGE to listOf(around(d))),
            )
            inputs.setSourceCoverage(MetricFamily.SLEEP, Src.GH_SLEEP, listOf(around(d)))
            return inputs
        }

        private inline fun <T> withJvmDefaultZone(id: String, block: () -> T): T {
            val saved = java.util.TimeZone.getDefault()
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(id))
            try {
                return block()
            } finally {
                java.util.TimeZone.setDefault(saved)
            }
        }

        @JvmStatic
        fun windowLengths(): List<Arguments> = listOf(
            Arguments.of("X1 UTC engine day", "UTC", "2026-03-07", DailyWindow.ENGINE_DAY, 24),
            Arguments.of("X2 Los Angeles springs forward at 02:00", "America/Los_Angeles", "2026-03-07", DailyWindow.ENGINE_DAY, 23),
            Arguments.of("X3 Los Angeles calendar day of the gap", "America/Los_Angeles", "2026-03-08", DailyWindow.CALENDAR_DAY, 23),
            Arguments.of("X4 Los Angeles falls back at 02:00", "America/Los_Angeles", "2026-10-31", DailyWindow.ENGINE_DAY, 25),
            Arguments.of("X5 Los Angeles late night of the overlap", "America/Los_Angeles", "2026-10-31", DailyWindow.LATE_NIGHT, 7),
            Arguments.of("X6 Kolkata has no DST", "Asia/Kolkata", "2026-10-31", DailyWindow.ENGINE_DAY, 24),
            Arguments.of("X7 Chatham springs forward at 02:45", "Pacific/Chatham", "2026-09-26", DailyWindow.ENGINE_DAY, 23),
            Arguments.of("X8 Chatham late night of the gap", "Pacific/Chatham", "2026-09-26", DailyWindow.LATE_NIGHT, 5),
            Arguments.of("X9 Chatham calendar day of the gap", "Pacific/Chatham", "2026-09-27", DailyWindow.CALENDAR_DAY, 23),
            Arguments.of("X10 Chatham falls back at 03:45", "Pacific/Chatham", "2026-04-04", DailyWindow.ENGINE_DAY, 25),
            Arguments.of(
                "X11 Chatham evening before the overlap is untouched",
                "Pacific/Chatham",
                "2026-04-04",
                DailyWindow.EVENING_22_24,
                2,
            ),
            Arguments.of("X12 Adelaide springs forward at 02:00", "Australia/Adelaide", "2026-10-03", DailyWindow.ENGINE_DAY, 23),
            Arguments.of("X13 Adelaide falls back at 03:00", "Australia/Adelaide", "2026-04-04", DailyWindow.ENGINE_DAY, 25),
            Arguments.of("X14 Adelaide sleep night of the gap", "Australia/Adelaide", "2026-10-03", DailyWindow.SLEEP_NIGHT, 19),
            Arguments.of("X15 St John's springs forward at 02:00", "America/St_Johns", "2026-03-07", DailyWindow.ENGINE_DAY, 23),
            Arguments.of("X16 St John's falls back at 02:00", "America/St_Johns", "2026-10-31", DailyWindow.ENGINE_DAY, 25),
            Arguments.of("X17 St John's daytime is untouched", "America/St_Johns", "2026-11-01", DailyWindow.DAYTIME_08_21, 13),
        )

        @JvmStatic
        fun nights(): List<Arguments> = listOf(
            Arguments.of("Z1 UTC", "UTC", "2026-03-07", 240.0, 480.0),
            Arguments.of("Z2 Los Angeles spring forward", "America/Los_Angeles", "2026-03-07", 180.0, 420.0),
            Arguments.of("Z3 Los Angeles fall back", "America/Los_Angeles", "2026-10-31", 300.0, 540.0),
            Arguments.of("Z4 Kolkata half-hour offset", "Asia/Kolkata", "2026-10-31", 240.0, 480.0),
            Arguments.of("Z5 Chatham spring forward at 02:45", "Pacific/Chatham", "2026-09-26", 180.0, 420.0),
            Arguments.of("Z6 Chatham fall back at 03:45", "Pacific/Chatham", "2026-04-04", 300.0, 540.0),
            Arguments.of("Z7 Adelaide spring forward", "Australia/Adelaide", "2026-10-03", 180.0, 420.0),
            Arguments.of("Z8 Adelaide fall back", "Australia/Adelaide", "2026-04-04", 300.0, 540.0),
            Arguments.of("Z9 St John's spring forward", "America/St_Johns", "2026-03-07", 180.0, 420.0),
            Arguments.of("Z10 St John's fall back", "America/St_Johns", "2026-10-31", 300.0, 540.0),
        )
    }
}
