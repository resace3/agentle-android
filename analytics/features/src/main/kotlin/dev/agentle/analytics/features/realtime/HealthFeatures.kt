package dev.agentle.analytics.features.realtime

import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.Quality
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus

/**
 * Group G, sleep (R10 §5.4 G): the main session of last night from the canonical sleep source. Without one the value
 * is missing, never 0: `NO_DATA` when the source is synced, `NOT_SYNCED` otherwise.
 */
internal object SleepFeatures {
    suspend fun compute(pass: FeaturePass, featureId: String): FeatureValue =
        pass.sleepEnding(SleepNight.endRangeFor(pass.localDate)).orMissing { series ->
            val session = SleepNight.mainSession(series.sessions, pass.localDate)
                ?: return pass.absentDailyValue(series.source, HealthMetric.SLEEP)
            // Stages may still change while the source processes them (R05 §4.4): every sleep value is provisional.
            val quality = if (session.processed == false) Quality.PROVISIONAL else Quality.FINAL
            val scalar = when (featureId) {
                "sleep_minutes_last_night" -> FeatureScalar.IntValue(SleepNight.minutesAsleep(session))
                "bedtime_last_night" -> FeatureScalar.NightTimeValue(SleepNight.bedtimeMinute(session))
                else -> FeatureScalar.LocalTimeValue(SleepNight.wakeMinute(session))
            }
            pass.known(scalar, quality = quality)
        }
}

/**
 * Group H, heart (R10 §5.4 H): daily resting heart rate by **civil date**. Today is the local date of `t` in the
 * current zone; at 01:30 that is the new date, and a missing row is missing, never yesterday's value.
 */
internal object HeartFeatures {
    const val BASELINE_DAYS: Int = 28
    const val MIN_BASELINE_VALUES: Int = 14

    /** Observed resting heart rates outside this range are invalid (the catalog's valid values of `resting_hr_today`). */
    private val VALID: LongRange = checkNotNull(RealtimeFeatureCatalog["resting_hr_today"]?.validRange)

    suspend fun compute(pass: FeaturePass, featureId: String): FeatureValue {
        val today = pass.localDate
        val first = today.minus(DatePeriod(days = BASELINE_DAYS))
        return pass.dailyValues(DailyMetric.RESTING_HEART_RATE, first, today).orMissing { series ->
            val value = series.values[today] ?: return pass.absentDailyValue(series.source, HealthMetric.RESTING_HEART_RATE)
            if (featureId == "resting_hr_today") return pass.known(FeatureScalar.IntValue(value))
            if (value !in VALID) return FeatureValue.Missing(MissingReason.INVALID_VALUE)
            val baseline = series.values.filterKeys { it >= first && it < today }.values.filter { it in VALID }.sorted()
            if (baseline.size < MIN_BASELINE_VALUES) return FeatureValue.Missing(MissingReason.NO_DATA)
            pass.known(FeatureScalar.IntValue(value - lowerMedian(baseline)))
        }
    }

    /** The lower median of a sorted, non-empty list: the middle value, or the lower of the two middle values. */
    fun lowerMedian(sorted: List<Long>): Long = sorted[(sorted.size - 1) / 2]
}
