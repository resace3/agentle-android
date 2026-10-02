package dev.agentle.data.ingest

import dev.agentle.core.database.EngineStateKeys
import dev.agentle.core.database.StorageHashes
import dev.agentle.core.database.TermCache
import dev.agentle.core.database.TermKind
import dev.agentle.core.database.entity.DedupCollisionEntity
import dev.agentle.core.database.entity.EventEntity
import dev.agentle.core.database.entity.EventTombstoneEntity
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventCodec
import dev.agentle.core.model.EventProjections
import dev.agentle.core.model.EventType
import dev.agentle.core.model.ExercisePayload
import dev.agentle.core.model.NotificationPayload
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.Provenance
import dev.agentle.core.model.SleepSessionPayload
import dev.agentle.core.time.EngineDay
import dev.agentle.data.Tx
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlin.time.Instant

/** What [EventEntity.changeKind] records: the transition that assigned the row's `change_seq`. */
internal object ChangeKind {
    const val INSERTED: Int = 0
    const val UPDATED: Int = 1

    /** A sleep session whose stages became final (`processed` false or unknown -> true). */
    const val PROCESSED: Int = 2
}

/** Counts of one ingest call. */
internal data class IngestCounts(val inserted: Int = 0, val updated: Int = 0, val ignored: Int = 0, val deleted: Int = 0) {
    operator fun plus(other: IngestCounts): IngestCounts =
        IngestCounts(inserted + other.inserted, updated + other.updated, ignored + other.ignored, deleted + other.deleted)
}

/**
 * The ingest rules applied inside one write transaction (docs/ARCHITECTURE.md §5.3; rounds 1-3):
 * - dedup by (account, dedup key) through a 64-bit hash with a collision check against the stored key;
 * - within a batch the newest copy of a key wins (by upstream update time; a later copy wins a tie);
 * - insert new keys; update a stored row only when its payload hash changed and the incoming copy is not older;
 *   otherwise ignore;
 * - drop records older than their import floor;
 * - one POSTED row per active notification key: re-posts fold into `updateCount` and `lastUpdateEpochMs` until the
 *   notification is removed (round 1 correction 3);
 * - every insert, semantic update and deletion takes the next value of the single change counter, marks the engine
 *   days it touches dirty with that value as generation, and sets the JITAI dirty flag (round 2 correction 3);
 * - deletions leave tombstones, so change readers see them.
 */
