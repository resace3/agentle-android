package dev.agentle.analytics.features.daily

import dev.agentle.analytics.features.MissingReason
import dev.agentle.core.model.AppUsagePayload
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventType
import dev.agentle.core.model.ExercisePayload
import dev.agentle.core.model.Lineage
import dev.agentle.core.model.LocationVisitPayload
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.ScreenPayload
import dev.agentle.core.model.SourceFamily
import dev.agentle.core.model.family
import dev.agentle.core.time.ClosedOpenRange
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Computes every daily feature of one date from range-bounded [DailyInputs] reads (docs/ARCHITECTURE.md §10). The result
 * is a pure function of the inputs and `now`, so recomputing a date always gives the same rows (idempotent).
 */
public class DailyFeatureCalculator(private val inputs: DailyInputs, private val config: DailyFeatureConfig = DailyFeatureConfig()) {
    /** All rows of [date] as of [now]. */
    public suspend fun compute(date: LocalDate, now: Instant): List<DailySummaryRow> {
        val day = DayContext.load(date, now, inputs, config)
        val rows = ArrayList<DailySummaryRow>()
        rows += UsageFeatures(day).rows()
        rows += NotificationFeatures(day).rows()
        rows += ChargingFeatures(day).rows()
        rows += PlaceAndActivityFeatures(day).rows()
        rows += StepFeatures(day).rows()
        rows += ExerciseFeatures(day).rows()
        rows += HeartFeatures(day).rows()
        rows += SleepFeatures(day).rows()
        rows += calendarRows(day)
        rows += historyRows(day)
        return rows.filter { config.includeUnavailable || feature(it.featureId).isAvailable }
    }

    private fun calendarRows(day: DayContext): List<DailySummaryRow> {
        val def = feature("is_weekend")
        val value = if (day.date.dayOfWeek in config.weekendDays) 1.0 else 0.0
        return listOf(
            DailySummaryRow(day.date, def.id, def.id, value, 1.0, DailyRowStatus.FINAL, lineage = Lineage.NONE, computedAt = day.now),
        )
    }

    private suspend fun historyRows(day: DayContext): List<DailySummaryRow> {
        val window = day.window(DailyWindow.ENGINE_DAY)
        val events = day.events(DailyGroupQueries.HISTORY, day.hullOf(DailyWindow.ENGINE_DAY))
        return listOf("interventions_delivered", "interventions_opened", "interventions_dismissed").map { id ->
            val def = feature(id)
            val count = events.count { it.type in def.eventTypes && IntervalMath.contains(window, it.startTime) }
            day.rows.alwaysKnown(def, def.id, count.toDouble(), DailyWindow.ENGINE_DAY)
        }
    }

    internal companion object {
        fun feature(id: String): DailyFeatureDefinition = requireNotNull(DailyFeatureCatalog[id]) { "unknown daily feature $id" }
    }
}

/** Event-type groups queried once per date. */
internal object DailyGroupQueries {
    val USAGE = setOf(EventType.SCREEN_SESSION, EventType.APP_SESSION)
    val UNLOCKS = setOf(EventType.DEVICE_UNLOCK)
    val NOTIFICATIONS = setOf(EventType.NOTIFICATION_POSTED)
    val CHARGING = setOf(EventType.CHARGING_STARTED, EventType.CHARGING_STOPPED)
    val VISITS = setOf(EventType.LOCATION_VISIT)
    val ACTIVITY = setOf(EventType.ACTIVITY)
    val SLEEP = setOf(EventType.SLEEP_SESSION)
    val HISTORY = setOf(EventType.JITAI_DELIVERED, EventType.JITAI_OPENED, EventType.JITAI_DISMISSED)
}

