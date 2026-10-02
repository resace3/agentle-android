package dev.agentle.connectors.android.collectors.usage

import dev.agentle.connectors.android.core.AndroidConnector
import dev.agentle.connectors.android.core.AndroidConnectorIds
import dev.agentle.connectors.android.core.AndroidSources
import dev.agentle.connectors.android.core.CollectOutcome
import dev.agentle.connectors.android.core.CollectorRuntime
import dev.agentle.connectors.android.core.CoverageIds
import dev.agentle.connectors.android.core.cursorSafely
import dev.agentle.connectors.android.core.epochSafely
import dev.agentle.connectors.android.core.importFloorSafely
import dev.agentle.connectors.android.core.writeChunked
import dev.agentle.connectors.android.core.writeSafely
import dev.agentle.connectors.api.CapabilityIds
import dev.agentle.connectors.api.CapabilityStatusProvider
import dev.agentle.connectors.api.CoverageEndCause
import dev.agentle.connectors.api.SyncCursor
import dev.agentle.connectors.api.SyncTrigger
import dev.agentle.connectors.api.WriteBatch
import dev.agentle.connectors.api.WriteResult
import dev.agentle.core.common.AppError
import dev.agentle.core.model.CapabilityStatus
import dev.agentle.core.model.EventType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * Usage events, foreground sessions, screen interactive/keyguard and device startup/shutdown from
 * `UsageStatsManager.queryEvents` (docs/ARCHITECTURE.md §6.4 row 1, docs/research/02 A1-A4).
 *
 * High-water mark (red team database-sync-13): stored as `(wall_ms, elapsed_ms, boot_count)` in the `("android",
 * "usage")` cursor. Each sweep reads `[mark - 10 min, now)`; a mark later than now + 5 min (the wall clock went back) is
 * clamped to now - overlap and a coverage gap (CLOCK_CHANGED) is recorded, before each sweep and from [clampHighWaterMark]
 * at boot and on TIME_SET. `queryEvents` is never called with begin >= end. A null result (user locked) leaves the mark
 * unchanged. Writes go through the serialized writer in transactions of at most 500 rows, the cursor with the last one.
 */
