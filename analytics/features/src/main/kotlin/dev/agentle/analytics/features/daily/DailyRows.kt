package dev.agentle.analytics.features.daily

import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.MissingReason
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.Lineage
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.time.Instant

/** State of one daily value. */
public enum class DailyRowStatus {
    /** The window is over and fully observed; [DailySummaryRow.value] is final. */
    FINAL,

    /**
     * The window has not ended yet, or the source has not asserted completeness for it (`coverageThrough`); the value is
     * what is known so far (a lower bound for additive features) and may change. Re-evaluated on every refresh until
     * at most `provisionalGrace` after the window ends.
     */
    PROVISIONAL,

    /** The window is over but only partly observed (a collector gap); the value covers the observed part only. */
    PARTIAL,

    /** No value: see [DailySummaryRow.missingReason]. */
    MISSING,
}

/**
 * One row of `daily_summary` (docs/ARCHITECTURE.md §5.2): the value of [metric] on [date].
 *
 * @property metric the row key: the feature id, or a feature ref key with the subject (`app_minutes{package=a.b}`).
 * @property coverage the observed fraction of the window, 0..1: collector coverage, or the share of the window the
 *   sources used assert to have fully delivered (`coverageThrough`).
 * @property source the source used, when exactly one was (a per-minute fused value may use several).
 * @property lineage categories and source families of the inputs (never empty for personal data).
 * @property catalogVersion the [DailyFeatureCatalog.VERSION] that computed the row; rows of another version are
 *   recomputed (`DailyFeatureEngine.recomputeRecent` after a catalog-version change).
 */
public data class DailySummaryRow(
    val date: LocalDate,
    val metric: String,
    val featureId: String,
    val value: Double?,
    val coverage: Double,
    val status: DailyRowStatus,
    val missingReason: MissingReason? = null,
    val source: DataSourceId? = null,
    val lineage: Lineage,
    val computedAt: Instant,
    val catalogVersion: Int = DailyFeatureCatalog.VERSION,
) {
    init {
        require(coverage in 0.0..1.0) { "coverage out of range: $coverage" }
        when (status) {
            DailyRowStatus.MISSING -> require(value == null && missingReason != null) { "MISSING needs a reason and no value: $metric" }
            DailyRowStatus.FINAL, DailyRowStatus.PARTIAL -> requireNotNull(value) { "$status needs a value: $metric" }
            DailyRowStatus.PROVISIONAL -> Unit
        }
    }

    /** The value when it can be used as an observation of the whole day. */
    val finalValue: Double? get() = if (status == DailyRowStatus.FINAL) value else null

    public companion object {
        /** The row key of [featureId] for [subject] (null for features without a subject). */
        public fun metricKey(featureId: String, subject: SubjectKind?, subjectValue: String?): String =
            if (subject == null || subjectValue == null) featureId else FeatureRef(featureId, mapOf(subject.argName to subjectValue)).key
    }
}

/** State of a rolling-window value (`derived_feature.status`). */
public enum class DerivedStatus {
    /** Enough final days in the window. */
    OK,

    /** Too few final days: the window has no value (never a misleading mean). */
    UNKNOWN,

    /** A value from the final days, while at least one day of the window is still provisional. */
    STALE,
}

/**
 * One row of `derived_feature` (docs/ARCHITECTURE.md §5.2): [featureId] aggregated over the [windowDays] days ending at
 * and including [anchorDate]. [coveredDays] counts the final days the value used; [lineage] is the union of the
 * lineages of the daily rows in the window.
 */
public data class DerivedFeatureRow(
    val featureId: String,
    val windowDays: Int,
    val anchorDate: LocalDate,
    val value: Double?,
    val status: DerivedStatus,
    val coveredDays: Int,
    val lineage: Lineage,
    val computedAt: Instant,
    val catalogVersion: Int = DailyFeatureCatalog.VERSION,
) {
    init {
        require((status == DerivedStatus.UNKNOWN) == (value == null)) { "UNKNOWN rows (and only they) have no value" }
    }
}

