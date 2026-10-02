package dev.agentle.analytics.features.daily

import dev.agentle.core.model.ActivityTransitionPayload
import dev.agentle.core.model.AppUsagePayload
import dev.agentle.core.model.DailyTotalMetric
import dev.agentle.core.model.DailyTotalPayload
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventType
import dev.agentle.core.model.HeartRatePayload
import dev.agentle.core.model.LocationVisitPayload
import dev.agentle.core.model.NotificationPayload
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.RestingHeartRatePayload
import dev.agentle.core.model.StepsPayload
import dev.agentle.core.time.ClosedOpenRange
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * One range query over the `event` table: events of [types] that overlap [range] (an interval event with
 * `start < range.end && end > range.start`, so intervals that span the window start are found; a point event with its
 * start in the range), optionally restricted to [sources] and to one [subject] (the `subject` column, see
 * [DailyInputs.subjectOf]). Every daily computation reads through bounded queries like this; nothing scans the table.
 */
public data class EventQuery(
    val types: Set<EventType>,
    val range: ClosedOpenRange,
    val sources: Set<DataSourceId>? = null,
    val subject: String? = null,
)

/**
 * One source record and the parts of it the per-minute fusion selected ([MinuteFusion]). An interval record contributes
 * `count * |selected ∩ window| / |record|` (exact proration); a point record (a heart-rate sample) is selected when
 * [selected] is not empty.
 */
public data class FusedPiece(val event: PersonalEvent, val selected: List<ClosedOpenRange>) {
    val isSelected: Boolean get() = selected.isNotEmpty()
}

/**
 * A value an upstream source reports for a civil date (a wearable daily step total, a daily resting heart rate). It is
 * stored and read by local date and never shifted onto the 04:00 engine day (docs/research/05 §5.10).
 */
public data class CivilDateValue(val date: LocalDate, val family: MetricFamily, val value: Double, val source: DataSourceId)

/**
 * What the daily feature engine reads (the `FeatureDataSource` of docs/ARCHITECTURE.md §3, implemented by the Android
 * `:data` repositories over Room; [InMemoryDailyInputs] in tests). Contract for implementations:
 * - Every read is range-bounded; nothing scans a whole table.
 * - Only rows of the active Google Health account are returned: rows of a disconnected or previous account never reach
 *   features or insights (database-sync-15). Filtering by account is the repository's job, not the caller's.
 * - Multi-source metrics are read only through [fusedSeries]; no caller sums a metric across sources (database-sync-02).
 * - Coverage (collector and source) is reported only where the data it covers is still stored: deleting or expiring
 *   data removes the coverage of that range too, so a removed day reads as UNKNOWN, never as zero (database-sync-16).
 */
public interface DailyInputs {
    /** Events matching [query] (overlap semantics), in start order. */
    public suspend fun events(query: EventQuery): List<PersonalEvent>

    /**
     * The fused series of [family] over [range]: per minute, the records of the highest-priority source that has a
     * record in that minute (a true zero counts), else the next source by priority. [MinuteFusion] defines the exact
     * rule and is what implementations use, with [sourcePolicy]. Only selected pieces that overlap [range] are returned.
     */
    public suspend fun fusedSeries(family: MetricFamily, range: ClosedOpenRange): List<FusedPiece>

    /** Civil-date values of [family] for [date], from every source, keyed by the date the source reported. */
    public suspend fun civilDateValues(family: MetricFamily, date: LocalDate): List<CivilDateValue>

    /**
     * Intervals overlapping [range] in which the on-device collector [collectorId] (a capability id) was known to be
     * healthy (permission granted, listener connected, subscription active; `collector_coverage`, docs/research/10 §5.1)
     * and whose data is still stored.
     */
    public suspend fun collectorCoverage(collectorId: String, range: ClosedOpenRange): List<ClosedOpenRange>

