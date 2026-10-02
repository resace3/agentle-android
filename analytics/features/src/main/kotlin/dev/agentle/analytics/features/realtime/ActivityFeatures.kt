package dev.agentle.analytics.features.realtime

import dev.agentle.analytics.features.FeatureDefinition
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.Freshness
import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.Quality
import dev.agentle.core.model.ActivityKind
import dev.agentle.core.model.TransitionKind
import dev.agentle.core.time.ClosedOpenRange
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Group F, activity and steps (R10 §5.4 F). */
internal object ActivityFeatures {
    private val REPORTED =
        setOf(ActivityKind.STILL, ActivityKind.WALKING, ActivityKind.RUNNING, ActivityKind.ON_BICYCLE, ActivityKind.IN_VEHICLE)
    private const val SECONDS_PER_MINUTE = 60L
    private const val STEPS_TODAY = "steps_today"

    /**
     * `activity_state`: the activity of the latest ENTER, while its EXIT has not followed (a later EXIT of that activity
     * means no activity is known now: `Missing(NO_DATA)`). The transition subscription must have been healthy since that
     * ENTER (collector coverage).
     */
    suspend fun activityState(pass: FeaturePass, collectorId: String): FeatureValue {
        val lookback = ClosedOpenRange(pass.at - pass.config.activityLookback, pass.at)
        return pass.activityTransitions(lookback).orMissing { transitions ->
            val ordered = transitions.filter { it.at in lookback }.sortedBy { it.at }
            val enterIndex = ordered.indexOfLast { it.transition == TransitionKind.ENTER && it.activity in REPORTED }
            val enter = ordered.getOrNull(enterIndex)
            val covered = ClosedOpenRange(enter?.at ?: lookback.start, pass.at)
            val gap = pass.collectorGap(collectorId, covered)
            val exited = enter != null &&
                ordered.drop(enterIndex + 1).any { it.transition == TransitionKind.EXIT && it.activity == enter.activity }
            when {
                gap != null -> FeatureValue.Missing(gap)
                enter == null || exited -> FeatureValue.Missing(MissingReason.NO_DATA)
                else -> pass.known(FeatureScalar.EnumValue(enter.activity.name))
            }
        }
    }

    /**
     * `steps_today`, `steps_last_60m`, `steps_last_30m`: exact prorated sums over the fused series. Fresh when the
     * series' `coverageThrough >= t - maxLag`; otherwise `Stale` (the monotone lower bound applies to `steps_today` in
     * the evaluator). A window without any step interval is `Missing(NO_DATA)`, never 0.
     *
     * A minute in no fused segment is not observed: no source has a value or coverage for it, so it is no data, never
     * 0 steps (R10 §5.4 F, lifecycle-battery-01). `steps_last_60m` and `steps_last_30m` need at least 80 % of their
     * window observed (48 of 60 and 24 of 30 minutes, the rule of `activity_level_last_30m`); otherwise they are
     * `Missing(COVERAGE_GAP)` when the series is fresh and `Missing(NOT_SYNCED)` when it is not (REALTIME-FEATURES-R1-1).
     * `steps_today` keeps R10 §12.D: uncovered parts of the day count no steps (D1), and only a day with no step
     * interval at all is `NO_DATA` (D6).
     */
    suspend fun steps(pass: FeaturePass, definition: FeatureDefinition): FeatureValue {
        val today = definition.id == STEPS_TODAY
        val window = when (definition.id) {
            STEPS_TODAY -> ClosedOpenRange(LocalTimeRules.today(pass.at, pass.zone).start, pass.at)
            "steps_last_60m" -> ClosedOpenRange(pass.at - 60.minutes, pass.at)
            else -> ClosedOpenRange(pass.at - 30.minutes, pass.at)
        }
        return pass.fusedSteps(window).orMissing { series ->
            val through = series.coverageThrough
            val fresh = through != null && through >= pass.at - maxLag(definition)
            when {
                // Nothing asserted for today yet: no lower bound exists for the new day (R10 §12.D D8).
                through == null || (through < window.start && today) -> FeatureValue.Missing(MissingReason.NOT_SYNCED)

                !today && !StepMath.mostlyObserved(series, window) ->
                    FeatureValue.Missing(if (fresh) MissingReason.COVERAGE_GAP else MissingReason.NOT_SYNCED)

                !StepMath.hasData(series, window) -> FeatureValue.Missing(if (fresh) MissingReason.NO_DATA else MissingReason.NOT_SYNCED)

                else -> {
                    val steps = FeatureScalar.IntValue(StepMath.prorated(series, window).floor())
                    fromCoverage(pass, steps, through, fresh, provisional(series, window))
                }
            }
        }
    }

    /**
     * `activity_level_last_30m` over the 30 whole minutes before `t` (minute-aligned, so a source's per-minute counts are
     * not split across two buckets; design). Needs `coverageThrough >= t - 20 min` and at least 24 observed minutes.
     */
    suspend fun activityLevel(pass: FeaturePass, definition: FeatureDefinition): FeatureValue {
        val endMinute = Instant.fromEpochSeconds(Math.floorDiv(pass.at.epochSeconds, SECONDS_PER_MINUTE) * SECONDS_PER_MINUTE)
        val start = endMinute - StepMath.CADENCE_MINUTES.minutes
        val window = ClosedOpenRange(start, endMinute)
        return pass.fusedSteps(window).orMissing { series ->
            val through = series.coverageThrough
            val fresh = through != null && through >= pass.at - maxLag(definition)
            val cadence = StepMath.cadence(series, start)
            when {
                through == null -> FeatureValue.Missing(MissingReason.NOT_SYNCED)

                cadence.observedMinutes < StepMath.MIN_OBSERVED_MINUTES ->
                    FeatureValue.Missing(if (fresh) MissingReason.COVERAGE_GAP else MissingReason.NOT_SYNCED)

                else -> fromCoverage(pass, FeatureScalar.EnumValue(StepMath.level(cadence)), through, fresh, provisional(series, window))
            }
        }
    }

    /**
     * `Known` when the series is fresh, `PROVISIONAL` when a local copy fills minutes the canonical source has not synced
     * yet (jitai-correctness-03); otherwise `Stale` with the value as a lower bound for `steps_today` (R10 §6.3).
     */
    private fun fromCoverage(
        pass: FeaturePass,
        value: FeatureScalar,
        through: Instant,
        fresh: Boolean,
        provisional: Boolean,
    ): FeatureValue = if (fresh) {
        pass.known(value, asOf = minOf(through, pass.at), quality = if (provisional) Quality.PROVISIONAL else Quality.FINAL)
    } else {
        FeatureValue.Stale(value, through, MissingReason.NOT_SYNCED)
    }

    private fun provisional(series: FusedStepSeries, window: ClosedOpenRange): Boolean =
        series.segments.any { it.provisional && it.range.overlaps(window) }

    private fun maxLag(definition: FeatureDefinition): Duration = (definition.freshness as? Freshness.SourceLag)?.maxLag ?: Duration.ZERO
}
