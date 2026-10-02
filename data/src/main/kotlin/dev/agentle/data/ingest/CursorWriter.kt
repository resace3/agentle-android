package dev.agentle.data.ingest

import dev.agentle.connectors.api.StreamCoverage
import dev.agentle.connectors.api.SyncCursor
import dev.agentle.core.database.LineageCodec
import dev.agentle.core.database.entity.SourceCoverageEntity
import dev.agentle.core.database.entity.SyncCursorEntity
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.Lineage
import dev.agentle.core.security.BootCountSource
import dev.agentle.core.time.AgentleClock
import dev.agentle.data.Tx
import kotlin.time.Instant

/**
 * Cursor and coverage writes of the ingest transaction (round 2 correction 5). A cursor is stored per
 * (connector, account, stream); a write is a compare-and-set on `fetch_generation` and stores `generation + 1`;
 * `synced_through`, `backfilled_from` and coverage only move in one direction (max, min), except through
 * [dev.agentle.data.sync.SyncStateRepository.clampFutureCursors].
 */
internal class CursorWriter(private val clock: AgentleClock, private val boots: BootCountSource?) {
    /** The compare-and-set precondition: the stored generation (0 when none) equals [cursor]'s. */
    suspend fun matches(tx: Tx, cursor: SyncCursor): Boolean {
        val stored = tx.db.syncDao().cursor(cursor.connectorId, accountKey(cursor.accountId), cursor.stream)
        return (stored?.fetchGeneration ?: 0L) == cursor.generation
    }

    /**
     * Stores [cursor] with `generation + 1`. [coverage] of the same stream advances `synced_through`; [categories] are
     * added to the cursor's lineage (so category deletions find the stream); [earliestMs] lowers `backfilled_from`.
     */
    suspend fun store(tx: Tx, cursor: SyncCursor, coverage: StreamCoverage?, categories: Set<DataCategory>, earliestMs: Long?, nowMs: Long) {
        val dao = tx.db.syncDao()
        val account = accountKey(cursor.accountId)
        val stored = dao.cursor(cursor.connectorId, account, cursor.stream)
        val coveredThrough = coverage?.takeIf { it.stream == cursor.stream && it.connectorId == cursor.connectorId }
            ?.coverageThrough?.toEpochMilliseconds()
        val syncedThrough = maxOfNullable(stored?.syncedThroughMs, coveredThrough)
        val moved = syncedThrough != stored?.syncedThroughMs
        val lineage = Lineage(categories = categories) + (stored?.categories?.let(LineageCodec::decode) ?: Lineage.NONE)
        val row = SyncCursorEntity(
            connectorId = cursor.connectorId,
            accountId = account,
            stream = cursor.stream,
            lastSuccessCursor = cursor.lastSuccessCursor,
            lastAttemptCursor = cursor.lastAttemptCursor,
            syncStartMs = cursor.syncStartedAt?.toEpochMilliseconds(),
            syncEndMs = cursor.syncFinishedAt?.toEpochMilliseconds(),
            lastErrorCode = cursor.lastErrorCode,
            fetchGeneration = (stored?.fetchGeneration ?: 0L) + 1,
            syncedThroughMs = syncedThrough,
            syncedThroughElapsedMs = if (moved) clock.elapsed().inWholeMilliseconds else stored?.syncedThroughElapsedMs,
            syncedThroughBootCount = if (moved) boots?.bootCount() else stored?.syncedThroughBootCount,
            backfilledFromMs = minOfNullable(stored?.backfilledFromMs, earliestMs),
            nextAllowedAtMs = stored?.nextAllowedAtMs,
            consecutiveFailures = if (cursor.lastErrorCode != null) (stored?.consecutiveFailures ?: 0) + 1 else 0,
            importFloorMs = stored?.importFloorMs,
            categories = LineageCodec.encode(Lineage(categories = lineage.categories)),
            updatedMs = nowMs,
        )
        if (stored == null) dao.insertCursor(row) else dao.updateCursor(row)
    }

    /** Stores [coverage]; `coverage_through` never moves backward (max). */
    suspend fun storeCoverage(tx: Tx, coverage: StreamCoverage, nowMs: Long) {
        val dao = tx.db.syncDao()
        val account = accountKey(coverage.accountId)
        val stored = dao.coverage(coverage.connectorId, coverage.stream, account)
        val through = coverage.coverageThrough.toEpochMilliseconds()
        val row = SourceCoverageEntity(
            connectorId = coverage.connectorId,
            stream = coverage.stream,
            accountId = account,
            coverageThroughMs = maxOf(stored?.coverageThroughMs ?: through, through),
            deviceLastSyncMs = maxOfNullable(stored?.deviceLastSyncMs, coverage.deviceLastSync?.toEpochMilliseconds()),
            updatedMs = nowMs,
        )
        if (stored == null) dao.insertCoverage(row) else dao.updateCoverage(row)
    }

    companion object {
        /** The `account_id` column value: "" for streams that are not account-bound. */
        fun accountKey(accountId: String?): String = accountId.orEmpty()

        fun toCursor(row: SyncCursorEntity): SyncCursor = SyncCursor(
            connectorId = row.connectorId,
            stream = row.stream,
            lastSuccessCursor = row.lastSuccessCursor,
            lastAttemptCursor = row.lastAttemptCursor,
            syncStartedAt = row.syncStartMs?.let { Instant.fromEpochMilliseconds(it) },
            syncFinishedAt = row.syncEndMs?.let { Instant.fromEpochMilliseconds(it) },
            lastErrorCode = row.lastErrorCode,
            accountId = row.accountId.ifEmpty { null },
            generation = row.fetchGeneration,
        )
    }
}
