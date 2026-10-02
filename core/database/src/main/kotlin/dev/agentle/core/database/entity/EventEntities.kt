package dev.agentle.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Small-integer dictionary for repeated strings (event types, source ids, account ids), so the `event` table stores
 * and indexes integers instead of text (round 2 correction 8). Ids are never reused; rows are never deleted.
 */
@Entity(tableName = "term", indices = [Index(value = ["kind", "value"], unique = true, name = "index_term_kind_value")])
data class TermEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val kind: Int, val value: String)

/**
 * One normalized event (docs/ARCHITECTURE.md §5.2, rounds 1-2). `seq` is the rowid; `id` (the model's UUID) is kept
 * but not indexed. `dedup_hash` is a 64-bit hash of (account, dedup key) with a collision check against `dedup_key`.
 * `payload_hash` hashes a canonical, versioned projection of the record. `change_seq` comes from one counter and moves
 * on every insert and semantic update; deletions leave an [EventTombstoneEntity] with their own `change_seq`.
 */
@Entity(
    tableName = "event",
    indices = [
        Index(value = ["dedup_hash"], unique = true, name = "index_event_dedup_hash"),
        Index(value = ["type", "start_ms"], name = "index_event_type_start"),
        Index(value = ["source", "start_ms"], name = "index_event_source_start"),
        Index(value = ["subject", "start_ms"], name = "index_event_subject_start"),
        Index(value = ["start_ms"], name = "index_event_start"),
        Index(value = ["type", "end_ms"], name = "index_event_type_end"),
        Index(value = ["change_seq"], name = "index_event_change_seq"),
    ],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val id: String,
    val type: Long,
    val source: Long,
    /** Term id of the upstream account, 0 when the source is not account-bound. */
    val account: Long,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long?,
    @ColumnInfo(name = "start_offset_s") val startOffsetS: Int?,
    @ColumnInfo(name = "end_offset_s") val endOffsetS: Int?,
    /** ISO civil date for day-keyed records (daily totals, resting heart rate); null otherwise. */
    @ColumnInfo(name = "local_date") val localDate: String?,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "dedup_hash") val dedupHash: Long,
    @ColumnInfo(name = "dedup_key") val dedupKey: String,
    @ColumnInfo(name = "upstream_id") val upstreamId: String?,
    @ColumnInfo(name = "upstream_update_ms") val upstreamUpdateMs: Long?,
    @ColumnInfo(name = "payload_hash") val payloadHash: Long,
    val subject: String?,
    @ColumnInfo(name = "value_num") val valueNum: Double?,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "payload_version") val payloadVersion: Int,
    val confidence: Double?,
    @ColumnInfo(name = "ingested_ms") val ingestedMs: Long,
    /** 0 NORMAL, 1 PERSONAL, 2 SENSITIVE. */
    val sensitivity: Int,
    val origin: String?,
    @ColumnInfo(name = "provenance_json") val provenanceJson: String?,
    @ColumnInfo(name = "change_seq") val changeSeq: Long,
    /** What assigned [changeSeq]: 0 inserted, 1 updated, 2 processed (a sleep session whose stages became final). */
    @ColumnInfo(name = "change_kind") val changeKind: Int,
)

/** A deleted event, so change readers (the JITAI dispatcher, feature refresh) see deletions too. */
@Entity(tableName = "event_tombstone", indices = [Index(value = ["deleted_ms"], name = "index_event_tombstone_deleted")])
data class EventTombstoneEntity(
    @PrimaryKey @ColumnInfo(name = "change_seq") val changeSeq: Long,
    @ColumnInfo(name = "event_seq") val eventSeq: Long,
    val type: Long,
    val source: Long,
    val account: Long,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long?,
    @ColumnInfo(name = "deleted_ms") val deletedMs: Long,
)

/** The rare dedup key whose 64-bit hash collided with another key's: it is stored under an alternative hash. */
@Entity(tableName = "dedup_collision", primaryKeys = ["account", "dedup_key"])
data class DedupCollisionEntity(
    val account: Long,
    @ColumnInfo(name = "dedup_key") val dedupKey: String,
    @ColumnInfo(name = "dedup_hash") val dedupHash: Long,
)

/** Single-value engine state: change counter, evaluation watermark, dirty flag, database generation, data epoch. */
@Entity(tableName = "engine_state")
data class EngineStateEntity(
    @PrimaryKey val name: String,
    @ColumnInfo(name = "int_value") val intValue: Long?,
    @ColumnInfo(name = "text_value") val textValue: String?,
)

/** An engine day whose features must be recomputed; refresh clears it only if `generation` is unchanged. */
@Entity(tableName = "dirty_day")
data class DirtyDayEntity(@PrimaryKey @ColumnInfo(name = "engine_day") val engineDay: String, val generation: Long)

/**
 * Import floors written by deletions: ingestion drops any record that starts before the floor of its scope
 * (`ALL`, `G:<source group>`, `C:<category>` or `S:<source>`), so deleted data never comes back from a re-sync.
 */
@Entity(tableName = "ingest_floor")
data class IngestFloorEntity(
    @PrimaryKey val scope: String,
    @ColumnInfo(name = "floor_ms") val floorMs: Long,
    @ColumnInfo(name = "updated_ms") val updatedMs: Long,
)
