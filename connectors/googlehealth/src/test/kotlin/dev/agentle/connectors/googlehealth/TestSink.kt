package dev.agentle.connectors.googlehealth

import dev.agentle.connectors.api.CommitResult
import dev.agentle.connectors.api.EventSink
import dev.agentle.connectors.api.StreamCoverage
import dev.agentle.connectors.api.SyncCursor
import dev.agentle.core.model.ConnectorIds
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.PersonalEvent
import kotlin.time.Instant

/**
 * An in-memory [EventSink] with the contract's semantics: dedup by key (insert, update when the payload changed,
 * else ignore), compare-and-set on the cursor generation (a rejected call writes nothing), diff windows per source and
 * account, coverage that never moves backward, an import floor, and a per-source deletion watermark whose older
 * records are dropped as ignored (round-1 correction 5). It also records what the contract forbids, so tests can
 * assert that nothing of it happened.
 */
internal class TestSink : EventSink {
    data class Row(val event: PersonalEvent, val accountId: String?)

    /** One write call: the stream, the window (null for [commit]) and whether coverage came with it. */
    data class Write(
        val stream: String?,
        val start: Instant?,
        val end: Instant?,
        val events: Int,
        val coverage: StreamCoverage?,
        val rejected: Boolean,
    )

    private val rows = LinkedHashMap<String, Row>()
    private val cursors = HashMap<Pair<String, String>, SyncCursor>()
    private val coverages = HashMap<Pair<String, String>, StreamCoverage>()

    /** Import floor per source (retention), on top of the deletion watermark. */
    val floors = HashMap<DataSourceId, Instant>()

    /** Deletion watermark per source: records starting before it are dropped (counted as ignored). */
    val watermarks = HashMap<DataSourceId, Instant>()

    val writes = ArrayList<Write>()
    val violations = ArrayList<String>()
    var deleted = 0
        private set

    /** The next call throws this (a broken database). */
    var failNext: Exception? = null

    /** Called with the stream name at the start of every write, before the generation check (a concurrent writer). */
    var onWrite: ((String?) -> Unit)? = null

    /** Whether the deletion watermark is reported as the import floor (a sink that forgets to is still safe). */
    var watermarkIsFloor: Boolean = true

    override suspend fun commit(events: List<PersonalEvent>, cursor: SyncCursor?, coverage: StreamCoverage?): CommitResult = locked {
        failNext?.let {
            failNext = null
            throw it
        }
        onWrite?.invoke(cursor?.stream)
        if (cursor != null && !casOk(cursor)) {
            writes += Write(cursor.stream, null, null, events.size, coverage, rejected = true)
            return@locked CommitResult(0, 0, 0, rejected = true)
        }
        val result = upsert(events, null)
        cursor?.let(::store)
        coverage?.let(::cover)
        writes += Write(cursor?.stream, null, null, events.size, coverage, rejected = false)
        result
    }

    override suspend fun replaceWindow(
        source: DataSourceId,
        windowStart: Instant,
        windowEnd: Instant,
        events: List<PersonalEvent>,
        cursor: SyncCursor?,
        coverage: StreamCoverage?,
        accountId: String?,
    ): CommitResult = locked {
        failNext?.let {
            failNext = null
            throw it
        }
        if (windowStart >= windowEnd) violations += "empty window $source $windowStart..$windowEnd"
        events.filter { it.source != source || it.startTime < windowStart || it.startTime >= windowEnd }
            .forEach { violations += "event outside its window: ${it.dedupKey}" }
        if (events.map { it.dedupKey }.toSet().size != events.size) violations += "duplicate keys in one window of $source"
        onWrite?.invoke(cursor?.stream ?: source.stream)
        if (cursor != null && !casOk(cursor)) {
            writes += Write(cursor.stream, windowStart, windowEnd, events.size, coverage, rejected = true)
            return@locked CommitResult(0, 0, 0, rejected = true)
        }
        val keep = events.map { it.dedupKey }.toSet()
        val stale = rows.values.filter {
            it.event.source == source && (accountId == null || it.accountId == accountId) &&
                it.event.startTime >= windowStart && it.event.startTime < windowEnd && it.event.dedupKey !in keep
        }
        stale.forEach { rows.remove(it.event.dedupKey) }
        deleted += stale.size
        val result = upsert(events, accountId)
        cursor?.let(::store)
        coverage?.let(::cover)
        writes += Write(cursor?.stream ?: source.stream, windowStart, windowEnd, events.size, coverage, rejected = false)
        result
    }

