package dev.agentle.data.events

import dev.agentle.core.database.EngineStateKeys
import dev.agentle.core.database.TermKind
import dev.agentle.core.database.dao.SampleRow
import dev.agentle.core.database.entity.EventEntity
import dev.agentle.core.database.entity.MetricSourcePolicyEntity
import dev.agentle.core.model.DailyTotalMetric
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventCodec
import dev.agentle.core.model.EventId
import dev.agentle.core.model.EventMetadata
import dev.agentle.core.model.EventPayload
import dev.agentle.core.model.EventType
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.Provenance
import dev.agentle.core.model.Sensitivity
import dev.agentle.core.model.UnknownPayload
import dev.agentle.data.DataAccess
import dev.agentle.data.Tx
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/** A stored event with its storage identity: [seq] orders the timeline, [changeSeq] the change feed. */
data class StoredEvent(val seq: Long, val changeSeq: Long, val accountId: String?, val event: PersonalEvent)

/** Position of the last row of a timeline page (keyset paging over (`start_ms`, `seq`)). */
data class TimelineKey(val startMs: Long, val seq: Long)

/** One page of the timeline, newest first; [next] continues it, null at the end. */
data class TimelinePage(val events: List<StoredEvent>, val next: TimelineKey?)

/** Row count and time span of one source or type. */
data class StreamCount(val name: String, val rows: Long, val first: Instant?, val last: Instant?)

/** How a fused minute series combines the samples of the chosen source within one minute. */
enum class MinuteAggregation {
    /** Counts and amounts (steps, distance, calories): interval values are spread uniformly over their minutes. */
    SUM,

    /** Rates (heart rate): the overlap-weighted mean. */
    MEAN,
}

/** One minute of a fused series and the source it came from. */
data class MinuteValue(val minuteStart: Instant, val value: Double, val source: String)

/** A per-civil-day total as the source computed it (`upstream_daily`). */
data class DailyTotal(val source: String, val metric: DailyTotalMetric, val date: LocalDate, val accountId: String?, val value: Double?)

/**
 * Reads of the event table (docs/ARCHITECTURE.md §5.2): the paged timeline, range queries, counts per source and
 * type, query-time fusion (round 2 correction 2) and the source-computed daily totals. Features and AI read through
 * [range], [overlapping], [latest], [fusedMinutes] and [dailyTotals], which only see data that is not account-bound
 * and data of each connector's active account (round 2 correction 5); the timeline shows everything that is stored.
 */
interface EventRepository {
    /** The newest events at or before [upperBound] ([after] continues a previous page); only [types] when given. */
    suspend fun timeline(upperBound: Instant, limit: Int, after: TimelineKey? = null, types: Set<EventType>? = null): TimelinePage

    /** Events of [type] starting in `[from, to)`, oldest first. */
    suspend fun range(type: EventType, from: Instant, to: Instant): List<StoredEvent>

    /** Events of [type] overlapping `[from, to)` (intervals by their end, points by their start), oldest first. */
    suspend fun overlapping(type: EventType, from: Instant, to: Instant): List<StoredEvent>

    suspend fun latest(type: EventType): StoredEvent?

    suspend fun count(): Long

    suspend fun countsPerSource(): List<StreamCount>

    suspend fun countsPerType(): List<StreamCount>

    /**
     * Per-minute values of [type] in `[from, to)`: for each minute the highest-priority source of [metric]'s
     * `metric_source_policy` that has data in that minute; sources are never summed (round 2 correction 2). Sources
     * without a policy rank last, in name order.
     */
    suspend fun fusedMinutes(type: EventType, metric: String, from: Instant, to: Instant, aggregation: MinuteAggregation): List<MinuteValue>

    /** Source-computed daily totals of [metric] for the active accounts, `from..to` inclusive. */
    suspend fun dailyTotals(metric: DailyTotalMetric, from: LocalDate, to: LocalDate): List<DailyTotal>

    /** The change counter; emits on every ingest, update and deletion (for refreshing screens). */
    fun changes(): Flow<Long>
}

internal class RoomEventRepository(private val access: DataAccess) : EventRepository {
    override suspend fun timeline(upperBound: Instant, limit: Int, after: TimelineKey?, types: Set<EventType>?): TimelinePage =
        access.read {
            val dao = db.eventDao()
            val rows = if (types == null) {
                if (after ==
                    null
                ) {
                    dao.firstPage(upperBound.toEpochMilliseconds(), limit)
                } else {
                    dao.pageAfter(after.startMs, after.seq, limit)
                }
            } else {
                val ids = types.mapNotNull { access.terms.idOf(db.termDao(), TermKind.TYPE, it.name) }
                when {
                    ids.isEmpty() -> emptyList()
                    after == null -> dao.firstPageOfTypes(ids, upperBound.toEpochMilliseconds(), limit)
                    else -> dao.pageOfTypesAfter(ids, after.startMs, after.seq, limit)
                }
            }
            val next = rows.lastOrNull()?.takeIf { rows.size == limit }?.let { TimelineKey(it.startMs, it.seq) }
            TimelinePage(decodeAll(rows), next)
        }

