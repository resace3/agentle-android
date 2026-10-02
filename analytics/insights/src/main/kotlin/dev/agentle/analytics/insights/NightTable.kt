package dev.agentle.analytics.insights

import dev.agentle.analytics.features.daily.DailyFeatureCatalog
import dev.agentle.analytics.features.daily.DailyRowStatus
import dev.agentle.analytics.features.daily.DailySummaryRow
import dev.agentle.core.model.Lineage
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** Night type (docs/research/10 §14.2): a weekend night is one whose next local date is a weekend day. */
public enum class NightType {
    WORK_NIGHT,
    WEEKEND_NIGHT,
    ;

    public companion object {
        /** The type of night [night]: weekend when the next date is in [weekendDays] (default: Friday and Saturday nights). */
        public fun of(night: LocalDate, weekendDays: Set<DayOfWeek>): NightType =
            if (night.plus(DatePeriod(days = 1)).dayOfWeek in weekendDays) WEEKEND_NIGHT else WORK_NIGHT
    }
}

/**
 * Settings of the discovery pipeline (docs/research/10 §14.2-§14.5). The statistical thresholds are the calibrated
 * values of §14.6; changing them invalidates that calibration.
 */
public data class InsightConfig(
    val windowNights: Int = 90,
    val weekendDays: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
    val maxOpenProposals: Int = 3,
    val muteDays: Int = 60,
    val trialDays: Int = 28,
    /** A previous run more than this many days before the current one does not count as the consecutive run (design). */
    val maxRunGapDays: Int = 10,
) {
    init {
        require(windowNights in MIN_NIGHTS..MAX_WINDOW_NIGHTS) { "windowNights must be $MIN_NIGHTS-$MAX_WINDOW_NIGHTS" }
        require(maxOpenProposals >= 1 && muteDays >= 1 && trialDays in 1..MAX_TRIAL_DAYS && maxRunGapDays >= WEEK_DAYS) {
            "invalid proposal policy settings"
        }
    }

    public companion object {
        public const val MIN_NIGHTS: Int = 28
        public const val MAX_WINDOW_NIGHTS: Int = 366
        public const val MAX_TRIAL_DAYS: Int = 90
        public const val WEEK_DAYS: Int = 7
    }
}

/**
 * One night of the analysis window: its type and the exposures and outcomes that are known. An absent key is unknown
 * (a collector gap, a provisional or missing daily row), never false.
 */
public data class Night(
    val date: LocalDate,
    val type: NightType,
    val exposures: Map<Exposure, Boolean>,
    val outcomes: Map<NightOutcome, Boolean>,
)

/**
 * The nights of one analysis window, oldest first, and the lineage of every daily feature they were read from (union
 * of the lineages of the rows used).
 */
public data class NightTable(
    val firstNight: LocalDate,
    val lastNight: LocalDate,
    val nights: List<Night>,
    val featureLineage: Map<String, Lineage> = emptyMap(),
) {
    /** Lineage of a hypothesis: its exposure's and its outcome's features. A feature never read has no lineage. */
    public fun lineageOf(hypothesis: Hypothesis): Lineage = (featureLineage[hypothesis.exposure.dailyFeatureId] ?: Lineage.NONE) +
        (featureLineage[hypothesis.outcome.dailyFeatureId] ?: Lineage.NONE)
}

/**
 * Builds the night table from `daily_summary` rows (docs/research/10 §14.2-§14.3). Only FINAL rows of the current
 * [DailyFeatureCatalog.VERSION] are observations; every other row (provisional, partial, missing, or computed by another
 * catalog version and not yet recomputed, or an additive feature such as minutes or counts whose coverage is below 1)
 * leaves the value unknown, never zero. Reads a bounded date range ([rowRange]).
 */
