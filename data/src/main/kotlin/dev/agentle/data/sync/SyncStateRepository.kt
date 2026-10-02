package dev.agentle.data.sync

import dev.agentle.core.database.entity.CollectorCoverageEntity
import dev.agentle.core.database.entity.ConnectorStateEntity
import dev.agentle.core.database.entity.GoogleHealthStateEntity
import dev.agentle.core.database.entity.SourceCoverageEntity
import dev.agentle.core.database.entity.SyncCursorEntity
import dev.agentle.core.time.AgentleClock
import dev.agentle.data.DataAccess
import dev.agentle.data.diagnostics.DiagnosticCode
import dev.agentle.data.diagnostics.DiagnosticWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The stored position of one stream of one account (round 2 correction 5), for the Data Sources screen and workers. */
data class StreamCursorState(
    val connectorId: String,
    val accountId: String?,
    val stream: String,
    val generation: Long,
    val syncedThrough: Instant?,
    val backfilledFrom: Instant?,
    val nextAllowedAt: Instant?,
    val consecutiveFailures: Int,
    val lastErrorCode: String?,
    val importFloor: Instant?,
    val updatedAt: Instant,
)

/** A stream's completeness claim (docs/research/10 §5.3). */
data class StreamCoverageState(
    val connectorId: String,
    val stream: String,
    val accountId: String?,
    val coverageThrough: Instant,
    val deviceLastSync: Instant?,
)

/** What [SyncStateRepository.clampFutureCursors] changed. */
data class ClockClampReport(val cursorsClamped: Int, val coverageClamped: Int)

/** Connection state of one connector; never tokens. */
data class ConnectorStateRecord(
    val connectorId: String,
    val enabled: Boolean = true,
    val connection: String = "NOT_CONNECTED",
    val permissionSummary: String = "UNKNOWN",
    val lastSuccess: Instant? = null,
    val lastAttempt: Instant? = null,
    val lastErrorCode: String? = null,
    val syncState: String = "IDLE",
    /** The upstream account (a hash) whose data features and AI may read; null when not account-bound. */
    val activeAccountId: String? = null,
    /** stream -> PermissionState name. */
    val streamPermissions: Map<String, String> = emptyMap(),
)

/** The Google Health connection (no tokens: they stay in Play services). */
data class GoogleHealthConnection(
    val healthUserId: String? = null,
    /** Pseudonymous account id used in dedup keys, cursors and coverage. */
    val accountId: String? = null,
    val grantedScopes: Set<String> = emptySet(),
    val connectedAt: Instant? = null,
    val disconnectedAt: Instant? = null,
    val lastFullResyncAt: Instant? = null,
)

/**
 * Sync bookkeeping outside the ingest transaction: cursors (failures and backoff, the clock-jump clamp), stream
 * coverage, connector state and the Google Health connection. Cursor positions themselves are written only by the
 * event sink, in the transaction of the data they cover.
 */
interface SyncStateRepository {
    suspend fun cursors(): List<StreamCursorState>

    /**
     * Records a failed run of a stream: `consecutive_failures + 1`, the error code and the earliest next attempt. The
     * position and its generation are unchanged.
     */
    suspend fun recordFailure(connectorId: String, accountId: String?, stream: String, errorCode: String, nextAllowedAt: Instant?)

    /**
     * After a wall-clock jump (round 2 correction 5): every `synced_through` and `coverage_through` later than now + 5 min
     * is clamped to now - [overlap], cursor generations are bumped (in-flight runs fail their compare-and-set) and each
     * clamp is recorded as a gap in the diagnostics.
     */
    suspend fun clampFutureCursors(overlap: Duration = DEFAULT_CLAMP_OVERLAP): ClockClampReport

    suspend fun coverage(): List<StreamCoverageState>

    fun observeCoverage(): Flow<List<StreamCoverageState>>

    suspend fun connectorState(connectorId: String): ConnectorStateRecord?

    suspend fun connectorStates(): List<ConnectorStateRecord>

    fun observeConnectorStates(): Flow<List<ConnectorStateRecord>>

    /** Atomically replaces the state of [connectorId] (created with defaults when absent). */
    suspend fun updateConnectorState(connectorId: String, transform: (ConnectorStateRecord) -> ConnectorStateRecord): ConnectorStateRecord

