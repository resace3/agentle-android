package dev.agentle.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import dev.agentle.core.database.entity.AiRequestEntity
import dev.agentle.core.database.entity.AiResultMetaEntity
import dev.agentle.core.database.entity.AiTextPoolEntity
import dev.agentle.core.database.entity.DiagnosticLogEntity
import dev.agentle.core.database.entity.KnownPlaceEntity
import dev.agentle.core.database.entity.MediaArtifactEntity
import dev.agentle.core.database.entity.PermissionSnapshotEntity
import dev.agentle.core.database.entity.UserGoalEntity
import dev.agentle.core.database.entity.UserLogEntity
import kotlinx.coroutines.flow.Flow

/** AI audit metadata and the pooled AI texts. */
@Dao
interface AiDao {
    @Insert
    suspend fun insertRequest(row: AiRequestEntity)

    @Query(
        "UPDATE ai_request SET status = :status, error_code = :errorCode, model = :model, " +
            "provider_request_id = COALESCE(:providerRequestId, provider_request_id) WHERE id = :id",
    )
    suspend fun finishRequest(id: String, status: String, errorCode: String?, model: String?, providerRequestId: String?): Int

    @Query("SELECT * FROM ai_request WHERE id = :id")
    suspend fun request(id: String): AiRequestEntity?

    @Query("SELECT * FROM ai_request ORDER BY created_ms DESC LIMIT :limit")
    suspend fun recentRequests(limit: Int): List<AiRequestEntity>

    @Query("SELECT * FROM ai_request ORDER BY created_ms DESC LIMIT :limit")
    fun observeRequests(limit: Int): Flow<List<AiRequestEntity>>

    @Query("DELETE FROM ai_request WHERE created_ms < :beforeMs")
    suspend fun deleteRequestsBefore(beforeMs: Long): Int

    @Insert
    suspend fun insertResultMeta(row: AiResultMetaEntity)

    @Query("SELECT * FROM ai_result_meta WHERE request_id = :requestId")
    suspend fun resultMeta(requestId: String): AiResultMetaEntity?

    @Query("DELETE FROM ai_result_meta WHERE request_id NOT IN (SELECT id FROM ai_request)")
    suspend fun deleteOrphanResultMeta(): Int

    // ------------------------------------------------------------------------------------------- text pool

    @Insert
    suspend fun insertPooled(row: AiTextPoolEntity)

    @Query(
        "SELECT * FROM ai_text_pool WHERE jitai_id = :jitaiId AND used_decision_key IS NULL AND expires_ms > :nowMs " +
            "ORDER BY created_ms",
    )
    suspend fun pooled(jitaiId: String, nowMs: Long): List<AiTextPoolEntity>

    /** Unused, unexpired items written for the rule content [contentHash] (index on content_hash). */
    @Query(
        "SELECT * FROM ai_text_pool WHERE content_hash = :contentHash AND used_decision_key IS NULL AND expires_ms > :nowMs " +
            "ORDER BY created_ms, id",
    )
    suspend fun pooledByContent(contentHash: String, nowMs: Long): List<AiTextPoolEntity>

    @Query("SELECT * FROM ai_text_pool WHERE id = :id")
    suspend fun pooledItem(id: String): AiTextPoolEntity?

    /** Items of [jitaiId] written for other rule content than [keepContentHash] (every item of it when null). */
    @Query("DELETE FROM ai_text_pool WHERE jitai_id = :jitaiId AND (:keepContentHash IS NULL OR content_hash != :keepContentHash)")
    suspend fun purgePool(jitaiId: String, keepContentHash: String?): Int

    @Query("DELETE FROM ai_text_pool WHERE created_ms < :beforeMs")
    suspend fun purgePoolCreatedBefore(beforeMs: Long): Int

    @Query("SELECT COUNT(*) FROM ai_text_pool")
    suspend fun poolSize(): Long

    @Query("UPDATE ai_text_pool SET used_decision_key = :decisionKey, used_ms = :atMs WHERE id = :id AND used_decision_key IS NULL")
    suspend fun markUsed(id: String, decisionKey: String, atMs: Long): Int

    @Query("DELETE FROM ai_text_pool WHERE expires_ms <= :nowMs OR used_ms < :usedBeforeMs")
    suspend fun deleteExpiredPooled(nowMs: Long, usedBeforeMs: Long): Int

    @Query("DELETE FROM ai_text_pool")
    suspend fun clearPool(): Int
}

/** Generated media rows; the files are deleted by the media repository. */
@Dao
interface MediaDao {
    @Insert
    suspend fun insert(row: MediaArtifactEntity)

