package dev.agentle.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Position of one connector stream for one upstream account (round 2 correction 5). `fetch_generation` is the
 * compare-and-set version; positions only move forward (max), except through an explicit clamp after a clock jump.
 * `account_id` is "" for streams that are not account-bound.
 */
@Entity(tableName = "sync_cursor", primaryKeys = ["connector_id", "account_id", "stream"])
data class SyncCursorEntity(
    @ColumnInfo(name = "connector_id") val connectorId: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    val stream: String,
    @ColumnInfo(name = "last_success_cursor") val lastSuccessCursor: String?,
    @ColumnInfo(name = "last_attempt_cursor") val lastAttemptCursor: String?,
    @ColumnInfo(name = "sync_start_ms") val syncStartMs: Long?,
    @ColumnInfo(name = "sync_end_ms") val syncEndMs: Long?,
    @ColumnInfo(name = "last_error_code") val lastErrorCode: String?,
    @ColumnInfo(name = "fetch_generation") val fetchGeneration: Long,
    @ColumnInfo(name = "synced_through_ms") val syncedThroughMs: Long?,
    @ColumnInfo(name = "synced_through_elapsed_ms") val syncedThroughElapsedMs: Long?,
    @ColumnInfo(name = "synced_through_boot_count") val syncedThroughBootCount: Int?,
    @ColumnInfo(name = "backfilled_from_ms") val backfilledFromMs: Long?,
    @ColumnInfo(name = "next_allowed_at_ms") val nextAllowedAtMs: Long?,
    @ColumnInfo(name = "consecutive_failures") val consecutiveFailures: Int,
    @ColumnInfo(name = "import_floor_ms") val importFloorMs: Long?,
    /** Lineage tokens of the data committed under this cursor, so a category deletion finds its streams. */
    val categories: String,
    @ColumnInfo(name = "updated_ms") val updatedMs: Long,
)

/**
 * A connector's completeness claim for one stream and account (docs/research/10 §5.3): everything recorded before
 * `coverage_through_ms` has been committed. Stored in the same transaction as the data; only moves forward.
 */
@Entity(tableName = "source_coverage", primaryKeys = ["connector_id", "stream", "account_id"])
data class SourceCoverageEntity(
    @ColumnInfo(name = "connector_id") val connectorId: String,
    val stream: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "coverage_through_ms") val coverageThroughMs: Long,
    @ColumnInfo(name = "device_last_sync_ms") val deviceLastSyncMs: Long?,
    @ColumnInfo(name = "updated_ms") val updatedMs: Long,
)

/**
 * An interval during which an on-device collector was known to be working (round 1 correction 3; red team
 * lifecycle-battery-01). `to_ms` and `end_cause` are null while the interval is open; `last_heartbeat_ms` lets a
 * process that died without closing it be closed at its last sign of life. `end_cause` is a closed code
 * (CoverageEndCause name: STOPPED, PERMISSION_LOST, DATA_DELETED, CLOCK_CHANGED, PROCESS_KILLED_BY_SYSTEM, ...).
 */
@Entity(
    tableName = "collector_coverage",
    primaryKeys = ["collector", "from_ms"],
    indices = [Index(value = ["to_ms"], name = "index_collector_coverage_to")],
)
data class CollectorCoverageEntity(
    val collector: String,
    @ColumnInfo(name = "from_ms") val fromMs: Long,
    @ColumnInfo(name = "to_ms") val toMs: Long?,
    @ColumnInfo(name = "last_heartbeat_ms") val lastHeartbeatMs: Long?,
    @ColumnInfo(name = "end_cause") val endCause: String?,
)

@Entity(tableName = "connector_state")
data class ConnectorStateEntity(
    @PrimaryKey @ColumnInfo(name = "connector_id") val connectorId: String,
    val enabled: Boolean,
    val connection: String,
    @ColumnInfo(name = "permission_summary") val permissionSummary: String,
    @ColumnInfo(name = "last_success_ms") val lastSuccessMs: Long?,
    @ColumnInfo(name = "last_attempt_ms") val lastAttemptMs: Long?,
    @ColumnInfo(name = "last_error_code") val lastErrorCode: String?,
    @ColumnInfo(name = "sync_state") val syncState: String,
    /** The upstream account (a hash) whose data features and AI may read; null when not account-bound. */
    @ColumnInfo(name = "active_account_id") val activeAccountId: String?,
    /** JSON object stream -> PermissionState name. */
    @ColumnInfo(name = "stream_permissions") val streamPermissions: String?,
    @ColumnInfo(name = "updated_ms") val updatedMs: Long,
)

/** Google Health connection state; never tokens (Google tokens stay in Play services). */
@Entity(tableName = "google_health_state")
data class GoogleHealthStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "health_user_id") val healthUserId: String?,
    /** Pseudonymous account id used in dedup keys, cursors and coverage. */
    @ColumnInfo(name = "account_id") val accountId: String?,
    @ColumnInfo(name = "granted_scopes") val grantedScopes: String?,
    @ColumnInfo(name = "connected_ms") val connectedMs: Long?,
    @ColumnInfo(name = "disconnected_ms") val disconnectedMs: Long?,
    @ColumnInfo(name = "last_full_resync_ms") val lastFullResyncMs: Long?,
) {
    companion object {
        const val SINGLETON_ID: Int = 1
    }
}