    suspend fun googleHealth(): GoogleHealthConnection?

    fun observeGoogleHealth(): Flow<GoogleHealthConnection?>

    suspend fun updateGoogleHealth(transform: (GoogleHealthConnection) -> GoogleHealthConnection): GoogleHealthConnection

    companion object {
        /** The future tolerance of the clamp. */
        val FUTURE_TOLERANCE: Duration = 5.minutes

        /** How far behind now a clamped position restarts (the incremental re-sync overlap). */
        val DEFAULT_CLAMP_OVERLAP: Duration = 48.hours
    }
}

internal class RoomSyncStateRepository(
    private val access: DataAccess,
    private val clock: AgentleClock,
    private val diagnostics: DiagnosticWriter,
) : SyncStateRepository {
    override suspend fun cursors(): List<StreamCursorState> = access.read { db.syncDao().cursors().map(::cursorState) }

    override suspend fun recordFailure(connectorId: String, accountId: String?, stream: String, errorCode: String, nextAllowedAt: Instant?) {
        val now = nowMs()
        access.write {
            val dao = db.syncDao()
            val account = accountId.orEmpty()
            val stored = dao.cursor(connectorId, account, stream)
            if (stored == null) {
                dao.insertCursor(
                    SyncCursorEntity(
                        connectorId = connectorId,
                        accountId = account,
                        stream = stream,
                        lastSuccessCursor = null,
                        lastAttemptCursor = null,
                        syncStartMs = null,
                        syncEndMs = null,
                        lastErrorCode = errorCode,
                        fetchGeneration = 0L,
                        syncedThroughMs = null,
                        syncedThroughElapsedMs = null,
                        syncedThroughBootCount = null,
                        backfilledFromMs = null,
                        nextAllowedAtMs = nextAllowedAt?.toEpochMilliseconds(),
                        consecutiveFailures = 1,
                        importFloorMs = null,
                        categories = "|",
                        updatedMs = now,
                    ),
                )
            } else {
                dao.updateCursor(
                    stored.copy(
                        lastErrorCode = errorCode,
                        consecutiveFailures = stored.consecutiveFailures + 1,
                        nextAllowedAtMs = nextAllowedAt?.toEpochMilliseconds(),
                        updatedMs = now,
                    ),
                )
            }
        }
    }

    override suspend fun clampFutureCursors(overlap: Duration): ClockClampReport {
        val now = nowMs()
        val limit = now + SyncStateRepository.FUTURE_TOLERANCE.inWholeMilliseconds
        val restart = now - overlap.inWholeMilliseconds
        val elapsed = clock.elapsed().inWholeMilliseconds
        return access.write {
            val dao = db.syncDao()
            val cursors = dao.cursors().filter { (it.syncedThroughMs ?: Long.MIN_VALUE) > limit }
            cursors.forEach { row ->
                dao.updateCursor(
                    row.copy(
                        syncedThroughMs = restart,
                        syncedThroughElapsedMs = elapsed,
                        fetchGeneration = row.fetchGeneration + 1,
                        updatedMs = now,
                    ),
                )
                diagnostics.write(
                    this,
                    DiagnosticCode.SYNC_CURSOR_CLAMPED,
                    mapOf("connector" to row.connectorId, "stream" to row.stream, "future_ms" to ((row.syncedThroughMs ?: now) - now)),
                )
            }
            val coverage = dao.coverages().filter { it.coverageThroughMs > limit }
            coverage.forEach { row ->
                dao.updateCoverage(row.copy(coverageThroughMs = restart, updatedMs = now))
                diagnostics.write(
                    this,
                    DiagnosticCode.SYNC_COVERAGE_CLAMPED,
                    mapOf("connector" to row.connectorId, "stream" to row.stream, "future_ms" to (row.coverageThroughMs - now)),
                )
            }
            ClockClampReport(cursors.size, coverage.size)
        }
    }

    override suspend fun coverage(): List<StreamCoverageState> = access.read { db.syncDao().coverages().map(::coverageState) }

    override fun observeCoverage(): Flow<List<StreamCoverageState>> = flow {
        emitAll(access.database().syncDao().observeCoverages().map { rows -> rows.map(::coverageState) })
    }

    override suspend fun connectorState(connectorId: String): ConnectorStateRecord? =
        access.read { db.syncDao().connectorState(connectorId)?.let(::connectorRecord) }

    override suspend fun connectorStates(): List<ConnectorStateRecord> = access.read { db.syncDao().connectorStates().map(::connectorRecord) }

    override fun observeConnectorStates(): Flow<List<ConnectorStateRecord>> = flow {
        emitAll(access.database().syncDao().observeConnectorStates().map { rows -> rows.map(::connectorRecord) })
    }

    override suspend fun updateConnectorState(
        connectorId: String,
        transform: (ConnectorStateRecord) -> ConnectorStateRecord,
    ): ConnectorStateRecord {
        val now = nowMs()
        return access.write {
            val dao = db.syncDao()
            val stored = dao.connectorState(connectorId)
            val next = transform(stored?.let(::connectorRecord) ?: ConnectorStateRecord(connectorId)).copy(connectorId = connectorId)
            val row = connectorEntity(next, now)
            if (stored == null) dao.insertConnectorState(row) else dao.updateConnectorState(row)
            next
        }
    }

    override suspend fun googleHealth(): GoogleHealthConnection? = access.read { db.syncDao().googleHealthState()?.let(::googleHealthRecord) }

    override fun observeGoogleHealth(): Flow<GoogleHealthConnection?> = flow {
        emitAll(access.database().syncDao().observeGoogleHealthState().map { row -> row?.let(::googleHealthRecord) })
    }

    override suspend fun updateGoogleHealth(transform: (GoogleHealthConnection) -> GoogleHealthConnection): GoogleHealthConnection =
        access.write {
            val dao = db.syncDao()
            val stored = dao.googleHealthState()
            val next = transform(stored?.let(::googleHealthRecord) ?: GoogleHealthConnection())
            val row = GoogleHealthStateEntity(
                healthUserId = next.healthUserId,
                accountId = next.accountId,
                grantedScopes = next.grantedScopes.sorted().joinToString(" ").ifEmpty { null },
                connectedMs = next.connectedAt?.toEpochMilliseconds(),
                disconnectedMs = next.disconnectedAt?.toEpochMilliseconds(),
                lastFullResyncMs = next.lastFullResyncAt?.toEpochMilliseconds(),
            )
            if (stored == null) dao.insertGoogleHealthState(row) else dao.updateGoogleHealthState(row)
            next
        }

    private fun nowMs(): Long = clock.now().toEpochMilliseconds()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private fun instant(ms: Long): Instant = Instant.fromEpochMilliseconds(ms)

        fun cursorState(row: SyncCursorEntity): StreamCursorState = StreamCursorState(
            connectorId = row.connectorId,
            accountId = row.accountId.ifEmpty { null },
            stream = row.stream,
            generation = row.fetchGeneration,
            syncedThrough = row.syncedThroughMs?.let { instant(it) },
            backfilledFrom = row.backfilledFromMs?.let { instant(it) },
            nextAllowedAt = row.nextAllowedAtMs?.let { instant(it) },
            consecutiveFailures = row.consecutiveFailures,
            lastErrorCode = row.lastErrorCode,
            importFloor = row.importFloorMs?.let { instant(it) },
            updatedAt = instant(row.updatedMs),
        )

        fun coverageState(row: SourceCoverageEntity): StreamCoverageState = StreamCoverageState(
            connectorId = row.connectorId,
            stream = row.stream,
            accountId = row.accountId.ifEmpty { null },
            coverageThrough = instant(row.coverageThroughMs),
            deviceLastSync = row.deviceLastSyncMs?.let { instant(it) },
        )

        fun connectorRecord(row: ConnectorStateEntity): ConnectorStateRecord = ConnectorStateRecord(
            connectorId = row.connectorId,
            enabled = row.enabled,
            connection = row.connection,
            permissionSummary = row.permissionSummary,
            lastSuccess = row.lastSuccessMs?.let { instant(it) },
            lastAttempt = row.lastAttemptMs?.let { instant(it) },
            lastErrorCode = row.lastErrorCode,
            syncState = row.syncState,
            activeAccountId = row.activeAccountId,
            streamPermissions = row.streamPermissions?.let(::decodePermissions).orEmpty(),
        )

        fun connectorEntity(record: ConnectorStateRecord, nowMs: Long): ConnectorStateEntity = ConnectorStateEntity(
            connectorId = record.connectorId,
            enabled = record.enabled,
            connection = record.connection,
            permissionSummary = record.permissionSummary,
            lastSuccessMs = record.lastSuccess?.toEpochMilliseconds(),
            lastAttemptMs = record.lastAttempt?.toEpochMilliseconds(),
            lastErrorCode = record.lastErrorCode,
            syncState = record.syncState,
            activeAccountId = record.activeAccountId,
            streamPermissions = record.streamPermissions.takeIf { it.isNotEmpty() }
                ?.let { map -> JsonObject(map.toSortedMap().mapValues { JsonPrimitive(it.value) }).toString() },
            updatedMs = nowMs,
        )

        fun googleHealthRecord(row: GoogleHealthStateEntity): GoogleHealthConnection = GoogleHealthConnection(
            healthUserId = row.healthUserId,
            accountId = row.accountId,
            grantedScopes = row.grantedScopes?.split(' ')?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
            connectedAt = row.connectedMs?.let { instant(it) },
            disconnectedAt = row.disconnectedMs?.let { instant(it) },
            lastFullResyncAt = row.lastFullResyncMs?.let { instant(it) },
        )

        private fun decodePermissions(text: String): Map<String, String> = try {
            (json.parseToJsonElement(text) as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        } catch (expected: IllegalArgumentException) {
            emptyMap()
        }
    }
}

