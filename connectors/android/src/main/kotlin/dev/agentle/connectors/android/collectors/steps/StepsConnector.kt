package dev.agentle.connectors.android.collectors.steps

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.fitness.FitnessLocal
import com.google.android.gms.fitness.data.LocalDataType
import com.google.android.gms.fitness.data.LocalField
import com.google.android.gms.fitness.request.LocalDataReadRequest
import dev.agentle.connectors.android.core.AndroidConnector
import dev.agentle.connectors.android.core.AndroidConnectorIds
import dev.agentle.connectors.android.core.AndroidSources
import dev.agentle.connectors.android.core.CollectOutcome
import dev.agentle.connectors.android.core.CollectorRuntime
import dev.agentle.connectors.android.core.CoverageIds
import dev.agentle.connectors.android.core.cursorSafely
import dev.agentle.connectors.android.core.epochSafely
import dev.agentle.connectors.android.core.importFloorSafely
import dev.agentle.connectors.android.core.writeWindows
import dev.agentle.connectors.api.CapabilityIds
import dev.agentle.connectors.api.CapabilityStatusProvider
import dev.agentle.connectors.api.ReplaceWindow
import dev.agentle.connectors.api.SyncCursor
import dev.agentle.connectors.api.SyncTrigger
import dev.agentle.connectors.api.WriteBatch
import dev.agentle.core.common.AppError
import dev.agentle.core.model.CapabilityStatus
import dev.agentle.core.model.EventType
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.StepsPayload
import kotlinx.coroutines.tasks.await
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Steps counted in one bucket `[start, end)`. */
public data class StepBucket(val start: Instant, val end: Instant, val steps: Long)

/** The Recording API (`LocalRecordingClient`) behind a seam. Calls throw `SecurityException` without ACTIVITY_RECOGNITION. */
public interface StepsRecordingGateway {
    /** Subscribes to `TYPE_STEP_COUNT_DELTA` (idempotent); false when Play services refused. */
    public suspend fun subscribe(): Boolean

    /** Aggregated step buckets of [bucket] length over `[start, end)`. */
    public suspend fun readBuckets(start: Instant, end: Instant, bucket: Duration): List<StepBucket>
}

/**
 * [StepsRecordingGateway] over `FitnessLocal.getLocalRecordingClient` (play-services-fitness 21.2.0, the version the
 * guide uses; docs/research/03 §5.3). Values are read through `LocalValue.toString()`, as the official sample does;
 * that this is the plain number is UNVERIFIED (a non-numeric value is skipped).
 */
public class RecordingApiStepsGateway(private val context: Context) : StepsRecordingGateway {
    @SuppressLint("MissingPermission") // The connector runs only with ACTIVITY_RECOGNITION; a SecurityException is handled there.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun subscribe(): Boolean = try {
        FitnessLocal.getLocalRecordingClient(context).subscribe(LocalDataType.TYPE_STEP_COUNT_DELTA).await()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: SecurityException) {
        throw e
    } catch (ignored: Exception) {
        false
    }

    @SuppressLint("MissingPermission")
    override suspend fun readBuckets(start: Instant, end: Instant, bucket: Duration): List<StepBucket> {
        val request = LocalDataReadRequest.Builder()
            .aggregate(LocalDataType.TYPE_STEP_COUNT_DELTA)
            .bucketByTime(bucket.inWholeMinutes.toInt(), TimeUnit.MINUTES)
            .setTimeRange(start.epochSeconds, end.epochSeconds, TimeUnit.SECONDS)
            .build()
        val response = FitnessLocal.getLocalRecordingClient(context).readData(request).await()
        return response.buckets.mapNotNull { localBucket ->
            val steps = localBucket.dataSets.flatMap { it.dataPoints }.sumOf { point ->
                point.getValue(LocalField.FIELD_STEPS).toString().trim().toLongOrNull() ?: 0L
            }
            StepBucket(
                start = Instant.fromEpochMilliseconds(localBucket.getStartTime(TimeUnit.MILLISECONDS)),
                end = Instant.fromEpochMilliseconds(localBucket.getEndTime(TimeUnit.MILLISECONDS)),
                steps = steps,
            )
        }
    }
}

/**
 * Phone steps from the Recording API (§6.4 "Steps", docs/research/03 §5.3): used only while Health Connect on-device
 * steps are unavailable, never in addition to them. Each sweep re-reads `[last end - 2 h, now)` (the first one the
 * 10-day buffer), clamped to the import floor, in 15-minute buckets, and replaces that window per local day (the
 * clock's zone): a bucket that grew is updated, nothing is ever summed twice. Rows are STEP_SAMPLE keyed
 * `steps|rec|<bucketStartMs>`; empty buckets are not stored.
 */