    /**
     * For each connected source of [family]: the parts of [range] for which the source asserts it has delivered
     * everything it recorded (from its import floor to its `coverageThrough`, docs/research/10 §5.3), minus ranges whose
     * data was deleted or expired. A source that makes no claim is absent or maps to an empty list.
     */
    public suspend fun sourceCoverage(family: MetricFamily, range: ClosedOpenRange): Map<DataSourceId, List<ClosedOpenRange>>

    /** The canonical-source policy the repository fuses with (the user's choice per metric). */
    public suspend fun sourcePolicy(): CanonicalSourcePolicy

    /** The user's zone history over [range]. */
    public suspend fun zoneTimeline(range: ClosedOpenRange): ZoneTimeline

    public companion object {
        /** The `subject` projection of an event: package, place class or activity; null for other events. */
        public fun subjectOf(event: PersonalEvent): String? = when (val p = event.payload) {
            is AppUsagePayload -> p.packageName
            is NotificationPayload -> p.packageName
            is LocationVisitPayload -> p.placeClass.name
            is ActivityTransitionPayload -> p.activity.name
            else -> null
        }

        /**
         * The civil-date value an event carries, or null: a daily step total ([DailyTotalPayload] of
         * [DailyTotalMetric.STEPS]) or a daily resting heart rate ([RestingHeartRatePayload]; legacy rows with a
         * [HeartRatePayload] are dated by their start in their own zone). The payload date is authoritative.
         */
        public fun civilValueOf(event: PersonalEvent): CivilDateValue? = when (val p = event.payload) {
            is DailyTotalPayload -> if (p.metric ==
                DailyTotalMetric.STEPS
            ) {
                CivilDateValue(p.date, MetricFamily.STEPS, p.value, event.source)
            } else {
                null
            }

            is RestingHeartRatePayload -> CivilDateValue(p.date, MetricFamily.RESTING_HEART_RATE, p.bpm, event.source)

            is HeartRatePayload -> if (event.type == EventType.RESTING_HEART_RATE) {
                CivilDateValue(event.startTime.toLocalDateTime(zoneOf(event)).date, MetricFamily.RESTING_HEART_RATE, p.bpm, event.source)
            } else {
                null
            }

            else -> null
        }

        /** Event types that carry civil-date values of [family]. */
        public fun civilTypes(family: MetricFamily): Set<EventType> = when (family) {
            MetricFamily.STEPS -> setOf(EventType.DAILY_TOTAL)
            MetricFamily.RESTING_HEART_RATE -> setOf(EventType.RESTING_HEART_RATE)
            else -> emptySet()
        }

        /** A bounded instant range that contains every event dated [date] in any zone (UTC-12 to UTC+14, with slack). */
        public fun civilQueryRange(date: LocalDate): ClosedOpenRange = ClosedOpenRange(
            date.minus(DatePeriod(days = 2)).atStartOfDayIn(TimeZone.UTC),
            date.plus(DatePeriod(days = 3)).atStartOfDayIn(TimeZone.UTC),
        )
    }
}

/**
 * Per-minute source fusion (database-sync-02): for every minute of the range, the minute belongs to the
 * highest-priority source ([CanonicalSourcePolicy]) that has a record in it; the records of every other source are
 * ignored for that minute. A watch and a phone that count the same walk therefore never double the steps, and the
 * phone still fills the minutes the watch was off the wrist. Sources the policy does not list are never used.
 * Within one source, records are assumed not to overlap (connector contract, docs/research/05 §7.5).
 */
public object MinuteFusion {
    private const val MINUTE_MS = IntervalMath.MS_PER_MINUTE

