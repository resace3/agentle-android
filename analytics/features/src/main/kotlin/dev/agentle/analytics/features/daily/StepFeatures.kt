package dev.agentle.analytics.features.daily

import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.StepsPayload
import dev.agentle.core.time.ClosedOpenRange
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Step features of the local calendar day from the fused step series (docs/research/10 §5.4 F): every selected part of
 * a record adds `count * overlap / duration` as an exact rational, floored once. The daily total prefers the canonical
 * source's own civil-date total (`DailyTotalPayload`) when that source ranks at least as high as the best source in the
 * fused series; it is read by local date and never shifted. Sedentary features read minutes without a selected step
 * record as step-free (sources omit zero minutes), so an unworn tracker reads as sedentary (limitation, docs/research/10
 * §5.4 F `activity_level_last_30m`); they exist only on days with step records.
 */
internal class StepFeatures(private val day: DayContext) {
    private val calendar = day.window(DailyWindow.CALENDAR_DAY)

    suspend fun rows(): List<DailySummaryRow> {
        val pieces = day.fused(MetricFamily.STEPS, day.hullOf(DailyWindow.CALENDAR_DAY, DailyWindow.DAYTIME_08_21))
        val inDay = pieces.mapNotNull { piece -> partsIn(piece, calendar).takeIf { it.isNotEmpty() }?.let { piece to it } }
        val buckets = Buckets()
        inDay.forEach { (piece, parts) -> buckets.add(piece, parts) }
        val sources = inDay.mapTo(sortedSetOf(compareBy { it.value })) { it.first.event.source }
        val rows = ArrayList<DailySummaryRow>()
        rows += totalRow(sources, buckets)
        rows += hourRows(buckets)
        rows += if (inDay.isEmpty()) {
            listOf("active_minutes", "sedentary_minutes", "sedentary_bouts", "longest_sedentary_minutes").map {
                day.rows.noSource(feature(it))
            }
        } else {
            activityRows(sources, buckets)
        }
        return rows
    }

    private suspend fun totalRow(sources: Set<DataSourceId>, buckets: Buckets): DailySummaryRow {
        val def = feature("steps")
        val civil = day.civil(MetricFamily.STEPS).filter { day.policy.isEligible(MetricFamily.STEPS, it.source) }
        val civilSource = day.policy.choose(MetricFamily.STEPS, civil.map { it.source })
        val bestFused = sources.minOfOrNull { day.policy.rank(MetricFamily.STEPS, it) }
        return when {
            civilSource != null && (bestFused == null || day.policy.rank(MetricFamily.STEPS, civilSource) <= bestFused) -> {
                val total = civil.filter { it.source == civilSource }.maxOf { it.value }
                day.rows.sourced(def, def.id, total.takeIf { it >= 0.0 }?.let { kotlin.math.floor(it) }, setOf(civilSource))
            }

            sources.isNotEmpty() -> day.rows.sourced(def, def.id, buckets.total.floor().toDouble(), sources)

            else -> day.rows.noSource(def)
        }
    }

    private suspend fun hourRows(buckets: Buckets): List<DailySummaryRow> {
        val def = feature("steps_by_hour")
        return buckets.hours.toSortedMap().map { (hour, sum) ->
            day.rows.sourced(
                def,
                DailySummaryRow.metricKey(def.id, def.subject, hour.toString()),
                sum.floor().toDouble(),
                buckets.hourSources.getValue(hour),
            )
        }
    }

    private suspend fun activityRows(sources: Set<DataSourceId>, buckets: Buckets): List<DailySummaryRow> {
        val active = buckets.minutes.values.count { it.floor() >= day.config.activeCadence }
        val all = day.sourceCoverage(MetricFamily.STEPS, DailyWindow.DAYTIME_08_21)
        val runs = stepFreeRuns(buckets, sources.flatMap { all[it].orEmpty() })
        val bouts = runs.filter { it >= day.config.sedentaryBoutMinutes }
        return listOf(
            day.rows.sourced(feature("active_minutes"), "active_minutes", active.toDouble(), sources),
            day.rows.sourced(feature("sedentary_minutes"), "sedentary_minutes", runs.sum().toDouble(), sources),
            day.rows.sourced(feature("sedentary_bouts"), "sedentary_bouts", bouts.size.toDouble(), sources),
            day.rows.sourced(
                feature("longest_sedentary_minutes"),
                "longest_sedentary_minutes",
                (runs.maxOrNull() ?: 0).toDouble(),
                sources,
            ),
        )
    }

