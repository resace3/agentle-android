package dev.agentle.connectors.api

import dev.agentle.core.common.AppError
import dev.agentle.core.model.PersonalEvent
import kotlin.time.Instant

/**
 * The single serialized writer every on-device collector writes through (red team database-sync-11/14). ANDROID-DATA
 * provides the implementation; this port fixes its contract:
 * - calls are serialized process-wide, and each [write] is one transaction of at most [WriteBatch.MAX_ROWS] rows;
 * - events are deduplicated by `dedupKey` (insert new, update only when the payload changed, otherwise ignore), rows
 *   named in [WriteBatch.deleteDedupKeys] are deleted, and [WriteBatch.cursors] are stored, all in that transaction;
 * - the transaction aborts with [WriteResult.StaleEpoch] when the data epoch is no longer [WriteBatch.epoch], so
 *   nothing collected before a deletion is written after it (database-sync, "never write after the DataEpoch changed");
 * - a database that cannot be opened (for example a transient Keystore error) yields [WriteResult.Unavailable]: the
 *   caller skips the batch, records a coverage gap and never retries in a tight loop;
 * - rows older than the stream's import floor are dropped silently and count as ignored.
 */
public interface CollectorEventWriter {
    /** The current data epoch. Read it before collecting a batch and pass it in [WriteBatch.epoch]. */
    public suspend fun currentEpoch(): Long

    public suspend fun write(batch: WriteBatch): WriteResult

    /** The stored cursor of [connectorId]'s [stream], or null if none. */
    public suspend fun cursor(connectorId: String, stream: String): SyncCursor?

    /**
     * The instant before which [connectorId]'s [stream] must not import data (max(now - retention, last deletion)),
     * or null when there is no floor. Re-reads after a lost cursor (an expired Health Connect changes token, a deleted
     * high-water mark) start no earlier than this.
     */
    public suspend fun importFloor(connectorId: String, stream: String): Instant?
}

/** One transaction for [CollectorEventWriter.write]. */
public data class WriteBatch(
    val epoch: Long,
    val events: List<PersonalEvent> = emptyList(),
    /** Rows to delete by dedup key, e.g. a Health Connect `DeletionChange` (`hc|<metadata.id>`). */
    val deleteDedupKeys: Set<String> = emptySet(),
    /** Cursors committed together with the data they cover. */
    val cursors: List<SyncCursor> = emptyList(),
) {
    init {
        require(rows <= MAX_ROWS) { "A batch holds at most $MAX_ROWS rows, got $rows" }
    }

    val rows: Int get() = events.size + deleteDedupKeys.size

    public companion object {
        /** Upper bound of rows (events plus deletions) per transaction. */
        public const val MAX_ROWS: Int = 500
    }
}

public sealed interface WriteResult {
    /** The batch was committed. [deleted] counts rows removed through [WriteBatch.deleteDedupKeys]. */
    public data class Committed(val commit: CommitResult, val deleted: Int = 0) : WriteResult

    /** The data epoch changed since the batch's epoch was read (a deletion ran): nothing was written. */
    public data object StaleEpoch : WriteResult

    /** The database is unavailable right now: nothing was written. */
    public data class Unavailable(val error: AppError) : WriteResult
}