    override suspend fun range(type: EventType, from: Instant, to: Instant): List<StoredEvent> = access.read {
        val typeId = typeId(type) ?: return@read emptyList()
        decodeAll(db.eventDao().byTypeStartingInForAccounts(typeId, activeAccounts(), from.toEpochMilliseconds(), to.toEpochMilliseconds()))
    }

    override suspend fun overlapping(type: EventType, from: Instant, to: Instant): List<StoredEvent> = access.read {
        val typeId = typeId(type) ?: return@read emptyList()
        val accounts = activeAccounts().toSet()
        decodeAll(
            db.eventDao().byTypeOverlapping(typeId, from.toEpochMilliseconds(), to.toEpochMilliseconds()).filter {
                it.account in
                    accounts
            },
        )
    }

    override suspend fun latest(type: EventType): StoredEvent? = access.read {
        val typeId = typeId(type) ?: return@read null
        db.eventDao().latestOfTypeForAccounts(typeId, activeAccounts())?.let { decodeAll(listOf(it)).firstOrNull() }
    }

    override suspend fun count(): Long = access.read { db.eventDao().count() }

    override suspend fun countsPerSource(): List<StreamCount> = access.read {
        db.eventDao().countsPerSource().mapNotNull { row ->
            access.terms.term(db.termDao(), row.term)?.let { term ->
                StreamCount(term.value, row.rowCount, row.firstMs?.let { instant(it) }, row.lastMs?.let { instant(it) })
            }
        }.sortedBy { it.name }
    }

    override suspend fun countsPerType(): List<StreamCount> = access.read {
        db.eventDao().countsPerType().mapNotNull { row ->
            access.terms.term(db.termDao(), row.term)?.let { term ->
                StreamCount(term.value, row.rowCount, row.firstMs?.let { instant(it) }, row.lastMs?.let { instant(it) })
            }
        }.sortedBy { it.name }
    }

    override suspend fun fusedMinutes(
        type: EventType,
        metric: String,
        from: Instant,
        to: Instant,
        aggregation: MinuteAggregation,
    ): List<MinuteValue> = access.read {
        val typeId = typeId(type) ?: return@read emptyList()
        val fromMs = from.toEpochMilliseconds()
        val toMs = to.toEpochMilliseconds()
        val samples = db.eventDao().samplesOverlapping(typeId, activeAccounts(), fromMs, toMs)
        val sourceNames = samples.map { it.source }.distinct().associateWith { access.terms.term(db.termDao(), it)?.value ?: "" }
        MinuteFusion(db.analyticsDao().policies(metric), aggregation).fuse(samples, sourceNames, fromMs, toMs)
    }

    override suspend fun dailyTotals(metric: DailyTotalMetric, from: LocalDate, to: LocalDate): List<DailyTotal> = access.read {
        activeAccountIds().flatMap { accountId ->
            db.analyticsDao().upstreamDaily(metric.name, accountId, from.toString(), to.toString()).map { row ->
                DailyTotal(row.source, metric, LocalDate.parse(row.localDate), row.accountId.ifEmpty { null }, row.value)
            }
        }
    }

