package dev.agentle.analytics.features.daily

import dev.agentle.core.model.Lineage
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlin.math.sqrt
import kotlin.time.Instant

/**
 * Rolling-window aggregation of daily rows (`derived_feature`, docs/ARCHITECTURE.md §10). A window of `n` days ending at
 * the anchor date uses only FINAL days for means and standard deviations and needs at least `ceil(2n / 3)` of them
 * (and two for a standard deviation); otherwise it is UNKNOWN, never a mean of a few days. A sum needs a value for
 * every day of the window. A window that still contains a PROVISIONAL day is STALE.
 */
public object RollingWindows {
    /** Minimum number of final days for a mean over [windowDays] days. */
    public fun minDays(windowDays: Int): Int = (2 * windowDays + 2) / 3

    /** The row of [def] over [windowDays] days ending at [anchor]; [daily] holds the daily rows of `def.dailyFeatureId`. */
    public fun compute(
        def: RollingFeatureDefinition,
        windowDays: Int,
        anchor: LocalDate,
        daily: Map<LocalDate, DailySummaryRow>,
        now: Instant,
    ): DerivedFeatureRow {
        require(windowDays > 0) { "windowDays must be positive" }
        val all = (0 until windowDays).mapNotNull { daily[anchor.minus(DatePeriod(days = it))] }
        // A row of another catalog version is waiting for its recompute: never final, it makes the window STALE.
        val rows = all.filter { it.catalogVersion == DailyFeatureCatalog.VERSION }
        val final = rows.mapNotNull { it.finalValue }
        val pending = rows.size < all.size || rows.any { it.status == DailyRowStatus.PROVISIONAL }
        val known = if (pending) DerivedStatus.STALE else DerivedStatus.OK
        val result: Pair<Double?, Int> = when (def.aggregator) {
            RollingAggregator.MEAN -> (if (final.size >= minDays(windowDays)) final.average() else null) to final.size

            RollingAggregator.STDDEV ->
                (if (final.size >= maxOf(2, minDays(windowDays))) sampleStandardDeviation(final) else null) to final.size

            RollingAggregator.SUM -> {
                val values = rows.filter {
                    it.status == DailyRowStatus.FINAL || it.status == DailyRowStatus.PROVISIONAL
                }.mapNotNull { it.value }
                (if (values.size == windowDays) values.sum() else null) to values.size
            }

            RollingAggregator.NONE -> error("${def.id} has no rolling aggregation")
        }
        val (value, covered) = result
        return DerivedFeatureRow(
            featureId = def.id,
            windowDays = windowDays,
            anchorDate = anchor,
            value = value,
            status = if (value == null) DerivedStatus.UNKNOWN else known,
            coveredDays = covered,
            lineage = Lineage.union(rows.map { it.lineage }),
            computedAt = now,
        )
    }

    /** Every rolling row anchored at [anchor]; [byFeature] holds daily rows by feature id and date. */
    public fun computeAll(
        anchor: LocalDate,
        byFeature: Map<String, Map<LocalDate, DailySummaryRow>>,
        now: Instant,
    ): List<DerivedFeatureRow> = DailyFeatureCatalog.rolling.flatMap { def ->
        val daily = byFeature[def.dailyFeatureId].orEmpty()
        def.windows.map { compute(def, it, anchor, daily, now) }
    }

    /** Sample standard deviation (n - 1); [values] has at least two elements. */
    internal fun sampleStandardDeviation(values: List<Double>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    }
}
