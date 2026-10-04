package dev.agentle.app.shell

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** One stored value of a dashboard metric: when it counts, which source reported it, and the value. */
internal data class Reading(val at: Instant, val source: String, val value: Double)

/**
 * Per-day values of dashboard metrics. Two sources that measure the same thing (a phone and a watch both counting
 * steps) are never added together, and a source's own daily total is never added to its samples.
 */
internal object DashboardMath {
    /** Per day, the readings added up: per-minute values already merged across sources, or one source's values. */
    fun total(readings: List<Reading>, zone: TimeZone): Map<LocalDate, Double> =
        byDay(readings, zone).mapValues { (_, day) -> day.sumOf { it.value } }

    /** Per day, each source's readings added up; the day shows the largest source total. */
    fun largestSourceTotal(readings: List<Reading>, zone: TimeZone): Map<LocalDate, Double> =
        byDay(readings, zone).mapValues { (_, day) -> day.groupBy { it.source }.values.maxOf { source -> source.sumOf { it.value } } }

    /** Per day, the mean of the readings (heart rate per minute). */
    fun mean(readings: List<Reading>, zone: TimeZone): Map<LocalDate, Double> =
        byDay(readings, zone).mapValues { (_, day) -> day.sumOf { it.value } / day.size }

    /** Daily totals the sources computed win over [fromReadings] for their days; two sources' totals show the larger. */
    fun preferDailyTotals(totals: List<Pair<LocalDate, Double>>, fromReadings: Map<LocalDate, Double>): Map<LocalDate, Double> =
        fromReadings + totals.groupBy({ it.first }, { it.second }).mapValues { (_, values) -> values.max() }

    /** Values keyed by the civil date the source reported (resting heart rate); the mean when sources differ. */
    fun byReportedDate(values: List<Pair<LocalDate, Double>>): Map<LocalDate, Double> =
        values.groupBy({ it.first }, { it.second }).mapValues { (_, day) -> day.average() }

    private fun byDay(readings: List<Reading>, zone: TimeZone): Map<LocalDate, List<Reading>> =
        readings.groupBy { it.at.toLocalDateTime(zone).date }
}
