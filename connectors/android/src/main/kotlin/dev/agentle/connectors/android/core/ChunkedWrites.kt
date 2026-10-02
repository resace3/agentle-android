package dev.agentle.connectors.android.core

import dev.agentle.connectors.api.CoverageEndCause
import dev.agentle.connectors.api.ReplaceWindow
import dev.agentle.connectors.api.SyncCursor
import dev.agentle.connectors.api.WriteBatch
import dev.agentle.connectors.api.WriteResult
import dev.agentle.core.common.AppError
import dev.agentle.core.model.PersonalEvent

/** Result of a run's writes: rows written and the error that stopped them, if any. */
public data class RunWrites(val written: Int, val deleted: Int = 0, val error: AppError? = null, val cursorRejected: Boolean = false) {
    val ok: Boolean get() = error == null && !cursorRejected
}

/**
 * Writes a sweep through the serialized writer in transactions of at most [WriteBatch.MAX_ROWS] rows (red team
 * database-sync-11/14). Only the last transaction carries [cursor], so the position moves only after every row is
 * stored; a crash in between re-reads the window and dedup keys converge. A stale data epoch stops the run without
 * writing (nothing collected before a deletion is written after it); an unavailable database stops it and closes the
 * coverage of [coverageIds] (capability ids, see [CoverageIds]); nothing retries in a loop.
 */
public suspend fun CollectorRuntime.writeChunked(
    coverageIds: List<String>,
    epoch: Long,
    events: List<PersonalEvent>,
    cursor: SyncCursor?,
    deleteKeys: Set<String> = emptySet(),
): RunWrites {
    val chunks = mutableListOf<WriteBatch>()
    val keys = deleteKeys.toList().chunked(WriteBatch.MAX_ROWS)
    keys.forEach { chunks += WriteBatch(epoch = epoch, deleteDedupKeys = it.toSet()) }
    events.chunked(WriteBatch.MAX_ROWS).forEach { chunks += WriteBatch(epoch = epoch, events = it) }
    if (chunks.isEmpty()) chunks += WriteBatch(epoch = epoch)
    val last = chunks.removeAt(chunks.lastIndex)
    chunks += last.copy(cursor = cursor)
    return writeAll(coverageIds, chunks)
}

/** Writes window replacements (each one transaction, see [ReplaceWindow]); only the last carries [cursor]. */
public suspend fun CollectorRuntime.writeWindows(
    coverageIds: List<String>,
    epoch: Long,
    windows: List<Pair<ReplaceWindow, List<PersonalEvent>>>,
    cursor: SyncCursor?,
): RunWrites {
    if (windows.isEmpty()) return writeAll(coverageIds, listOf(WriteBatch(epoch = epoch, cursor = cursor)))
    val batches = windows.mapIndexed { index, (window, events) ->
        WriteBatch(epoch = epoch, events = events, window = window, cursor = if (index == windows.lastIndex) cursor else null)
    }
    return writeAll(coverageIds, batches)
}

private suspend fun CollectorRuntime.writeAll(coverageIds: List<String>, batches: List<WriteBatch>): RunWrites {
    val name = coverageIds.firstOrNull() ?: "none"
    var written = 0
    var deleted = 0
    for (batch in batches) {
        when (val result = writer.writeSafely(batch)) {
            is WriteResult.Committed -> {
                written += result.commit.written
                deleted += result.deleted
            }

            WriteResult.Rejected -> return RunWrites(written, deleted, cursorRejected = true)

            WriteResult.StaleEpoch -> {
                logger.i("collectors.write", "Run dropped after a deletion", fields = mapOf("coverage" to name))
                return RunWrites(written, deleted, error = AppError.Cancelled("data_epoch_changed"))
            }

            is WriteResult.Unavailable -> {
                coverage.close(coverageIds, clock.now(), CoverageEndCause.DATABASE_UNAVAILABLE)
                logger.w("collectors.write", "Database unavailable; run skipped", error = result.error, fields = mapOf("coverage" to name))
                return RunWrites(written, deleted, error = result.error)
            }
        }
    }
    return RunWrites(written, deleted)
}

/** Adds up the writes of a run made of several independent writes (state streams, samples). */
public class RunTally {
    public var fetched: Int = 0
    public var committed: Int = 0
    public var error: AppError? = null
        private set

    /** Counts [result]; null (nothing changed) counts nothing. */
    public fun add(result: WriteResult?) {
        when (result) {
            is WriteResult.Committed -> committed += result.commit.written
            is WriteResult.Unavailable -> error = error ?: result.error
            WriteResult.StaleEpoch -> error = error ?: AppError.Cancelled("data_epoch_changed")
            WriteResult.Rejected, null -> Unit
        }
    }

    public fun add(writes: RunWrites) {
        committed += writes.written
        if (writes.error != null) error = error ?: writes.error
    }

    /** Records an unreadable part of the run (an error code, never a message). */
    public fun fail(code: String) {
        error = error ?: AppError.Unexpected(code)
    }

    public fun outcome(partial: Boolean = false): CollectOutcome =
        CollectOutcome(fetched = fetched, committed = committed, partial = partial, error = error)
}
