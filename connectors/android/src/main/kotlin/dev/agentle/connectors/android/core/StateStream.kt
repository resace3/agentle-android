package dev.agentle.connectors.android.core

import dev.agentle.connectors.api.CoverageEndCause
import dev.agentle.connectors.api.SyncCursor
import dev.agentle.connectors.api.WriteBatch
import dev.agentle.connectors.api.WriteResult
import dev.agentle.core.model.PersonalEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A change-detected stream (DND filter, next alarm, standby bucket, Bluetooth adapter, connectivity, time zone): the
 * last recorded state lives in the stream's cursor (`("android", <stream>)`), so an event is written only when the state
 * differs, and the event and the new state are committed together (compare-and-set on the cursor generation). Process
 * death between two observations loses nothing: the next observation compares against the stored state. Repeated
 * observations of the state last written in this process skip the database entirely (callbacks such as the network
 * callback fire often). A write lost to an unavailable database closes the coverage of [coverageIds] (capability ids;
 * the owning sweep reopens it, see [CoverageIds]).
 */
public class StateStream(private val runtime: CollectorRuntime, private val stream: String, private val coverageIds: List<String>) {
    private val lock = Mutex()

    @Volatile private var lastKnown: String? = null

    /** The last recorded state, or null if none (or unreadable). */
    public suspend fun lastState(): String? = runtime.writer.cursorSafely(AndroidSources.CURSOR_CONNECTOR, stream)?.lastSuccessCursor

    /**
     * Records [state] when it differs from the stored one; [event] builds the row with the previous state (null on the
     * first observation, when [recordFirst] decides whether a row is written at all). Returns the write result, or null
     * when nothing changed.
     */
    public suspend fun record(state: String, recordFirst: Boolean = true, event: (previous: String?) -> PersonalEvent?): WriteResult? =
        lock.withLock {
            if (state == lastKnown) return@withLock null
            val epoch = runtime.writer.epochSafely() ?: return@withLock unavailable()
            val cursor = runtime.writer.cursorSafely(AndroidSources.CURSOR_CONNECTOR, stream)
            val previous = cursor?.lastSuccessCursor
            if (previous == state) {
                lastKnown = state
                return@withLock null
            }
            val row = if (previous == null && !recordFirst) null else event(previous)
            val now = runtime.clock.now()
            val next = (cursor ?: SyncCursor(AndroidSources.CURSOR_CONNECTOR, stream)).copy(
                lastSuccessCursor = state,
                syncFinishedAt = now,
                lastErrorCode = null,
            )
            var result = runtime.writer.writeSafely(WriteBatch(epoch = epoch, events = listOfNotNull(row), cursor = next))
            if (result is WriteResult.Rejected) {
                // Another writer moved the cursor: re-read once; if it already holds this state there is nothing to do.
                val reread = runtime.writer.cursorSafely(AndroidSources.CURSOR_CONNECTOR, stream)
                if (reread?.lastSuccessCursor == state) {
                    lastKnown = state
                    return@withLock null
                }
                val retry = (
                    reread ?: SyncCursor(
                        AndroidSources.CURSOR_CONNECTOR,
                        stream,
                    )
                    ).copy(lastSuccessCursor = state, syncFinishedAt = now)
                result = runtime.writer.writeSafely(WriteBatch(epoch = epoch, events = listOfNotNull(row), cursor = retry))
            }
            when (result) {
                is WriteResult.Committed -> lastKnown = state
                is WriteResult.Unavailable -> runtime.coverage.close(coverageIds, now, CoverageEndCause.DATABASE_UNAVAILABLE)
                WriteResult.Rejected, WriteResult.StaleEpoch -> Unit
            }
            result
        }

    /**
     * Records now ([via] null: a sweep) or hands the observation to a live source's [LiveWriter.Channel] (batched and
     * rate limited, red team lifecycle-battery-17); the latter returns null.
     */
    public suspend fun record(
        state: String,
        recordFirst: Boolean = true,
        via: LiveWriter.Channel?,
        event: (previous: String?) -> PersonalEvent?,
    ): WriteResult? {
        if (via == null) return record(state, recordFirst, event)
        via.observe(StateObservation(this, state, recordFirst, event))
        return null
    }

    /** Whether [state] is the state this process last wrote (observing it again changes nothing). */
    public fun isLast(state: String): Boolean = state == lastKnown

    /** Forgets the in-process state (after a deletion: the next observation is compared with the store again). */
    public fun forget() {
        lastKnown = null
    }

    private suspend fun unavailable(): WriteResult? {
        runtime.coverage.close(coverageIds, runtime.clock.now(), CoverageEndCause.DATABASE_UNAVAILABLE)
        return null
    }

    public companion object {
        /** Rows written by [result] (0 when the batch was not committed). */
        public fun committedRows(result: WriteResult?): Int = (result as? WriteResult.Committed)?.commit?.written ?: 0
    }
}
