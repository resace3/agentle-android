package dev.agentle.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import dev.agentle.core.database.entity.DerivedFeatureEntity
import dev.agentle.core.database.entity.EngineDaySummaryEntity
import dev.agentle.core.database.entity.InsightEntity
import dev.agentle.core.database.entity.MetricSourcePolicyEntity
import dev.agentle.core.database.entity.UpstreamDailyView
import kotlinx.coroutines.flow.Flow

/** Engine-day summaries, upstream daily totals, source policies, derived features and insights. */
@Dao
interface AnalyticsDao {
    @Query(
        "INSERT OR REPLACE INTO engine_day_summary(engine_day, metric, value, coverage, computed_ms, lineage) " +
            "VALUES (:engineDay, :metric, :value, :coverage, :computedMs, :lineage)",
    )
    suspend fun putSummary(engineDay: String, metric: String, value: Double?, coverage: Double?, computedMs: Long, lineage: String)

    @Query("SELECT * FROM engine_day_summary WHERE engine_day >= :fromDay AND engine_day <= :toDay ORDER BY engine_day, metric")
    suspend fun summaries(fromDay: String, toDay: String): List<EngineDaySummaryEntity>

    @Query(
        "SELECT * FROM engine_day_summary WHERE metric = :metric AND engine_day >= :fromDay AND engine_day <= :toDay " +
            "ORDER BY engine_day",
    )
    suspend fun summariesOf(metric: String, fromDay: String, toDay: String): List<EngineDaySummaryEntity>

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
        "INSERT OR REPLACE INTO derived_feature(feature_id, feature_window, anchor_date, value, value_text, status, " +
            "computed_ms, lineage) VALUES (:featureId, :window, :anchorDate, :value, :valueText, :status, :computedMs, :lineage)",
    )
    suspend fun putFeature(
        featureId: String,
        window: String,
        anchorDate: String,
        value: Double?,
        valueText: String?,
        status: String,
        computedMs: Long,
        lineage: String,
    )

    @Query("SELECT * FROM derived_feature WHERE feature_id = :featureId AND feature_window = :window AND anchor_date = :anchorDate")
    suspend fun feature(featureId: String, window: String, anchorDate: String): DerivedFeatureEntity?

    @Query("SELECT * FROM derived_feature WHERE anchor_date = :anchorDate ORDER BY feature_id, feature_window")
    suspend fun featuresOn(anchorDate: String): List<DerivedFeatureEntity>

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
}