/** Everything one date's computation shares: windows under the zone timeline, cached reads, row construction. */
internal class DayContext private constructor(
    val date: LocalDate,
    val now: Instant,
    val timeline: ZoneTimeline,
    val config: DailyFeatureConfig,
    val policy: CanonicalSourcePolicy,
    private val inputs: DailyInputs,
) {
    private val windows = HashMap<DailyWindow, List<ClosedOpenRange>>()
    private val queries = HashMap<Pair<Set<EventType>, ClosedOpenRange>, List<PersonalEvent>>()
    private val fused = HashMap<Pair<MetricFamily, ClosedOpenRange>, List<FusedPiece>>()
    private val coverages = HashMap<Pair<String, DailyWindow>, Double>()
    private val sourceRanges = HashMap<Pair<MetricFamily, DailyWindow>, Map<DataSourceId, List<ClosedOpenRange>>>()
    val rows: RowFactory = RowFactory(this)

    fun window(kind: DailyWindow): List<ClosedOpenRange> = windows.getOrPut(kind) { kind.ranges(date, timeline) }

    /** The single range spanning the windows of [kinds]. */
    fun hullOf(vararg kinds: DailyWindow): ClosedOpenRange = requireNotNull(hull(kinds.flatMap { window(it) })) { "empty window" }

    fun windowEnd(kind: DailyWindow): Instant = hullOf(kind).end

    fun windowStarted(kind: DailyWindow): Boolean = now >= hullOf(kind).start

    fun windowEnded(kind: DailyWindow): Boolean = now >= windowEnd(kind)

    /** Whether rows of [kind] may still wait for late data (the window ended less than `provisionalGrace` ago). */
    fun inGrace(kind: DailyWindow): Boolean = now < windowEnd(kind) + config.provisionalGrace

    /** Events of [types] overlapping [range] widened back by [lookback] (state streams only). */
    suspend fun events(types: Set<EventType>, range: ClosedOpenRange, lookback: Duration = Duration.ZERO): List<PersonalEvent> {
        val query = ClosedOpenRange(range.start - lookback, range.end)
        return queries.getOrPut(types to query) { inputs.events(EventQuery(types, query)) }
    }

    /** The selected pieces of the fused series of [family] over [range]. */
    suspend fun fused(family: MetricFamily, range: ClosedOpenRange): List<FusedPiece> =
        fused.getOrPut(family to range) { inputs.fusedSeries(family, range).filter { it.isSelected } }

    /** Civil-date values of [family] for this date. */
    suspend fun civil(family: MetricFamily): List<CivilDateValue> = inputs.civilDateValues(family, date)

    /** The fraction of [kind]'s window covered by [collectorId]'s healthy intervals. */
    suspend fun coverage(collectorId: String, kind: DailyWindow): Double = coverages.getOrPut(collectorId to kind) {
        val window = window(kind)
        val covered = IntervalMath.unionMillis(inputs.collectorCoverage(collectorId, hullOf(kind)), window)
        IntervalMath.fraction(covered, IntervalMath.windowMillis(window))
    }

    /** Per connected source of [family], the parts of [kind]'s window it asserts to have fully delivered. */
    suspend fun sourceCoverage(family: MetricFamily, kind: DailyWindow): Map<DataSourceId, List<ClosedOpenRange>> =
        sourceRanges.getOrPut(family to kind) {
            val window = window(kind)
            inputs.sourceCoverage(family, hullOf(kind)).mapValues { (_, ranges) -> IntervalMath.clip(ranges, window) }
        }

    /** The fraction of [kind]'s window that [sources] together assert to have fully delivered. */
    suspend fun sourceCoverageFraction(family: MetricFamily, kind: DailyWindow, sources: Collection<DataSourceId>): Double {
        val all = sourceCoverage(family, kind)
        val window = window(kind)
        val covered = IntervalMath.unionMillis(sources.flatMap { all[it].orEmpty() }, window)
        return IntervalMath.fraction(covered, IntervalMath.windowMillis(window))
    }

    companion object {
        suspend fun load(date: LocalDate, now: Instant, inputs: DailyInputs, config: DailyFeatureConfig): DayContext {
            val envelope = ClosedOpenRange(
                DailyWindow.CALENDAR_DAY.bounds(date.minus(DatePeriod(days = 2)), TimeZone.UTC).start,
                DailyWindow.CALENDAR_DAY.bounds(date.plus(DatePeriod(days = 3)), TimeZone.UTC).end,
            )
            return DayContext(date, now, inputs.zoneTimeline(envelope), config, inputs.sourcePolicy(), inputs)
        }
    }
}