/** A dirty date with the version of its latest mark (compare-and-clear, so a mark made during a recompute survives). */
public data class DirtyMark(val date: LocalDate, val version: Long)

/** Where daily and rolling rows and the dirty-day set live (Room in the app; [InMemoryDailyFeatureStore] in tests). */
public interface DailyFeatureStore {
    /** Atomically replaces every daily row of each date in [dates] with [rows] (all of which belong to [dates]). */
    public suspend fun replaceDays(dates: Set<LocalDate>, rows: List<DailySummaryRow>)

    /** Daily rows with `from <= date <= to`. */
    public suspend fun dailyRows(from: LocalDate, to: LocalDate): List<DailySummaryRow>

    /** Inserts or replaces rolling rows by (`featureId`, `windowDays`, `anchorDate`). */
    public suspend fun upsertDerived(rows: List<DerivedFeatureRow>)

    /** Rolling rows with `from <= anchorDate <= to`, optionally of one feature. */
    public suspend fun derivedRows(from: LocalDate, to: LocalDate, featureId: String? = null): List<DerivedFeatureRow>

    /** Marks [dates] dirty; each mark gets a new version. */
    public suspend fun markDirty(dates: Set<LocalDate>)

    public suspend fun dirtyMarks(): List<DirtyMark>

    /** Removes each mark whose version is unchanged; a date marked again since it was read stays dirty. */
    public suspend fun clearDirty(marks: Collection<DirtyMark>)

    /** Dates that have at least one PROVISIONAL row. */
    public suspend fun provisionalDates(): Set<LocalDate>
}

/** An in-memory [DailyFeatureStore]. */
public class InMemoryDailyFeatureStore : DailyFeatureStore {
    private val daily = sortedMapOf<LocalDate, List<DailySummaryRow>>()
    private val derived = LinkedHashMap<Triple<String, Int, LocalDate>, DerivedFeatureRow>()
    private val dirty = LinkedHashMap<LocalDate, Long>()
    private var version = 0L

    override suspend fun replaceDays(dates: Set<LocalDate>, rows: List<DailySummaryRow>) {
        require(rows.all { it.date in dates }) { "rows outside the replaced dates" }
        dates.forEach { daily.remove(it) }
        rows.groupBy { it.date }.forEach { (date, list) -> daily[date] = list }
    }

    override suspend fun dailyRows(from: LocalDate, to: LocalDate): List<DailySummaryRow> =
        if (from > to) emptyList() else daily.subMap(from, to.plusDaysExclusive()).values.flatten()

    override suspend fun upsertDerived(rows: List<DerivedFeatureRow>) {
        rows.forEach { derived[Triple(it.featureId, it.windowDays, it.anchorDate)] = it }
    }

    override suspend fun derivedRows(from: LocalDate, to: LocalDate, featureId: String?): List<DerivedFeatureRow> =
        derived.values.filter { it.anchorDate in from..to && (featureId == null || it.featureId == featureId) }
            .sortedWith(compareBy({ it.anchorDate }, { it.featureId }, { it.windowDays }))

    override suspend fun markDirty(dates: Set<LocalDate>) {
        dates.forEach { dirty[it] = ++version }
    }

    override suspend fun dirtyMarks(): List<DirtyMark> = dirty.map { DirtyMark(it.key, it.value) }.sortedBy { it.date }

    override suspend fun clearDirty(marks: Collection<DirtyMark>) {
        marks.forEach { if (dirty[it.date] == it.version) dirty.remove(it.date) }
    }

    override suspend fun provisionalDates(): Set<LocalDate> =
        daily.filterValues { rows -> rows.any { it.status == DailyRowStatus.PROVISIONAL } }.keys.toSet()

    /** Every daily row, by date (tests). */
    public val allDailyRows: List<DailySummaryRow> get() = daily.values.flatten()

    /** Every rolling row (tests). */
    public val allDerivedRows: List<DerivedFeatureRow> get() = derived.values.toList()

    private fun LocalDate.plusDaysExclusive(): LocalDate = plus(DatePeriod(days = 1))
}
