package dev.agentle.analytics.features.daily

import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.onFailure
import dev.agentle.core.common.outcomeOf
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.time.AgentleClock
import dev.agentle.core.time.ClosedOpenRange
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Which dates a change can affect. A date's rows read instants from its local day minus a few hours to the next local
 * day at 14:00 (sleep), state streams look back `stateLookback`, and a local date lies within 14 hours of the UTC date,
 * so an instant range marks the dates from the day before its UTC start date (minus 14 h) to the UTC date of its end
 * plus 14 h and the lookback. Civil-date records also mark their own date. Marking a few dates too many is harmless:
 * recomputing a date is idempotent.
 */
public object DirtyDays {
    private val ZONE_SLACK = 14.hours

    /** Dates whose rows can change when [event] is added, corrected or removed. */
    public fun of(event: PersonalEvent, config: DailyFeatureConfig = DailyFeatureConfig()): Set<LocalDate> =
        overlapping(event.interval(), config) + setOfNotNull(DailyInputs.civilValueOf(event)?.date)

    /** Dates whose rows can change when anything inside [range] changes (coverage, a deletion, an upstream correction). */
    public fun overlapping(range: ClosedOpenRange, config: DailyFeatureConfig = DailyFeatureConfig()): Set<LocalDate> {
        val first = utcDate(range.start - ZONE_SLACK).minus(DatePeriod(days = 1))
        val last = utcDate(range.end + ZONE_SLACK + config.stateLookback)
        return datesBetween(first, last)
    }

    /** Every date from [from] to [to], both included (empty when [from] is after [to]). */
    public fun datesBetween(from: LocalDate, to: LocalDate): Set<LocalDate> {
        val out = LinkedHashSet<LocalDate>()
        var d = from
        while (d <= to) {
            out += d
            d = d.plus(DatePeriod(days = 1))
        }
        return out
    }

    private fun utcDate(instant: Instant): LocalDate = instant.toLocalDateTime(TimeZone.UTC).date
}

/** What one refresh or recompute did. */
public data class RefreshReport(val dates: Set<LocalDate>, val dailyRows: Int, val derivedRows: Int)

/**
 * Keeps `daily_summary` and `derived_feature` current (docs/ARCHITECTURE.md §10). Ingestion, coverage changes and
 * deletions mark dates dirty in the store ([markChanged], [markRangeChanged]); [refresh] recomputes the dirty dates, every
 * date that still has a PROVISIONAL row, and today and yesterday, then the rolling windows anchored on or after them.
 * Computing a date is idempotent and reads only bounded ranges, so a date may be recomputed any number of times and a
 * change may be reported more than once. Dates after today are never computed.
 *
 * Bounded recomputes for the background scheduler: [recomputeRecent] after a catalog-version or canonical-source change,
 * [recomputeAfterZoneChange] after a time-zone change; both cover at most [DailyFeatureConfig.recomputeDays] dates.
 */
public class DailyFeatureEngine(
    private val inputs: DailyInputs,
    private val store: DailyFeatureStore,
    private val clock: AgentleClock,
    private val config: DailyFeatureConfig = DailyFeatureConfig(),
    private val logger: Logger = Logger.NONE,
) {
    private val calculator = DailyFeatureCalculator(inputs, config)

    /** Marks the dates [events] can affect (after an ingestion, correction or deletion of them). */
    public suspend fun markChanged(events: Collection<PersonalEvent>) {
        store.markDirty(events.flatMapTo(LinkedHashSet()) { DirtyDays.of(it, config) })
    }

    /** Marks the dates a change inside [range] can affect (collector or source coverage, a deleted range). */
    public suspend fun markRangeChanged(range: ClosedOpenRange) {
        store.markDirty(DirtyDays.overlapping(range, config))
    }

    /**
     * Recomputes the store's dirty dates, the dates with PROVISIONAL rows, and today and yesterday; then clears the
     * marks it read (compare-and-clear: a date marked again during the run stays dirty).
     */
    public suspend fun refresh(): Outcome<RefreshReport> = guarded("refresh") {
        val marks = store.dirtyMarks()
        val today = clock.today()
        val dates = marks.mapTo(LinkedHashSet()) { it.date } + store.provisionalDates() + setOf(today, today.minus(DatePeriod(days = 1)))
        val report = run(dates, today)
        store.clearDirty(marks)
        report
    }

    /** Recomputes exactly [dates] (repeats are fine) and the rolling windows they feed; marks are not touched. */
    public suspend fun recompute(dates: Set<LocalDate>): Outcome<RefreshReport> = guarded("recompute") { run(dates, clock.today()) }

    /** Recomputes the last [DailyFeatureConfig.recomputeDays] dates (catalog-version or canonical-source change). */
    public suspend fun recomputeRecent(): Outcome<RefreshReport> = guarded("recompute_recent") {
        val today = clock.today()
        run(DirtyDays.datesBetween(today.minus(DatePeriod(days = config.recomputeDays - 1)), today), today)
    }

    /**
     * Recomputes the dates whose windows a zone change at [changeAt] moves: from the day before the change through
     * today, bounded to the last [DailyFeatureConfig.recomputeDays] dates.
     */
    public suspend fun recomputeAfterZoneChange(changeAt: Instant): Outcome<RefreshReport> = guarded("recompute_zone") {
        val today = clock.today()
        val floor = today.minus(DatePeriod(days = config.recomputeDays - 1))
        val first = maxOf(floor, DirtyDays.overlapping(ClosedOpenRange(changeAt, changeAt), config).min())
        run(DirtyDays.datesBetween(first, today), today)
    }

    private suspend fun run(requested: Set<LocalDate>, today: LocalDate): RefreshReport {
        val dates = requested.filter { it <= today }.toSortedSet()
        val now = clock.now()
        var dailyRows = 0
        for (date in dates) {
            val rows = calculator.compute(date, now)
            store.replaceDays(setOf(date), rows)
            dailyRows += rows.size
        }
        val derived = if (dates.isEmpty()) 0 else refreshRolling(dates, today, now)
        return RefreshReport(dates, dailyRows, derived)
    }

    /** Recomputes every rolling row anchored at a changed date or at most 89 days after one (up to today). */
    private suspend fun refreshRolling(changed: Set<LocalDate>, today: LocalDate, now: Instant): Int {
        val span = DailyFeatureCatalog.WINDOWS.max()
        val anchors = changed.flatMapTo(sortedSetOf()) { DirtyDays.datesBetween(it, minOf(today, it.plus(DatePeriod(days = span - 1)))) }
        if (anchors.isEmpty()) return 0
        val daily = store.dailyRows(anchors.first().minus(DatePeriod(days = span - 1)), anchors.last())
        val needed = DailyFeatureCatalog.rolling.mapTo(HashSet()) { it.dailyFeatureId }
        val byFeature = daily.filter { it.metric == it.featureId && it.featureId in needed }
            .groupBy { it.featureId }
            .mapValues { (_, rows) -> rows.associateBy { it.date } }
        val rows = anchors.flatMap { RollingWindows.computeAll(it, byFeature, now) }
        store.upsertDerived(rows)
        return rows.size
    }

    private suspend fun <T> guarded(operation: String, block: suspend () -> T): Outcome<T> =
        outcomeOf { block() }.onFailure { error -> logger.w(COMPONENT, "daily features $operation failed", error) }

    private companion object {
        const val COMPONENT = "DailyFeatureEngine"
    }
}
