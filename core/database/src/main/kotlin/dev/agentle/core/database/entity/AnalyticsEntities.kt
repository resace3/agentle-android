package dev.agentle.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.DatabaseView
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Per engine day (04:00 rollover) aggregates (round 2 correction 2: renamed from `daily_summary`). `metric` is the row
 * key (the feature id, or a feature ref key with its subject); `status` is FINAL, PROVISIONAL, PARTIAL or MISSING with
 * `missing_reason` for MISSING; `source` is the one source used, when exactly one was; `catalog_version` is the daily
 * feature catalog version that computed the row. `lineage` holds the deletion tokens of the inputs
 * (`|F:GH_API|C:ACTIVITY|`).
 */
@Entity(
    tableName = "engine_day_summary",
    primaryKeys = ["engine_day", "metric"],
    indices = [Index(value = ["status", "engine_day"], name = "index_engine_day_summary_status_day")],
)
data class EngineDaySummaryEntity(
    @ColumnInfo(name = "engine_day") val engineDay: String,
    val metric: String,
    @ColumnInfo(name = "feature_id") val featureId: String,
    val value: Double?,
    val coverage: Double?,
    val status: String,
    @ColumnInfo(name = "missing_reason") val missingReason: String?,
    val source: String?,
    @ColumnInfo(name = "catalog_version") val catalogVersion: Int,
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

/**
 * A rolling-window feature value over the `window_days` days ending at and including `anchor_date` (the column is not
 * called `window`, an SQL keyword). `covered_days` counts the final days the value used.
 */
@Entity(
    tableName = "derived_feature",
    primaryKeys = ["feature_id", "window_days", "anchor_date"],
    indices = [Index(value = ["anchor_date"], name = "index_derived_feature_anchor")],
)
data class DerivedFeatureEntity(
    @ColumnInfo(name = "feature_id") val featureId: String,
    @ColumnInfo(name = "window_days") val windowDays: Int,
    @ColumnInfo(name = "anchor_date") val anchorDate: String,
    val value: Double?,
    @ColumnInfo(name = "value_text") val valueText: String?,
    /** OK, UNKNOWN or STALE. */
    val status: String,
    @ColumnInfo(name = "covered_days") val coveredDays: Int,
    @ColumnInfo(name = "catalog_version") val catalogVersion: Int,
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

/**
 * One weekly run of the pattern-discovery pipeline (docs/research/10 §14): the run as JSON (findings over aggregates,
 * never raw events), kept for the persistence check against the previous weekly run. `last_night` is the ISO date of
 * the run's last night.
 */
@Entity(tableName = "discovery_run", indices = [Index(value = ["run_at_ms"], name = "index_discovery_run_at")])
data class DiscoveryRunEntity(
    @PrimaryKey @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "run_at_ms") val runAtMs: Long,
    @ColumnInfo(name = "last_night") val lastNight: String,
    @ColumnInfo(name = "family_version") val familyVersion: Int,
    val json: String,
    val lineage: String,
)

/** A discovered JITAI proposal (docs/research/10 §14.7-14.8): PROPOSED, APPROVED or DECLINED. */
@Entity(tableName = "discovery_proposal", indices = [Index(value = ["status", "created_ms"], name = "index_discovery_proposal_status")])
data class DiscoveryProposalEntity(
    @PrimaryKey @ColumnInfo(name = "proposal_id") val proposalId: String,
    @ColumnInfo(name = "pattern_id") val patternId: String,
    @ColumnInfo(name = "hypothesis_id") val hypothesisId: String,
    val tier: String,
    @ColumnInfo(name = "created_ms") val createdMs: Long,
    val status: String,
    @ColumnInfo(name = "proposal_json") val proposalJson: String,
    @ColumnInfo(name = "decided_ms") val decidedMs: Long?,
    val lineage: String,
)

/** A muted discovery pattern: "Not now" until `until_ms`, "Never suggest this" with `until_ms` null. */
@Entity(tableName = "pattern_mute")
data class PatternMuteEntity(
    @PrimaryKey @ColumnInfo(name = "pattern_id") val patternId: String,
    @ColumnInfo(name = "until_ms") val untilMs: Long?,
)