internal class IngestSession(
    private val tx: Tx,
    private val terms: TermCache,
    private val floors: FloorSnapshot,
    private val nowMs: Long,
) {
    private var inserted = 0
    private var updated = 0
    private var ignored = 0
    private var deleted = 0
    private var changeSeq: Long? = null
    private val dirtyDays = HashMap<String, Long>()
    private val zones = HashMap<String, TimeZone>()

    /** Dedup hashes given to new rows in this transaction, so two new keys never take the same one. */
    private val assigned = HashMap<Long, String>()

    val counts: IngestCounts get() = IngestCounts(inserted, updated, ignored, deleted)

    /** The account term of [accountId], created on first use; [TermKind.NO_ACCOUNT] for data that is not account-bound. */
    suspend fun accountTerm(accountId: String?): Long =
        if (accountId.isNullOrEmpty()) TermKind.NO_ACCOUNT else terms.idOrCreate(tx.db.termDao(), TermKind.ACCOUNT, accountId)

    /** Inserts or updates [events] of [account]. Returns the stored dedup keys of the events that passed the floors. */
    suspend fun upsert(events: List<PersonalEvent>, account: Long): Set<String> {
        val (folded, regular) = events.partition { foldKeyHash(it) != null || removedKeyHash(it) != null }
        val kept = upsertBulk(regular, account)
        folded.sortedWith(compareBy<PersonalEvent> { it.startTime }.thenBy { if (it.type == EventType.NOTIFICATION_POSTED) 0 else 1 })
            .forEach { event ->
                val key = foldNotification(event, account)
                if (key != null) kept += key
            }
        return kept
    }

    /**
     * Diff semantics (round 2 correction 1): afterwards the stored events of [source] and [account] starting in
     * `[fromMs, toMs)` are exactly [events]. Stored keys that are not returned are deleted (with tombstones); new keys
     * are inserted; changed rows are updated as in [upsert]; unchanged rows are not touched.
     */
    suspend fun replaceWindow(source: DataSourceId, account: Long, fromMs: Long, toMs: Long, events: List<PersonalEvent>) {
        val sourceTerm = terms.idOrCreate(tx.db.termDao(), TermKind.SOURCE, source.value)
        val (inWindow, outside) = events.partition {
            it.source == source && it.startTime.toEpochMilliseconds() in fromMs until toMs
        }
        ignored += outside.size
        val incoming = inWindow.mapTo(HashSet()) { storedKey(it) }
        val stale = tx.db.eventDao().inWindow(sourceTerm, account, fromMs, toMs).filter { it.dedupKey !in incoming }
        deleteRows(stale)
        upsert(inWindow, account)
    }

    /** Deletes the rows of [keys] (a Health Connect `DeletionChange`). Returns how many rows were deleted. */
    suspend fun deleteKeys(keys: Collection<String>, account: Long): Int {
        if (keys.isEmpty()) return 0
        val hashes = resolveHashes(account, keys.toSet())
        val rows = tx.db.eventDao().byDedupHashes(hashes.values.distinct()).filter { it.account == account && it.dedupKey in keys }
        deleteRows(rows)
        return rows.size
    }

    /** Writes the change counter, the dirty engine days and the JITAI dirty flag. Call once, at the end. */
    suspend fun finish() {
        val seq = changeSeq ?: return
        val state = tx.db.stateDao()
        state.putState(EngineStateKeys.CHANGE_SEQ, seq, null)
        dirtyDays.forEach { (day, generation) -> state.markDirty(day, generation) }
        state.putState(EngineStateKeys.JITAI_DIRTY, 1L, null)
    }

    // ------------------------------------------------------------------------------------------------ bulk path

    private suspend fun upsertBulk(events: List<PersonalEvent>, account: Long): MutableSet<String> {
        val newest = LinkedHashMap<String, PersonalEvent>()
        events.forEach { event ->
            val key = storedKey(event)
            val previous = newest[key]
            if (previous != null) {
                ignored++
                if (isOlder(
                        event.metadata.upstreamUpdatedAt?.toEpochMilliseconds(),
                        previous.metadata.upstreamUpdatedAt?.toEpochMilliseconds(),
                    )
                ) {
                    return@forEach
                }
            }
            newest[key] = event
        }
        val kept = newest.filterValues { event ->
            val floor = floors.floorFor(event.type, event.source)
            val dropped = floor != null && event.startTime.toEpochMilliseconds() < floor
            if (dropped) ignored++
            !dropped
        }
        if (kept.isEmpty()) return mutableSetOf()
        val hashes = resolveHashes(account, kept.keys)
        val existing = tx.db.eventDao().byDedupHashes(hashes.values.distinct()).associateBy { it.dedupHash }
        kept.forEach { (key, event) ->
            var hash = hashes.getValue(key)
            var row = existing[hash]
            val takenInBatch = assigned[hash]
            if ((row != null && (row.dedupKey != key || row.account != account)) ||
                (row == null && takenInBatch != null && takenInBatch != key)
            ) {
                hash = allocateAlternate(account, key)
                row = null
            }
            if (row == null) insert(event, key, hash, account) else maybeUpdate(row, event)
        }
        return kept.keys.toMutableSet()
    }

    /** The hash of every key: a recorded alternative for keys that once collided, else the primary hash. */
    private suspend fun resolveHashes(account: Long, keys: Set<String>): Map<String, Long> {
        val recorded = keys.chunked(MAX_IN_ARGS).flatMap { tx.db.stateDao().collisions(account, it) }.associate {
            it.dedupKey to
                it.dedupHash
        }
        return keys.associateWith { key -> recorded[key] ?: StorageHashes.dedupHash(account, key) }
    }

    /** A free alternative hash for [key], recorded in `dedup_collision` (round 2 correction 8: collision check). */
    private suspend fun allocateAlternate(account: Long, key: String): Long {
        for (attempt in 1..MAX_HASH_ATTEMPTS) {
            val candidate = StorageHashes.alternateDedupHash(account, key, attempt)
            if (candidate !in assigned && tx.db.stateDao().hashTaken(candidate) == 0L) {
                tx.db.stateDao().insertCollision(DedupCollisionEntity(account = account, dedupKey = key, dedupHash = candidate))
                return candidate
            }
        }
        error("no free dedup hash")
    }

    private suspend fun insert(event: PersonalEvent, key: String, hash: Long, account: Long) {
        val seq = nextChangeSeq()
        val entity = entityOf(event, key, hash, account, seq, ChangeKind.INSERTED, seqId = 0L)
        tx.db.eventDao().insert(entity)
        assigned[hash] = key
        markDirty(entity.startMs, entity.endMs, entity.zoneId, seq)
        inserted++
    }

    private suspend fun maybeUpdate(row: EventEntity, event: PersonalEvent) {
        val payloadHash = StorageHashes.payloadHash(event)
        val incomingUpdate = event.metadata.upstreamUpdatedAt?.toEpochMilliseconds()
        if (row.payloadHash == payloadHash || isOlder(incomingUpdate, row.upstreamUpdateMs)) {
            ignored++
            return
        }
        val seq = nextChangeSeq()
        val kind = if (becameProcessed(row, event)) ChangeKind.PROCESSED else ChangeKind.UPDATED
        val next = entityOf(event, row.dedupKey, row.dedupHash, row.account, seq, kind, seqId = row.seq)
            .copy(id = row.id, ingestedMs = row.ingestedMs)
        tx.db.eventDao().update(next)
        markDirty(row.startMs, row.endMs, row.zoneId, seq)
        markDirty(next.startMs, next.endMs, next.zoneId, seq)
        updated++
    }

    private suspend fun deleteRows(rows: List<EventEntity>) {
        if (rows.isEmpty()) return
        val tombstones = rows.map { row ->
            val seq = nextChangeSeq()
            markDirty(row.startMs, row.endMs, row.zoneId, seq)
            EventTombstoneEntity(
                changeSeq = seq,
                eventSeq = row.seq,
                type = row.type,
                source = row.source,
                account = row.account,
                startMs = row.startMs,
                endMs = row.endMs,
                deletedMs = nowMs,
            )
        }
        tx.db.stateDao().insertTombstones(tombstones)
        rows.map { it.seq }.chunked(MAX_IN_ARGS).forEach { tx.db.eventDao().deleteBySeq(it) }
        deleted += rows.size
    }

    // ------------------------------------------------------------------------------------ notification fold

    /**
     * A POSTED notification with a key folds into the open POSTED row of that key (stored under [foldKey]): a later
     * post becomes `updateCount + 1` and `lastUpdateEpochMs`; the first start is kept; a copy that is not newer than
     * the row's last update is ignored. A REMOVED notification is stored as usual and closes the open row, so the
     * next post of a reused key starts a new row. Returns the stored key, or null when the event was dropped.
     */
    private suspend fun foldNotification(event: PersonalEvent, account: Long): String? {
        val floor = floors.floorFor(event.type, event.source)
        if (floor != null && event.startTime.toEpochMilliseconds() < floor) {
            ignored++
            return null
        }
        val removedKey = removedKeyHash(event)
        if (removedKey != null) {
            upsertBulk(listOf(event), account)
            closeOpenPost(removedKey, account, event.startTime.toEpochMilliseconds())
            return event.dedupKey
        }
        val keyHash = foldKeyHash(event) ?: return null
        val key = foldKey(keyHash)
        val hash = resolveHashes(account, setOf(key)).getValue(key)
        val open = tx.db.eventDao().byDedupHashes(listOf(hash)).firstOrNull { it.dedupKey == key && it.account == account }
        if (open == null) {
            val free = if (assigned[hash] != null || tx.db.stateDao().hashTaken(hash) > 0L) allocateAlternate(account, key) else hash
            insert(event, key, free, account)
            return key
        }
        val stored = decodeNotification(open.payloadJson)
        val at = event.startTime.toEpochMilliseconds()
        val lastSeen = stored?.lastUpdateEpochMs ?: open.startMs
        if (at <= lastSeen) {
            ignored++
            return key
        }
        val incoming = event.payload as NotificationPayload
        val merged = incoming.copy(
            updateCount = maxOf((stored?.updateCount ?: 0) + 1, incoming.updateCount),
            lastUpdateEpochMs = at,
        )
        maybeUpdate(open, event.copy(startTime = Instant.fromEpochMilliseconds(open.startMs), endTime = null, payload = merged))
        return key
    }

    private suspend fun closeOpenPost(keyHash: String, account: Long, removedAtMs: Long) {
        val key = foldKey(keyHash)
        val hash = resolveHashes(account, setOf(key)).getValue(key)
        val open = tx.db.eventDao().byDedupHashes(listOf(hash)).firstOrNull { it.dedupKey == key && it.account == account } ?: return
        if (removedAtMs < open.startMs) return
        val closedKey = "$key|${open.seq}"
        val primary = StorageHashes.dedupHash(account, closedKey)
        val closedHash = if (assigned[primary] != null ||
            tx.db.stateDao().hashTaken(primary) > 0L
        ) {
            allocateAlternate(account, closedKey)
        } else {
            primary
        }
        tx.db.eventDao().update(open.copy(dedupKey = closedKey, dedupHash = closedHash))
        assigned[closedHash] = closedKey
    }

    private fun decodeNotification(json: String): NotificationPayload? = try {
        EventCodec.decode(json) as? NotificationPayload
    } catch (expected: IllegalArgumentException) {
        // SerializationException is an IllegalArgumentException; an unreadable stored copy folds like a first post.
        null
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private suspend fun nextChangeSeq(): Long {
        val current = changeSeq ?: (tx.db.stateDao().state(EngineStateKeys.CHANGE_SEQ)?.intValue ?: 0L)
        val next = current + 1
        changeSeq = next
        return next
    }

    private suspend fun entityOf(
        event: PersonalEvent,
        key: String,
        hash: Long,
        account: Long,
        changeSeq: Long,
        changeKind: Int,
        seqId: Long,
    ): EventEntity {
        val startMs = event.startTime.toEpochMilliseconds()
        val endMs = event.endTime?.toEpochMilliseconds()
        val payload = event.payload
        val (startOffset, endOffset) = offsets(event, startMs, endMs)
        return EventEntity(
            seq = seqId,
            id = event.id.value,
            type = terms.idOrCreate(tx.db.termDao(), TermKind.TYPE, event.type.name),
            source = terms.idOrCreate(tx.db.termDao(), TermKind.SOURCE, event.source.value),
            account = account,
            startMs = startMs,
            endMs = endMs,
            startOffsetS = startOffset,
            endOffsetS = endOffset,
            localDate = EventProjections.localDateOf(payload)?.toString(),
            zoneId = event.zoneId,
            dedupHash = hash,
            dedupKey = key,
            upstreamId = event.metadata.upstreamId ?: foldKeyHash(event) ?: removedKeyHash(event),
            upstreamUpdateMs = event.metadata.upstreamUpdatedAt?.toEpochMilliseconds(),
            payloadHash = StorageHashes.payloadHash(event),
            subject = EventProjections.subjectOf(payload),
            valueNum = EventProjections.valueOf(payload),
            payloadJson = EventCodec.encode(payload),
            payloadVersion = event.metadata.schemaVersion,
            confidence = event.confidence,
            ingestedMs = event.metadata.ingestedAt.toEpochMilliseconds(),
            sensitivity = event.metadata.sensitivity.ordinal,
            origin = event.metadata.origin,
            provenanceJson = event.metadata.provenance?.let { EventCodec.json.encodeToString(Provenance.serializer(), it) },
            changeSeq = changeSeq,
            changeKind = changeKind,
        )
    }

    /** UTC offsets at the start and end: the source's own when it reports them, else those of the event's zone. */
    private fun offsets(event: PersonalEvent, startMs: Long, endMs: Long?): Pair<Int?, Int?> {
        val payload = event.payload
        val reported: Pair<Int?, Int?> = when (payload) {
            is SleepSessionPayload -> payload.startUtcOffsetSeconds to payload.endUtcOffsetSeconds
            is ExercisePayload -> payload.startUtcOffsetSeconds to payload.endUtcOffsetSeconds
            else -> null to null
        }
        val zone = zone(event.zoneId)
        val start = reported.first ?: zone.offsetAt(Instant.fromEpochMilliseconds(startMs)).totalSeconds
        val end = reported.second ?: endMs?.let { zone.offsetAt(Instant.fromEpochMilliseconds(it)).totalSeconds }
        return start to end
    }

    private fun becameProcessed(row: EventEntity, event: PersonalEvent): Boolean {
        val incoming = event.payload as? SleepSessionPayload ?: return false
        if (incoming.processed != true) return false
        val stored = try {
            EventCodec.decode(row.payloadJson) as? SleepSessionPayload
        } catch (expected: IllegalArgumentException) {
            null
        }
        return stored?.processed != true
    }

    /** Marks every engine day of `[startMs, endMs)` in the event's own zone (at most [MAX_DIRTY_DAYS] days). */
    private fun markDirty(startMs: Long, endMs: Long?, zoneId: String, generation: Long) {
        val zone = zone(zoneId)
        val first = EngineDay.of(Instant.fromEpochMilliseconds(startMs), zone)
        val lastMs = if (endMs != null && endMs > startMs) endMs - 1 else startMs
        val last = EngineDay.of(Instant.fromEpochMilliseconds(lastMs), zone)
        var day: LocalDate = first
        var count = 0
        while (day <= last && count < MAX_DIRTY_DAYS) {
            val name = day.toString()
            dirtyDays[name] = maxOf(dirtyDays[name] ?: 0L, generation)
            day = day.plus(DatePeriod(days = 1))
            count++
        }
    }

    private fun zone(zoneId: String): TimeZone = zones.getOrPut(zoneId) {
        try {
            TimeZone.of(zoneId)
        } catch (expected: IllegalArgumentException) {
            // IllegalTimeZoneException: an unknown zone id is read as UTC rather than dropping the record.
            TimeZone.UTC
        }
    }

    companion object {
        /** SQLite bound-parameter budget per statement. */
        const val MAX_IN_ARGS: Int = 500
        const val MAX_HASH_ATTEMPTS: Int = 8
        const val MAX_DIRTY_DAYS: Int = 8
        private const val FOLD_PREFIX = "nposted|"

        fun foldKey(keyHash: String): String = FOLD_PREFIX + keyHash

        /** The dedup key [event] is stored under: the fold key for keyed POSTED notifications, else its own key. */
        fun storedKey(event: PersonalEvent): String = foldKeyHash(event)?.let(::foldKey) ?: event.dedupKey

        fun foldKeyHash(event: PersonalEvent): String? =
            if (event.type == EventType.NOTIFICATION_POSTED) (event.payload as? NotificationPayload)?.keyHash else null

        fun removedKeyHash(event: PersonalEvent): String? =
            if (event.type == EventType.NOTIFICATION_REMOVED) (event.payload as? NotificationPayload)?.keyHash else null

        /** True when [incoming] is known to be older than [stored] (upstream update times; unknown after known is older). */
        fun isOlder(incoming: Long?, stored: Long?): Boolean = stored != null && (incoming == null || incoming < stored)
    }
}
