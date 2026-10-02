package dev.agentle.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * The current version of a JITAI definition (round 2 correction 4); its immutable JSON lives in
 * [JitaiDefinitionHistoryEntity]. `kind` is INTERVENTION or SUPPRESSION and `category` the JITAI category (round 1
 * correction 3). `state` holds a JitaiStatus name (DRAFT, PROPOSED, ACTIVE, PAUSED, EXPIRED, DECLINED, ARCHIVED).
 */
@Entity(tableName = "jitai_definition", indices = [Index(value = ["enabled", "state"], name = "index_jitai_definition_enabled_state")])
data class JitaiDefinitionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "current_version") val currentVersion: Int,
    val enabled: Boolean,
    val state: String,
    val kind: String,
    val category: String?,
    val origin: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "created_ms") val createdMs: Long,
    @ColumnInfo(name = "modified_ms") val modifiedMs: Long,
    @ColumnInfo(name = "expires_ms") val expiresMs: Long?,
)

/** Every saved version of a definition; rows are inserted once and never updated. */
@Entity(tableName = "jitai_definition_history", primaryKeys = ["jitai_id", "version"])
data class JitaiDefinitionHistoryEntity(
    @ColumnInfo(name = "jitai_id") val jitaiId: String,
    val version: Int,
    val json: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "saved_ms") val savedMs: Long,
)

/**
 * Runtime state of one JITAI (R10 §3.5): it survives process death and is not intervention history. Instants that the
 * engine compares across clock changes are stored on both clocks plus the boot count (wall ms, elapsed ms, boot).
 */
@Entity(tableName = "jitai_runtime")
data class JitaiRuntimeEntity(
    @PrimaryKey @ColumnInfo(name = "jitai_id") val jitaiId: String,
    @ColumnInfo(name = "snoozed_until_ms") val snoozedUntilMs: Long?,
    @ColumnInfo(name = "snoozed_until_elapsed_ms") val snoozedUntilElapsedMs: Long?,
    @ColumnInfo(name = "snoozed_until_boot") val snoozedUntilBoot: Int?,
    @ColumnInfo(name = "snooze_mode") val snoozeMode: String?,
    /** The decision whose `RE_EVALUATE_AFTER` follow-up is pending. */
    @ColumnInfo(name = "follow_up_of") val followUpOf: String?,
    @ColumnInfo(name = "consecutive_ignored") val consecutiveIgnored: Int,
    @ColumnInfo(name = "last_event_eval_ms") val lastEventEvalMs: Long?,
    @ColumnInfo(name = "last_event_eval_elapsed_ms") val lastEventEvalElapsedMs: Long?,
    @ColumnInfo(name = "last_event_eval_boot") val lastEventEvalBoot: Int?,
    @ColumnInfo(name = "pending_change_seq") val pendingChangeSeq: Long?,
    @ColumnInfo(name = "pending_event_type") val pendingEventType: String?,
    @ColumnInfo(name = "pending_event_ms") val pendingEventMs: Long?,
    @ColumnInfo(name = "pending_activity_state") val pendingActivityState: String?,
    /** Result signature of the latest evaluation-log entry written with a trace (an equal result is logged without one). */
    @ColumnInfo(name = "last_eval_signature") val lastEvalSignature: String?,
    @ColumnInfo(name = "updated_ms") val updatedMs: Long,
)

/**
 * One resolved decision point (docs/research/10 §8.3; round 2 correction 4; round 3 correction 2). `seq` is the
 * insertion order. The content-free columns form the ledger kept for 400 days; `snapshot_json`, `snapshot_hash`,
 * `trace_json`, `trace_summary_json`, `content_ref`, `response` and `responded_ms` are content: "delete intervention
 * history" clears them and a category deletion scrubs the category's values out of the JSON. Every instant the protocol
 * compares (decided, claimed, lease, delivered) is stored as wall ms, elapsed ms and boot count; the lease is compared
 * by elapsed time within one boot, never by wall time. States are the full R10 §8.3 enum plus CARD_PENDING.
 */
