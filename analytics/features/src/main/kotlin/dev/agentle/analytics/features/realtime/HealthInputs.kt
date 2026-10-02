package dev.agentle.analytics.features.realtime

import dev.agentle.core.common.Outcome
import dev.agentle.core.model.SleepStageKind
import dev.agentle.core.time.ClosedOpenRange
import kotlinx.datetime.LocalDate
import kotlinx.datetime.UtcOffset
import kotlin.time.Duration
import kotlin.time.Instant

/** Health metrics whose completeness a synced source asserts with `coverageThrough` (R10 §5.3). */
public enum class HealthMetric { STEPS, SLEEP, RESTING_HEART_RATE, HEART_RATE }

/**
 * One step count over `[start, end)` as a source recorded it (`interval_obs`, R05 §7.5). A [count] of 0 is a true zero
 * (worn, nothing counted); a period without any interval is no data (R05 §5.4).
 */
public data class StepInterval(val start: Instant, val end: Instant, val count: Long) {
    init {
        require(end >= start) { "step interval ends before it starts" }
        require(count >= 0) { "negative step count" }
    }
}

/**
 * A run of whole minutes [range] whose steps come from [source] alone, with that source's intervals that overlap the
 * run (an interval may extend beyond it; only its prorated share inside the run counts). The intervals may overlap one
 * another (several devices' records of one source); they are never summed where they overlap.
 *
 * @property provisional the run lies after the canonical source's `coverageThrough` and a local copy fills it
 *   (jitai-correctness-03): the canonical source replaces these minutes once it syncs them.
 */
public data class FusedStepSegment(
    val range: ClosedOpenRange,
    val source: String,
    val intervals: List<StepInterval>,
    val provisional: Boolean = false,
)

/**
 * The fused step series of a window (database-sync-02): per minute, the canonical source where it has coverage, else
 * the next source by priority. A metric is never summed across sources (R05 §7.7), so a watch at 7,800 steps and a
 * phone at 7,000 over the same minutes give 7,800, not 14,800. A minute in no segment has no source with coverage:
 * no data, never zero.
 *
 * A source has coverage for a minute when it reported a value for it (a true zero counts) or, for a source that omits
 * zero minutes (Health Connect on-device steps), when the minute ends at or before that source's `coverageThrough`.
 *
 * One source's intervals may overlap when its connector delivers several devices' records: the Google Health list
 * returns the records of all data sources without deduplication (R05 §5.3), so a watch and the phone that both
 * recorded one walk give two overlapping records of `googlehealth.steps`. They are never summed where they overlap
 * (REALTIME-FEATURES-R1-4): per minute, the source counts the largest total of its intervals that do not overlap one
 * another, so overlapping records of one walk give the larger device's share, not both. A repository that can tell
 * the devices apart may instead split them into sources of their own, fused by priority like any other source.
 *
 * Wearable users whose API source syncs rarely (jitai-correctness-03): minutes after the canonical source's
 * `coverageThrough` are filled by the freshest local copy (Health Connect Fitbit-origin steps, on-device steps) as
 * [provisional][FusedStepSegment.provisional] segments; once the canonical source covers them, its values win again,
 * so daily totals stay canonical.
 *
 * @property segments disjoint, ordered, minute-aligned runs.
 * @property coverageThrough the instant before which the fused series is complete: the latest `coverageThrough` of
 *   its sources, since every minute before it is asserted by the canonical source or by the local copy that fills
 *   after it (R10 §5.3). Null when no source has asserted anything.
 */
public data class FusedStepSeries(val segments: List<FusedStepSegment>, val coverageThrough: Instant?)

/**
 * Steps as a fused minute series over the connected step sources, canonical first (the user's choice per metric,
 * R05 §7.7). The Android repository implements the fusion over the stored sources and may use [StepFusion] for it; the
 * in-memory port in `testing` uses the same rule. Only rows of the active Google Health account are returned
 * (database-sync-15); the repository enforces it.
 */
