package dev.agentle.connectors.api

import dev.agentle.core.common.AppError
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.PersonalEvent
import kotlin.time.Instant

/**
 * The single serialized writer every on-device collector writes through (red team database-sync-11/14). ANDROID-DATA
 * provides the implementation (until then `:connectors:android` adapts [EventSink]); this port fixes its contract:
 * - calls are serialized process-wide, and each [write] is one transaction of at most [WriteBatch.MAX_ROWS] rows;
 * - events are deduplicated by `dedupKey` as in [EventSink.commit] (insert new, update only when the payload changed,
 *   otherwise ignore); rows named in [WriteBatch.deleteDedupKeys] are deleted (a Health Connect `DeletionChange`);
 *   with [WriteBatch.window] the batch has [EventSink.replaceWindow] diff semantics instead (afterwards the stored
 *   events of that source starting in the window are exactly [WriteBatch.events]; calendar instances that moved or
 *   were deleted disappear); [WriteBatch.cursor] (compare-and-set on [SyncCursor.generation]) and
 *   [WriteBatch.coverage] are stored in the same transaction;
 * - the transaction aborts with [WriteResult.StaleEpoch] when the data epoch is no longer [WriteBatch.epoch], so
 *   nothing collected before a deletion is written after it;
 * - a database that cannot be opened (for example a transient Keystore error) yields [WriteResult.Unavailable]: the
 *   caller skips the batch, records a coverage gap and never retries in a tight loop;
 * - rows older than the source's import floor are dropped silently and count as ignored.
 */
public interface CollectorEventWriter {
    /** The current data epoch. Read it before collecting a batch and pass it in [WriteBatch.epoch]. */
    public suspend fun currentEpoch(): Long

    public suspend fun write(batch: WriteBatch): WriteResult

    /** The stored cursor of [connectorId]'s [stream], or null if none. */
    public suspend fun cursor(connectorId: String, stream: String): SyncCursor?

    /** As [EventSink.importFloor]: re-reads after a lost cursor start no earlier than this. Null means no floor. */
    public suspend fun importFloor(source: DataSourceId): Instant?
}

/** One transaction for [CollectorEventWriter.write]. */
public data class WriteBatch(
    val epoch: Long,
    val events: List<PersonalEvent> = emptyList(),
    /** Rows to delete by dedup key, e.g. a Health Connect `DeletionChange` (`hc|<metadata.id>`). */
    val deleteDedupKeys: Set<String> = emptySet(),
    /** When set, [events] replace the stored events of this window (diff semantics, see [CollectorEventWriter]). */
    val window: ReplaceWindow? = null,
    /** The cursor covering this data, with the generation that was read before fetching. */
    val cursor: SyncCursor? = null,
    val coverage: StreamCoverage? = null,
) {
    init {
        require(rows <= MAX_ROWS) { "A batch holds at most $MAX_ROWS rows, got $rows" }
        if (window != null) {
            require(deleteDedupKeys.isEmpty()) { "A window replacement cannot also delete by key" }
            require(events.all { it.source == window.source && it.startTime >= window.start && it.startTime < window.end }) {
                "Every event of a window replacement must belong to its source and window"
            }
        }
    }

    val rows: Int get() = events.size + deleteDedupKeys.size

    public companion object {
        /** Upper bound of rows (events plus deletions) per transaction. */
        public const val MAX_ROWS: Int = 500
    }
}

/** The half-open window `[start, end)` of [source] that a [WriteBatch] replaces. */
public data class ReplaceWindow(val source: DataSourceId, val start: Instant, val end: Instant) {
    init {
        require(end > start) { "A replace window must not be empty" }
    }
}

public sealed interface WriteResult {
    /** The batch was committed. [deleted] counts rows removed through [WriteBatch.deleteDedupKeys]. */
    public data class Committed(val commit: CommitResult, val deleted: Int = 0) : WriteResult

    /** The cursor's generation changed since it was read (another run moved it): nothing was written; re-read. */
    public data object Rejected : WriteResult

    /** The data epoch changed since the batch's epoch was read (a deletion ran): nothing was written. */
    public data object StaleEpoch : WriteResult

    /** The database is unavailable right now: nothing was written. */
    public data class Unavailable(val error: AppError) : WriteResult
}