public class UsageConnector(
    runtime: CollectorRuntime,
    permissions: CapabilityStatusProvider,
    private val source: UsageEventSource,
    private val bootCounts: BootCountSource,
    categories: AppCategorySource,
) : AndroidConnector(
    id = AndroidConnectorIds.USAGE,
    name = "App usage and screen",
    supportedEventTypes = setOf(
        EventType.APP_FOREGROUND,
        EventType.APP_BACKGROUND,
        EventType.APP_SESSION,
        EventType.SCREEN_ON,
        EventType.SCREEN_OFF,
        EventType.SCREEN_SESSION,
        EventType.DEVICE_UNLOCK,
        EventType.DEVICE_LOCK,
        EventType.BOOT_COMPLETED,
        EventType.SHUTDOWN,
        EventType.STANDBY_BUCKET_CHANGED,
    ),
    capabilityIds = listOf(
        CapabilityIds.APP_USAGE_EVENTS,
        CapabilityIds.SCREEN_INTERACTIVE_EVENTS,
        CapabilityIds.UNLOCK_KEYGUARD_EVENTS,
        CapabilityIds.BOOT_SHUTDOWN_EVENTS,
    ),
    runtime = runtime,
    permissions = permissions,
) {
    private val mapper = UsageMapper(runtime.events, categories)
    private val markLock = Mutex()

    override val requiredCapabilityIds: List<String> = listOf(CapabilityIds.APP_USAGE_EVENTS)

    override val coverageIds: List<String> = CoverageIds.USAGE

    override suspend fun collect(trigger: SyncTrigger, statuses: Map<String, CapabilityStatus>): CollectOutcome = markLock.withLock {
        val epoch = runtime.writer.epochSafely() ?: return@withLock unavailable()
        val stored = runtime.writer.cursorSafely(AndroidSources.CURSOR_CONNECTOR, STREAM)
        val now = runtime.clock.now()
        val nowMs = now.toEpochMilliseconds()
        var mark = UsageMark.decode(stored?.lastSuccessCursor)
        if (mark != null && isInFuture(mark, nowMs)) {
            mark = clamp(mark, nowMs)
            runtime.coverage.close(coverageIds, now, CoverageEndCause.CLOCK_CHANGED)
        }
        val floorMs = runtime.writer.importFloorSafely(AndroidSources.USAGE)?.toEpochMilliseconds() ?: Long.MIN_VALUE
        val beginMs = maxOf(mark?.wallMs?.minus(OVERLAP_MS) ?: (nowMs - INITIAL_BACKFILL_MS), floorMs)
        if (beginMs >= nowMs) return@withLock CollectOutcome.EMPTY
        val raw = source.query(beginMs, nowMs) ?: return@withLock CollectOutcome(error = AppError.Unexpected("usage_events_unavailable"))
        val window = mapper.map(raw, sinceMs = mark?.wallMs, open = mark?.open.orEmpty(), screenOnMs = mark?.screenOnMs)
        val next = UsageMark(
            wallMs = nowMs,
            elapsedMs = runtime.clock.elapsed().inWholeMilliseconds,
            bootCount = bootCounts.bootCount(),
            open = window.open,
            screenOnMs = window.screenOnMs,
        )
        val cursor = (stored ?: SyncCursor(AndroidSources.CURSOR_CONNECTOR, STREAM)).copy(
            lastSuccessCursor = next.encode(),
            lastAttemptCursor = next.encode(),
            syncStartedAt = now,
            syncFinishedAt = runtime.clock.now(),
            lastErrorCode = null,
        )
        val writes = runtime.writeChunked(coverageIds, epoch, window.events, cursor)
        CollectOutcome(fetched = raw.size, committed = writes.written, partial = writes.cursorRejected, error = writes.error)
    }

    /**
     * Clamps a high-water mark later than now + 5 min to now - overlap and records a coverage gap (red team
     * database-sync-13). Called at boot and on TIME_SET; every sweep also does it. Returns whether it clamped.
     */
    public suspend fun clampHighWaterMark(): Boolean = markLock.withLock {
        val stored = runtime.writer.cursorSafely(AndroidSources.CURSOR_CONNECTOR, STREAM) ?: return@withLock false
        val mark = UsageMark.decode(stored.lastSuccessCursor) ?: return@withLock false
        val now = runtime.clock.now()
        val nowMs = now.toEpochMilliseconds()
        if (!isInFuture(mark, nowMs)) return@withLock false
        val epoch = runtime.writer.epochSafely() ?: return@withLock false
        val clamped = clamp(mark, nowMs)
        val cursor = stored.copy(lastSuccessCursor = clamped.encode(), syncFinishedAt = now)
        val result = runtime.writer.writeSafely(WriteBatch(epoch = epoch, cursor = cursor))
        runtime.coverage.close(coverageIds, now, CoverageEndCause.CLOCK_CHANGED)
        result is WriteResult.Committed
    }

    private fun clamp(mark: UsageMark, nowMs: Long): UsageMark = mark.copy(
        wallMs = nowMs - OVERLAP_MS,
        elapsedMs = runtime.clock.elapsed().inWholeMilliseconds,
        bootCount = bootCounts.bootCount(),
        // Sessions opened "in the future" cannot be closed correctly any more.
        open = mark.open.filter { it.startMs <= nowMs },
        screenOnMs = mark.screenOnMs?.takeIf { it <= nowMs },
    )

    private fun unavailable(): CollectOutcome = CollectOutcome(error = AppError.DatabaseError("writer_unavailable"))

    public companion object {
        public const val STREAM: String = "usage"

        /** Re-read overlap of every sweep (docs/ARCHITECTURE.md §6.4). */
        public val OVERLAP_MS: Long = 10.minutes.inWholeMilliseconds

        /** A mark more than this ahead of now is in the future (the wall clock was set back). */
        public val FUTURE_TOLERANCE_MS: Long = 5.minutes.inWholeMilliseconds

        /** First sweep: the system keeps usage events only for a few days (docs/research/02 A1). */
        public val INITIAL_BACKFILL_MS: Long = 3.days.inWholeMilliseconds

        public fun isInFuture(mark: UsageMark, nowMs: Long): Boolean = mark.wallMs > nowMs + FUTURE_TOLERANCE_MS
    }
}