public interface StepSeriesPort {
    /**
     * The fused series of the minutes that overlap [window]. Window queries find every interval that overlaps the
     * window (`start < windowEnd AND end > windowStart`), never only intervals that start in it (database-sync-18).
     * Unavailable with `NO_PERMISSION` or `SOURCE_DISCONNECTED` when no step source can be read, `NO_DATA` when the
     * device has no step source at all (R03 §5.3).
     */
    public suspend fun fusedMinuteSeries(window: ClosedOpenRange): Outcome<InputAnswer<FusedStepSeries>>
}

/** One stage of a sleep session. Out-of-bed segments are stages of kind `OUT_OF_BED`. */
public data class SleepStageSpan(val kind: SleepStageKind, val start: Instant, val end: Instant) {
    init {
        require(end >= start) { "sleep stage ends before it starts" }
    }
}

/**
 * One sleep session (`sleep_session` / `sleep_stage`, R05 §7.5). Times keep their own UTC offsets (R05 §5.10).
 *
 * @property minutesAsleep the upstream summary (`summary.minutesAsleep`), when the source has one.
 * @property mainSleep `metadata.mainSleep`; null when the source has no such flag.
 * @property nap `metadata.nap`; null when the source has no nap flag (Health Connect), so the 3-hour rule applies.
 * @property processed `metadata.processed`; false while stages are still processing. Null when unknown.
 */
public data class SleepSessionRecord(
    val start: Instant,
    val end: Instant,
    val startOffset: UtcOffset,
    val endOffset: UtcOffset,
    val stages: List<SleepStageSpan> = emptyList(),
    val minutesAsleep: Long? = null,
    val mainSleep: Boolean? = null,
    val nap: Boolean? = null,
    val processed: Boolean? = null,
) {
    init {
        require(end >= start) { "sleep session ends before it starts" }
    }

    val duration: Duration get() = end - start
}

/** Sleep sessions of the canonical sleep source. */
public data class SleepSeries(val source: String, val sessions: List<SleepSessionRecord>)

/** Sleep from the canonical source. Only the active Google Health account's rows are returned (database-sync-15). */
public interface SleepSessionPort {
    /** Sessions whose end instant lies in [endRange] (a night is attributed by its end, R10 §5.4 G). */
    public suspend fun sessionsEnding(endRange: ClosedOpenRange): Outcome<InputAnswer<SleepSeries>>
}

/** Daily values that carry a civil date only and are never converted to instants or engine days (R05 §5.10). */
public enum class DailyMetric { RESTING_HEART_RATE, }

/** Daily values of the canonical source by civil date. */
public data class DailySeries(val source: String, val values: Map<LocalDate, Long>)

/**
 * Upstream daily summaries (Google Health `dailyRestingHeartRate`, Health Connect `RestingHeartRateRecord`), keyed by
 * the civil date the source reports, in the user's zone; never an engine day. Only the active Google Health account's
 * rows are returned (database-sync-15).
 */
public interface DailySummaryPort {
    /** Values of [metric] for the civil dates `first..last` (both inclusive); dates without a value are absent. */
    public suspend fun values(metric: DailyMetric, first: LocalDate, last: LocalDate): Outcome<InputAnswer<DailySeries>>
}

/** One heart-rate sample. */
public data class HeartRateSample(val at: Instant, val bpm: Long)

/** Heart-rate samples of the canonical source. */
public data class HeartRateSeries(val source: String, val samples: List<HeartRateSample>)

/**
 * Heart-rate samples. No v1 catalog feature reads them; outcome metrics and later features may. Only the active Google
 * Health account's rows are returned (database-sync-15).
 */
public interface HeartRateSamplePort {
    /** Samples with `at` in [range], ordered by time. */
    public suspend fun samples(range: ClosedOpenRange): Outcome<InputAnswer<HeartRateSeries>>
}

/** `source_coverage(source, metric, coverageThrough)` published by the health connectors (R10 §5.3). */
public interface SourceCoveragePort {
    /**
     * The instant before which [source] asserts it has delivered everything recorded for [metric]; null when it has
     * never asserted anything (never synced). Only the active Google Health account's coverage counts.
     */
    public suspend fun coverageThrough(source: String, metric: HealthMetric): Outcome<Instant?>
}
