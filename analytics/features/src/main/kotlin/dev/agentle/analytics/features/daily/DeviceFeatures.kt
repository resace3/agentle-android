package dev.agentle.analytics.features.daily

import dev.agentle.core.model.ActivityKind
import dev.agentle.core.model.ActivityTransitionPayload
import dev.agentle.core.model.EventType
import dev.agentle.core.model.LocationVisitPayload
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.TransitionKind
import dev.agentle.core.time.ClosedOpenRange
import kotlin.time.Instant

/**
 * Charging features from the battery collector's start/stop events. The state at the window start comes from the
 * latest event within `stateLookback` before it; a stop with no earlier start in that range means the phone was
 * already charging when the range began.
 */
internal class ChargingFeatures(private val day: DayContext) {
    suspend fun rows(): List<DailySummaryRow> {
        val kind = DailyWindow.ENGINE_DAY
        val window = day.window(kind)
        val range = day.hullOf(kind)
        val events = day.events(DailyGroupQueries.CHARGING, range, day.config.stateLookback).sortedWith(EVENT_ORDER)
        val charging = intervals(events, range.start - day.config.stateLookback, minOf(range.end, day.now))
        val inWindow = events.filter { IntervalMath.contains(window, it.startTime) }
        val starts = inWindow.filter { it.type == EventType.CHARGING_STARTED }
        val stops = inWindow.filter { it.type == EventType.CHARGING_STOPPED }
        val collector = collectorOf(DailyFeatureCalculator.feature("charging_minutes"))
        // State intervals count only where the collector was healthy: a gap is never "still charging".
        val chargingInWindow = MinuteFusion.intersect(IntervalMath.clip(charging, window), day.coveredRanges(collector, kind))
        val coverage = day.coverage(collector, kind)
        val seen = inWindow.isNotEmpty() || chargingInWindow.isNotEmpty()
        return listOf(
            row("charging_minutes", (IntervalMath.totalMillis(chargingInWindow) / IntervalMath.MS_PER_MINUTE).toDouble(), coverage, seen),
            row("charging_starts", starts.size.toDouble(), coverage, seen),
            row(
                "charging_last_start",
                starts.lastOrNull()?.let {
                    nightMinute(minuteOfDay(it.startTime, zoneOf(it))).toDouble()
                },
                coverage,
                seen,
            ),
            row("charging_first_end", stops.firstOrNull()?.let { minuteOfDay(it.startTime, zoneOf(it)).toDouble() }, coverage, seen),
        )
    }

    private fun row(id: String, value: Double?, coverage: Double, seen: Boolean): DailySummaryRow {
        val def = DailyFeatureCalculator.feature(id)
        return day.rows.collector(def, def.id, value, coverage, seen)
    }

    private fun intervals(events: List<PersonalEvent>, from: Instant, until: Instant): List<ClosedOpenRange> {
        val out = ArrayList<ClosedOpenRange>()
        var since: Instant? = null
        events.forEachIndexed { index, e ->
            if (e.type == EventType.CHARGING_STARTED) {
                if (since == null) since = e.startTime
            } else {
                val start = since ?: from.takeIf { index == 0 }
                if (start != null && e.startTime > start) out += ClosedOpenRange(start, e.startTime)
                since = null
            }
        }
        since?.let { if (until > it) out += ClosedOpenRange(it, until) }
        return mergeRanges(out)
    }
}

/**
 * Time at a place class (location visits) and time in a detected activity (activity transitions). Place minutes need
 * background location, which this version does not collect, so `place_minutes` is unavailable and skipped unless
 * [DailyFeatureConfig.includeUnavailable].
 */
internal class PlaceAndActivityFeatures(private val day: DayContext) {
    suspend fun rows(): List<DailySummaryRow> = placeRows() + activityRows()

    private suspend fun placeRows(): List<DailySummaryRow> {
        val def = DailyFeatureCalculator.feature("place_minutes")
        if (!def.isAvailable && !day.config.includeUnavailable) return emptyList()
        val visits = day.events(DailyGroupQueries.VISITS, day.hullOf(def.window)).filter { it.payload is LocationVisitPayload }
        val byPlace = visits.groupBy {
            (it.payload as LocationVisitPayload).placeClass.name
        }.mapValues { (_, list) -> list.map { it.interval() } }
        return subjectRows(def, byPlace)
    }

    private suspend fun activityRows(): List<DailySummaryRow> {
        val def = DailyFeatureCalculator.feature("activity_minutes")
        val range = day.hullOf(def.window)
        val events = day.events(DailyGroupQueries.ACTIVITY, range, day.config.stateLookback)
            .filter { it.payload is ActivityTransitionPayload }
            .sortedWith(EVENT_ORDER)
        return subjectRows(def, activityIntervals(events, minOf(range.end, day.now)).mapKeys { it.key.name })
    }

    private suspend fun subjectRows(def: DailyFeatureDefinition, bySubject: Map<String, List<ClosedOpenRange>>): List<DailySummaryRow> {
        val window = day.window(def.window)
        val coverage = day.coverage(collectorOf(def), def.window)
        val covered = day.coveredRanges(collectorOf(def), def.window)
        return bySubject.toSortedMap().mapNotNull { (subject, intervals) ->
            // State intervals (an activity still "entered") count only inside the collector's healthy time.
            val millis = IntervalMath.unionMillis(MinuteFusion.intersect(intervals, covered), window)
            if (millis == 0L) {
                null
            } else {
                val key = DailySummaryRow.metricKey(def.id, def.subject, subject)
                day.rows.collector(def, key, (millis / IntervalMath.MS_PER_MINUTE).toDouble(), coverage, hasEvents = true)
            }
        }
    }

    /** Pairs each ENTER with the EXIT of the same activity; a new ENTER ends the previous activity (they exclude each other). */
    private fun activityIntervals(events: List<PersonalEvent>, until: Instant): Map<ActivityKind, List<ClosedOpenRange>> {
        val out = LinkedHashMap<ActivityKind, MutableList<ClosedOpenRange>>()
        var current: ActivityKind? = null
        var since: Instant? = null
        fun close(at: Instant) {
            val activity = current
            val start = since
            if (activity != null && start != null && at > start) out.getOrPut(activity) { ArrayList() } += ClosedOpenRange(start, at)
            current = null
            since = null
        }
        for (e in events) {
            val p = e.payload as ActivityTransitionPayload
            when (p.transition) {
                TransitionKind.ENTER -> {
                    close(e.startTime)
                    current = p.activity
                    since = e.startTime
                }

                TransitionKind.EXIT -> if (p.activity == current) close(e.startTime)
            }
        }
        close(until)
        return out
    }
}

/** Deterministic event order: start, then dedup key. */
internal val EVENT_ORDER: Comparator<PersonalEvent> = compareBy({ it.startTime }, { it.dedupKey })
