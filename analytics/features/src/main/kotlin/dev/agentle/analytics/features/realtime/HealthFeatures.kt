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
            val value = series.values[today]
            when {
                value == null -> pass.absentDailyValue(series.source, HealthMetric.RESTING_HEART_RATE)
                featureId == "resting_hr_today" -> pass.known(FeatureScalar.IntValue(value))
                else -> delta(pass, value, series.values.filterKeys { it >= first && it < today }.values)
            }
        }
    }

    /** Today's value minus the lower median of the valid values of the previous 28 civil dates (at least 14). */
    private fun delta(pass: FeaturePass, today: Long, previous: Collection<Long>): FeatureValue {
        val baseline = previous.filter { it in VALID }.sorted()
        return when {
            today !in VALID -> FeatureValue.Missing(MissingReason.INVALID_VALUE)
            baseline.size < MIN_BASELINE_VALUES -> FeatureValue.Missing(MissingReason.NO_DATA)
            else -> pass.known(FeatureScalar.IntValue(today - lowerMedian(baseline)))
        }
    }

    /** The lower median of a sorted, non-empty list: the middle value, or the lower of the two middle values. */
    fun lowerMedian(sorted: List<Long>): Long = sorted[(sorted.size - 1) / 2]
}
