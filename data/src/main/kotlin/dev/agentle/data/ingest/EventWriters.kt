package dev.agentle.data.ingest

import dev.agentle.connectors.api.CommitResult
import dev.agentle.connectors.api.EventSink
import dev.agentle.connectors.api.StreamCoverage
import dev.agentle.connectors.api.SyncCursor
import dev.agentle.core.common.AppError
import dev.agentle.core.common.AppException
import dev.agentle.core.database.EngineStateKeys
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.time.AgentleClock
import dev.agentle.data.DataAccess
import dev.agentle.data.Tx
import kotlin.time.Instant

/**
 * The single serialized writer of the on-device collectors (red team database-sync-11/14). It mirrors
 * `CollectorEventWriter` of `:connectors:api` (ANDROID-COLLECTORS), so the app binds that port with a one-line adapter:
 * each [write] is one transaction of at most [EventBatch.MAX_ROWS] rows; dedup as [EventSink.commit]; rows named in
 * [EventBatch.deleteDedupKeys] are deleted; with [EventBatch.window] the batch has [EventSink.replaceWindow] diff
 * semantics; the cursor (compare-and-set) and coverage are stored in the same transaction; a changed data epoch aborts
 * with [EventWriteResult.StaleEpoch]; an unavailable database yields [EventWriteResult.Unavailable].
 */
interface EventBatchWriter {
    /** The current data epoch: read it before collecting a batch and pass it in [EventBatch.epoch]. */
    suspend fun currentEpoch(): Long

    suspend fun write(batch: EventBatch): EventWriteResult

    suspend fun cursor(connectorId: String, stream: String): SyncCursor?

    suspend fun importFloor(source: DataSourceId): Instant?
}

/** One transaction for [EventBatchWriter.write]. */
data class EventBatch(
    val epoch: Long,
    val events: List<PersonalEvent> = emptyList(),
    /** Rows to delete by dedup key (a Health Connect `DeletionChange`: `hc|<metadata.id>`). */
    val deleteDedupKeys: Set<String> = emptySet(),
    val window: EventWindow? = null,
    val cursor: SyncCursor? = null,
    val coverage: StreamCoverage? = null,
) {
    init {
        require(events.size + deleteDedupKeys.size <= MAX_ROWS) { "A batch holds at most $MAX_ROWS rows" }
        require(window == null || deleteDedupKeys.isEmpty()) { "A window replacement cannot also delete by key" }
    }

    companion object {
        const val MAX_ROWS: Int = 500
    }
}

/** The half-open window `[start, end)` of [source] a batch replaces. */
data class EventWindow(val source: DataSourceId, val start: Instant, val end: Instant) {
    init {
        require(end > start) { "A replace window must not be empty" }
    }
}

sealed interface EventWriteResult {
    data class Committed(val commit: CommitResult, val deleted: Int = 0) : EventWriteResult

    /** The cursor's generation changed since it was read: nothing was written. */
    data object Rejected : EventWriteResult

    /** A deletion changed the data epoch since the batch's epoch was read: nothing was written. */
    data object StaleEpoch : EventWriteResult

    data class Unavailable(val error: AppError) : EventWriteResult
}

/**
 * [EventSink] and [EventBatchWriter] on Room (docs/ARCHITECTURE.md §5.3; rounds 1-3).
 *
 * [commit] runs in transactions of at most [CHUNK_ROWS] events (round 2 correction 8); every chunk re-checks the
 * cursor's generation and the data epoch, and the cursor and coverage are stored with the last chunk. A commit that is
 * stopped after some chunks has stored only rows that a re-run converges on (dedup), never a moved cursor.
 * [replaceWindow] runs in one transaction, because its diff must be atomic (a deviation from the 500-row bound for
 * windows larger than that).
 */
