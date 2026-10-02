package dev.agentle.analytics.features.realtime

import dev.agentle.core.time.ClosedOpenRange
import kotlin.time.Instant

/**
 * The step fusion rule of [FusedStepSeries] as a pure function (database-sync-02, jitai-correctness-03): per minute,
 * the first source in priority order that has coverage for it; a metric is never summed across sources. Minutes after
 * the canonical source's `coverageThrough` that another source fills are provisional. The in-memory port uses it, and
 * a repository may call it with the rows it loaded: each connected source's intervals that overlap the window and its
 * coverage, canonical first.
 */
public object StepFusion {
    /**
     * One step source.
     *
     * @property reportsTrueZeros the source writes an explicit 0 for every worn minute without steps (the Google Health
     *   API, R05 §5.4), so only its reported minutes are covered; a source that omits zero minutes (Health Connect
     *   on-device steps) also covers every minute that ends at or before its [coverageThrough].
     * @property intervals the source's records as stored. They may overlap one another when a connector delivers
     *   several devices' records under one source: the Google Health list returns the records of every data source
     *   without deduplication (R05 §5.3), so a walk recorded by a watch and by the phone arrives twice. Fusion keeps
     *   them; the step arithmetic never sums intervals of one source where they overlap (REALTIME-FEATURES-R1-4).
     */
    public data class Source(
        val id: String,
        val reportsTrueZeros: Boolean,
        val intervals: List<StepInterval>,
        val coverageThrough: Instant?,
    )

    /** Fuses [sources], canonical first, over the whole minutes that overlap [window]. */
    public fun fuse(window: ClosedOpenRange, sources: List<Source>): FusedStepSeries {
        val coverageThrough = sources.mapNotNull { it.coverageThrough }.maxOrNull()
        val canonicalThrough = sources.firstOrNull()?.coverageThrough
        val first = floorMinute(window.start)
        val end = ceilMinute(window.end)
        val span = ClosedOpenRange(instantOf(first), instantOf(end))
        val relevant = sources.map { source -> source.intervals.filter { touches(it, span) } }
        val reported = relevant.map { intervals -> reportedMinutes(intervals, first, end) }
        val runs = mutableListOf<Run>()
        for (minute in first until end) {
            val owner = sources.indices.firstOrNull { i -> minute in reported[i] || coveredWithoutValue(sources[i], minute) }
                ?: continue
            val provisional = owner > 0 && (canonicalThrough == null || instantOf(minute + 1) > canonicalThrough)
            val last = runs.lastOrNull()
            if (last != null && last.source == owner && last.provisional == provisional && last.end == minute) {
                last.end = minute + 1
            } else {
                runs += Run(owner, provisional, minute, minute + 1)
            }
        }
        val segments = runs.map { run ->
            val range = ClosedOpenRange(instantOf(run.start), instantOf(run.end))
            FusedStepSegment(range, sources[run.source].id, relevant[run.source].filter { touches(it, range) }, run.provisional)
        }
        return FusedStepSeries(segments, coverageThrough)
    }

    private class Run(val source: Int, val provisional: Boolean, val start: Long, var end: Long)

    /** Epoch minutes in `[first, end)` for which the source reported a value (a true zero counts). */
    private fun reportedMinutes(intervals: List<StepInterval>, first: Long, end: Long): Set<Long> {
        val minutes = HashSet<Long>()
        for (interval in intervals) {
            val from = maxOf(floorMinute(interval.start), first)
            val until = minOf(if (interval.end == interval.start) floorMinute(interval.start) + 1 else ceilMinute(interval.end), end)
            for (minute in from until until) minutes += minute
        }
        return minutes
    }

    private fun coveredWithoutValue(source: Source, minute: Long): Boolean {
        val through = source.coverageThrough
        return !source.reportsTrueZeros && through != null && instantOf(minute + 1) <= through
    }

    private fun touches(interval: StepInterval, range: ClosedOpenRange): Boolean =
        if (interval.end == interval.start) interval.start in range else interval.start < range.end && interval.end > range.start

    private fun floorMinute(at: Instant): Long = Math.floorDiv(at.epochSeconds, SECONDS_PER_MINUTE)

    private fun ceilMinute(at: Instant): Long {
        val floor = floorMinute(at)
        return if (instantOf(floor) == at) floor else floor + 1
    }

    private fun instantOf(epochMinute: Long): Instant = Instant.fromEpochSeconds(epochMinute * SECONDS_PER_MINUTE)

    private const val SECONDS_PER_MINUTE = 60L
}
