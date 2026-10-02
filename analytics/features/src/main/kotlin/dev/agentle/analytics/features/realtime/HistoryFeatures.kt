package dev.agentle.analytics.features.realtime

import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlin.time.Duration

/**
 * Group I, intervention history (R10 §5.4 I) from Agentle's own decision rows: always known. Rows in state `DELIVERED`
 * or `DELIVERY_UNCERTAIN` are deliveries; the port returns nothing else.
 */
internal object HistoryFeatures {
    /** Rows read for the "latest delivery" features; `consecutive_ignored` saturates here (its literal maximum). */
    const val LATEST_LIMIT: Int = 1000

    /** `minutes_since_last_delivery` saturates at its literal maximum, one year, instead of becoming invalid (design). */
    const val MAX_MINUTES: Long = 525_600

    private val IGNORED = setOf(InterventionResponse.IGNORED, InterventionResponse.DISMISSED)
    private const val WEEK_DAYS_BEFORE_TODAY = 6

    suspend fun compute(pass: FeaturePass, featureId: String, selector: JitaiSelector): FeatureValue = when (featureId) {
        "deliveries_today" -> count(pass, selector, pass.engineDay)

        "deliveries_last_7d" -> count(pass, selector, pass.engineDay.minus(DatePeriod(days = WEEK_DAYS_BEFORE_TODAY)))

        else -> pass.latestDeliveries(selector, LATEST_LIMIT).orMissing { rows ->
            val latest = rows.filter { selector.matches(it) }
            when (featureId) {
                "minutes_since_last_delivery" -> minutesSince(pass, latest.firstOrNull())
                "last_response" -> pass.known(FeatureScalar.EnumValue((latest.firstOrNull()?.response ?: InterventionResponse.NONE).name))
                else -> pass.known(FeatureScalar.IntValue(latest.takeWhile { it.response in IGNORED }.size.toLong()))
            }
        }
    }

    /** Deliveries whose stored engine day is in `first..today` (engine days, R10 §10.2). */
    private suspend fun count(pass: FeaturePass, selector: JitaiSelector, first: LocalDate): FeatureValue =
        pass.deliveriesInEngineDays(selector, first, pass.engineDay).orMissing { rows ->
            val counted = rows.count { selector.matches(it) && it.engineDay >= first && it.engineDay <= pass.engineDay }
            pass.known(FeatureScalar.IntValue(counted.toLong()))
        }

    /** `NEVER` without a delivery, else the floored elapsed minutes of R10 §8.6, saturated at [MAX_MINUTES]. */
    private suspend fun minutesSince(pass: FeaturePass, latest: DeliveryRecord?): FeatureValue {
        if (latest == null) return pass.known(FeatureScalar.Never)
        val minutes = elapsedSince(pass, latest).inWholeMinutes
        return pass.known(FeatureScalar.IntValue(minOf(minutes, MAX_MINUTES)))
    }

    /**
     * The elapsed-time rule of R10 §8.6: the monotonic difference when the delivery was recorded in the current boot
     * (same boot count), otherwise the wall-clock difference, clamped at 0. When the current boot count cannot be read,
     * the wall-clock rule applies.
     */
    suspend fun elapsedSince(pass: FeaturePass, record: DeliveryRecord): Duration {
        val recordedElapsed = record.elapsedRealtime
        val recordedBoot = record.bootCount
        val sameBoot = recordedElapsed != null && recordedBoot != null && (pass.bootCount() as? Read.Ok)?.value == recordedBoot
        val elapsed = if (sameBoot) pass.elapsedNow - recordedElapsed else pass.at - record.at
        return if (elapsed.isNegative()) Duration.ZERO else elapsed
    }
}