internal class RoomEventWriter(
    private val access: DataAccess,
    private val clock: AgentleClock,
    private val floorReader: ImportFloorReader,
    private val cursors: CursorWriter,
) : EventSink,
    EventBatchWriter {
    override suspend fun commit(events: List<PersonalEvent>, cursor: SyncCursor?, coverage: StreamCoverage?): CommitResult {
        val accountId = cursor?.accountId ?: coverage?.accountId
        val retention = floorReader.retentionCutoffs()
        val chunks = events.chunked(CHUNK_ROWS).ifEmpty { listOf(emptyList()) }
        var epoch: Long? = null
        var total = IngestCounts()
        for ((index, chunk) in chunks.withIndex()) {
            val last = index == chunks.lastIndex
            val counts = access.write {
                val current = dataEpoch(this)
                if (epoch != null && epoch != current) return@write null
                epoch = current
                if (cursor != null && !cursors.matches(this, cursor)) return@write null
                val session = IngestSession(this, access.terms, floorReader.snapshot(this, retention), nowMs())
                val account = session.accountTerm(accountId)
                session.upsert(chunk, account)
                if (last) storeSyncState(this, cursor, coverage, events)
                session.finish()
                session.counts
            } ?: return total.toCommitResult(rejected = true)
            total += counts
        }
        return total.toCommitResult()
    }

    override suspend fun replaceWindow(
        source: DataSourceId,
        windowStart: Instant,
        windowEnd: Instant,
        events: List<PersonalEvent>,
        cursor: SyncCursor?,
        coverage: StreamCoverage?,
        accountId: String?,
    ): CommitResult {
        val retention = floorReader.retentionCutoffs()
        val account = accountId ?: cursor?.accountId ?: coverage?.accountId
        val counts = access.write {
            if (cursor != null && !cursors.matches(this, cursor)) return@write null
            val session = IngestSession(this, access.terms, floorReader.snapshot(this, retention), nowMs())
            session.replaceWindow(
                source,
                session.accountTerm(account),
                windowStart.toEpochMilliseconds(),
                windowEnd.toEpochMilliseconds(),
                events,
            )
            storeSyncState(this, cursor, coverage, events, windowStart.toEpochMilliseconds())
            session.finish()
            session.counts
        } ?: return CommitResult(0, 0, 0, rejected = true)
        return counts.toCommitResult()
    }

    override suspend fun cursor(connectorId: String, stream: String): SyncCursor? = access.read {
        val rows = db.syncDao().cursorsOfStream(connectorId, stream)
        val active = db.syncDao().activeAccount(connectorId)
        val row = if (active != null) rows.firstOrNull { it.accountId == active } else rows.firstOrNull()
        row?.let { CursorWriter.toCursor(it) }
    }

    override suspend fun importFloor(source: DataSourceId): Instant? {
        val retention = floorReader.retentionCutoffs()
        val floor = access.read {
            val snapshot = floorReader.snapshot(this, retention)
            val cursorFloor = db.syncDao().cursorsOfStream(source.connectorId, source.stream).mapNotNull { it.importFloorMs }.maxOrNull()
            maxOfNullable(snapshot.sourceFloor(source), cursorFloor)
        }
        return floor?.let { Instant.fromEpochMilliseconds(it) }
    }

    // --------------------------------------------------------------------------------------------- batch writer

    override suspend fun currentEpoch(): Long = access.read { dataEpoch(this) }

    override suspend fun write(batch: EventBatch): EventWriteResult = try {
        val retention = floorReader.retentionCutoffs()
        val account = batch.cursor?.accountId ?: batch.coverage?.accountId
        access.write { writeBatch(this, batch, account, retention) }
    } catch (expected: AppException) {
        if (expected.error is AppError.DatabaseError) EventWriteResult.Unavailable(expected.error) else throw expected
    }

    private suspend fun writeBatch(tx: Tx, batch: EventBatch, accountId: String?, retention: Map<RetentionFamily, Long>): EventWriteResult {
        if (dataEpoch(tx) != batch.epoch) return EventWriteResult.StaleEpoch
        if (batch.cursor != null && !cursors.matches(tx, batch.cursor)) return EventWriteResult.Rejected
        val session = IngestSession(tx, access.terms, floorReader.snapshot(tx, retention), nowMs())
        val account = session.accountTerm(accountId)
        val window = batch.window
        var deleted = 0
        if (window != null) {
            session.replaceWindow(
                window.source,
                account,
                window.start.toEpochMilliseconds(),
                window.end.toEpochMilliseconds(),
                batch.events,
            )
        } else {
            deleted = session.deleteKeys(batch.deleteDedupKeys, account)
            session.upsert(batch.events, account)
        }
        storeSyncState(tx, batch.cursor, batch.coverage, batch.events, window?.start?.toEpochMilliseconds())
        session.finish()
        val counts = session.counts
        return EventWriteResult.Committed(counts.toCommitResult(), deleted = if (window != null) counts.deleted else deleted)
    }

    private suspend fun storeSyncState(
        tx: Tx,
        cursor: SyncCursor?,
        coverage: StreamCoverage?,
        events: List<PersonalEvent>,
        windowStartMs: Long? = null,
    ) {
        val now = nowMs()
        if (cursor != null) {
            val earliest = minOfNullable(events.minOfOrNull { it.startTime.toEpochMilliseconds() }, windowStartMs)
            cursors.store(tx, cursor, coverage, events.mapTo(HashSet()) { it.type.category }, earliest, now)
        }
        if (coverage != null) cursors.storeCoverage(tx, coverage, now)
    }

    private suspend fun dataEpoch(tx: Tx): Long = tx.db.stateDao().state(EngineStateKeys.DATA_EPOCH)?.intValue ?: 0L

    private fun nowMs(): Long = clock.now().toEpochMilliseconds()

    private fun IngestCounts.toCommitResult(rejected: Boolean = false): CommitResult = CommitResult(inserted, updated, ignored, rejected)

    companion object {
        const val CHUNK_ROWS: Int = 500
    }
}
