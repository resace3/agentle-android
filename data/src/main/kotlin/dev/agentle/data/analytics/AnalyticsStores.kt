package dev.agentle.data.analytics

import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.daily.DailyFeatureStore
import dev.agentle.analytics.features.daily.DailyRowStatus
import dev.agentle.analytics.features.daily.DailySummaryRow
import dev.agentle.analytics.features.daily.DerivedFeatureRow
import dev.agentle.analytics.features.daily.DerivedStatus
import dev.agentle.analytics.features.daily.DirtyMark
import dev.agentle.core.database.EngineStateKeys
import dev.agentle.core.database.LineageCodec
import dev.agentle.core.database.TermKind
import dev.agentle.core.database.entity.EngineDaySummaryEntity
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventType
import dev.agentle.data.DataAccess
import dev.agentle.data.Tx
import dev.agentle.data.ingest.ChangeKind
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * The analytics [DailyFeatureStore] over `engine_day_summary`, `derived_feature` and `dirty_day`. [replaceDays] is
 * one transaction; dirty marks take their version from the single change counter, so [clearDirty] is a
 * compare-and-clear: a date marked again after it was read stays dirty.
 */
internal class RoomDailyFeatureStore(private val access: DataAccess) : DailyFeatureStore {
    override suspend fun replaceDays(dates: Set<LocalDate>, rows: List<DailySummaryRow>) {
        require(rows.all { it.date in dates }) { "rows outside the replaced dates" }
        access.write {
            val dao = db.analyticsDao()
            dao.deleteSummaryDays(dates.map { it.toString() })
            dao.insertSummaries(rows.map(::entityOf))
        }
    }

    override suspend fun dailyRows(from: LocalDate, to: LocalDate): List<DailySummaryRow> =
        access.read { db.analyticsDao().summaries(from.toString(), to.toString()).mapNotNull(::rowOf) }

    override suspend fun upsertDerived(rows: List<DerivedFeatureRow>) {
        access.write {
            val dao = db.analyticsDao()
            rows.forEach { row ->
                dao.putFeature(
                    featureId = row.featureId,
                    windowDays = row.windowDays,
                    anchorDate = row.anchorDate.toString(),
                    value = row.value,
                    valueText = null,
                    status = row.status.name,
                    coveredDays = row.coveredDays,
                    catalogVersion = row.catalogVersion,
                    computedMs = row.computedAt.toEpochMilliseconds(),
                    lineage = LineageCodec.encode(row.lineage),
                )
            }
        }
    }

    override suspend fun derivedRows(from: LocalDate, to: LocalDate, featureId: String?): List<DerivedFeatureRow> = access.read {
        val dao = db.analyticsDao()
        val rows = if (featureId ==
            null
        ) {
            dao.features(from.toString(), to.toString())
        } else {
            dao.featuresOf(featureId, from.toString(), to.toString())
        }
        rows.mapNotNull { e ->
            val status = DerivedStatus.entries.firstOrNull { it.name == e.status } ?: return@mapNotNull null
            if ((status == DerivedStatus.UNKNOWN) != (e.value == null)) return@mapNotNull null
            DerivedFeatureRow(
                featureId = e.featureId,
                windowDays = e.windowDays,
                anchorDate = LocalDate.parse(e.anchorDate),
                value = e.value,
                status = status,
                coveredDays = e.coveredDays,
                lineage = LineageCodec.decode(e.lineage),
                computedAt = Instant.fromEpochMilliseconds(e.computedMs),
                catalogVersion = e.catalogVersion,
            )
        }
    }

    override suspend fun markDirty(dates: Set<LocalDate>) {
        if (dates.isEmpty()) return
        access.write {
            val version = nextChangeSeq(this)
            dates.forEach { db.stateDao().markDirty(it.toString(), version) }
        }
    }

    override suspend fun dirtyMarks(): List<DirtyMark> =
        access.read { db.stateDao().dirtyDays().map { DirtyMark(LocalDate.parse(it.engineDay), it.generation) } }

    override suspend fun clearDirty(marks: Collection<DirtyMark>) {
        access.write { marks.forEach { db.stateDao().clearDirtyIf(it.date.toString(), it.version) } }
    }