public object NightTableBuilder {
    /** The first and last dates of the daily rows needed for the window that ends with [lastNight]. */
    public fun rowRange(lastNight: LocalDate, config: InsightConfig = InsightConfig()): Pair<LocalDate, LocalDate> {
        val firstNight = lastNight.minus(DatePeriod(days = config.windowNights - 1))
        return firstNight.minus(DatePeriod(days = NightOutcome.RHR_BASELINE_DAYS - 1)) to lastNight.plus(DatePeriod(days = 1))
    }

    public fun build(lastNight: LocalDate, rows: List<DailySummaryRow>, config: InsightConfig = InsightConfig()): NightTable {
        val firstNight = lastNight.minus(DatePeriod(days = config.windowNights - 1))
        val used = rows.filter {
            it.status == DailyRowStatus.FINAL && it.catalogVersion == DailyFeatureCatalog.VERSION && it.metric == it.featureId &&
                it.featureId in FEATURES && (it.coverage >= 1.0 || DailyFeatureCatalog[it.featureId]?.additive != true)
        }
        val values = used.associate { (it.featureId to it.date) to requireNotNull(it.value) }
        fun value(feature: String, date: LocalDate): Double? = values[feature to date]

        val dates = (0 until config.windowNights).map { firstNight.plus(DatePeriod(days = it)) }
        val medianBedtime = median(dates.mapNotNull { value(NightOutcome.LATE_BEDTIME.dailyFeatureId, it) })
        val medianSleep = median(dates.mapNotNull { value(NightOutcome.SHORT_SLEEP.dailyFeatureId, it) })

        val nights = dates.map { d ->
            val exposures = Exposure.entries.mapNotNull { e -> value(e.dailyFeatureId, d)?.let { e to e.isExposed(it) } }.toMap()
            val outcomes = buildMap {
                val bedtime = value(NightOutcome.LATE_BEDTIME.dailyFeatureId, d)
                if (bedtime != null && medianBedtime != null) {
                    put(NightOutcome.LATE_BEDTIME, bedtime >= medianBedtime + NightOutcome.LATE_MARGIN_MINUTES)
                }
                val asleep = value(NightOutcome.SHORT_SLEEP.dailyFeatureId, d)
                if (asleep != null &&
                    medianSleep != null
                ) {
                    put(NightOutcome.SHORT_SLEEP, asleep <= medianSleep - NightOutcome.SHORT_MARGIN_MINUTES)
                }
                highRestingHr(d) { feature, date -> value(feature, date) }?.let { put(NightOutcome.HIGH_RESTING_HR, it) }
            }
            Night(d, NightType.of(d, config.weekendDays), exposures, outcomes)
        }
        val lineage = used.groupBy { it.featureId }.mapValues { (_, list) -> Lineage.union(list.map { it.lineage }) }
        return NightTable(firstNight, lastNight, nights, lineage)
    }

    /** O_rhr of night [d]: resting HR of `d+1` against the lower median of the 28 dates before it (>= 14 values). */
    private fun highRestingHr(d: LocalDate, value: (String, LocalDate) -> Double?): Boolean? {
        val feature = NightOutcome.HIGH_RESTING_HR.dailyFeatureId
        val next = value(feature, d.plus(DatePeriod(days = 1))) ?: return null
        val baseline = (0 until NightOutcome.RHR_BASELINE_DAYS).mapNotNull { value(feature, d.minus(DatePeriod(days = it))) }
        if (baseline.size < NightOutcome.RHR_MIN_BASELINE) return null
        return next >= lowerMedian(baseline) + NightOutcome.RHR_MARGIN_BPM
    }

    /** The median (mean of the two middle values for an even count); null for no values. */
    public fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** The lower median: the lower of the two middle values for an even count. */
    public fun lowerMedian(values: List<Double>): Double {
        require(values.isNotEmpty()) { "no values" }
        return values.sorted()[(values.size - 1) / 2]
    }

    private val FEATURES: Set<String> = (
        Exposure.entries.map {
            it.dailyFeatureId
        } + NightOutcome.entries.map { it.dailyFeatureId }
        ).toSet()
}
