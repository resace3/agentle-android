package dev.agentle.connectors.android.core

import dev.agentle.connectors.api.CoverageEndCause
import dev.agentle.connectors.api.WriteBatch
import dev.agentle.connectors.api.WriteResult
import dev.agentle.core.model.PersonalEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** A per-source write budget: [burst] rows at once, refilled at [perHour] rows per hour (red team lifecycle-battery-17). */
public data class RateLimit(val burst: Int, val perHour: Int) {
    init {
        require(burst > 0 && perHour > 0) { "A rate limit must allow rows" }
    }
}

/** One observation of a change-detected state, written through [StateStream.record] now or later. */
public class StateObservation(
    public val stream: StateStream,
    public val state: String,
    public val recordFirst: Boolean,
    private val event: (previous: String?) -> PersonalEvent?,
) {
    public suspend fun recordNow(): WriteResult? = stream.record(state, recordFirst, event)
}

/**
 * The one batched writer of every live callback (red team lifecycle-battery-17). With notification access the listener
 * keeps the process alive around the clock, so callbacks must neither each become a transaction nor write unbounded
 * rows:
 * - every source writes through its own [Channel], whose [RateLimit] (a token bucket on the monotonic clock) drops rows
 *   over the budget and counts them; a drop closes the channel's coverage (RATE_LIMITED) until its next committed row;
 * - plain rows are coalesced by dedup key (the newest copy wins) and written together, at most [WriteBatch.MAX_ROWS]
 *   per transaction, [flushDelay] after the first pending one, when full, or on [flush];
 * - state observations keep only the newest per stream, and one equal to the state last written costs nothing; a flush
 *   records each changed stream once (row and state in one transaction).
 * A batch that is not committed (stale epoch, rejected, unavailable) is skipped with every later chunk, and closes the
 * sources in it own (only call state owns some, see [CoverageIds]); the next committed write reopens it. Nothing retries
 * in a loop. Periodic sweeps do not go through here: their schedule bounds them.
 */
public class LiveWriter(private val runtime: CollectorRuntime, private val flushDelay: Duration = DEFAULT_FLUSH_DELAY) {
    private class PendingRow(val channel: Channel, val event: PersonalEvent)

    private class PendingObservation(val channel: Channel, val observation: StateObservation)

    private val lock = Mutex()
    private val writeLock = Mutex()
    private val rows = LinkedHashMap<String, PendingRow>()
    private val observations = LinkedHashMap<StateStream, PendingObservation>()
    private var pendingEpoch: Long? = null
    private var flushJob: Job? = null

    /** A source's handle. */
    public inner class Channel internal constructor(
        public val sourceId: String,
        private val limit: RateLimit,
        internal val coverageIds: List<String>,
    ) {
        private var tokens: Double = limit.burst.toDouble()
        private var refilledAt: Duration? = null
        internal var gapOpen: Boolean = false
        internal var droppedSinceCheck: Boolean = false
        internal var lastHeartbeat: Duration? = null

        /** Rows dropped by the rate limit so far. */
        public var dropped: Int = 0
            private set

        public suspend fun submit(events: Collection<PersonalEvent>): Int = this@LiveWriter.submit(this, events)

        public suspend fun observe(observation: StateObservation): Boolean = this@LiveWriter.observe(this, observation)

        /** Drops this source's pending rows and observations (delete-all). */
        public suspend fun discard(): Unit = this@LiveWriter.discard(this)

        public suspend fun flush(): Int = this@LiveWriter.flush()

        internal fun take(): Boolean {
            val now = runtime.clock.elapsed()
            val last = refilledAt
            if (last != null && now > last) {
                tokens = minOf(limit.burst.toDouble(), tokens + (now - last) / 1.hours * limit.perHour)
            }
            refilledAt = now
            if (tokens < 1.0) {
                dropped += 1
                droppedSinceCheck = true
                return false
            }
            tokens -= 1.0
            return true
        }
    }

    public fun channel(sourceId: String, limit: RateLimit = DEFAULT_LIMIT, coverageIds: List<String> = emptyList()): Channel =
        Channel(sourceId, limit, coverageIds)

    /** Number of pending rows and observations (tests). */
    public suspend fun pendingCount(): Int = lock.withLock { rows.size + observations.size }

    private suspend fun submit(channel: Channel, events: Collection<PersonalEvent>): Int {
        if (events.isEmpty()) return 0
        val epoch = runtime.writer.epochSafely()
        var accepted = 0
        var full = false
        lock.withLock {
            events.forEach { event ->
                if (event.dedupKey in rows || channel.take()) {
                    if (rows.isEmpty() && observations.isEmpty()) pendingEpoch = epoch
                    rows[event.dedupKey] = PendingRow(channel, event)
                    accepted += 1
                }
            }
            full = rows.size >= WriteBatch.MAX_ROWS
            if (accepted > 0 && !full) scheduleFlush()
        }
        closeIfDropped(channel)
        if (full) flush()
        return accepted
    }