    /** Fuses the [family] records among [events] over [range]. */
    public fun fuse(
        events: List<PersonalEvent>,
        family: MetricFamily,
        policy: CanonicalSourcePolicy,
        range: ClosedOpenRange,
    ): List<FusedPiece> {
        val eligible = events.filter {
            it.type in family.eventTypes && policy.isEligible(family, it.source) &&
                it.interval().overlapsOrHolds(range)
        }
        if (eligible.isEmpty()) return emptyList()
        val bySource = eligible.groupBy { it.source }
        val order = bySource.keys.sortedWith(compareBy<DataSourceId>({ policy.rank(family, it) }, { it.value }))
        var remaining = listOf(minutesOf(range))
        val assigned = HashMap<DataSourceId, List<ClosedOpenRange>>()
        for (source in order) {
            val covered = mergeRanges(bySource.getValue(source).map { minutesOf(it.interval()) })
            assigned[source] = intersect(covered, remaining)
            remaining = subtract(remaining, covered)
        }
        return eligible.map { event ->
            val interval = event.interval()
            val mine = assigned.getValue(event.source)
            val selected = if (interval.duration == Duration.ZERO) {
                if (interval.start in range && mine.any { interval.start in it }) listOf(interval) else emptyList()
            } else {
                intersect(mine, listOf(interval)).mapNotNull { it.intersect(range) }
            }
            FusedPiece(event, selected)
        }
    }

    private fun ClosedOpenRange.overlapsOrHolds(range: ClosedOpenRange): Boolean =
        if (duration == Duration.ZERO) start in range else overlaps(range)

    /** The whole minutes a range touches (a point touches the minute it lies in). */
    private fun minutesOf(interval: ClosedOpenRange): ClosedOpenRange {
        val first = Math.floorDiv(interval.start.toEpochMilliseconds(), MINUTE_MS)
        val endMs = interval.end.toEpochMilliseconds()
        val last = if (interval.duration == Duration.ZERO) first + 1 else Math.floorDiv(endMs + MINUTE_MS - 1, MINUTE_MS)
        return ClosedOpenRange(Instant.fromEpochMilliseconds(first * MINUTE_MS), Instant.fromEpochMilliseconds(last * MINUTE_MS))
    }

    /** Intersection of two lists of ranges, as sorted disjoint ranges. */
    public fun intersect(a: List<ClosedOpenRange>, b: List<ClosedOpenRange>): List<ClosedOpenRange> =
        mergeRanges(a.flatMap { x -> b.mapNotNull { y -> x.intersect(y) } })

    /** [a] minus [b], as sorted disjoint ranges. */
    public fun subtract(a: List<ClosedOpenRange>, b: List<ClosedOpenRange>): List<ClosedOpenRange> {
        val cuts = mergeRanges(b)
        return mergeRanges(
            a.flatMap { piece ->
                cuts.fold(listOf(piece)) { parts, cut ->
                    parts.flatMap { p ->
                        if (!p.overlaps(cut)) {
                            listOf(p)
                        } else {
                            listOfNotNull(
                                if (cut.start > p.start) ClosedOpenRange(p.start, cut.start) else null,
                                if (cut.end < p.end) ClosedOpenRange(cut.end, p.end) else null,
                            )
                        }
                    }
                }
            },
        )
    }
}

/**
 * An in-memory [DailyInputs] over a list of events (tests, debug tools). Events are kept sorted by start; a query is a
 * binary search plus a scan bounded by the longest stored interval. [fusedSeries] uses [MinuteFusion] with [policy].
 */