    @Query("SELECT * FROM media_artifact WHERE id = :id")
    suspend fun byId(id: String): MediaArtifactEntity?

    @Query("SELECT * FROM media_artifact ORDER BY created_ms DESC")
    fun observeAll(): Flow<List<MediaArtifactEntity>>

    @Query("SELECT * FROM media_artifact ORDER BY created_ms")
    suspend fun all(): List<MediaArtifactEntity>

    @Query("SELECT * FROM media_artifact WHERE expires_ms IS NOT NULL AND expires_ms <= :nowMs")
    suspend fun expired(nowMs: Long): List<MediaArtifactEntity>

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM media_artifact")
    suspend fun totalBytes(): Long

    @Query("UPDATE media_artifact SET last_access_ms = :atMs WHERE id = :id")
    suspend fun touch(id: String, atMs: Long): Int

    @Query("DELETE FROM media_artifact WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>): Int
}

/** Goals and manual logs (user input). */
@Dao
interface UserDao {
    @Insert
    suspend fun insertGoal(row: UserGoalEntity)

    @Update
    suspend fun updateGoal(row: UserGoalEntity): Int

    @Query("DELETE FROM user_goal WHERE id = :id")
    suspend fun deleteGoal(id: String): Int

    @Query("SELECT * FROM user_goal WHERE id = :id")
    suspend fun goal(id: String): UserGoalEntity?

    @Query("SELECT * FROM user_goal ORDER BY created_ms")
    fun observeGoals(): Flow<List<UserGoalEntity>>

    @Query("SELECT * FROM user_goal WHERE active = 1 ORDER BY created_ms")
    suspend fun activeGoals(): List<UserGoalEntity>

    @Insert
    suspend fun insertLog(row: UserLogEntity)

    @Query("SELECT * FROM user_log WHERE id = :id")
    suspend fun log(id: String): UserLogEntity?

    @Query("DELETE FROM user_log WHERE id = :id")
    suspend fun deleteLog(id: String): Int

    @Query("SELECT * FROM user_log WHERE at_ms >= :fromMs AND at_ms < :toMs ORDER BY at_ms")
    suspend fun logs(fromMs: Long, toMs: Long): List<UserLogEntity>

    @Query("SELECT * FROM user_log ORDER BY at_ms DESC LIMIT :limit")
    fun observeRecentLogs(limit: Int): Flow<List<UserLogEntity>>

    @Query("SELECT * FROM known_place ORDER BY id")
    suspend fun knownPlaces(): List<KnownPlaceEntity>

    @Query("SELECT * FROM known_place ORDER BY id")
    fun observeKnownPlaces(): Flow<List<KnownPlaceEntity>>

    @Insert
    suspend fun insertKnownPlaces(rows: List<KnownPlaceEntity>)

    @Query("DELETE FROM known_place")
    suspend fun deleteKnownPlaces(): Int
}

/** Permission snapshots (written on change only) and the structured diagnostics ring buffer. */
@Dao
interface SystemDao {
    @Insert
    suspend fun insertPermissionSnapshot(row: PermissionSnapshotEntity)

    @Query("SELECT * FROM permission_snapshot WHERE capability_id = :capabilityId ORDER BY at_ms DESC, id DESC LIMIT 1")
    suspend fun latestPermission(capabilityId: String): PermissionSnapshotEntity?

    @Query(
        "SELECT * FROM permission_snapshot WHERE id IN (SELECT MAX(id) FROM permission_snapshot GROUP BY capability_id) " +
            "ORDER BY capability_id",
    )
    suspend fun latestPermissions(): List<PermissionSnapshotEntity>

    @Query("SELECT * FROM permission_snapshot WHERE capability_id = :capabilityId ORDER BY at_ms, id")
    suspend fun permissionHistory(capabilityId: String): List<PermissionSnapshotEntity>

    @Insert
    suspend fun insertDiagnostic(row: DiagnosticLogEntity)

    @Query("SELECT * FROM diagnostic_log ORDER BY id DESC LIMIT :limit")
    suspend fun recentDiagnostics(limit: Int): List<DiagnosticLogEntity>

    @Query("SELECT * FROM diagnostic_log ORDER BY id DESC LIMIT :limit")
    fun observeDiagnostics(limit: Int): Flow<List<DiagnosticLogEntity>>

    @Query("SELECT COUNT(*) FROM diagnostic_log")
    suspend fun diagnosticCount(): Long

    /** Keeps the newest [keep] rows (ring buffer). */
    @Query("DELETE FROM diagnostic_log WHERE id <= (SELECT id FROM diagnostic_log ORDER BY id DESC LIMIT 1 OFFSET :keep)")
    suspend fun trimDiagnostics(keep: Int): Int
}
