package dev.agentle.connectors.android.collectors.healthconnect

import dev.agentle.connectors.android.core.AndroidConnector
import dev.agentle.connectors.android.core.AndroidConnectorIds
import dev.agentle.connectors.android.core.CollectOutcome
import dev.agentle.connectors.android.core.CollectorRuntime
import dev.agentle.connectors.android.core.CoverageIds
import dev.agentle.connectors.android.core.RunWrites
import dev.agentle.connectors.android.core.cursorSafely
import dev.agentle.connectors.android.core.epochSafely
import dev.agentle.connectors.android.core.importFloorSafely
import dev.agentle.connectors.android.core.writeChunked
import dev.agentle.connectors.api.CapabilityIds
import dev.agentle.connectors.api.CapabilityStatusProvider
import dev.agentle.connectors.api.SyncCursor
import dev.agentle.connectors.api.SyncTrigger
import dev.agentle.core.common.AppError
import dev.agentle.core.model.CaloriesPayload
import dev.agentle.core.model.CapabilityStatus
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.DistancePayload
import dev.agentle.core.model.EnergyBasis
import dev.agentle.core.model.EventPayload
import dev.agentle.core.model.EventType
import dev.agentle.core.model.ExercisePayload
import dev.agentle.core.model.HeartRatePayload
import dev.agentle.core.model.PermissionState
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.RestingHeartRatePayload
import dev.agentle.core.model.SleepSessionPayload
import dev.agentle.core.model.SleepStage
import dev.agentle.core.model.SleepStageKind
import dev.agentle.core.model.StepsPayload
import dev.agentle.core.model.WeightPayload
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Health Connect records (§6.4 "Health Connect", docs/research/05, red team database-sync-01/02/09).
 *
 * Per record type: one changes token in the `("healthconnect", <stream>)` cursor. The first run takes the token first,
 * then reads `[max(now - 30 days, import floor), now)` page by page, so nothing written meanwhile is missed. Later runs
 * read the changes: upserts are stored, `DeletionChange`s delete the row with key `hc|<metadata.id>`. An expired token is
 * replaced and the window re-read, clamped to the import floor (data the user deleted is never fetched again). Health
 * Connect data is stored even while Google Health is connected: fusion happens at query time.
 *
 * Grants are per type: a type without its permission, or one revoked mid-run (`SecurityException`), is skipped and
 * reported in the stream permissions; the run is then PARTIAL. Background runs need READ_HEALTH_DATA_IN_BACKGROUND
 * (Health Connect rejects background reads without it); without it a run happens only while an Agentle screen is
 * visible. Quota errors (`IllegalStateException`) stop the run as RateLimited.
 */
