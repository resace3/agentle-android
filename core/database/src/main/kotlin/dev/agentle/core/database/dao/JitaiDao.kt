package dev.agentle.core.database.dao

import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import dev.agentle.core.database.entity.InterventionOutcomeEntity
import dev.agentle.core.database.entity.JitaiDecisionEntity
import dev.agentle.core.database.entity.JitaiDefinitionEntity
import dev.agentle.core.database.entity.JitaiDefinitionHistoryEntity
import dev.agentle.core.database.entity.JitaiEvalLogEntity
import dev.agentle.core.database.entity.JitaiResponseLogEntity
import dev.agentle.core.database.entity.JitaiRuntimeEntity
import dev.agentle.core.database.entity.JitaiTimerEntity
import kotlinx.coroutines.flow.Flow

/** A definition with the JSON of its current version. */
data class CurrentDefinitionRow(@Embedded val definition: JitaiDefinitionEntity, val json: String)

/**
 * JITAI definitions, runtime state, the decision ledger, outcomes, logs and timers (docs/research/10 §8; round 2
 * correction 4; round 3 corrections 1-2). Conditional updates (compare-and-set on the decision state) are done by the
 * decision runner of `:data` inside one IMMEDIATE transaction.
 */
@Dao
interface JitaiDao {
    @Query("SELECT * FROM jitai_definition WHERE id = :id")
    suspend fun definition(id: String): JitaiDefinitionEntity?

    @Query(
        "SELECT d.*, h.json AS json FROM jitai_definition AS d JOIN jitai_definition_history AS h " +
            "ON h.jitai_id = d.id AND h.version = d.current_version WHERE d.state != 'ARCHIVED' ORDER BY d.created_ms, d.id",
    )
    suspend fun currentDefinitions(): List<CurrentDefinitionRow>

    @Query("SELECT * FROM jitai_definition ORDER BY created_ms, id")
    fun observeDefinitions(): Flow<List<JitaiDefinitionEntity>>

    @Insert
    suspend fun insertDefinition(row: JitaiDefinitionEntity)

    @Update
    suspend fun updateDefinition(row: JitaiDefinitionEntity): Int

    @Query("DELETE FROM jitai_definition WHERE id = :id")
    suspend fun deleteDefinition(id: String): Int

    @Insert
    suspend fun insertHistory(row: JitaiDefinitionHistoryEntity)

    @Query("SELECT * FROM jitai_definition_history WHERE jitai_id = :jitaiId AND version = :version")
    suspend fun history(jitaiId: String, version: Int): JitaiDefinitionHistoryEntity?

    @Query("SELECT * FROM jitai_definition_history WHERE jitai_id = :jitaiId ORDER BY version")
    suspend fun historyOf(jitaiId: String): List<JitaiDefinitionHistoryEntity>

    @Query("DELETE FROM jitai_definition_history WHERE jitai_id = :jitaiId")
    suspend fun deleteHistory(jitaiId: String): Int

    // ------------------------------------------------------------------------------------------------- runtime

    @Query("SELECT * FROM jitai_runtime WHERE jitai_id = :jitaiId")
    suspend fun runtime(jitaiId: String): JitaiRuntimeEntity?

    @Insert
    suspend fun insertRuntime(row: JitaiRuntimeEntity)

    @Update
    suspend fun updateRuntime(row: JitaiRuntimeEntity): Int

    @Query("DELETE FROM jitai_runtime WHERE jitai_id = :jitaiId")
    suspend fun deleteRuntime(jitaiId: String): Int

    @Query(
        "UPDATE jitai_runtime SET pending_change_seq = NULL, pending_event_type = NULL, pending_event_ms = NULL, " +
            "pending_activity_state = NULL WHERE pending_change_seq IS NOT NULL",
    )
    suspend fun clearPendingEvents(): Int

    // ------------------------------------------------------------------------------------------------ decisions

    @Insert
    suspend fun insertDecision(row: JitaiDecisionEntity): Long

    @Update
    suspend fun updateDecision(row: JitaiDecisionEntity): Int

    @Query("SELECT * FROM jitai_decision WHERE decision_key = :decisionKey")
    suspend fun decision(decisionKey: String): JitaiDecisionEntity?

    @Query("SELECT decision_key FROM jitai_decision WHERE decision_key IN (:keys)")
    suspend fun existingKeys(keys: List<String>): List<String>

    @Query(
        "SELECT * FROM jitai_decision WHERE engine_day >= :fromDay AND engine_day <= :toDay AND state IN (:states) " +
            "ORDER BY seq",
    )
    suspend fun inEngineDays(fromDay: String, toDay: String, states: List<String>): List<JitaiDecisionEntity>

    @Query("SELECT * FROM jitai_decision WHERE jitai_id = :jitaiId AND state IN (:states) ORDER BY seq DESC LIMIT :limit")
    suspend fun recentOf(jitaiId: String, states: List<String>, limit: Int): List<JitaiDecisionEntity>

    @Query("SELECT COUNT(*) FROM jitai_decision WHERE jitai_id = :jitaiId AND state IN (:states)")
    suspend fun countOf(jitaiId: String, states: List<String>): Long

    @Query("SELECT * FROM jitai_decision WHERE state IN (:states) ORDER BY seq")
    suspend fun inStates(states: List<String>): List<JitaiDecisionEntity>

    /** The newest rows in [states], newest first by insertion order (never by wall time). */
    @Query("SELECT * FROM jitai_decision WHERE state IN (:states) ORDER BY seq DESC LIMIT :limit")
    suspend fun recentInStates(states: List<String>, limit: Int): List<JitaiDecisionEntity>