public class InMemoryDailyInputs(
    events: List<PersonalEvent> = emptyList(),
    private val timeline: ZoneTimeline = ZoneTimeline.fixed(TimeZone.UTC),
    collectorCoverage: Map<String, List<ClosedOpenRange>> = emptyMap(),
    private val policy: CanonicalSourcePolicy = CanonicalSourcePolicy.DEFAULT,
) : DailyInputs {
    private var sorted: List<PersonalEvent> = emptyList()
    private var longest: Duration = Duration.ZERO
    private val coverage: MutableMap<String, List<ClosedOpenRange>> = collectorCoverage.mapValuesTo(mutableMapOf()) {
        mergeRanges(it.value)
    }
    private val sourceCoverage = HashMap<MetricFamily, MutableMap<DataSourceId, List<ClosedOpenRange>>>()

    init {
        add(events)
    }

    /** Number of [events] calls, so tests can check that computation stays range-bounded. */
    public var queryCount: Int = 0
        private set

    /** Adds events (dedup by `dedupKey`, the newer copy wins, as the Room upsert does). */
    public fun add(newEvents: Collection<PersonalEvent>) {
        val byKey = LinkedHashMap<String, PersonalEvent>()
        (sorted + newEvents).forEach { byKey[it.dedupKey] = it }
        sorted = byKey.values.sortedWith(compareBy({ it.startTime }, { it.dedupKey }))
        longest = sorted.maxOfOrNull { it.interval().duration } ?: Duration.ZERO
    }

    /** Removes events (retention or deletion); the caller removes the matching coverage with [removeCoverage]. */
    public fun remove(predicate: (PersonalEvent) -> Boolean) {
        sorted = sorted.filterNot(predicate)
    }

    /** Adds healthy intervals of a collector. */
    public fun addCoverage(collectorId: String, ranges: List<ClosedOpenRange>) {
        coverage[collectorId] = mergeRanges(coverage[collectorId].orEmpty() + ranges)
    }

    /** Sets what [source] asserts for [family] (its covered ranges). */
    public fun setSourceCoverage(family: MetricFamily, source: DataSourceId, ranges: List<ClosedOpenRange>) {
        sourceCoverage.getOrPut(family) { LinkedHashMap() }[source] = mergeRanges(ranges)
    }

    /** Removes the coverage of [range] for every collector and source (the data in it was deleted). */
    public fun removeCoverage(range: ClosedOpenRange) {
        coverage.replaceAll { _, ranges -> MinuteFusion.subtract(ranges, listOf(range)) }
        sourceCoverage.values.forEach { bySource -> bySource.replaceAll { _, ranges -> MinuteFusion.subtract(ranges, listOf(range)) } }
    }

    public val allEvents: List<PersonalEvent> get() = sorted

    override suspend fun events(query: EventQuery): List<PersonalEvent> {
        queryCount++
        val range = query.range
        val out = ArrayList<PersonalEvent>()
        var i = lowerBound(range.start - longest)
        while (i < sorted.size && sorted[i].startTime < range.end) {
            val e = sorted[i]
            if (e.type in query.types && overlaps(e, range) &&
                (query.sources == null || e.source in query.sources) &&
                (query.subject == null || DailyInputs.subjectOf(e) == query.subject)
            ) {
                out += e
            }
            i++
        }
        return out
    }

    override suspend fun fusedSeries(family: MetricFamily, range: ClosedOpenRange): List<FusedPiece> {
        val events = events(EventQuery(family.eventTypes, range))
        return MinuteFusion.fuse(events, family, policy, range).filter { it.isSelected }
    }

    override suspend fun civilDateValues(family: MetricFamily, date: LocalDate): List<CivilDateValue> {
        val types = DailyInputs.civilTypes(family)
        if (types.isEmpty()) return emptyList()
        return events(EventQuery(types, DailyInputs.civilQueryRange(date)))
            .mapNotNull { DailyInputs.civilValueOf(it) }
            .filter { it.family == family && it.date == date }
    }

    override suspend fun collectorCoverage(collectorId: String, range: ClosedOpenRange): List<ClosedOpenRange> =
        coverage[collectorId].orEmpty().mapNotNull { it.intersect(range) }

    override suspend fun sourceCoverage(family: MetricFamily, range: ClosedOpenRange): Map<DataSourceId, List<ClosedOpenRange>> =
        sourceCoverage[family].orEmpty().mapValues { (_, ranges) -> ranges.mapNotNull { it.intersect(range) } }

    override suspend fun sourcePolicy(): CanonicalSourcePolicy = policy

    override suspend fun zoneTimeline(range: ClosedOpenRange): ZoneTimeline = timeline

    private fun overlaps(e: PersonalEvent, range: ClosedOpenRange): Boolean {
        val interval = e.interval()
        return if (interval.duration == Duration.ZERO) e.startTime in range else interval.overlaps(range)
    }

    private fun lowerBound(start: Instant): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].startTime < start) lo = mid + 1 else hi = mid
        }
        return lo
    }

    public companion object {
        /** The step count of a step record (0 for anything else). */
        public fun stepCount(event: PersonalEvent): Long = (event.payload as? StepsPayload)?.count ?: 0L
    }
}
