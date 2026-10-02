package dev.agentle.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import dev.agentle.core.database.entity.DerivedFeatureEntity
import dev.agentle.core.database.entity.DiscoveryProposalEntity
import dev.agentle.core.database.entity.DiscoveryRunEntity
import dev.agentle.core.database.entity.EngineDaySummaryEntity
import dev.agentle.core.database.entity.InsightEntity
import dev.agentle.core.database.entity.MetricSourcePolicyEntity
import dev.agentle.core.database.entity.PatternMuteEntity
import dev.agentle.core.database.entity.UpstreamDailyView
import kotlinx.coroutines.flow.Flow

/**
 * Engine-day summaries, upstream daily totals, source policies, derived features, insights and the discovery tables.
 * Every read is bounded by a date range or a key (the analytics ports never scan a table).
 */
@Dao
interface AnalyticsDao {
    @Insert
    suspend fun insertSummaries(rows: List<EngineDaySummaryEntity>)

    @Query("DELETE FROM engine_day_summary WHERE engine_day IN (:engineDays)")
    suspend fun deleteSummaryDays(engineDays: List<String>): Int

    @Query("SELECT * FROM engine_day_summary WHERE engine_day >= :fromDay AND engine_day <= :toDay ORDER BY engine_day, metric")
    suspend fun summaries(fromDay: String, toDay: String): List<EngineDaySummaryEntity>

    @Query(
        "SELECT * FROM engine_day_summary WHERE metric = :metric AND engine_day >= :fromDay AND engine_day <= :toDay " +
            "ORDER BY engine_day",
    )
    suspend fun summariesOf(metric: String, fromDay: String, toDay: String): List<EngineDaySummaryEntity>

    @Query("SELECT DISTINCT engine_day FROM engine_day_summary WHERE status = :status ORDER BY engine_day")
    suspend fun summaryDaysWithStatus(status: String): List<String>

    @Query("SELECT DISTINCT engine_day FROM engine_day_summary WHERE instr(lineage, :token) > 0")
    suspend fun summaryDaysWithLineage(token: String): List<String>

    // ------------------------------------------------------------------------------------------ upstream daily

    @Query(
        "SELECT * FROM upstream_daily WHERE metric = :metric AND account_id = :accountId AND local_date >= :fromDate " +
            "AND local_date <= :toDate ORDER BY local_date, source",
    )
    suspend fun upstreamDaily(metric: String, accountId: String, fromDate: String, toDate: String): List<UpstreamDailyView>

    // ------------------------------------------------------------------------------------- metric source policy

    @Query("SELECT * FROM metric_source_policy WHERE metric = :metric ORDER BY priority, valid_from_ms")
    suspend fun policies(metric: String): List<MetricSourcePolicyEntity>

    @Query("SELECT * FROM metric_source_policy ORDER BY metric, priority, valid_from_ms")
    suspend fun allPolicies(): List<MetricSourcePolicyEntity>

    @Insert
    suspend fun insertPolicies(rows: List<MetricSourcePolicyEntity>)

    @Query("DELETE FROM metric_source_policy WHERE metric = :metric")
    suspend fun deletePolicies(metric: String): Int

    // ----------------------------------------------------------------------------------------- derived features

    @Query(
        "INSERT OR REPLACE INTO derived_feature(feature_id, window_days, anchor_date, value, value_text, status, covered_days, " +
            "catalog_version, computed_ms, lineage) VALUES (:featureId, :windowDays, :anchorDate, :value, :valueText, :status, " +
            ":coveredDays, :catalogVersion, :computedMs, :lineage)",
    )
    suspend fun putFeature(
        featureId: String,
        windowDays: Int,
        anchorDate: String,
        value: Double?,
        valueText: String?,
        status: String,
        coveredDays: Int,
        catalogVersion: Int,
        computedMs: Long,
        lineage: String,
    )

