package dev.agentle.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.DatabaseView
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Per engine day (04:00 rollover) aggregates (round 2 correction 2: renamed from `daily_summary`). `lineage` holds the
 * deletion tokens of the inputs (`|F:GH_API|C:ACTIVITY|`).
 */
@Entity(tableName = "engine_day_summary", primaryKeys = ["engine_day", "metric"])
data class EngineDaySummaryEntity(
    @ColumnInfo(name = "engine_day") val engineDay: String,
    val metric: String,
    val value: Double?,
    val coverage: Double?,
    @ColumnInfo(name = "computed_ms") val computedMs: Long,
    val lineage: String,
)

/**
 * Per-civil-day totals as the source computed them (Google Health daily roll-ups; round 2 correction 2): a view over the
 * DAILY_TOTAL events, so it can never disagree with them or outlive their deletion. Never summed with interval samples.
 * `account_id` is "" for sources that are not account-bound.
 */
@DatabaseView(
    viewName = "upstream_daily",
    value = """
        SELECT src.value AS source, e.subject AS metric, e.local_date AS local_date, COALESCE(acc.value, '') AS account_id,
               e.value_num AS value, e.upstream_update_ms AS upstream_update_ms, e.ingested_ms AS updated_ms
        FROM event AS e
        JOIN term AS t ON t.id = e.type AND t.kind = 1 AND t.value = 'DAILY_TOTAL'
        JOIN term AS src ON src.id = e.source
        LEFT JOIN term AS acc ON acc.id = e.account
        WHERE e.local_date IS NOT NULL AND e.subject IS NOT NULL
    """,
)
data class UpstreamDailyView(
    val source: String,
    val metric: String,
    @ColumnInfo(name = "local_date") val localDate: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    val value: Double?,
    @ColumnInfo(name = "upstream_update_ms") val upstreamUpdateMs: Long?,
    @ColumnInfo(name = "updated_ms") val updatedMs: Long,
)

/**
 * Which source is canonical for a metric over a validity range (round 2 correction 2). Query-time fusion takes, per
 * minute, the highest-priority (lowest number) source that has coverage there; sources are never summed.
 */
@Entity(tableName = "metric_source_policy", primaryKeys = ["metric", "source", "valid_from_ms"])
data class MetricSourcePolicyEntity(
    val metric: String,
    val source: String,
    val priority: Int,
    @ColumnInfo(name = "valid_from_ms") val validFromMs: Long,
    @ColumnInfo(name = "valid_to_ms") val validToMs: Long?,
)

/** A rolling-window feature value; `feature_window` is the window id (`7d`, `28d`, ...). */
@Entity(tableName = "derived_feature", primaryKeys = ["feature_id", "feature_window", "anchor_date"])
data class DerivedFeatureEntity(
    @ColumnInfo(name = "feature_id") val featureId: String,
    @ColumnInfo(name = "feature_window") val window: String,
    @ColumnInfo(name = "anchor_date") val anchorDate: String,
    val value: Double?,
    @ColumnInfo(name = "value_text") val valueText: String?,
    /** OK, UNKNOWN or STALE. */
    val status: String,
    @ColumnInfo(name = "computed_ms") val computedMs: Long,
    val lineage: String,
)

@Entity(tableName = "insight", indices = [Index(value = ["state", "created_ms"], name = "index_insight_state_created")])
data class InsightEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val finding: String,
    @ColumnInfo(name = "support_json") val supportJson: String,
    @ColumnInfo(name = "period_start_ms") val periodStartMs: Long,
    @ColumnInfo(name = "period_end_ms") val periodEndMs: Long,
    val strength: String,
    val confidence: Double?,
    val origin: String,
    val categories: String,
    @ColumnInfo(name = "created_ms") val createdMs: Long,
    val state: String,
    val lineage: String,
)
