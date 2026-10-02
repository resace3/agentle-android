package dev.agentle.connectors.googlehealth

import dev.agentle.connectors.api.EventSink
import dev.agentle.connectors.api.StreamCoverage
import dev.agentle.connectors.api.SyncCursor
import dev.agentle.connectors.api.SyncTrigger
import dev.agentle.core.common.Logger
import dev.agentle.core.model.ConnectorIds
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.time.AgentleClock
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** The outcome of one stream in one run; counts cover what was committed (or attempted) in that run. */
internal sealed interface GhStreamOutcome {
    val fetched: Int
    val committed: Int
    val skipped: Int

    data class Done(override val fetched: Int, override val committed: Int, override val skipped: Int, val coverage: Instant?) :
        GhStreamOutcome

    data class Failed(
        val failure: GhFailure,
        override val fetched: Int,
        override val committed: Int,
        override val skipped: Int,
        val coverage: Instant?,
    ) : GhStreamOutcome

    /** Another run moved the cursor first (generation check); this run stopped for the stream, which is not an error. */
    data class Superseded(override val fetched: Int, override val committed: Int, override val skipped: Int) : GhStreamOutcome
}

/** Everything one run shares across its streams. */
internal class GhRun(
    val api: GhApi,
    val fetcher: GhFetcher,
    val mapper: GhMapper,
    val accountId: String,
    val zone: TimeZone,
    val trigger: SyncTrigger,
    val now: Instant,
) {
    /** Last upload of the user's tracker and scale, from `pairedDevices` in this run (null when unknown). */
    var trackerLastSync: Instant? = null
    var scaleLastSync: Instant? = null
}

/**
 * Syncs one stream (docs/research/05 §5.6, §7.4; round-2 corrections 4 to 6). Each fetched window whose pages all
 * succeeded is written with [EventSink.replaceWindow] together with its cursor (compare-and-set on the generation read
 * before fetching) and, for forward windows, the stream's coverage; a failed window writes nothing but a failure
 * cursor that keeps the position. Windows walk forward, then the backfill walks down.
 */
internal class GhSyncEngine(private val sink: EventSink, private val clock: AgentleClock, private val config: GoogleHealthConfig) {
    suspend fun sync(stream: GhStream, run: GhRun, logger: Logger): GhStreamOutcome =
        if (stream.kind == GhKind.DEVICES) devices(stream, run) else timeSeries(stream, run, logger)

    @Suppress("LongMethod")
    private suspend fun timeSeries(stream: GhStream, run: GhRun, logger: Logger): GhStreamOutcome {
        val stored = sink.cursor(CONNECTOR, stream.id)
        val own = stored?.takeIf { it.accountId == run.accountId }
        val floor = sink.importFloor(stream.source)
        var state = GhStreamState.decode(own?.lastSuccessCursor)
        val plan = GhPlanner.plan(stream, state, run.trigger, run.now, floor, run.zone, config)
        val deviceSync = when (stream.device) {
            GhDeviceClass.TRACKER -> run.trackerLastSync
            GhDeviceClass.SCALE -> run.scaleLastSync
            GhDeviceClass.NONE -> null
        }
        val tally = Tally(stored?.generation ?: 0L)
        val streamRun = GhFetcher.StreamRun()

        suspend fun write(window: GhWindow, next: GhStreamState, coverage: StreamCoverage?): Boolean {
            val cursor = SyncCursor(
                connectorId = CONNECTOR,
                stream = stream.id,
                lastSuccessCursor = next.encode(),
                syncStartedAt = run.now,
                syncFinishedAt = clock.now(),
                accountId = run.accountId,
                generation = tally.generation,
            )
            val result = sink.replaceWindow(
                stream.source,
                window.range.start,
                window.range.end,
                window.events,
                cursor,
                coverage,
                run.accountId,
            )
            if (result.rejected) {
                tally.superseded = true
                return false
            }
            tally.generation++
            tally.fetched += window.fetched
            tally.skipped += window.skipped
            tally.committed += result.written
            state = next
            return true
        }

        var through = plan.baseThrough
        val lastForward = plan.forward.lastOrNull()
        val forwardFailure = run.fetcher.fetchAll(stream, plan.forward, streamRun) { window ->
            val reached = minOf(window.range.end, run.now)
            through = through?.let { maxOf(it, reached) } ?: reached
            val position = requireNotNull(through)
            val next = state.copy(
                through = position,
                backfilledFrom = minOf(state.backfilledFrom ?: plan.forward.first().start, plan.forward.first().start),
                fetchedAt = run.now,
                deepResyncAt = if (plan.firstSync || (plan.deep && window.range == lastForward)) run.now else state.deepResyncAt,
                deviceLastSync = deviceSync,
            )
            val covered = deviceSync?.let { minOf(position, it) } ?: position
            tally.coverage = maxOf(tally.coverage ?: covered, covered)
            write(window, next, StreamCoverage(CONNECTOR, stream.id, covered, run.accountId, deviceSync))
        }
        val failure = forwardFailure ?: if (tally.superseded) {
            null
        } else {
            run.fetcher.fetchAll(stream, plan.backward, streamRun) { window ->
                write(window, state.copy(backfilledFrom = minOf(window.range.start, state.backfilledFrom ?: window.range.start)), null)
            }
        }
        if (tally.superseded) {
            logger.i(GhApi.COMPONENT, "cursor moved by another run", mapOf("stream" to stream.id))
            return GhStreamOutcome.Superseded(tally.fetched, tally.committed, tally.skipped)
        }
        if (failure != null) {
            writeFailure(stream, run, state, tally.generation, failure)
            return GhStreamOutcome.Failed(failure, tally.fetched, tally.committed, tally.skipped, tally.coverage)
        }
        return GhStreamOutcome.Done(tally.fetched, tally.committed, tally.skipped, tally.coverage)
    }

