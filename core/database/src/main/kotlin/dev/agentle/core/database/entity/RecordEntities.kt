package dev.agentle.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Metadata of one AI request; never a copy of the payload (docs/ARCHITECTURE.md §5.2). */
@Entity(tableName = "ai_request", indices = [Index(value = ["created_ms"], name = "index_ai_request_created")])
data class AiRequestEntity(
    @PrimaryKey val id: String,
    val purpose: String,
    /** Exactly the categories present in the request body, as `|C:<CATEGORY>|` tokens. */
    val categories: String,
    @ColumnInfo(name = "range_start_ms") val rangeStartMs: Long?,
    @ColumnInfo(name = "range_end_ms") val rangeEndMs: Long?,
    @ColumnInfo(name = "raw_events_sent") val rawEventsSent: Boolean,
    @ColumnInfo(name = "aggregates_sent") val aggregatesSent: Boolean,
    val model: String?,
    val status: String,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "created_ms") val createdMs: Long,
    @ColumnInfo(name = "bytes_sent") val bytesSent: Long,
    @ColumnInfo(name = "consent_version") val consentVersion: Int?,
)

@Entity(tableName = "ai_result_meta")
data class AiResultMetaEntity(
    @PrimaryKey @ColumnInfo(name = "request_id") val requestId: String,
    val schema: String,
    val valid: Boolean,
    /** Closed validation error codes, comma separated; never model text. */
    @ColumnInfo(name = "validation_errors") val validationErrors: String,
    @ColumnInfo(name = "produced_entity_id") val producedEntityId: String?,
    @ColumnInfo(name = "created_ms") val createdMs: Long,
)

/**
 * AI-written intervention texts waiting to be used (round 1 correction 3; R10 §3.3): at most 24 hours old, purged on
 * every consent change, retention run and deletion. `categories` are the data categories the request used, as
 * `|C:<CATEGORY>|` tokens; an item is delivered only under the consent version it was generated with.
 */
@Entity(
    tableName = "ai_text_pool",
    indices = [
        Index(value = ["jitai_id"], name = "index_ai_text_pool_jitai"),
        Index(value = ["expires_ms"], name = "index_ai_text_pool_expires"),
    ],
)
data class AiTextPoolEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "jitai_id") val jitaiId: String,
    val title: String,
    val body: String,
    @ColumnInfo(name = "consent_version") val consentVersion: Int,
    val categories: String,
    @ColumnInfo(name = "snapshot_hash") val snapshotHash: String?,
    @ColumnInfo(name = "created_ms") val createdMs: Long,
    @ColumnInfo(name = "expires_ms") val expiresMs: Long,
    @ColumnInfo(name = "used_decision_key") val usedDecisionKey: String?,
    @ColumnInfo(name = "used_ms") val usedMs: Long?,
    val lineage: String,
)

/** A generated media file; the file lives in app-private no-backup storage, this is its row. */
@Entity(
    tableName = "media_artifact",
    indices = [
        Index(value = ["expires_ms"], name = "index_media_artifact_expires"),
        Index(value = ["decision_key"], name = "index_media_artifact_decision"),
    ],
)
data class MediaArtifactEntity(
    @PrimaryKey val id: String,
    val kind: String,
    @ColumnInfo(name = "created_ms") val createdMs: Long,
    @ColumnInfo(name = "source_jitai_id") val sourceJitaiId: String?,
    @ColumnInfo(name = "decision_key") val decisionKey: String?,
    val method: String,
    @ColumnInfo(name = "local_uri") val localUri: String,
    val mime: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "expires_ms") val expiresMs: Long?,
    @ColumnInfo(name = "last_access_ms") val lastAccessMs: Long?,
    val lineage: String,
)

@Entity(tableName = "user_goal")
data class UserGoalEntity(
    @PrimaryKey val id: String,
    val text: String,
    val metric: String?,
    val target: Double?,
    @ColumnInfo(name = "created_ms") val createdMs: Long,
    val active: Boolean,
)

/** Manual input; every row is mirrored as a USER_LOG event. */
@Entity(tableName = "user_log", indices = [Index(value = ["at_ms"], name = "index_user_log_at")])
data class UserLogEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "at_ms") val atMs: Long,
    val kind: String,
    val value: Double?,
    val note: String?,
    val label: String?,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "created_ms") val createdMs: Long,
)

/** A capability's permission state, written only when it changes. */
@Entity(
    tableName = "permission_snapshot",
    indices = [Index(value = ["capability_id", "at_ms"], name = "index_permission_snapshot_capability_at")],
)
data class PermissionSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "capability_id") val capabilityId: String,
    val state: String,
    val blockers: String,
    @ColumnInfo(name = "at_ms") val atMs: Long,
)

/**
 * A structured diagnostic (round 1 correction 7): a closed component and code plus an allow-listed JSON object of
 * closed keys to enums, numbers, durations or HTTP status codes. There is no free-text column.
 */
@Entity(tableName = "diagnostic_log", indices = [Index(value = ["at_ms"], name = "index_diagnostic_log_at")])
data class DiagnosticLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "at_ms") val atMs: Long,
    val severity: String,
    val component: String,
    val code: String,
    @ColumnInfo(name = "fields_json") val fieldsJson: String,
    val lineage: String,
)

/**
 * A place the user defined for classifying foreground location fixes (HOME, WORK, GYM). Coordinates are personal
 * data, so they live in the encrypted database rather than in a settings file, and never leave the device.
 */
@Entity(tableName = "known_place")
data class KnownPlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "place_class") val placeClass: String,
    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "radius_m") val radiusM: Double,
    @ColumnInfo(name = "updated_ms") val updatedMs: Long,
)
