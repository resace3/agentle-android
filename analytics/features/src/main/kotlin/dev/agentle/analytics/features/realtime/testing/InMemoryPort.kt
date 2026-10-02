package dev.agentle.analytics.features.realtime.testing

import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.realtime.InputAnswer
import dev.agentle.analytics.features.realtime.SnapshotReads
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome

/**
 * Test support, not for production code. Records every read of the in-memory ports in order, whether it ran inside
 * [read] (one consistent snapshot per `resolve()`, database-sync-05), and how many snapshots were opened. It is the
 * [SnapshotReads] of [InMemoryRealtimeInputs].
 */
public class ReadLog : SnapshotReads {
    private var depth = 0
    private val entries = mutableListOf<PortRead>()

    /** Number of [read] calls so far. */
    public var snapshots: Int = 0
        private set

    /** Every port read so far, in order. */
    public val reads: List<PortRead> get() = entries.toList()

    override suspend fun <T> read(block: suspend () -> T): T {
        snapshots++
        depth++
        try {
            return block()
        } finally {
            depth--
        }
    }

    /** How many times [port] (for example `usage.events`) was read. */
    public fun count(port: String): Int = entries.count { it.port == port }

    /** Forgets every read and snapshot. */
    public fun clear() {
        entries.clear()
        snapshots = 0
    }

    internal fun record(port: String) {
        entries += PortRead(port, insideSnapshot = depth > 0)
    }
}

/** One read of an in-memory port: its name (`<port>.<method>`) and whether it ran inside a snapshot. */
public data class PortRead(val port: String, val insideSnapshot: Boolean)

/**
 * Test support, not for production code: what every in-memory port shares. When set, a read fails with [failure] (a
 * technical failure), answers `Unavailable(`[unavailable]`)` (a data condition, for ports that answer an
 * [InputAnswer]), or throws [throwing] (a port bug the engine must survive). Every read is recorded in the [ReadLog].
 */
public sealed class InMemoryPort(private val log: ReadLog) {
    public var unavailable: MissingReason? = null

    public var failure: AppError? = null

    public var throwing: RuntimeException? = null

    internal fun <T> answer(port: String, value: () -> InputAnswer<T>): Outcome<InputAnswer<T>> {
        record(port)
        failure?.let { return Outcome.failure(it) }
        unavailable?.let { return InputAnswer.unavailable(it) }
        return Outcome.success(value())
    }

    internal fun <T> plain(port: String, value: () -> T): Outcome<T> {
        record(port)
        failure?.let { return Outcome.failure(it) }
        return Outcome.success(value())
    }

    private fun record(port: String) {
        log.record(port)
        throwing?.let { throw it }
    }
}