    override fun changes(): Flow<Long> = flow {
        emitAll(access.database().stateDao().observeInt(EngineStateKeys.CHANGE_SEQ).map { it ?: 0L })
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private suspend fun Tx.typeId(type: EventType): Long? = access.terms.idOf(db.termDao(), TermKind.TYPE, type.name)

    /** Account ids (the stored hashes) whose data features and AI may read: "" plus each connector's active account. */
    private suspend fun Tx.activeAccountIds(): List<String> {
        val states = db.syncDao().connectorStates().mapNotNull { it.activeAccountId }
        val googleHealth = db.syncDao().googleHealthState()?.accountId
        return (listOf("") + states + listOfNotNull(googleHealth)).distinct()
    }

    private suspend fun Tx.activeAccounts(): List<Long> = activeAccountIds().mapNotNull { id ->
        if (id.isEmpty()) TermKind.NO_ACCOUNT else access.terms.idOf(db.termDao(), TermKind.ACCOUNT, id)
    }

    private suspend fun Tx.decodeAll(rows: List<EventEntity>): List<StoredEvent> = rows.mapNotNull { decode(it) }

    private suspend fun Tx.decode(row: EventEntity): StoredEvent? {
        val typeName = access.terms.term(db.termDao(), row.type)?.value ?: return null
        val type = EventType.entries.firstOrNull { it.name == typeName } ?: return null
        val sourceName = access.terms.term(db.termDao(), row.source)?.value ?: return null
        val source = try {
            DataSourceId(sourceName)
        } catch (expected: IllegalArgumentException) {
            return null
        }
        val accountId = if (row.account == TermKind.NO_ACCOUNT) null else access.terms.term(db.termDao(), row.account)?.value
        val event = PersonalEvent(
            id = EventId(row.id),
            type = type,
            source = source,
            startTime = instant(row.startMs),
            endTime = row.endMs?.let { instant(it) },
            zoneId = row.zoneId,
            payload = payloadOf(row.payloadJson),
            confidence = row.confidence,
            dedupKey = row.dedupKey,
            metadata = EventMetadata(
                ingestedAt = instant(row.ingestedMs),
                schemaVersion = row.payloadVersion,
                origin = row.origin,
                sensitivity = Sensitivity.entries.getOrElse(row.sensitivity) { Sensitivity.SENSITIVE },
                provenance = row.provenanceJson?.let { provenanceOf(it) },
                upstreamId = row.upstreamId,
                upstreamUpdatedAt = row.upstreamUpdateMs?.let { instant(it) },
            ),
        )
        return StoredEvent(row.seq, row.changeSeq, accountId, event)
    }

    companion object {
        /** Placeholder for a stored payload this version cannot read; the row stays, nothing is dropped. */
        const val UNREADABLE_KIND: String = "unreadable"

        internal fun instant(ms: Long): Instant = Instant.fromEpochMilliseconds(ms)

        internal fun payloadOf(json: String): EventPayload = try {
            EventCodec.decode(json)
        } catch (expected: IllegalArgumentException) {
            UnknownPayload(UNREADABLE_KIND, "{}")
        }

        private fun provenanceOf(json: String): Provenance? = try {
            EventCodec.json.decodeFromString(Provenance.serializer(), json)
        } catch (expected: IllegalArgumentException) {
            null
        }
    }
}

/** Query-time fusion of one metric (round 2 correction 2): per minute, the best-priority source with data. */
internal class MinuteFusion(private val policies: List<MetricSourcePolicyEntity>, private val aggregation: MinuteAggregation) {
    private class Cell {
        var sum = 0.0
        var weight = 0.0
    }

    fun fuse(samples: List<SampleRow>, sourceNames: Map<Long, String>, fromMs: Long, toMs: Long): List<MinuteValue> {
        val cells = HashMap<Long, HashMap<Long, Cell>>()
        samples.forEach { sample ->
            accumulate(sample, fromMs, toMs) { minute, value, weight -> add(cells, sample.source, minute, value, weight) }
        }
        val minutes = cells.values.flatMapTo(sortedSetOf<Long>()) { it.keys }
        return minutes.mapNotNull { minute ->
            val best = cells.entries
                .filter { (_, byMinute) -> byMinute.containsKey(minute) }
                .minWithOrNull(
                    compareBy<Map.Entry<Long, HashMap<Long, Cell>>> {
                        priority(sourceNames[it.key].orEmpty(), minute)
                    }.thenBy { sourceNames[it.key].orEmpty() },
                )
                ?: return@mapNotNull null
            val cell = best.value.getValue(minute)
            val value = if (aggregation == MinuteAggregation.SUM) cell.sum else cell.sum / cell.weight
            MinuteValue(Instant.fromEpochMilliseconds(minute), value, sourceNames[best.key].orEmpty())
        }
    }

    private fun accumulate(sample: SampleRow, fromMs: Long, toMs: Long, sink: (minute: Long, value: Double, weight: Double) -> Unit) {
        val value = sample.valueNum ?: return
        val start = sample.startMs
        val end = sample.endMs
        if (end == null || end <= start) {
            if (start in fromMs until toMs) sink(floorMinute(start), value, 1.0)
            return
        }
        val duration = (end - start).toDouble()
        var minute = floorMinute(maxOf(start, fromMs))
        while (minute < minOf(end, toMs)) {
            val overlap = (minOf(end, minute + MINUTE_MS) - maxOf(start, minute)).coerceAtLeast(0L).toDouble()
            if (overlap > 0.0) {
                if (aggregation ==
                    MinuteAggregation.SUM
                ) {
                    sink(minute, value * overlap / duration, overlap)
                } else {
                    sink(minute, value * overlap, overlap)
                }
            }
            minute += MINUTE_MS
        }
    }

    private fun add(cells: HashMap<Long, HashMap<Long, Cell>>, source: Long, minute: Long, value: Double, weight: Double) {
        val cell = cells.getOrPut(source) { HashMap() }.getOrPut(minute) { Cell() }
        cell.sum += value
        cell.weight += weight
    }

    private fun priority(source: String, minute: Long): Int = policies
        .filter { policy ->
            val end = policy.validToMs
            policy.source == source && minute >= policy.validFromMs && (end == null || minute < end)
        }
        .minOfOrNull { it.priority } ?: Int.MAX_VALUE

    companion object {
        const val MINUTE_MS: Long = 60_000L

        fun floorMinute(ms: Long): Long = Math.floorDiv(ms, MINUTE_MS) * MINUTE_MS
    }
}