    @Query("SELECT * FROM jitai_decision WHERE category = :category AND state IN (:states) ORDER BY seq DESC LIMIT :limit")
    suspend fun recentOfCategory(category: String, states: List<String>, limit: Int): List<JitaiDecisionEntity>

    @Query("SELECT * FROM jitai_decision WHERE decided_ms >= :fromMs ORDER BY seq DESC LIMIT :limit")
    suspend fun recent(fromMs: Long, limit: Int): List<JitaiDecisionEntity>

    /** First response wins (R10 §8.7): sets the response only while it is still NONE. */
    @Query("UPDATE jitai_decision SET response = :response, responded_ms = :atMs WHERE decision_key = :decisionKey AND response = 'NONE'")
    suspend fun setResponseIfNone(decisionKey: String, response: String, atMs: Long): Int

    /** "Delete intervention history": clears the content of every row and keeps the content-free ledger. */
    @Query(
        "UPDATE jitai_decision SET snapshot_json = NULL, snapshot_hash = NULL, trace_json = NULL, trace_summary_json = NULL, " +
            "content_ref = NULL, response = 'NONE', responded_ms = NULL",
    )
    suspend fun clearAllContent(): Int

    @Query("SELECT COUNT(*) FROM jitai_decision")
    suspend fun decisionCount(): Long

    @Query("DELETE FROM jitai_decision WHERE decided_ms < :beforeMs")
    suspend fun deleteDecisionsBefore(beforeMs: Long): Int

    /** Retention: drops full traces and snapshots of old rows, keeping the trace summary. */
    @Query("UPDATE jitai_decision SET trace_json = NULL, snapshot_json = NULL WHERE decided_ms < :beforeMs AND trace_json IS NOT NULL")
    suspend fun reduceTracesBefore(beforeMs: Long): Int

    // ------------------------------------------------------------------------------------------------- outcomes

    @Query(
        "INSERT OR IGNORE INTO intervention_outcome(decision_key, outcome_state, outcome_value, metric_json, computed_ms, lineage) " +
            "VALUES (:decisionKey, :outcomeState, :outcomeValue, :metricJson, :computedMs, :lineage)",
    )
    suspend fun insertOutcomeIfAbsent(
        decisionKey: String,
        outcomeState: String,
        outcomeValue: Double?,
        metricJson: String?,
        computedMs: Long,
        lineage: String,
    )

    @Query("SELECT * FROM intervention_outcome WHERE decision_key = :decisionKey")
    suspend fun outcome(decisionKey: String): InterventionOutcomeEntity?

    @Query("DELETE FROM intervention_outcome")
    suspend fun clearOutcomes(): Int

    @Query("DELETE FROM intervention_outcome WHERE decision_key NOT IN (SELECT decision_key FROM jitai_decision)")
    suspend fun deleteOrphanOutcomes(): Int

    // ---------------------------------------------------------------------------------------- response and eval logs

    @Insert
    suspend fun insertResponseLog(row: JitaiResponseLogEntity)

    @Query("SELECT * FROM jitai_response_log WHERE decision_key = :decisionKey ORDER BY id")
    suspend fun responseLog(decisionKey: String): List<JitaiResponseLogEntity>

    @Query("DELETE FROM jitai_response_log")
    suspend fun clearResponseLog(): Int

    @Query("DELETE FROM jitai_response_log WHERE decision_key NOT IN (SELECT decision_key FROM jitai_decision)")
    suspend fun deleteOrphanResponses(): Int

    @Insert
    suspend fun insertEvalLog(rows: List<JitaiEvalLogEntity>)

    @Query("SELECT * FROM jitai_eval_log WHERE jitai_id = :jitaiId ORDER BY at_ms DESC, id DESC LIMIT :limit")
    suspend fun evalLog(jitaiId: String, limit: Int): List<JitaiEvalLogEntity>

    @Query("DELETE FROM jitai_eval_log WHERE at_ms < :beforeMs")
    suspend fun pruneEvalLog(beforeMs: Long): Int

    @Query("DELETE FROM jitai_eval_log")
    suspend fun clearEvalLog(): Int

    // --------------------------------------------------------------------------------------------------- timers

    @Insert
    suspend fun insertTimer(row: JitaiTimerEntity)

    @Query("DELETE FROM jitai_timer WHERE id = :id")
    suspend fun deleteTimer(id: String): Int

    @Query("DELETE FROM jitai_timer WHERE jitai_id = :jitaiId")
    suspend fun deleteTimersOf(jitaiId: String): Int

    @Query("SELECT * FROM jitai_timer WHERE jitai_id = :jitaiId ORDER BY due_at_ms, id")
    suspend fun timersOf(jitaiId: String): List<JitaiTimerEntity>

    @Query("SELECT * FROM jitai_timer ORDER BY due_at_ms, id")
    suspend fun timers(): List<JitaiTimerEntity>

    @Query("DELETE FROM jitai_timer")
    suspend fun deleteAllTimers(): Int

    @Query("SELECT * FROM jitai_timer WHERE due_at_ms <= :atMs ORDER BY due_at_ms, id")
    suspend fun dueTimers(atMs: Long): List<JitaiTimerEntity>

    @Query("SELECT * FROM jitai_timer ORDER BY due_at_ms, id LIMIT 1")
    suspend fun nextTimer(): JitaiTimerEntity?

    @Query("SELECT MIN(due_at_ms) FROM jitai_timer")
    fun observeNextDue(): Flow<Long?>
}