    @Query("SELECT * FROM derived_feature WHERE feature_id = :featureId AND window_days = :windowDays AND anchor_date = :anchorDate")
    suspend fun feature(featureId: String, windowDays: Int, anchorDate: String): DerivedFeatureEntity?

    @Query(
        "SELECT * FROM derived_feature WHERE anchor_date >= :fromDate AND anchor_date <= :toDate " +
            "ORDER BY anchor_date, feature_id, window_days",
    )
    suspend fun features(fromDate: String, toDate: String): List<DerivedFeatureEntity>

    @Query(
        "SELECT * FROM derived_feature WHERE feature_id = :featureId AND anchor_date >= :fromDate AND anchor_date <= :toDate " +
            "ORDER BY anchor_date, window_days",
    )
    suspend fun featuresOf(featureId: String, fromDate: String, toDate: String): List<DerivedFeatureEntity>

    @Query("SELECT DISTINCT anchor_date FROM derived_feature WHERE instr(lineage, :token) > 0")
    suspend fun featureDaysWithLineage(token: String): List<String>

    // ------------------------------------------------------------------------------------------------- insights

    @Insert
    suspend fun insertInsight(row: InsightEntity)

    @Query("UPDATE insight SET state = :state WHERE id = :id")
    suspend fun setInsightState(id: String, state: String): Int

    @Query("SELECT * FROM insight WHERE id = :id")
    suspend fun insight(id: String): InsightEntity?

    @Query("SELECT * FROM insight WHERE state = :state ORDER BY created_ms DESC")
    fun observeInsights(state: String): Flow<List<InsightEntity>>

    @Query("SELECT * FROM insight ORDER BY created_ms DESC LIMIT :limit")
    suspend fun recentInsights(limit: Int): List<InsightEntity>

    // ------------------------------------------------------------------------------------------------ discovery

    @Query(
        "INSERT OR REPLACE INTO discovery_run(run_id, run_at_ms, last_night, family_version, json, lineage) " +
            "VALUES (:runId, :runAtMs, :lastNight, :familyVersion, :json, :lineage)",
    )
    suspend fun putRun(runId: String, runAtMs: Long, lastNight: String, familyVersion: Int, json: String, lineage: String)

    @Query("SELECT * FROM discovery_run ORDER BY run_at_ms DESC LIMIT :limit")
    suspend fun recentRuns(limit: Int): List<DiscoveryRunEntity>

    /** Keeps the newest [keep] runs. */
    @Query("DELETE FROM discovery_run WHERE run_id NOT IN (SELECT run_id FROM discovery_run ORDER BY run_at_ms DESC LIMIT :keep)")
    suspend fun trimRuns(keep: Int): Int

    @Query(
        "INSERT OR REPLACE INTO discovery_proposal(proposal_id, pattern_id, hypothesis_id, tier, created_ms, status, proposal_json, " +
            "decided_ms, lineage) VALUES (:proposalId, :patternId, :hypothesisId, :tier, :createdMs, :status, :proposalJson, " +
            ":decidedMs, :lineage)",
    )
    suspend fun putProposal(
        proposalId: String,
        patternId: String,
        hypothesisId: String,
        tier: String,
        createdMs: Long,
        status: String,
        proposalJson: String,
        decidedMs: Long?,
        lineage: String,
    )

    @Query("SELECT * FROM discovery_proposal ORDER BY created_ms, proposal_id")
    suspend fun proposals(): List<DiscoveryProposalEntity>

    @Query("SELECT * FROM discovery_proposal WHERE status = :status ORDER BY created_ms DESC")
    fun observeProposals(status: String): Flow<List<DiscoveryProposalEntity>>

    @Query("INSERT OR REPLACE INTO pattern_mute(pattern_id, until_ms) VALUES (:patternId, :untilMs)")
    suspend fun putMute(patternId: String, untilMs: Long?)

    @Query("SELECT * FROM pattern_mute ORDER BY pattern_id")
    suspend fun mutes(): List<PatternMuteEntity>

    @Query("DELETE FROM pattern_mute")
    suspend fun clearMutes(): Int
}