/** An interval during which an on-device collector was working (round 1 correction 3; red team lifecycle-battery-01). */
data class CoverageInterval(val collector: String, val from: Instant, val to: Instant?, val lastHeartbeat: Instant?, val endCause: String?)

/**
 * The `collector_coverage` table. It mirrors `CoverageRecorder` of `:connectors:api` (ANDROID-COLLECTORS; `cause` is a
 * `CoverageEndCause` name), so the app binds that port with a one-line adapter. Calls are idempotent: [open] on an open
 * interval and [close] without one change nothing.
 */
interface CollectorCoverageStore {
    suspend fun open(collector: String, at: Instant)

    suspend fun heartbeat(collector: String, at: Instant)

    suspend fun close(collector: String, at: Instant, cause: String)

    /** Collectors with an open interval, each with its last heartbeat (or its start). */
    suspend fun openIntervals(): Map<String, Instant>

    /** Intervals of [collector] overlapping `[from, to)`, oldest first. */
    suspend fun intervals(collector: String, from: Instant, to: Instant): List<CoverageInterval>
}

internal class RoomCollectorCoverageStore(private val access: DataAccess) : CollectorCoverageStore {
    override suspend fun open(collector: String, at: Instant) {
        access.write {
            val dao = db.syncDao()
            if (dao.openInterval(collector) != null) return@write
            val fromMs = at.toEpochMilliseconds()
            // A closed interval that started at the same instant is reopened rather than duplicated (primary key).
            if (dao.intervals(collector, fromMs, fromMs + 1).any { it.fromMs == fromMs }) return@write
            dao.insertInterval(CollectorCoverageEntity(collector, fromMs, toMs = null, lastHeartbeatMs = null, endCause = null))
        }
    }

    override suspend fun heartbeat(collector: String, at: Instant) {
        access.write { db.syncDao().heartbeat(collector, at.toEpochMilliseconds()) }
    }

    override suspend fun close(collector: String, at: Instant, cause: String) {
        access.write { db.syncDao().closeInterval(collector, at.toEpochMilliseconds(), cause) }
    }

    override suspend fun openIntervals(): Map<String, Instant> = access.read {
        db.syncDao().openIntervals().associate { it.collector to Instant.fromEpochMilliseconds(it.lastHeartbeatMs ?: it.fromMs) }
    }

    override suspend fun intervals(collector: String, from: Instant, to: Instant): List<CoverageInterval> = access.read {
        db.syncDao().intervals(collector, from.toEpochMilliseconds(), to.toEpochMilliseconds()).map { row ->
            CoverageInterval(
                collector = row.collector,
                from = Instant.fromEpochMilliseconds(row.fromMs),
                to = row.toMs?.let { Instant.fromEpochMilliseconds(it) },
                lastHeartbeat = row.lastHeartbeatMs?.let { Instant.fromEpochMilliseconds(it) },
                endCause = row.endCause,
            )
        }
    }
}