public class StepsConnector(runtime: CollectorRuntime, permissions: CapabilityStatusProvider, private val gateway: StepsRecordingGateway) :
    AndroidConnector(
        id = AndroidConnectorIds.STEPS,
        name = "Steps (phone)",
        supportedEventTypes = setOf(EventType.STEP_SAMPLE),
        capabilityIds = listOf(CapabilityIds.STEP_COUNT_RECORDING_API),
        runtime = runtime,
        permissions = permissions,
    ) {
    override val coverageIds: List<String> = CoverageIds.STEPS

    /** Health Connect on-device steps, when available, replace the Recording API (never both). */
    override val observedCapabilityIds: List<String> = listOf(CapabilityIds.HEALTH_CONNECT_ON_DEVICE_STEPS)

    override suspend fun collect(trigger: SyncTrigger, statuses: Map<String, CapabilityStatus>): CollectOutcome {
        if (statuses[CapabilityIds.HEALTH_CONNECT_ON_DEVICE_STEPS]?.state?.canCollect == true) return CollectOutcome(covered = false)
        if (!gateway.subscribe()) return CollectOutcome(error = AppError.Unexpected("recording_api_subscribe_failed"))
        val epoch = runtime.writer.epochSafely() ?: return CollectOutcome(error = AppError.DatabaseError("writer_unavailable"))
        val stored = runtime.writer.cursorSafely(AndroidSources.CURSOR_CONNECTOR, STREAM)
        val now = runtime.clock.now()
        val nowMs = now.toEpochMilliseconds()
        val lastEndMs = stored?.lastSuccessCursor?.toLongOrNull()?.takeIf { it <= nowMs }
        val floorMs = runtime.writer.importFloorSafely(AndroidSources.STEPS)?.toEpochMilliseconds() ?: Long.MIN_VALUE
        val fromMs = maxOf(lastEndMs?.minus(OVERLAP.inWholeMilliseconds) ?: (nowMs - BUFFER.inWholeMilliseconds), floorMs)
        val startMs = ceilToBucket(fromMs)
        if (startMs >= nowMs) return CollectOutcome.EMPTY
        val start = Instant.fromEpochMilliseconds(startMs)
        val buckets = gateway.readBuckets(start, now, BUCKET).filter { it.start >= start && it.start < now }
        val events = buckets.filter { it.steps > 0 }.map(::event)
        val cursor = (stored ?: SyncCursor(AndroidSources.CURSOR_CONNECTOR, STREAM)).copy(
            lastSuccessCursor = nowMs.toString(),
            syncStartedAt = now,
            syncFinishedAt = runtime.clock.now(),
            lastErrorCode = null,
        )
        val writes = runtime.writeWindows(coverageIds, epoch, dayWindows(start, now, events, runtime.clock.zone()), cursor)
        return CollectOutcome(fetched = buckets.size, committed = writes.written, partial = writes.cursorRejected, error = writes.error)
    }

    private fun event(bucket: StepBucket): PersonalEvent = runtime.events.create(
        type = EventType.STEP_SAMPLE,
        source = AndroidSources.STEPS,
        start = bucket.start,
        end = bucket.end,
        payload = StepsPayload(bucket.steps),
        dedupKey = "steps|rec|${bucket.start.toEpochMilliseconds()}",
        origin = ORIGIN,
    )

    public companion object {
        public const val STREAM: String = "steps"
        public const val ORIGIN: String = "recording_api"
        public val BUCKET: Duration = 15.minutes
        public val OVERLAP: Duration = 2.hours

        /** "Data since the latest subscription - for up to 10 days - is accessible" (docs/research/03 §5.3). */
        public val BUFFER: Duration = 10.days - 1.hours

        public fun ceilToBucket(epochMs: Long): Long {
            val size = BUCKET.inWholeMilliseconds
            return Math.floorDiv(epochMs + size - 1, size) * size
        }

        /** Splits `[start, end)` at local midnights of [zone]; each window replaces its own events (at most 96 rows). */
        public fun dayWindows(
            start: Instant,
            end: Instant,
            events: List<PersonalEvent>,
            zone: TimeZone,
        ): List<Pair<ReplaceWindow, List<PersonalEvent>>> {
            val windows = ArrayList<Pair<ReplaceWindow, List<PersonalEvent>>>()
            var from = start
            while (from < end) {
                val nextMidnight = from.toLocalDateTime(zone).date.plus(DatePeriod(days = 1)).atStartOfDayIn(zone)
                val to = minOf(nextMidnight, end)
                // 15-minute buckets give at most 100 rows per local day (25-hour DST days), far below the row limit.
                val inWindow = events.filter { it.startTime >= from && it.startTime < to }.take(WriteBatch.MAX_ROWS)
                windows += ReplaceWindow(AndroidSources.STEPS, from, to) to inWindow
                from = to
            }
            return windows
        }
    }
}