    override suspend fun cursor(connectorId: String, stream: String): SyncCursor? = locked {
        failNext?.let {
            failNext = null
            throw it
        }
        cursors[connectorId to stream]
    }

    override suspend fun importFloor(source: DataSourceId): Instant? = locked {
        listOfNotNull(floors[source], watermarks[source]?.takeIf { watermarkIsFloor }).maxOrNull()
    }

    // ---------------------------------------------------------------- test views

    @Synchronized
    fun events(): List<PersonalEvent> = rows.values.map { it.event }.sortedWith(compareBy({ it.startTime }, { it.dedupKey }))

    fun events(stream: String): List<PersonalEvent> = events().filter { it.source.stream == stream }

    @Synchronized
    fun accountOf(key: String): String? = rows[key]?.accountId

    @Synchronized
    fun cursorOf(stream: String): SyncCursor? = cursors[ConnectorIds.GOOGLE_HEALTH to stream]

    @Synchronized
    fun coverageOf(stream: String): StreamCoverage? = coverages[ConnectorIds.GOOGLE_HEALTH to stream]

    /** Simulates the user deleting [source]'s data before [before]: rows go, the watermark moves. */
    @Synchronized
    fun deleteBefore(source: DataSourceId, before: Instant) {
        val gone = rows.values.filter { it.event.source == source && it.event.startTime < before }
        gone.forEach { rows.remove(it.event.dedupKey) }
        watermarks[source] = maxOf(watermarks[source] ?: before, before)
    }

    /** A direct cursor write, as another run (or an older app version) would leave it. */
    @Synchronized
    fun putCursor(cursor: SyncCursor) {
        cursors[cursor.connectorId to cursor.stream] = cursor
    }

    /** Another writer stores [stream]'s cursor again (same content, next generation). */
    @Synchronized
    fun bumpGeneration(stream: String) {
        val key = ConnectorIds.GOOGLE_HEALTH to stream
        cursors[key]?.let { cursors[key] = it.copy(generation = it.generation + 1) }
    }

    // ---------------------------------------------------------------- internals

    private fun <T> locked(block: () -> T): T = synchronized(this) { block() }

    private fun casOk(cursor: SyncCursor): Boolean = (cursors[cursor.connectorId to cursor.stream]?.generation ?: 0L) == cursor.generation

    private fun store(cursor: SyncCursor) {
        cursors[cursor.connectorId to cursor.stream] = cursor.copy(generation = cursor.generation + 1)
    }

    private fun cover(coverage: StreamCoverage) {
        val key = coverage.connectorId to coverage.stream
        val old = coverages[key]
        if (old == null || coverage.coverageThrough >= old.coverageThrough) coverages[key] = coverage
    }

    private fun upsert(events: List<PersonalEvent>, accountId: String?): CommitResult {
        var inserted = 0
        var updated = 0
        var ignored = 0
        for (event in events) {
            val mark = watermarks[event.source]
            val old = rows[event.dedupKey]
            when {
                mark != null && event.startTime < mark -> ignored++

                old == null -> {
                    rows[event.dedupKey] = Row(event, accountId)
                    inserted++
                }

                old.event.payload != event.payload || old.event.startTime != event.startTime || old.event.endTime != event.endTime -> {
                    rows[event.dedupKey] = Row(event, accountId ?: old.accountId)
                    updated++
                }

                else -> ignored++
            }
        }
        return CommitResult(inserted, updated, ignored)
    }
}