public class HealthConnectConnector(
    runtime: CollectorRuntime,
    permissions: CapabilityStatusProvider,
    private val gateway: HealthConnectGateway,
    private val foreground: () -> Boolean,
) : AndroidConnector(
    id = AndroidConnectorIds.HEALTH_CONNECT,
    name = "Health Connect",
    supportedEventTypes = setOf(
        EventType.STEP_SAMPLE,
        EventType.DISTANCE_SAMPLE,
        EventType.CALORIES_SAMPLE,
        EventType.HEART_RATE,
        EventType.RESTING_HEART_RATE,
        EventType.SLEEP_SESSION,
        EventType.EXERCISE_SESSION,
        EventType.WEIGHT,
    ),
    capabilityIds = listOf(
        CapabilityIds.HEALTH_CONNECT_RECORDS,
        CapabilityIds.HEALTH_CONNECT_BACKGROUND_READ,
        CapabilityIds.HEALTH_CONNECT_HISTORY_READ,
        CapabilityIds.HEALTH_CONNECT_ON_DEVICE_STEPS,
    ),
    runtime = runtime,
    permissions = permissions,
) {
    override val requiredCapabilityIds: List<String> = listOf(CapabilityIds.HEALTH_CONNECT_RECORDS)

    override val coverageIds: List<String> = CoverageIds.HEALTH_CONNECT

    override val streamIds: Set<String> = HcType.entries.map { it.stream }.toSet()

    override suspend fun collect(trigger: SyncTrigger, statuses: Map<String, CapabilityStatus>): CollectOutcome =
        collectTypes(HcType.entries, statuses)

    override suspend fun collectStream(stream: String, trigger: SyncTrigger, statuses: Map<String, CapabilityStatus>): CollectOutcome =
        collectTypes(HcType.entries.filter { it.stream == stream }, statuses)

    @Suppress("LoopWithTooManyJumpStatements")
    private suspend fun collectTypes(types: List<HcType>, statuses: Map<String, CapabilityStatus>): CollectOutcome {
        if (!foreground() && statuses[CapabilityIds.HEALTH_CONNECT_BACKGROUND_READ]?.state?.canCollect != true) {
            return CollectOutcome(
                skipped = true,
                error = AppError.PermissionDenied(CapabilityIds.HEALTH_CONNECT_BACKGROUND_READ, detail = "background_read"),
            )
        }
        val epoch = runtime.writer.epochSafely() ?: return CollectOutcome(error = AppError.DatabaseError("writer_unavailable"))
        val granted = gateway.grantedPermissions()
        val streamPermissions = LinkedHashMap<String, PermissionState>()
        var fetched = 0
        var committed = 0
        var error: AppError? = null
        var partial = false
        for (type in types) {
            if (type.permission !in granted) {
                streamPermissions[type.stream] = PermissionState.DENIED
                partial = true
                continue
            }
            streamPermissions[type.stream] = PermissionState.ALLOWED
            val result = try {
                syncType(type, epoch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                // Revoked mid-run: this type is skipped, the others continue.
                streamPermissions[type.stream] = PermissionState.DENIED
                partial = true
                runtime.logger.w(
                    COMPONENT,
                    "Health Connect type revoked mid-run",
                    fields = mapOf(
                        "stream" to type.stream,
                        "error" to e::class.simpleName,
                    ),
                )
                continue
            } catch (e: IllegalStateException) {
                error = AppError.RateLimited(detail = e::class.simpleName)
                partial = true
                break
            }
            fetched += result.fetched
            committed += result.writes.written
            if (result.writes.error != null) {
                error = result.writes.error
                break
            }
            if (result.writes.cursorRejected) partial = true
        }
        return CollectOutcome(
            fetched = fetched,
            committed = committed,
            partial = partial,
            error = error,
            streamPermissions = streamPermissions,
        )
    }

    private class TypeResult(val fetched: Int, val writes: RunWrites)

    private suspend fun syncType(type: HcType, epoch: Long): TypeResult {
        val stored = runtime.writer.cursorSafely(AndroidConnectorIds.HEALTH_CONNECT, type.stream)
        val token = stored?.lastSuccessCursor
        return if (token == null) readWindow(type, epoch, stored) else readChanges(type, epoch, stored, token)
    }

    /** First run or expired token: a fresh token first, then the window `[max(now - 30 d, floor), now)` page by page. */
    private suspend fun readWindow(type: HcType, epoch: Long, stored: SyncCursor?): TypeResult {
        val newToken = gateway.changesToken(type)
        val now = runtime.clock.now()
        val floor = runtime.writer.importFloorSafely(sourceOf(type))
        val start = maxOf(now - WINDOW, floor ?: Instant.DISTANT_PAST)
        var fetched = 0
        var written = 0
        if (start < now) {
            var pageToken: String? = null
            do {
                val page = gateway.readRecords(type, start, now, pageToken)
                fetched += page.records.size
                val writes = runtime.writeChunked(coverageIds, epoch, page.records.mapNotNull(::event), cursor = null)
                written += writes.written
                if (!writes.ok) return TypeResult(fetched, writes.copy(written = written))
                pageToken = page.nextPageToken
            } while (pageToken != null)
        }
        val cursor = cursorFor(type, stored, newToken, now)
        val last = runtime.writeChunked(coverageIds, epoch, emptyList(), cursor)
        return TypeResult(fetched, last.copy(written = written + last.written))
    }

    /** Changes since [token]: upserts stored, deletions removed by key; the new token is stored with the last batch. */
    private suspend fun readChanges(type: HcType, epoch: Long, stored: SyncCursor, token: String): TypeResult {
        var current = token
        var fetched = 0
        var written = 0
        var deleted = 0
        while (true) {
            val changes = gateway.changes(current, type)
            if (changes.tokenExpired) {
                val reread = readWindow(type, epoch, stored)
                return TypeResult(fetched + reread.fetched, reread.writes.copy(written = written + reread.writes.written))
            }
            fetched += changes.upserts.size + changes.deletedIds.size
            val events = changes.upserts.mapNotNull(::event)
            val deleteKeys = changes.deletedIds.map(::key).toSet() - events.map { it.dedupKey }.toSet()
            val cursor = if (changes.hasMore) null else cursorFor(type, stored, changes.nextToken, runtime.clock.now())
            val writes = runtime.writeChunked(coverageIds, epoch, events, cursor, deleteKeys)
            written += writes.written
            deleted += writes.deleted
            if (!writes.ok || !changes.hasMore) return TypeResult(fetched, writes.copy(written = written, deleted = deleted))
            current = changes.nextToken
        }
    }

    private fun cursorFor(type: HcType, stored: SyncCursor?, token: String, now: Instant): SyncCursor =
        (stored ?: SyncCursor(AndroidConnectorIds.HEALTH_CONNECT, type.stream)).copy(
            lastSuccessCursor = token,
            lastAttemptCursor = token,
            syncFinishedAt = now,
            lastErrorCode = null,
        )

    /** Maps one record; a record that cannot be stored (for example an empty heart-rate series) is skipped. */
    public fun event(record: HcRecord): PersonalEvent? {
        val zone = record.startOffsetSeconds?.let { seconds -> runCatching { UtcOffset(seconds = seconds).asTimeZone() }.getOrNull() }
        val (type, payload) = when (val value = record.value) {
            is HcValue.Steps -> EventType.STEP_SAMPLE to StepsPayload(value.count)

            is HcValue.Distance -> EventType.DISTANCE_SAMPLE to DistancePayload(value.meters)

            is HcValue.Calories -> EventType.CALORIES_SAMPLE to CaloriesPayload(
                value.kilocalories,
                if (record.type == HcType.ACTIVE_CALORIES) EnergyBasis.ACTIVE else EnergyBasis.TOTAL,
            )

            is HcValue.HeartRate -> EventType.HEART_RATE to HeartRatePayload(value.meanBpm, minBpm = value.minBpm, maxBpm = value.maxBpm)

            is HcValue.RestingHeartRate -> EventType.RESTING_HEART_RATE to RestingHeartRatePayload(
                bpm = value.bpm.toDouble(),
                date = record.start.toLocalDateTime(zone ?: runtime.clock.zone()).date,
            )

            is HcValue.Sleep -> EventType.SLEEP_SESSION to sleep(value, record)

            is HcValue.Exercise -> EventType.EXERCISE_SESSION to exercise(value, record)

            is HcValue.Weight -> EventType.WEIGHT to WeightPayload(value.kilograms)
        }
        return runtime.events.create(
            type = type,
            source = sourceOf(record.type),
            start = record.start,
            end = record.end,
            payload = payload,
            dedupKey = key(record.id),
            origin = record.origin,
            upstreamId = record.id,
            upstreamUpdatedAt = record.lastModified,
            zoneId = zone?.id,
        )
    }

    private fun sleep(value: HcValue.Sleep, record: HcRecord): EventPayload {
        val stages = value.stages.map { stage ->
            SleepStage(
                stage = stageKind(stage.stage),
                startEpochMs = stage.start.toEpochMilliseconds(),
                endEpochMs = stage.end.toEpochMilliseconds(),
                rawStage = stage.stage.toString().takeIf { stageKind(stage.stage) == SleepStageKind.UNKNOWN },
            )
        }
        val asleepMs = stages.filter { it.stage in ASLEEP }.sumOf { it.endEpochMs - it.startEpochMs }
        val awakeMs = stages.filter { it.stage == SleepStageKind.AWAKE }.sumOf { it.endEpochMs - it.startEpochMs }
        return SleepSessionPayload(
            stages = stages,
            minutesAsleep = if (stages.isEmpty()) null else asleepMs / MS_PER_MINUTE,
            minutesAwake = if (stages.isEmpty()) null else awakeMs / MS_PER_MINUTE,
            minutesInSleepPeriod = record.end?.let { (it - record.start).inWholeMinutes },
            startUtcOffsetSeconds = record.startOffsetSeconds,
        )
    }

    private fun exercise(value: HcValue.Exercise, record: HcRecord): EventPayload {
        val name = EXERCISE_TYPES[value.exerciseType]
        return ExercisePayload(
            exerciseType = name ?: "UNKNOWN",
            durationMs = record.end?.let { (it - record.start).inWholeMilliseconds } ?: 0L,
            rawExerciseType = if (name == null) value.exerciseType.toString() else null,
            startUtcOffsetSeconds = record.startOffsetSeconds,
        )
    }

    public companion object {
        private const val COMPONENT = "collectors.healthconnect"
        private const val MS_PER_MINUTE = 60_000L

        /** Without READ_HEALTH_DATA_HISTORY Health Connect serves 30 days before the first grant (docs/research/05). */
        public val WINDOW: Duration = 30.days

        public fun key(recordId: String): String = "hc|$recordId"

        public fun sourceOf(type: HcType): DataSourceId = DataSourceId("healthconnect.${type.stream}")

        private val ASLEEP = setOf(SleepStageKind.LIGHT, SleepStageKind.DEEP, SleepStageKind.REM, SleepStageKind.ASLEEP_UNSPECIFIED)

        /** `SleepSessionRecord.STAGE_TYPE_*` (connect-client 1.1.0 API file). */
        public fun stageKind(stage: Int): SleepStageKind = when (stage) {
            1 -> SleepStageKind.AWAKE
            2 -> SleepStageKind.ASLEEP_UNSPECIFIED
            3 -> SleepStageKind.OUT_OF_BED
            4 -> SleepStageKind.LIGHT
            5 -> SleepStageKind.DEEP
            6 -> SleepStageKind.REM
            7 -> SleepStageKind.AWAKE
            else -> SleepStageKind.UNKNOWN
        }

        /** `ExerciseSessionRecord.EXERCISE_TYPE_*` values to upstream names (connect-client 1.1.0 API file). */
        public val EXERCISE_TYPES: Map<Int, String> = mapOf(
            0 to "OTHER_WORKOUT", 2 to "BADMINTON", 4 to "BASEBALL", 5 to "BASKETBALL", 8 to "BIKING", 9 to "BIKING_STATIONARY",
            10 to "BOOT_CAMP", 11 to "BOXING", 13 to "CALISTHENICS", 14 to "CRICKET", 16 to "DANCING", 25 to "ELLIPTICAL",
            26 to "EXERCISE_CLASS", 27 to "FENCING", 28 to "FOOTBALL_AMERICAN", 29 to "FOOTBALL_AUSTRALIAN", 31 to "FRISBEE_DISC",
            32 to "GOLF", 33 to "GUIDED_BREATHING", 34 to "GYMNASTICS", 35 to "HANDBALL", 36 to "HIGH_INTENSITY_INTERVAL_TRAINING",
            37 to "HIKING", 38 to "ICE_HOCKEY", 39 to "ICE_SKATING", 44 to "MARTIAL_ARTS", 46 to "PADDLING", 47 to "PARAGLIDING",
            48 to "PILATES", 50 to "RACQUETBALL", 51 to "ROCK_CLIMBING", 52 to "ROLLER_HOCKEY", 53 to "ROWING", 54 to "ROWING_MACHINE",
            55 to "RUGBY", 56 to "RUNNING", 57 to "RUNNING_TREADMILL", 58 to "SAILING", 59 to "SCUBA_DIVING", 60 to "SKATING",
            61 to "SKIING", 62 to "SNOWBOARDING", 63 to "SNOWSHOEING", 64 to "SOCCER", 65 to "SOFTBALL", 66 to "SQUASH",
            68 to "STAIR_CLIMBING", 69 to "STAIR_CLIMBING_MACHINE", 70 to "STRENGTH_TRAINING", 71 to "STRETCHING", 72 to "SURFING",
            73 to "SWIMMING_OPEN_WATER", 74 to "SWIMMING_POOL", 75 to "TABLE_TENNIS", 76 to "TENNIS", 78 to "VOLLEYBALL", 79 to "WALKING",
            80 to "WATER_POLO", 81 to "WEIGHTLIFTING", 82 to "WHEELCHAIR", 83 to "YOGA",
        )
    }
}
