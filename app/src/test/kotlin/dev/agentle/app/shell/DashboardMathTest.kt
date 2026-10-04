package dev.agentle.app.shell

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Test

class DashboardMathTest {
    private val zone = TimeZone.of("Europe/London")
    private val oct3 = LocalDate(2026, 10, 3)
    private val oct4 = LocalDate(2026, 10, 4)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = LocalDateTime(date, LocalTime(hour, minute)).toInstant(zone)

    @Test
    fun `a phone and a watch counting the same day are not added together`() {
        val readings = listOf(
            Reading(at(oct4, 9), "android.steps", 1_200.0),
            Reading(at(oct4, 18), "android.steps", 800.0),
            Reading(at(oct4, 10), "googlehealth", 2_500.0),
            Reading(at(oct3, 23, 30), "googlehealth", 300.0),
        )

        val days = DashboardMath.largestSourceTotal(readings, zone)

        assertThat(days).containsExactly(oct4, 2_500.0, oct3, 300.0)
    }

    @Test
    fun `days follow the user's time zone`() {
        // 23:30 in London on 3 October is 22:30 UTC: it still counts on the 3rd.
        val readings = listOf(Reading(at(oct3, 23, 30), "android.phone", 1.0), Reading(at(oct4, 0, 30), "android.phone", 1.0))

        assertThat(DashboardMath.total(readings, zone)).containsExactly(oct3, 1.0, oct4, 1.0)
    }

    @Test
    fun `the mean of per-minute heart rates`() {
        val readings = listOf(Reading(at(oct4, 8), "googlehealth", 60.0), Reading(at(oct4, 8, 1), "googlehealth", 80.0))

        assertThat(DashboardMath.mean(readings, zone)).containsExactly(oct4, 70.0)
    }

    @Test
    fun `a source's daily total replaces the samples for its day and two totals are never added`() {
        val fromSamples = mapOf(oct3 to 4_000.0, oct4 to 1_000.0)
        val totals = listOf(oct4 to 6_000.0, oct4 to 5_500.0)

        assertThat(DashboardMath.preferDailyTotals(totals, fromSamples)).containsExactly(oct3, 4_000.0, oct4, 6_000.0)
    }

    @Test
    fun `resting heart rate keeps the date the source reported`() {
        assertThat(DashboardMath.byReportedDate(listOf(oct4 to 58.0, oct4 to 60.0, oct3 to 61.0))).containsExactly(oct4, 59.0, oct3, 61.0)
    }
}
