package dev.agentle.connectors.android.collectors.healthconnect

import dev.agentle.connectors.android.permissions.HealthConnectProbe
import dev.agentle.connectors.android.permissions.HealthPermissions
import kotlin.time.Instant

/** The Health Connect record types Agentle reads, each its own stream (`healthconnect.<stream>`) and changes token. */
public enum class HcType(public val stream: String, public val permission: String) {
    STEPS("steps", HealthPermissions.READ_STEPS),
    DISTANCE("distance", HealthPermissions.READ_DISTANCE),
    ACTIVE_CALORIES("active_calories", HealthPermissions.READ_ACTIVE_CALORIES_BURNED),
    TOTAL_CALORIES("total_calories", HealthPermissions.READ_TOTAL_CALORIES_BURNED),
    HEART_RATE("heart_rate", HealthPermissions.READ_HEART_RATE),
    RESTING_HEART_RATE("resting_heart_rate", HealthPermissions.READ_RESTING_HEART_RATE),
    SLEEP("sleep", HealthPermissions.READ_SLEEP),
    EXERCISE("exercise", HealthPermissions.READ_EXERCISE),
    WEIGHT("weight", HealthPermissions.READ_WEIGHT),
}

/** One sleep stage as Health Connect reports it (`SleepSessionRecord.STAGE_TYPE_*`). */
public data class HcSleepStage(val start: Instant, val end: Instant, val stage: Int)

/** The measured value of one Health Connect record. */
public sealed interface HcValue {
    public data class Steps(val count: Long) : HcValue

    public data class Distance(val meters: Double) : HcValue

    public data class Calories(val kilocalories: Double) : HcValue

    /** A heart-rate series folded into one row: mean, min and max over its samples. */
    public data class HeartRate(val meanBpm: Double, val minBpm: Double, val maxBpm: Double, val samples: Int) : HcValue

    public data class RestingHeartRate(val bpm: Long) : HcValue

    public data class Sleep(val stages: List<HcSleepStage>) : HcValue

    public data class Exercise(val exerciseType: Int) : HcValue

    public data class Weight(val kilograms: Double) : HcValue
}

/**
 * One Health Connect record in Agentle's terms. [id] is `metadata.id`, the dedup identity (red team database-sync-01):
 * rows key on `hc|<id>` and a `DeletionChange` deletes exactly that key.
 */
public data class HcRecord(
    val id: String,
    val type: HcType,
    val start: Instant,
    val end: Instant?,
    /** UTC offset at [start] when the writer recorded one. */
    val startOffsetSeconds: Int?,
    val origin: String?,
    val lastModified: Instant?,
    val value: HcValue,
)

public data class HcPage(val records: List<HcRecord>, val nextPageToken: String?)

public data class HcChanges(
    val upserts: List<HcRecord>,
    val deletedIds: List<String>,
    val nextToken: String,
    val hasMore: Boolean,
    val tokenExpired: Boolean,
)

/**
 * Agentle's seam over `HealthConnectClient` (the real one is [ClientHealthConnectGateway]); tests use fakes because
 * Health Connect response types have no public constructors. All calls may throw `SecurityException` when a permission
 * was revoked; the collector turns that into a PermissionDenied result.
 */
public interface HealthConnectGateway : HealthConnectProbe {
    public suspend fun readRecords(type: HcType, start: Instant, end: Instant, pageToken: String?): HcPage

    public suspend fun changesToken(type: HcType): String

    public suspend fun changes(token: String, type: HcType): HcChanges
}
