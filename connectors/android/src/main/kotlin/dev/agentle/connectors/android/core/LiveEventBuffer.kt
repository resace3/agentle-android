package dev.agentle.connectors.android.core

import dev.agentle.connectors.api.CoverageEndCause
import dev.agentle.connectors.api.SyncCursor
import dev.agentle.connectors.api.WriteBatch
import dev.agentle.connectors.api.WriteResult
import dev.agentle.core.model.PersonalEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The cursor a live source commits with its events (for example the notification listener's active-key state). */
public interface LiveCursor {
    /** The cursor for the next write, carrying the generation last read or written (compare-and-set). */
    public suspend fun cursorForWrite(): SyncCursor?

    /** The write committed: the stored generation moved one step. */
    public fun onCommitted()

    /** The generation check failed: re-read the stored cursor's generation before the retry. */
    public suspend fun onRejected()

    /** A deletion changed the data epoch: forget everything collected before it. */
    public suspend fun onEpochChanged()
}

/**
 * In-process batching for one live source (red team lifecycle-battery-02, database-sync-11/14). Events are coalesced
 * by dedup key, so the newest copy of a key wins (5,000 updates of one notification become one row), and written as one
 * [WriteBatch] of at most [WriteBatch.MAX_ROWS] rows after [flushDelay], when full, or on [flush]. There is never one
 * work request per event.
 *
 * A batch whose data epoch went stale is dropped (nothing collected before a deletion is written after it). An
 * unavailable database skips the batch and closes the coverage of [coverageIds] once
 * ([CoverageEndCause.DATABASE_UNAVAILABLE]); the next successful write reopens it. Only a source that owns its
 * capabilities' coverage passes [coverageIds] (see [CoverageIds]); [name] is for logs. Nothing retries in a loop.
 */
public class LiveEventBuffer(
    private val name: String,
    private val coverageIds: List<String>,
    private val runtime: CollectorRuntime,
    private val cursor: LiveCursor? = null,
    private val flushDelay: Duration = 2.seconds,
) {
    private val stateLock = Mutex()
    private val writeLock = Mutex()
    private val pending = LinkedHashMap<String, PersonalEvent>()
    private var pendingEpoch: Long? = null
    private var cursorDirty = false
    private var flushJob: Job? = null
    private var gapOpen = false
    private var lastHeartbeat: Instant? = null

    /** Number of distinct dedup keys waiting for the next flush. */
    public suspend fun pendingCount(): Int = stateLock.withLock { pending.size }

    /** Queues [events] (and marks the cursor dirty); a flush follows within [flushDelay]. */
    public suspend fun submit(events: Collection<PersonalEvent>, cursorChanged: Boolean = false) {
        if (events.isEmpty() && !cursorChanged) return
        val epoch = if (events.isNotEmpty()) runtime.writer.epochSafely() else null
        var full = false
        stateLock.withLock {
            if (pending.isEmpty() && epoch != null) pendingEpoch = epoch
            events.forEach { pending[it.dedupKey] = it }
            cursorDirty = cursorDirty || cursorChanged
            full = pending.size >= WriteBatch.MAX_ROWS
            if (!full && flushJob?.isActive != true) {
                flushJob = runtime.scope.launch {
                    delay(flushDelay)
                    flush()
                }
            }
        }
        if (full) flush()
    }

    /** Drops everything pending without writing it (delete-all: nothing collected before a deletion is written). */
    public suspend fun discard() {
        stateLock.withLock {
            pending.clear()
            pendingEpoch = null
            cursorDirty = false
            flushJob?.cancel()
            flushJob = null
        }
    }

    /** Writes everything pending now, in batches of at most [WriteBatch.MAX_ROWS] rows. Returns the last result. */
    public suspend fun flush(): WriteResult? = writeLock.withLock {
        var last: WriteResult? = null
        while (true) {
            val (events, epoch, withCursor) = stateLock.withLock {
                val batch = pending.values.take(WriteBatch.MAX_ROWS)
                batch.forEach { pending.remove(it.dedupKey) }
                val e = pendingEpoch
                if (pending.isEmpty()) pendingEpoch = null
                val c = cursorDirty
                cursorDirty = false
                Triple(batch, e, c)
            }
            if (events.isEmpty() && !withCursor) break
            last = write(events, epoch ?: runtime.writer.epochSafely() ?: 0L, withCursor || cursor != null)
            if (stateLock.withLock { pending.isEmpty() }) break
        }
        last
    }

    private suspend fun write(events: List<PersonalEvent>, epoch: Long, withCursor: Boolean): WriteResult {
        var result = runtime.writer.writeSafely(batch(events, epoch, withCursor))
        if (result is WriteResult.Rejected && cursor != null) {
            cursor.onRejected()
            result = runtime.writer.writeSafely(batch(events, epoch, withCursor))
        }
        val now = runtime.clock.now()
        when (result) {
            is WriteResult.Committed -> {
                cursor?.onCommitted()
                if (gapOpen) {
                    gapOpen = false
                    runtime.coverage.open(coverageIds, now)
                } else if (lastHeartbeat.let { it == null || now - it >= HEARTBEAT_INTERVAL }) {
                    runtime.coverage.heartbeat(coverageIds, now)
                }
                lastHeartbeat = now
            }

            WriteResult.Rejected -> runtime.logger.w(COMPONENT, "Live batch rejected twice; dropped", fields = mapOf("source" to name))

            WriteResult.StaleEpoch -> {
                cursor?.onEpochChanged()
                runtime.logger.i(COMPONENT, "Live batch dropped after a deletion", fields = mapOf("source" to name))
            }

            is WriteResult.Unavailable -> if (!gapOpen) {
                gapOpen = true
                runtime.coverage.close(coverageIds, now, CoverageEndCause.DATABASE_UNAVAILABLE)
                runtime.logger.w(
                    COMPONENT,
                    "Database unavailable; live batch skipped",
                    error = result.error,
                    fields = mapOf("source" to name),
                )
            }
        }
        return result
    }

    private suspend fun batch(events: List<PersonalEvent>, epoch: Long, withCursor: Boolean): WriteBatch =
        WriteBatch(epoch = epoch, events = events, cursor = if (withCursor) cursor?.cursorForWrite() else null)

    private companion object {
        const val COMPONENT = "collectors.live"
        val HEARTBEAT_INTERVAL = 1.minutes
    }
}
