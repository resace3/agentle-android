package dev.agentle.connectors.android.collectors.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass
import kotlin.time.Instant

/**
 * [HealthConnectGateway] over `HealthConnectClient` (connect-client 1.1.0). The client is created lazily and only when
 * the SDK is available. Exceptions are not caught here except in [grantedPermissions] and [featureAvailable]: the
 * collector maps `SecurityException` (a revoked permission) and `IllegalStateException` (quota) itself.
 */
public class ClientHealthConnectGateway(private val context: Context) : HealthConnectGateway {
    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    @Suppress("TooGenericExceptionCaught")
    override fun sdkStatus(): Int = try {
        HealthConnectClient.getSdkStatus(context)
    } catch (ignored: RuntimeException) {
        HealthConnectClient.SDK_UNAVAILABLE
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun grantedPermissions(): Set<String> {
        if (sdkStatus() != HealthConnectClient.SDK_AVAILABLE) return emptySet()
        return try {
            client.permissionController.getGrantedPermissions()
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            emptySet()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun featureAvailable(feature: Int): Boolean {
        if (sdkStatus() != HealthConnectClient.SDK_AVAILABLE) return false
        return try {
            client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        } catch (ignored: RuntimeException) {
            false
        }
    }

    override suspend fun readRecords(type: HcType, start: Instant, end: Instant, pageToken: String?): HcPage = when (type) {
        HcType.STEPS -> read(StepsRecord::class, start, end, pageToken)
        HcType.DISTANCE -> read(DistanceRecord::class, start, end, pageToken)
        HcType.ACTIVE_CALORIES -> read(ActiveCaloriesBurnedRecord::class, start, end, pageToken)
        HcType.TOTAL_CALORIES -> read(TotalCaloriesBurnedRecord::class, start, end, pageToken)
        HcType.HEART_RATE -> read(HeartRateRecord::class, start, end, pageToken)
        HcType.RESTING_HEART_RATE -> read(RestingHeartRateRecord::class, start, end, pageToken)
        HcType.SLEEP -> read(SleepSessionRecord::class, start, end, pageToken)
        HcType.EXERCISE -> read(ExerciseSessionRecord::class, start, end, pageToken)
        HcType.WEIGHT -> read(WeightRecord::class, start, end, pageToken)
    }

    private suspend fun <T : Record> read(recordType: KClass<T>, start: Instant, end: Instant, pageToken: String?): HcPage {
        val request = ReadRecordsRequest(
            recordType = recordType,
            timeRangeFilter = TimeRangeFilter.between(start.toJava(), end.toJava()),
            pageSize = PAGE_SIZE,
            pageToken = pageToken,
        )
        val response = client.readRecords(request)
        // An empty page token means "no more pages" (docs/research/05).
        return HcPage(response.records.mapNotNull { toHcRecord(it) }, response.pageToken?.takeIf { it.isNotEmpty() })
    }

    override suspend fun changesToken(type: HcType): String = client.getChangesToken(ChangesTokenRequest(setOf(recordClass(type))))

    override suspend fun changes(token: String, type: HcType): HcChanges {
        val response = client.getChanges(token)
        val upserts = response.changes.filterIsInstance<UpsertionChange>().mapNotNull { toHcRecord(it.record) }.filter { it.type == type }
        val deleted = response.changes.filterIsInstance<DeletionChange>().map { it.recordId }
        return HcChanges(upserts, deleted, response.nextChangesToken, response.hasMore, response.changesTokenExpired)
    }

    public companion object {
        private const val PAGE_SIZE = 500

        public fun recordClass(type: HcType): KClass<out Record> = when (type) {
            HcType.STEPS -> StepsRecord::class
            HcType.DISTANCE -> DistanceRecord::class
            HcType.ACTIVE_CALORIES -> ActiveCaloriesBurnedRecord::class
            HcType.TOTAL_CALORIES -> TotalCaloriesBurnedRecord::class
            HcType.HEART_RATE -> HeartRateRecord::class
            HcType.RESTING_HEART_RATE -> RestingHeartRateRecord::class
            HcType.SLEEP -> SleepSessionRecord::class
            HcType.EXERCISE -> ExerciseSessionRecord::class
            HcType.WEIGHT -> WeightRecord::class
        }

        /** Maps one record; types Agentle does not read return null. */
        public fun toHcRecord(record: Record): HcRecord? = when (record) {
            is StepsRecord -> interval(
                record.metadata,
                HcType.STEPS,
                record.startTime,
                record.endTime,
                record.startZoneOffset,
                HcValue.Steps(record.count),
            )

            is DistanceRecord -> interval(
                record.metadata,
                HcType.DISTANCE,
                record.startTime,
                record.endTime,
                record.startZoneOffset,
                HcValue.Distance(record.distance.inMeters),
            )

            is ActiveCaloriesBurnedRecord -> interval(
                record.metadata,
                HcType.ACTIVE_CALORIES,
                record.startTime,
                record.endTime,
                record.startZoneOffset,
                HcValue.Calories(record.energy.inKilocalories),
            )

            is TotalCaloriesBurnedRecord -> interval(
                record.metadata,
                HcType.TOTAL_CALORIES,
                record.startTime,
                record.endTime,
                record.startZoneOffset,
                HcValue.Calories(record.energy.inKilocalories),
            )

            is HeartRateRecord -> heartRate(record)

            is RestingHeartRateRecord -> instant(
                record.metadata,
                HcType.RESTING_HEART_RATE,
                record.time,
                record.zoneOffset,
                HcValue.RestingHeartRate(record.beatsPerMinute),
            )

            is SleepSessionRecord -> interval(
                record.metadata,
                HcType.SLEEP,
                record.startTime,
                record.endTime,
                record.startZoneOffset,
                HcValue.Sleep(record.stages.map { HcSleepStage(it.startTime.toKotlin(), it.endTime.toKotlin(), it.stage) }),
            )

            is ExerciseSessionRecord -> interval(
                record.metadata,
                HcType.EXERCISE,
                record.startTime,
                record.endTime,
                record.startZoneOffset,
                HcValue.Exercise(record.exerciseType),
            )

            is WeightRecord -> instant(
                record.metadata,
                HcType.WEIGHT,
                record.time,
                record.zoneOffset,
                HcValue.Weight(record.weight.inKilograms),
            )

            else -> null
        }

        private fun heartRate(record: HeartRateRecord): HcRecord? {
            val bpm = record.samples.map { it.beatsPerMinute.toDouble() }
            if (bpm.isEmpty()) return null
            val value = HcValue.HeartRate(meanBpm = bpm.average(), minBpm = bpm.min(), maxBpm = bpm.max(), samples = bpm.size)
            return interval(record.metadata, HcType.HEART_RATE, record.startTime, record.endTime, record.startZoneOffset, value)
        }

        private fun interval(
            metadata: Metadata,
            type: HcType,
            start: java.time.Instant,
            end: java.time.Instant,
            offset: ZoneOffset?,
            value: HcValue,
        ): HcRecord = HcRecord(
            id = metadata.id,
            type = type,
            start = start.toKotlin(),
            end = end.toKotlin(),
            startOffsetSeconds = offset?.totalSeconds,
            origin = metadata.dataOrigin.packageName,
            lastModified = metadata.lastModifiedTime.toKotlin(),
            value = value,
        )

        private fun instant(metadata: Metadata, type: HcType, time: java.time.Instant, offset: ZoneOffset?, value: HcValue): HcRecord =
            HcRecord(
                id = metadata.id,
                type = type,
                start = time.toKotlin(),
                end = null,
                startOffsetSeconds = offset?.totalSeconds,
                origin = metadata.dataOrigin.packageName,
                lastModified = metadata.lastModifiedTime.toKotlin(),
                value = value,
            )
    }
}

internal fun Instant.toJava(): java.time.Instant = java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong())

internal fun java.time.Instant.toKotlin(): Instant = Instant.fromEpochSeconds(epochSecond, nano)