    private class Tally(var generation: Long) {
        var fetched = 0
        var committed = 0
        var skipped = 0
        var coverage: Instant? = null
        var superseded = false
    }

    /** The paired devices (battery, last upload); their last uploads bound the other streams' coverage in this run. */
    private suspend fun devices(stream: GhStream, run: GhRun): GhStreamOutcome {
        val stored = sink.cursor(CONNECTOR, stream.id)
        val generation = stored?.generation ?: 0L
        val (points, failure) = run.fetcher.pairedDevices()
        if (failure != null) {
            writeFailure(
                stream,
                run,
                GhStreamState.decode(
                    stored?.takeIf {
                        it.accountId == run.accountId
                    }?.lastSuccessCursor,
                ),
                generation,
                failure,
            )
            return GhStreamOutcome.Failed(failure, 0, 0, 0, null)
        }
        val events = ArrayList<PersonalEvent>()
        var skipped = 0
        points.forEach { point ->
            val (mapped, device) = run.mapper.device(stream, point)
            when (mapped) {
                is Mapped.Event -> events += mapped.event
                is Mapped.Skip -> skipped++
            }
            val lastSync = device?.lastSync ?: return@forEach
            when (device.deviceType) {
                TRACKER -> run.trackerLastSync = maxOf(run.trackerLastSync ?: lastSync, lastSync)
                SCALE -> run.scaleLastSync = maxOf(run.scaleLastSync ?: lastSync, lastSync)
            }
        }
        val cursor = SyncCursor(
            connectorId = CONNECTOR,
            stream = stream.id,
            lastSuccessCursor = GhStreamState(through = run.now, fetchedAt = run.now).encode(),
            syncStartedAt = run.now,
            syncFinishedAt = clock.now(),
            accountId = run.accountId,
            generation = generation,
        )
        val result = sink.commit(events.distinctBy { it.dedupKey }, cursor)
        if (result.rejected) return GhStreamOutcome.Superseded(points.size, 0, skipped)
        return GhStreamOutcome.Done(points.size, result.written, skipped, null)
    }

    /** Keeps the stream's position and records the error code (never a message). */
    private suspend fun writeFailure(stream: GhStream, run: GhRun, state: GhStreamState, generation: Long, failure: GhFailure) {
        val cursor = SyncCursor(
            connectorId = CONNECTOR,
            stream = stream.id,
            lastSuccessCursor = state.encode(),
            syncStartedAt = run.now,
            syncFinishedAt = clock.now(),
            lastErrorCode = failure.toAppError(stream.id).code,
            accountId = run.accountId,
            generation = generation,
        )
        sink.commit(emptyList(), cursor)
    }

    companion object {
        const val CONNECTOR: String = ConnectorIds.GOOGLE_HEALTH
        private const val TRACKER = "TRACKER"
        private const val SCALE = "SCALE"
    }
}