@Entity(
    tableName = "jitai_decision",
    indices = [
        Index(value = ["decision_key"], unique = true, name = "index_jitai_decision_key"),
        Index(value = ["jitai_id", "state", "decided_ms"], name = "index_jitai_decision_jitai_state_decided"),
        Index(value = ["state", "decided_ms"], name = "index_jitai_decision_state_decided"),
        Index(value = ["engine_day", "channel", "state"], name = "index_jitai_decision_day_channel_state"),
    ],
)
data class JitaiDecisionEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    @ColumnInfo(name = "decision_key") val decisionKey: String,
    @ColumnInfo(name = "jitai_id") val jitaiId: String,
    @ColumnInfo(name = "jitai_version") val jitaiVersion: Int,
    @ColumnInfo(name = "trigger_type") val triggerType: String,
    val category: String,
    val channel: String,
    val state: String,
    @ColumnInfo(name = "decided_ms") val decidedMs: Long,
    @ColumnInfo(name = "decided_elapsed_ms") val decidedElapsedMs: Long,
    @ColumnInfo(name = "boot_count") val bootCount: Int?,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "local_date_time") val localDateTime: String,
    @ColumnInfo(name = "engine_day") val engineDay: String,
    /** The nominal decision time the key names (slot start, event time, follow-up time); null on rows without one. */
    @ColumnInfo(name = "nominal_ms") val nominalMs: Long?,
    /** The delivery deadline on both clocks, anchored at the nominal time; null for rows that never deliver. */
    @ColumnInfo(name = "deadline_ms") val deadlineMs: Long?,
    @ColumnInfo(name = "deadline_elapsed_ms") val deadlineElapsedMs: Long?,
    @ColumnInfo(name = "deadline_boot") val deadlineBoot: Int?,
    val reason: String?,
    @ColumnInfo(name = "reason_detail") val reasonDetail: String?,
    @ColumnInfo(name = "conditions_result") val conditionsResult: String?,
    @ColumnInfo(name = "context_result") val contextResult: String?,
    @ColumnInfo(name = "rand_probability") val randProbability: Double?,
    @ColumnInfo(name = "rand_draw") val randDraw: Double?,
    @ColumnInfo(name = "delivery_nonce") val deliveryNonce: String?,
    @ColumnInfo(name = "claimed_ms") val claimedMs: Long?,
    @ColumnInfo(name = "claimed_elapsed_ms") val claimedElapsedMs: Long?,
    @ColumnInfo(name = "claimed_boot") val claimedBoot: Int?,
    @ColumnInfo(name = "lease_until_ms") val leaseUntilMs: Long?,
    @ColumnInfo(name = "lease_until_elapsed_ms") val leaseUntilElapsedMs: Long?,
    @ColumnInfo(name = "lease_boot_count") val leaseBootCount: Int?,
    @ColumnInfo(name = "delivered_ms") val deliveredMs: Long?,
    @ColumnInfo(name = "delivered_elapsed_ms") val deliveredElapsedMs: Long?,
    @ColumnInfo(name = "delivered_boot") val deliveredBoot: Int?,
    @ColumnInfo(name = "finished_ms") val finishedMs: Long?,
    val recovered: Boolean,
    @ColumnInfo(name = "implied_state_json") val impliedStateJson: String?,
    @ColumnInfo(name = "snapshot_json") val snapshotJson: String?,
    @ColumnInfo(name = "snapshot_hash") val snapshotHash: String?,
    @ColumnInfo(name = "trace_json") val traceJson: String?,
    @ColumnInfo(name = "trace_summary_json") val traceSummaryJson: String?,
    @ColumnInfo(name = "content_ref") val contentRef: String?,
    val response: String,
    @ColumnInfo(name = "responded_ms") val respondedMs: Long?,
    /** Lineage tokens of the snapshot's inputs (`|F:GH_API|C:SLEEP|`), so category deletions find the row. */
    val lineage: String,
)

/** The outcome measured after a decision point, keyed by its decision key (R10 §8.7). */
@Entity(tableName = "intervention_outcome")
data class InterventionOutcomeEntity(
    @PrimaryKey @ColumnInfo(name = "decision_key") val decisionKey: String,
    @ColumnInfo(name = "outcome_state") val outcomeState: String,
    @ColumnInfo(name = "outcome_value") val outcomeValue: Double?,
    @ColumnInfo(name = "metric_json") val metricJson: String?,
    @ColumnInfo(name = "computed_ms") val computedMs: Long,
    val lineage: String,
)

/** Responses after the first one (docs/research/10 §8.7): append-only, content-free. */
@Entity(tableName = "jitai_response_log", indices = [Index(value = ["decision_key"], name = "index_jitai_response_log_key")])
data class JitaiResponseLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "decision_key") val decisionKey: String,
    val response: String,
    @ColumnInfo(name = "at_ms") val atMs: Long,
)

/** Non-firing evaluations of event triggers; never consumes a decision key; a ring buffer of 30 days. */
@Entity(tableName = "jitai_eval_log", indices = [Index(value = ["at_ms"], name = "index_jitai_eval_log_at")])
data class JitaiEvalLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "at_ms") val atMs: Long,
    @ColumnInfo(name = "jitai_id") val jitaiId: String,
    @ColumnInfo(name = "trigger_kind") val triggerKind: String,
    @ColumnInfo(name = "event_type") val eventType: String?,
    val result: String,
    val reason: String?,
    @ColumnInfo(name = "trace_json") val traceJson: String?,
    val lineage: String,
)

/**
 * A pending JITAI wake-up (round 3 correction 1). One unique WorkManager work targets min(`due_at_ms`); changing,
 * disabling, deleting or expiring a definition deletes its timers in the same transaction. `kind` is SLOT, PREFETCH,
 * OUTCOME, SNOOZE or BACKSTOP; `jitai_id` and `version` are null for the BACKSTOP row. `slot` is the decision point of
 * SLOT and PREFETCH rows in the engine's text form, `decision_key` the slot's, follow-up's or outcome's key,
 * `original_key` the snoozed decision of a SNOOZE row, `role` the outcome role, `feature_ids` the remote features of a
 * PREFETCH row (comma separated) and `deferrals` how often a SLOT was deferred.
 */
@Entity(
    tableName = "jitai_timer",
    indices = [
        Index(value = ["due_at_ms"], name = "index_jitai_timer_due"),
        Index(value = ["jitai_id"], name = "index_jitai_timer_jitai"),
    ],
)
data class JitaiTimerEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "due_at_ms") val dueAtMs: Long,
    val kind: String,
    @ColumnInfo(name = "jitai_id") val jitaiId: String?,
    val version: Int?,
    val slot: String?,
    @ColumnInfo(name = "decision_key") val decisionKey: String?,
    @ColumnInfo(name = "original_key") val originalKey: String?,
    val role: String?,
    @ColumnInfo(name = "feature_ids") val featureIds: String,
    val deferrals: Int,
    @ColumnInfo(name = "created_ms") val createdMs: Long,
)