    /** A dropped row is a coverage gap (RATE_LIMITED) until the next committed row of the channel reopens it. */
    private suspend fun closeIfDropped(channel: Channel) {
        val newGap = lock.withLock {
            val dropped = channel.droppedSinceCheck
            channel.droppedSinceCheck = false
            dropped && channel.coverageIds.isNotEmpty() && !channel.gapOpen.also { if (dropped) channel.gapOpen = true }
        }
        if (newGap) runtime.coverage.close(channel.coverageIds, runtime.clock.now(), CoverageEndCause.RATE_LIMITED)
    }

    private suspend fun observe(channel: Channel, observation: StateObservation): Boolean {
        if (observation.stream.isLast(observation.state)) return false
        val accepted = lock.withLock {
            val replaces = observation.stream in observations
            if (!replaces && !channel.take()) return@withLock false
            observations[observation.stream] = PendingObservation(channel, observation)
            scheduleFlush()
            true
        }
        closeIfDropped(channel)
        return accepted
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = runtime.scope.launch {
            delay(flushDelay)
            flush()
        }
    }

    private suspend fun discard(channel: Channel) {
        lock.withLock {
            rows.entries.removeAll { it.value.channel === channel }
            observations.entries.removeAll { it.value.channel === channel }
            if (rows.isEmpty()) pendingEpoch = null
        }
    }

    /** Writes everything pending now. Returns the rows written. */
    public suspend fun flush(): Int = writeLock.withLock {
        val (pendingObservations, pendingRows, epoch) = lock.withLock {
            val o = observations.values.toList()
            val r = rows.values.toList()
            val e = pendingEpoch
            observations.clear()
            rows.clear()
            pendingEpoch = null
            Triple(o, r, e)
        }
        var written = 0
        pendingObservations.forEach { pending ->
            val result = pending.observation.recordNow()
            written += StateStream.committedRows(result)
            if (result != null) afterWrite(listOf(pending.channel), result)
        }
        if (pendingRows.isNotEmpty()) {
            val batchEpoch = epoch ?: runtime.writer.epochSafely() ?: 0L
            val chunks = pendingRows.chunked(WriteBatch.MAX_ROWS)
            for ((index, chunk) in chunks.withIndex()) {
                val result = runtime.writer.writeSafely(WriteBatch(epoch = batchEpoch, events = chunk.map { it.event }))
                written += StateStream.committedRows(result)
                if (result is WriteResult.Committed) {
                    afterWrite(chunk.map { it.channel }.distinct(), result)
                    continue
                }
                // Not retried in a loop: every channel with rows in this or a later chunk records the gap, and the
                // skipped rows are counted and logged (never dropped silently).
                val remaining = chunks.drop(index).flatten()
                afterWrite(remaining.map { it.channel }.distinct(), gapResult(result))
                runtime.logger.w(COMPONENT, "Live rows skipped", fields = mapOf("rows" to remaining.size.toString()))
                break
            }
        }
        written
    }

    /** Rejected or stale batches are gaps as much as an unavailable database is. */
    private fun gapResult(result: WriteResult): WriteResult = when (result) {
        is WriteResult.Unavailable -> result
        else -> WriteResult.Unavailable(dev.agentle.core.common.AppError.DatabaseError("live_batch_not_committed"))
    }

    private suspend fun afterWrite(channels: List<Channel>, result: WriteResult) {
        val now = runtime.clock.now()
        val elapsed = runtime.clock.elapsed()
        channels.filter { it.coverageIds.isNotEmpty() }.forEach { channel ->
            when (result) {
                is WriteResult.Committed -> if (channel.gapOpen) {
                    channel.gapOpen = false
                    runtime.coverage.open(channel.coverageIds, now)
                } else if (channel.lastHeartbeat.let { it == null || elapsed - it >= HEARTBEAT_INTERVAL }) {
                    channel.lastHeartbeat = elapsed
                    runtime.coverage.heartbeat(channel.coverageIds, now)
                }

                is WriteResult.Unavailable -> if (!channel.gapOpen) {
                    channel.gapOpen = true
                    runtime.coverage.close(channel.coverageIds, now, CoverageEndCause.DATABASE_UNAVAILABLE)
                }

                WriteResult.Rejected, WriteResult.StaleEpoch -> Unit
            }
        }
        if (result !is WriteResult.Committed) {
            runtime.logger.w(COMPONENT, "Live batch not written", fields = mapOf("result" to result::class.simpleName))
        }
    }

    public companion object {
        private const val COMPONENT = "collectors.live"
        public val DEFAULT_FLUSH_DELAY: Duration = 5.seconds
        private val HEARTBEAT_INTERVAL = 60.seconds

        /** Default budget: 6 rows at once, 12 per hour (at most 102 rows in 8 hours). */
        public val DEFAULT_LIMIT: RateLimit = RateLimit(burst = 6, perHour = 12)
    }
}