/** Builds rows with the status rules of the null semantics. */
internal class RowFactory(private val day: DayContext) {
    private fun lineage(def: DailyFeatureDefinition, families: Set<SourceFamily>) = Lineage(setOfNotNull(def.category), families)

    /**
     * A collector feature: FINAL when the window is over and covered, PARTIAL when partly covered, MISSING when the
     * collector saw nothing; PROVISIONAL while the window runs. [value] null means "nothing to report" (a time-of-event
     * feature without the event).
     */
    fun collector(def: DailyFeatureDefinition, metric: String, value: Double?, coverage: Double, hasEvents: Boolean): DailySummaryRow {
        val observed = coverage > 0.0 || hasEvents
        val lineage = lineage(def, if (observed) setOf(SourceFamily.ON_DEVICE) else emptySet())
        val (status, reason) = when {
            !day.windowStarted(def.window) -> DailyRowStatus.PROVISIONAL to MissingReason.NOT_YET_AVAILABLE
            !day.windowEnded(def.window) -> DailyRowStatus.PROVISIONAL to if (observed) null else MissingReason.COLLECTOR_INACTIVE
            coverage >= day.config.minCollectorCoverage -> DailyRowStatus.FINAL to null
            observed -> DailyRowStatus.PARTIAL to null
            else -> DailyRowStatus.MISSING to MissingReason.COLLECTOR_INACTIVE
        }
        val shown = if (observed && status != DailyRowStatus.MISSING) value else null
        return finish(def, metric, shown, coverage, status, reason, null, lineage)
    }

    /**
     * A feature computed from [sources]' records. PROVISIONAL while the window runs, while [pending] (the source is
     * still processing the record), or while the sources do not assert coverage of the whole window, but at most
     * `provisionalGrace` after the window ends; FINAL afterwards. [value] null gives MISSING with [reason].
     */
    suspend fun sourced(
        def: DailyFeatureDefinition,
        metric: String,
        value: Double?,
        sources: Set<DataSourceId>,
        pending: Boolean = false,
        reason: MissingReason = MissingReason.INVALID_VALUE,
    ): DailySummaryRow {
        val family = requireNotNull(def.metricFamily) { "${def.id} has no metric family" }
        val coverage = day.sourceCoverageFraction(family, def.window, sources)
        val waiting = day.inGrace(def.window) && (pending || coverage < 1.0)
        val status = if (!day.windowEnded(def.window) || waiting) DailyRowStatus.PROVISIONAL else DailyRowStatus.FINAL
        val lineage = lineage(def, sources.mapTo(mutableSetOf()) { it.family })
        return finish(def, metric, value, coverage, status, if (value == null) reason else null, sources.singleOrNull(), lineage)
    }

    /**
     * No source has data for the window: MISSING with [reason] once the window is over (or, with [waitInGrace], once
     * `provisionalGrace` has passed after it), PROVISIONAL before.
     */
    fun noSource(
        def: DailyFeatureDefinition,
        metric: String = def.id,
        reason: MissingReason = MissingReason.NO_DATA,
        waitInGrace: Boolean = false,
    ): DailySummaryRow {
        val over = day.windowEnded(def.window) && !(waitInGrace && day.inGrace(def.window))
        val status = if (over) DailyRowStatus.MISSING else DailyRowStatus.PROVISIONAL
        val why = if (day.windowStarted(def.window)) reason else MissingReason.NOT_YET_AVAILABLE
        return DailySummaryRow(day.date, metric, def.id, null, 0.0, status, why, null, lineage(def, emptySet()), day.now)
    }

