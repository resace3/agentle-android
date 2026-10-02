package dev.agentle.interventions.storage

import dev.agentle.core.model.MediaKind
import dev.agentle.interventions.ports.MediaRecord
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private const val MIB = 1024L * 1024L

/**
 * Media storage caps (R09 §9.3): 200 MB in total, 120 MB video, 40 MB voice, 60 MB images. Nothing younger than
 * [minAge] is evicted (it may be about to be shown).
 */
data class MediaQuota(
    val totalBytes: Long = 200 * MIB,
    val perKindBytes: Map<MediaKind, Long> = mapOf(
        MediaKind.VIDEO to 120 * MIB,
        MediaKind.VOICE to 40 * MIB,
        MediaKind.IMAGE to 60 * MIB,
    ),
    val minAge: Duration = 10.minutes,
)

/**
 * What the eviction policy knows about one stored file.
 *
 * @property pending the file belongs to a delivery whose notification or in-app card is still showing.
 */
data class EvictionCandidate(
    val id: String,
    val kind: MediaKind,
    val sizeBytes: Long,
    val createdAt: Instant,
    val lastUsedAt: Instant,
    val pending: Boolean,
    val expiresAt: Instant?,
) {
    companion object {
        fun of(record: MediaRecord, pendingKeys: Set<String>): EvictionCandidate = EvictionCandidate(
            id = record.artifact.id,
            kind = record.artifact.kind,
            sizeBytes = record.artifact.sizeBytes,
            createdAt = record.artifact.createdAt,
            lastUsedAt = record.lastAccessedAt ?: record.artifact.createdAt,
            pending = record.artifact.decisionKey?.let { it in pendingKeys } == true,
            expiresAt = record.artifact.expiresAt,
        )
    }
}

/** Plan to make room for a new file: what to delete first, and whether the new file then fits. */
data class Admission(val evictIds: List<String>, val admitted: Boolean)

/** Expiry plus least-recently-used eviction under [MediaQuota] (R09 §9.3, ported to `Instant`). */
object MediaEvictionPolicy {
    /**
     * The ids to delete: expired files first, then least recently used until every per-kind cap and the total cap hold.
     * Pending and young files are never picked, so the store may stay over quota (new media is then refused).
     */
    fun select(records: List<EvictionCandidate>, quota: MediaQuota, now: Instant): List<String> {
        val evictable = { r: EvictionCandidate -> !r.pending && now - r.createdAt >= quota.minAge }
        val chosen = LinkedHashSet<String>()
        records.filter { evictable(it) && it.expiresAt != null && it.expiresAt <= now }
            .sortedBy { it.expiresAt }
            .forEach { chosen += it.id }
        val lru = records.filter { evictable(it) && it.id !in chosen }
            .sortedWith(compareBy<EvictionCandidate> { it.lastUsedAt }.thenBy { it.createdAt })
        for ((kind, cap) in quota.perKindBytes) {
            var over = used(records, chosen, kind) - cap
            lru.filter { it.kind == kind && it.id !in chosen }.forEach { r ->
                if (over > 0) {
                    chosen += r.id
                    over -= r.sizeBytes
                }
            }
        }
        var overTotal = used(records, chosen, null) - quota.totalBytes
        lru.filter { it.id !in chosen }.forEach { r ->
            if (overTotal > 0) {
                chosen += r.id
                overTotal -= r.sizeBytes
            }
        }
        return chosen.toList()
    }

    /** Eviction that also makes room for [incomingBytes] of [kind] before it is generated (R09 §9.4 step 2). */
    fun planAdmission(records: List<EvictionCandidate>, quota: MediaQuota, kind: MediaKind, incomingBytes: Long, now: Instant): Admission {
        val kindCap = quota.perKindBytes[kind] ?: quota.totalBytes
        val reduced = quota.copy(
            totalBytes = quota.totalBytes - incomingBytes,
            perKindBytes = quota.perKindBytes + (kind to (kindCap - incomingBytes)),
        )
        val evict = select(records, reduced, now)
        val evicted = evict.toSet()
        val kept = records.filter { it.id !in evicted }
        val fits = kept.filter { it.kind == kind }.sumOf { it.sizeBytes } + incomingBytes <= kindCap &&
            kept.sumOf { it.sizeBytes } + incomingBytes <= quota.totalBytes
        return Admission(evict, fits)
    }

    private fun used(records: List<EvictionCandidate>, chosen: Set<String>, kind: MediaKind?): Long =
        records.filter { it.id !in chosen && (kind == null || it.kind == kind) }.sumOf { it.sizeBytes }
}

/** One file under the media root, as the orphan sweep sees it. */
data class StoredFile(val relativePath: String, val modifiedAt: Instant)

/** What the orphan sweep deletes (R09 §9.4 step 6). */
data class ReconcilePlan(val deleteFiles: List<String>, val deleteRows: List<String>)

/** The orphan sweep as a pure function, so crash leftovers are testable without a device. */
object MediaReconciler {
    const val TMP_SUFFIX: String = ".tmp"

    /** Orphans and temp files older than this go; younger ones may belong to a write in progress. */
    val ORPHAN_AGE: Duration = 1.hours

    /**
     * Files without a row and `*.tmp` files, both older than [orphanAge], are deleted (a crash between rename and insert,
     * or a cancelled export that renamed late). Rows whose file is gone are deleted (their deliveries fall back to text).
     */
    fun plan(files: List<StoredFile>, rows: List<MediaRecord>, now: Instant, orphanAge: Duration = ORPHAN_AGE): ReconcilePlan {
        val rowPaths = rows.mapTo(HashSet()) { it.artifact.localUri }
        val filePaths = files.mapTo(HashSet()) { it.relativePath }
        val deleteFiles = files
            .filter { now - it.modifiedAt >= orphanAge && (it.relativePath.endsWith(TMP_SUFFIX) || it.relativePath !in rowPaths) }
            .map { it.relativePath }
        val deleteRows = rows.filter { it.artifact.localUri !in filePaths }.map { it.artifact.id }
        return ReconcilePlan(deleteFiles, deleteRows)
    }
}
