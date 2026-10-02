package dev.agentle.connectors.api

import dev.agentle.core.common.AppError
import dev.agentle.core.model.ConnectorMetadata
import dev.agentle.core.model.EventType
import dev.agentle.core.model.PersonalEvent
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/** Why a sync runs; connectors may use it to pick window sizes (e.g. a weekly deep re-sync). */
public enum class SyncTrigger { SCHEDULED, MANUAL, APP_START, EVENT, BACKFILL, DEEP_RESYNC }

/** Outcome of one sync run. [committed] counts rows actually written (new or changed). */
public data class SyncResult(
    val connectorId: String,
    val status: Status,
    val startedAt: Instant,
    val finishedAt: Instant,
    val fetched: Int = 0,
    val committed: Int = 0,
    val skippedInvalid: Int = 0,
    val error: AppError? = null,
) {
    public enum class Status { SUCCESS, PARTIAL, FAILED, SKIPPED_DISABLED, SKIPPED_NOT_CONNECTED, SKIPPED_NO_PERMISSION }
}

/**
 * A data connector (docs/ARCHITECTURE.md §6.1). Implementations must be idempotent: running [sync] twice, with
 * overlapping windows or after a partial failure, converges to the same stored state. Data and the cursor that
 * covers it are committed atomically through [EventSink.commit].
 */
public interface Connector {
    public val id: String
    public val name: String
    public val supportedEventTypes: Set<EventType>

    /** Capability ids (from the registry) this connector needs. */
    public val capabilityIds: List<String>

    public val metadata: StateFlow<ConnectorMetadata>

    public suspend fun sync(trigger: SyncTrigger): SyncResult

    /**
     * Stream ids [syncStream] accepts (for example `steps`, `sleep`); empty if the connector cannot sync one stream
     * on its own.
     */
    public val streamIds: Set<String> get() = emptySet()

    /**
     * Syncs one stream now, outside the regular schedule: a staleness retry, or a prefetch shortly before a rule
     * reads that stream. Same guarantees as [sync] (idempotent, data and cursor committed together). The default
     * runs a full [sync].
     */
    public suspend fun syncStream(stream: String, trigger: SyncTrigger = SyncTrigger.MANUAL): SyncResult = sync(trigger)

    /** Disabling stops all collection immediately; it does not delete data. */
    public suspend fun setEnabled(enabled: Boolean)
}

/** Position of a connector stream; opaque to everyone but the connector. */
public data class SyncCursor(
    val connectorId: String,
    val stream: String,
    val lastSuccessCursor: String? = null,
    val lastAttemptCursor: String? = null,
    val syncStartedAt: Instant? = null,
    val syncFinishedAt: Instant? = null,
    val lastErrorCode: String? = null,
)

/**
 * A connector's assertion that it has delivered everything [connectorId]'s [stream] recorded before
 * [coverageThrough] (docs/research/10 §5.3). Stored in the same transaction as the data it covers, so a reader
 * can tell "no data" (covered, nothing stored) from "not synced yet" (not covered).
 */
public data class StreamCoverage(val connectorId: String, val stream: String, val coverageThrough: Instant)

/** Result of committing a batch: how many rows were inserted, updated (payload changed) or ignored (duplicates). */
public data class CommitResult(val inserted: Int, val updated: Int, val ignored: Int) {
    val written: Int get() = inserted + updated

    public companion object {
        public val EMPTY: CommitResult = CommitResult(0, 0, 0)
    }
}

/**
 * Where connectors write. Implemented by the event repository (Room) and by in-memory fakes in tests.
 *
 * [commit] stores [events] (dedup by `dedupKey`: insert new, update only if the payload changed, ignore otherwise)
 * and, in the same transaction, replaces the cursor with [cursor] and the stream's coverage with `coverage` if given.
 * Nothing is committed if it throws.
 * [replaceWindow] is for upstream types without stable ids: it deletes this source's events whose start lies in
 * `[windowStart, windowEnd)` and inserts [events], atomically with the cursor and the coverage.
 * A sink may drop records it must not keep (for example older than a deletion watermark); they count as ignored,
 * which is never an error for the connector.
 */
public interface EventSink {
    public suspend fun commit(events: List<PersonalEvent>, cursor: SyncCursor? = null, coverage: StreamCoverage? = null): CommitResult

    public suspend fun replaceWindow(
        source: dev.agentle.core.model.DataSourceId,
        windowStart: Instant,
        windowEnd: Instant,
        events: List<PersonalEvent>,
        cursor: SyncCursor? = null,
        coverage: StreamCoverage? = null,
    ): CommitResult

    public suspend fun cursor(connectorId: String, stream: String): SyncCursor?
}