    override suspend fun provisionalDates(): Set<LocalDate> =
        access.read { db.analyticsDao().summaryDaysWithStatus(DailyRowStatus.PROVISIONAL.name).mapTo(HashSet(), LocalDate::parse) }

    companion object {
        suspend fun nextChangeSeq(tx: Tx): Long {
            val state = tx.db.stateDao()
            val next = (state.state(EngineStateKeys.CHANGE_SEQ)?.intValue ?: 0L) + 1
            state.putState(EngineStateKeys.CHANGE_SEQ, next, null)
            return next
        }

        fun entityOf(row: DailySummaryRow): EngineDaySummaryEntity = EngineDaySummaryEntity(
            engineDay = row.date.toString(),
            metric = row.metric,
            featureId = row.featureId,
            value = row.value,
            coverage = row.coverage,
            status = row.status.name,
            missingReason = row.missingReason?.name,
            source = row.source?.value,
            catalogVersion = row.catalogVersion,
            computedMs = row.computedAt.toEpochMilliseconds(),
            lineage = LineageCodec.encode(row.lineage),
        )

        /** A row an older version wrote with values the current invariants reject is skipped (it is recomputed). */
        fun rowOf(e: EngineDaySummaryEntity): DailySummaryRow? = try {
            DailySummaryRow(
                date = LocalDate.parse(e.engineDay),
                metric = e.metric,
                featureId = e.featureId,
                value = e.value,
                coverage = e.coverage ?: 0.0,
                status = DailyRowStatus.valueOf(e.status),
                missingReason = e.missingReason?.let { name -> MissingReason.entries.firstOrNull { it.name == name } },
                source = e.source?.let(::DataSourceId),
                lineage = LineageCodec.decode(e.lineage),
                computedAt = Instant.fromEpochMilliseconds(e.computedMs),
                catalogVersion = e.catalogVersion,
            )
        } catch (expected: IllegalArgumentException) {
            null
        }
    }
}

/** What happened to an event, in change order. */
enum class EventTransition { INSERTED, UPDATED, PROCESSED, TOMBSTONED }

/** One entry of the change feed: the event's row id, type, time range and the change sequence that recorded it. */
data class EventChange(
    val changeSeq: Long,
    val eventSeq: Long,
    val transition: EventTransition,
    val type: EventType?,
    val startMs: Long,
    val endMs: Long?,
    val zoneId: String?,
    val subject: String?,
)

/**
 * Every insert, update, processing and deletion of events after a change sequence, in order (round 2 correction 3):
 * the JITAI dispatcher and feature refresh read it to find dirty days. Tombstones keep deletions visible.
 */
interface EventChangeFeed {
    /** At most [limit] changes with `changeSeq > after`, ascending. */
    suspend fun changesAfter(after: Long, limit: Int): List<EventChange>
}

internal class RoomEventChangeFeed(private val access: DataAccess) : EventChangeFeed {
    override suspend fun changesAfter(after: Long, limit: Int): List<EventChange> = access.read {
        val types = db.termDao().all().filter { it.kind == TermKind.TYPE }.associate { it.id to it.value }
        fun typeOf(id: Long): EventType? = types[id]?.let { name -> EventType.entries.firstOrNull { it.name == name } }
        val rows = db.eventDao().changesAfter(after, limit).map { row ->
            val transition = when (row.changeKind) {
                ChangeKind.UPDATED -> EventTransition.UPDATED
                ChangeKind.PROCESSED -> EventTransition.PROCESSED
                else -> EventTransition.INSERTED
            }
            EventChange(row.changeSeq, row.seq, transition, typeOf(row.type), row.startMs, row.endMs, row.zoneId, row.subject)
        }
        val tombstones = db.stateDao().tombstonesAfter(after, limit).map { t ->
            EventChange(t.changeSeq, t.eventSeq, EventTransition.TOMBSTONED, typeOf(t.type), t.startMs, t.endMs, null, null)
        }
        (rows + tombstones).sortedBy { it.changeSeq }.take(limit)
    }
}
