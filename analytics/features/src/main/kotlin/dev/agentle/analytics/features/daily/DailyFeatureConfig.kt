package dev.agentle.analytics.features.daily

import kotlinx.datetime.DayOfWeek
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Tunables of the daily features. Defaults are design values unless a source is named.
 *
 * @property weekendDays configured weekend days (docs/research/10 fixture F0: SAT and SUN).
 * @property minCollectorCoverage a collector window counts as fully observed at this covered fraction (design).
 * @property provisionalGrace a row stays PROVISIONAL at most this long after its window ends while its source has not
 *   asserted completeness; later data still marks the day dirty (docs/research/10 §15.1 uses 48 h for outcomes).
 * @property activeCadence steps per minute for an active minute; 100 steps/min is moderate intensity per
 *   CADENCE-Adults (docs/research/10 §1.5 [TL19]; exact threshold wording UNVERIFIED).
 * @property sedentaryMaxSteps a minute with at most this many steps is step-free (design).
 * @property sedentaryBoutMinutes minimum length of a sedentary period (design).
 * @property minExerciseMinutes exercise minutes that make an exercise day (design).
 * @property healthConnectNapMaxDuration Health Connect has no nap flag: shorter sessions are naps (docs/research/10 §5.4 G).
 * @property appGapMergeMillis gaps of at most this length inside one app's use are merged (docs/research/10 §5.5).
 * @property heartRateValid plausible heart-rate samples in bpm; others are ignored.
 * @property restingHeartRateValid valid resting heart rates (docs/research/10 §5.4 H).
 * @property ownPackages Agentle's own notifications are not counted (docs/research/10 §5.4 D: "from another package").
 * @property appCategoryOverrides the user's per-package app category (`SOCIAL`, `VIDEO`, ...), over the declared one.
 * @property stateLookback how far back a state stream (charging, activity) is read to know the state at a window
 *   start. Intervals that span a window start are found by the overlap query itself.
 * @property includeUnavailable also compute features whose availability is unavailable in this build (debug, tests).
 * @property recomputeDays how many dates (ending today) a bounded recompute after a catalog-version, time-zone or
 *   canonical-source change covers; the largest rolling window (90 days) by default.
 */
public data class DailyFeatureConfig(
    val weekendDays: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
    val minCollectorCoverage: Double = 0.95,
    val provisionalGrace: Duration = 48.hours,
    val activeCadence: Long = 100,
    val sedentaryMaxSteps: Long = 0,
    val sedentaryBoutMinutes: Int = 60,
    val minExerciseMinutes: Long = 10,
    val healthConnectNapMaxDuration: Duration = 3.hours,
    val appGapMergeMillis: Long = 2_000,
    val heartRateValid: ClosedFloatingPointRange<Double> = 25.0..250.0,
    val restingHeartRateValid: ClosedFloatingPointRange<Double> = 20.0..220.0,
    val ownPackages: Set<String> = setOf("dev.agentle.app", "dev.agentle.app.fake"),
    val appCategoryOverrides: Map<String, String> = emptyMap(),
    val stateLookback: Duration = 24.hours,
    val includeUnavailable: Boolean = false,
    val recomputeDays: Int = 90,
) {
    init {
        require(minCollectorCoverage in 0.0..1.0) { "minCollectorCoverage must be 0..1" }
        require(sedentaryBoutMinutes > 0 && activeCadence > 0) { "thresholds must be positive" }
        require(recomputeDays in 1..MAX_RECOMPUTE_DAYS) { "recomputeDays must be 1..$MAX_RECOMPUTE_DAYS" }
    }

    public companion object {
        /** Upper bound of any recompute range, so a bad request can never recompute the whole history at once. */
        public const val MAX_RECOMPUTE_DAYS: Int = 400
    }
}
