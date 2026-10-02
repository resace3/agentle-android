package dev.agentle.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import dev.agentle.core.database.entity.CollectorCoverageEntity
import dev.agentle.core.database.entity.ConnectorStateEntity
import dev.agentle.core.database.entity.GoogleHealthStateEntity
import dev.agentle.core.database.entity.SourceCoverageEntity
import dev.agentle.core.database.entity.SyncCursorEntity
import kotlinx.coroutines.flow.Flow

/** Sync cursors, stream and collector coverage, connector state and the Google Health connection. */
@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_cursor WHERE connector_id = :connectorId AND account_id = :accountId AND stream = :stream")
    suspend fun cursor(connectorId: String, accountId: String, stream: String): SyncCursorEntity?

    @Query("SELECT * FROM sync_cursor WHERE connector_id = :connectorId AND stream = :stream ORDER BY updated_ms DESC")
    suspend fun cursorsOfStream(connectorId: String, stream: String): List<SyncCursorEntity>

    @Query("SELECT * FROM sync_cursor ORDER BY connector_id, account_id, stream")
    suspend fun cursors(): List<SyncCursorEntity>

    @Insert
    suspend fun insertCursor(row: SyncCursorEntity)

    @Update
    suspend fun updateCursor(row: SyncCursorEntity): Int

    /** Invalidates every in-flight run of [connectorIds]: their compare-and-set on the old generation fails. */
    @Query("UPDATE sync_cursor SET fetch_generation = fetch_generation + 1, updated_ms = :nowMs WHERE connector_id IN (:connectorIds)")
    suspend fun bumpGenerations(connectorIds: List<String>, nowMs: Long): Int

    /** Records the import floor on the cursors of [connectorIds] (max with the stored one). */
    @Query(
        "UPDATE sync_cursor SET import_floor_ms = MAX(COALESCE(import_floor_ms, 0), :floorMs), updated_ms = :nowMs " +
            "WHERE connector_id IN (:connectorIds)",
    )
    suspend fun raiseImportFloor(connectorIds: List<String>, floorMs: Long, nowMs: Long): Int

    // -------------------------------------------------------------------------------------------- stream coverage

    @Query("SELECT * FROM source_coverage WHERE connector_id = :connectorId AND stream = :stream AND account_id = :accountId")
    suspend fun coverage(connectorId: String, stream: String, accountId: String): SourceCoverageEntity?

    @Query("SELECT * FROM source_coverage ORDER BY connector_id, stream, account_id")
    suspend fun coverages(): List<SourceCoverageEntity>

    @Query("SELECT * FROM source_coverage ORDER BY connector_id, stream, account_id")
    fun observeCoverages(): Flow<List<SourceCoverageEntity>>

    @Insert
    suspend fun insertCoverage(row: SourceCoverageEntity)

    @Update
    suspend fun updateCoverage(row: SourceCoverageEntity): Int

    // ----------------------------------------------------------------------------------------- collector coverage

    @Query("SELECT * FROM collector_coverage WHERE to_ms IS NULL ORDER BY collector")
    suspend fun openIntervals(): List<CollectorCoverageEntity>

    @Query("SELECT * FROM collector_coverage WHERE collector = :collector AND to_ms IS NULL")
    suspend fun openInterval(collector: String): CollectorCoverageEntity?

    @Query(
        "SELECT * FROM collector_coverage WHERE collector = :collector AND from_ms < :toMs AND (to_ms IS NULL OR to_ms > :fromMs) " +
            "ORDER BY from_ms",
    )
    suspend fun intervals(collector: String, fromMs: Long, toMs: Long): List<CollectorCoverageEntity>

    @Insert
    suspend fun insertInterval(row: CollectorCoverageEntity)

    @Query(
        "UPDATE collector_coverage SET last_heartbeat_ms = :atMs WHERE collector = :collector AND to_ms IS NULL " +
            "AND (last_heartbeat_ms IS NULL OR last_heartbeat_ms < :atMs)",
    )
    suspend fun heartbeat(collector: String, atMs: Long): Int

    @Query("UPDATE collector_coverage SET to_ms = MAX(from_ms, :toMs), end_cause = :cause WHERE collector = :collector AND to_ms IS NULL")
    suspend fun closeInterval(collector: String, toMs: Long, cause: String): Int

    // -------------------------------------------------------------------------------------------- connector state

    @Query("SELECT * FROM connector_state WHERE connector_id = :connectorId")
    suspend fun connectorState(connectorId: String): ConnectorStateEntity?

    @Query("SELECT * FROM connector_state ORDER BY connector_id")
    suspend fun connectorStates(): List<ConnectorStateEntity>

    @Query("SELECT * FROM connector_state ORDER BY connector_id")
    fun observeConnectorStates(): Flow<List<ConnectorStateEntity>>

    @Insert
    suspend fun insertConnectorState(row: ConnectorStateEntity)

    @Update
    suspend fun updateConnectorState(row: ConnectorStateEntity): Int

    @Query("SELECT active_account_id FROM connector_state WHERE connector_id = :connectorId")
    suspend fun activeAccount(connectorId: String): String?

    // --------------------------------------------------------------------------------------------- Google Health

    @Query("SELECT * FROM google_health_state WHERE id = 1")
    suspend fun googleHealthState(): GoogleHealthStateEntity?

    @Query("SELECT * FROM google_health_state WHERE id = 1")
    fun observeGoogleHealthState(): Flow<GoogleHealthStateEntity?>

    @Insert
    suspend fun insertGoogleHealthState(row: GoogleHealthStateEntity)

    @Update
    suspend fun updateGoogleHealthState(row: GoogleHealthStateEntity): Int
}