    /** Clock, calendar and Agentle's own records. */
    fun alwaysKnown(def: DailyFeatureDefinition, metric: String, value: Double, kind: DailyWindow): DailySummaryRow =
        if (!day.windowStarted(kind)) {
            DailySummaryRow(
                day.date, metric, def.id, null, 0.0, DailyRowStatus.PROVISIONAL, MissingReason.NOT_YET_AVAILABLE, null,
                lineage(def, emptySet()), day.now,
            )
        } else {
            DailySummaryRow(
                day.date, metric, def.id, value, 1.0,
                if (day.windowEnded(kind)) DailyRowStatus.FINAL else DailyRowStatus.PROVISIONAL,
                null, null, lineage(def, setOf(SourceFamily.ON_DEVICE)), day.now,
            )
        }

    private fun finish(
        def: DailyFeatureDefinition,
        metric: String,
        value: Double?,
        coverage: Double,
        status: DailyRowStatus,
        reason: MissingReason?,
        source: DataSourceId?,
        lineage: Lineage,
    ): DailySummaryRow {
        val noValue = value == null && status != DailyRowStatus.PROVISIONAL
        return DailySummaryRow(
            date = day.date,
            metric = metric,
            featureId = def.id,
            value = value,
            coverage = coverage,
            status = if (noValue) DailyRowStatus.MISSING else status,
            missingReason = when {
                value != null -> null
                noValue && status == DailyRowStatus.PARTIAL -> MissingReason.COVERAGE_GAP
                else -> reason ?: MissingReason.NO_DATA
            },
            source = source,
            lineage = lineage,
            computedAt = day.now,
        )
    }
}

/** The instant range an event covers: `[start, end)`, or `[start, start + payload duration)`, or a point. */
internal fun PersonalEvent.interval(): ClosedOpenRange {
    val duration = when (val p = payload) {
        is AppUsagePayload -> p.durationMs
        is ScreenPayload -> p.durationMs
        is ExercisePayload -> p.durationMs
        is LocationVisitPayload -> p.durationMs
        else -> null
    }
    val end = endTime ?: duration?.let { startTime + it.milliseconds } ?: startTime
    return ClosedOpenRange(startTime, maxOf(end, startTime))
}

/** Local minute of the day (0..1439). */
internal fun minuteOfDay(instant: Instant, zone: TimeZone): Int = instant.toLocalDateTime(zone).let { it.hour * 60 + it.minute }

/** Local minute of the day (0..1439) at a fixed UTC offset in seconds. */
internal fun minuteOfDayAt(instant: Instant, offsetSeconds: Int): Int = Math.floorMod(
    Math.floorDiv(instant.toEpochMilliseconds() + offsetSeconds * MS_PER_SECOND, IntervalMath.MS_PER_MINUTE),
    MINUTES_PER_DAY.toLong(),
).toInt()

/** Local date at a fixed UTC offset in seconds (a record's own offset, docs/research/05 §5.10). */
internal fun localDateAt(instant: Instant, offsetSeconds: Int): LocalDate {
    val days = Math.floorDiv(instant.toEpochMilliseconds() + offsetSeconds * MS_PER_SECOND, MS_PER_DAY)
    return EPOCH_DATE.plus(DatePeriod(days = days.toInt()))
}

private const val MS_PER_SECOND = 1_000L
private const val MS_PER_DAY = 86_400_000L
private val EPOCH_DATE = LocalDate(1970, 1, 1)

/** Minutes after 12:00 noon (0..1439), so evening and early-morning times order and average correctly. */
internal fun nightMinute(minuteOfDay: Int): Int = Math.floorMod(minuteOfDay - NOON_MINUTE, MINUTES_PER_DAY)

internal const val NOON_MINUTE: Int = 720
internal const val MINUTES_PER_DAY: Int = 1440

/** The zone an event was captured in; a malformed id in an old row falls back to UTC instead of failing the day. */
internal fun zoneOf(event: PersonalEvent): TimeZone = try {
    TimeZone.of(event.zoneId)
} catch (@Suppress("SwallowedException") e: IllegalArgumentException) {
    TimeZone.UTC
}