    /**
     * Lengths of the runs of consecutive step-free minutes in the daytime window (before now), cut at window edges.
     * Only minutes inside the sources' [covered] time count: an uncovered minute ends a run and is never sedentary.
     */
    private fun stepFreeRuns(buckets: Buckets, covered: List<ClosedOpenRange>): List<Int> {
        val runs = ArrayList<Int>()
        for (range in day.window(DailyWindow.DAYTIME_08_21)) {
            var run = 0
            var minute = Math.floorDiv(range.start.toEpochMilliseconds(), IntervalMath.MS_PER_MINUTE)
            val end = minOf(range.end, day.now).toEpochMilliseconds()
            while (minute * IntervalMath.MS_PER_MINUTE < end) {
                val steps = buckets.minutes[minute]?.floor() ?: 0L
                val at = Instant.fromEpochMilliseconds(minute * IntervalMath.MS_PER_MINUTE)
                val inCoverage = covered.any { at >= it.start && at < it.end }
                if (inCoverage && steps <= day.config.sedentaryMaxSteps) {
                    run++
                } else {
                    if (run > 0) runs += run
                    run = 0
                }
                minute++
            }
            if (run > 0) runs += run
        }
        return runs
    }

    /** Exact prorated sums per day, per epoch minute and per local hour. */
    private inner class Buckets {
        val total = ExactSum()
        val minutes = HashMap<Long, ExactSum>()
        val hours = HashMap<Int, ExactSum>()
        val hourSources = HashMap<Int, MutableSet<DataSourceId>>()

        fun add(piece: FusedPiece, parts: List<ClosedOpenRange>) {
            val count = (piece.event.payload as? StepsPayload)?.count ?: 0L
            val interval = piece.event.interval()
            val length = interval.duration.inWholeMilliseconds
            if (length == 0L) {
                total.add(count)
                bucket(interval.start).forEach { it.add(count) }
                hourSources.getOrPut(hourOf(interval.start)) { mutableSetOf() } += piece.event.source
                return
            }
            total.addProrated(count, IntervalMath.totalMillis(parts), length)
            for (part in parts) {
                var minute = Math.floorDiv(part.start.toEpochMilliseconds(), IntervalMath.MS_PER_MINUTE)
                while (minute * IntervalMath.MS_PER_MINUTE < part.end.toEpochMilliseconds()) {
                    val start = maxOf(minute * IntervalMath.MS_PER_MINUTE, part.start.toEpochMilliseconds())
                    val end = minOf((minute + 1) * IntervalMath.MS_PER_MINUTE, part.end.toEpochMilliseconds())
                    val at = Instant.fromEpochMilliseconds(minute * IntervalMath.MS_PER_MINUTE)
                    bucket(at).forEach { it.addProrated(count, end - start, length) }
                    hourSources.getOrPut(hourOf(at)) { mutableSetOf() } += piece.event.source
                    minute++
                }
            }
        }

        private fun bucket(at: Instant): List<ExactSum> {
            val minute = Math.floorDiv(at.toEpochMilliseconds(), IntervalMath.MS_PER_MINUTE)
            return listOf(minutes.getOrPut(minute) { ExactSum() }, hours.getOrPut(hourOf(at)) { ExactSum() })
        }

        private fun hourOf(at: Instant): Int = at.toLocalDateTime(day.timeline.zoneAt(at)).hour
    }

    private fun feature(id: String) = DailyFeatureCalculator.feature(id)

    internal companion object {
        /** The selected parts of [piece] inside [window]; a point record is its own part when it lies in the window. */
        fun partsIn(piece: FusedPiece, window: List<ClosedOpenRange>): List<ClosedOpenRange> {
            val interval = piece.event.interval()
            return if (interval.duration.inWholeMilliseconds == 0L) {
                if (piece.isSelected && IntervalMath.contains(window, interval.start)) listOf(interval) else emptyList()
            } else {
                IntervalMath.clip(piece.selected, window)
            }
        }
    }
}
