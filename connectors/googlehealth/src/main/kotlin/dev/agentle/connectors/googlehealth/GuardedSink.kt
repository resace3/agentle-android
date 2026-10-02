package dev.agentle.connectors.googlehealth

import dev.agentle.connectors.api.CommitResult
import dev.agentle.connectors.api.EventSink
import dev.agentle.connectors.api.StreamCoverage
import dev.agentle.connectors.api.SyncCursor
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.PersonalEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/** A store failure, reduced to the class name of what the store threw (never its message). */
internal class SinkFailure(val className: String) : RuntimeException(className)

/**
 * The connector's view of the [EventSink]: anything the store throws becomes a [SinkFailure], which the connector
 * reports as a retryable database error instead of letting it cross the module boundary. Cancellation passes through.
 */
internal class GuardedSink(private val delegate: EventSink) : EventSink {
    override suspend fun commit(events: List<PersonalEvent>, cursor: SyncCursor?, coverage: StreamCoverage?): CommitResult =
        guard { delegate.commit(events, cursor, coverage) }

    override suspend fun replaceWindow(
        source: DataSourceId,
        windowStart: Instant,
        windowEnd: Instant,
        events: List<PersonalEvent>,
        cursor: SyncCursor?,
        coverage: StreamCoverage?,
        accountId: String?,
    ): CommitResult = guard { delegate.replaceWindow(source, windowStart, windowEnd, events, cursor, coverage, accountId) }

    override suspend fun cursor(connectorId: String, stream: String): SyncCursor? = guard { delegate.cursor(connectorId, stream) }

    override suspend fun importFloor(source: DataSourceId): Instant? = guard { delegate.importFloor(source) }

    private suspend inline fun <T> guard(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SinkFailure) {
        throw e
    } catch (e: Exception) {
        throw SinkFailure(e::class.simpleName ?: "Exception")
    }
}
