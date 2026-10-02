package dev.agentle.connectors.android.core

import dev.agentle.connectors.api.CollectorEventWriter
import dev.agentle.connectors.api.EventSink
import dev.agentle.connectors.api.SyncCursor
import dev.agentle.connectors.api.WriteBatch
import dev.agentle.connectors.api.WriteResult
import dev.agentle.core.common.AppError
import dev.agentle.core.model.DataSourceId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * Adapts the shared [EventSink] to the [CollectorEventWriter] contract until ANDROID-DATA's serialized writer is bound.
 * Calls are serialized in process. Limits: [EventSink] has no data epoch (so [currentEpoch] is constant and a deletion
 * cannot abort a batch here) and cannot delete by dedup key (so [WriteBatch.deleteDedupKeys] are not applied and
 * `deleted` is 0); both need the real writer.
 */
internal class EventSinkWriter(private val sink: EventSink) : CollectorEventWriter {
    private val mutex = Mutex()

    override suspend fun currentEpoch(): Long = 0L

    override suspend fun write(batch: WriteBatch): WriteResult = mutex.withLock {
        try {
            val window = batch.window
            val result = if (window != null) {
                sink.replaceWindow(window.source, window.start, window.end, batch.events, batch.cursor, batch.coverage)
            } else {
                sink.commit(batch.events, batch.cursor, batch.coverage)
            }
            if (result.rejected) WriteResult.Rejected else WriteResult.Committed(result, deleted = 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WriteResult.Unavailable(AppError.DatabaseError(e::class.simpleName))
        }
    }

    override suspend fun cursor(connectorId: String, stream: String): SyncCursor? = try {
        sink.cursor(connectorId, stream)
    } catch (e: CancellationException) {
        throw e
    } catch (ignored: Exception) {
        null
    }

    override suspend fun importFloor(source: DataSourceId): Instant? = try {
        sink.importFloor(source)
    } catch (e: CancellationException) {
        throw e
    } catch (ignored: Exception) {
        null
    }
}

/** Used when neither a writer nor an event sink is bound: every write is skipped and recorded as a coverage gap. */
internal object UnavailableWriter : CollectorEventWriter {
    private val error = AppError.DatabaseError("writer_not_bound")

    override suspend fun currentEpoch(): Long = 0L

    override suspend fun write(batch: WriteBatch): WriteResult = WriteResult.Unavailable(error)

    override suspend fun cursor(connectorId: String, stream: String): SyncCursor? = null

    override suspend fun importFloor(source: DataSourceId): Instant? = null
}

/** Never throws: every writer exception becomes [WriteResult.Unavailable] (class name only, never the message). */
internal suspend fun CollectorEventWriter.writeSafely(batch: WriteBatch): WriteResult = try {
    write(batch)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    WriteResult.Unavailable(AppError.DatabaseError(e::class.simpleName))
}

internal suspend fun CollectorEventWriter.cursorSafely(connectorId: String, stream: String): SyncCursor? = try {
    cursor(connectorId, stream)
} catch (e: CancellationException) {
    throw e
} catch (ignored: Exception) {
    null
}

internal suspend fun CollectorEventWriter.importFloorSafely(source: DataSourceId): Instant? = try {
    importFloor(source)
} catch (e: CancellationException) {
    throw e
} catch (ignored: Exception) {
    null
}

internal suspend fun CollectorEventWriter.epochSafely(): Long? = try {
    currentEpoch()
} catch (e: CancellationException) {
    throw e
} catch (ignored: Exception) {
    null
}
