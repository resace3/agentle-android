package dev.agentle.interventions.storage

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.flatMap
import dev.agentle.core.common.getOrNull
import dev.agentle.core.common.map
import dev.agentle.core.common.onFailure
import dev.agentle.core.model.GenerationMethod
import dev.agentle.core.model.MediaArtifact
import dev.agentle.core.model.MediaKind
import dev.agentle.core.time.AgentleClock
import dev.agentle.interventions.ports.MediaMetadataStore
import dev.agentle.interventions.ports.MediaRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Decision keys whose notification or in-app card is still showing; their media is never evicted. */
fun interface PendingDeliveries {
    suspend fun decisionKeys(): Set<String>
}

/** A file about to be written: its id (also the file stem and the row id) and where it goes. */
data class MediaTarget(val id: String, val file: File)

/** The row fields of a new file. [expiresAfter] null keeps it until eviction or deletion. */
data class MediaSpec(
    val kind: MediaKind,
    val method: GenerationMethod,
    val mimeType: String,
    val decisionKey: String?,
    val jitaiId: String?,
    val expiresAfter: Duration?,
)

/**
 * Generated media: files under [MediaPaths], rows through [MediaMetadataStore] (the data team's `media_artifact`).
 * Admission runs before anything is generated; files are written to a temp file and renamed; a row is inserted only for
 * a file in place, and the file is deleted when the insert fails.
 */
class MediaLibrary(
    private val paths: MediaPaths,
    private val store: MediaMetadataStore,
    private val clock: AgentleClock,
    private val pending: PendingDeliveries,
    private val logger: Logger,
    private val quota: MediaQuota = MediaQuota(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val ids: () -> String = { UUID.randomUUID().toString() },
) {
    fun newTarget(kind: MediaKind, extension: String): MediaTarget {
        val id = ids()
        return MediaTarget(id, File(paths.dir(kind), "$id.$extension"))
    }

    /**
     * Makes room for [estimateBytes] of [kind] (R09 §9.4 step 2): deletes what the policy picks and answers whether the
     * new file then fits. False means: skip generation and deliver text (`MEDIA_QUOTA`).
     */
    suspend fun admit(kind: MediaKind, estimateBytes: Long): Outcome<Boolean> = store.all().flatMap { rows ->
        val pendingKeys = pending.decisionKeys()
        val candidates = rows.map { EvictionCandidate.of(it, pendingKeys) }
        val plan = MediaEvictionPolicy.planAdmission(candidates, quota, kind, estimateBytes, clock.now())
        if (plan.evictIds.isEmpty()) Outcome.success(plan.admitted) else delete(plan.evictIds).map { plan.admitted }
    }

    /** Inserts the row of [target], whose file is already in place; deletes the file when the insert fails. */
    suspend fun register(target: MediaTarget, spec: MediaSpec): Outcome<MediaRecord> {
        val now = clock.now()
        val artifact = MediaArtifact(
            id = target.id,
            kind = spec.kind,
            createdAt = now,
            sourceJitaiId = spec.jitaiId,
            decisionKey = spec.decisionKey,
            method = spec.method,
            localUri = paths.relativePath(target.file),
            mimeType = spec.mimeType,
            sizeBytes = target.file.length(),
            expiresAt = spec.expiresAfter?.let { now + it },
        )
        val record = MediaRecord(artifact, lastAccessedAt = null)
        return store.insert(record).map { record }.onFailure {
            withContext(io) { target.file.delete() }
            logger.w(COMPONENT, "media row not stored", it, mapOf("kind" to spec.kind.name))
        }
    }

    /** The row of [id] when its file is still there. */
    suspend fun record(id: String): MediaRecord? = store.get(id).getOrNull()?.takeIf { fileOf(it)?.isFile == true }

    fun fileOf(record: MediaRecord): File? = paths.fileOf(record.artifact.localUri)

    /** The user opened or played [id]: it becomes the most recently used. */
    suspend fun touch(id: String) {
        store.touch(id, clock.now()).onFailure { logger.w(COMPONENT, "media touch failed", it) }
    }

    /** Every file made for [decisionKey] (the detail screen shows them). */
    suspend fun forDecision(decisionKey: String): List<MediaRecord> =
        store.all().getOrNull().orEmpty().filter { it.artifact.decisionKey == decisionKey && fileOf(it)?.isFile == true }

    /** Deletes rows first, then their files; a crash in between leaves orphans for the sweep. */
    suspend fun delete(ids: Collection<String>): Outcome<Int> {
        if (ids.isEmpty()) return Outcome.success(0)
        val rows = store.all().getOrNull().orEmpty().filter { it.artifact.id in ids }
        return store.delete(ids).map { deleted ->
            withContext(io) { rows.forEach { row -> fileOf(row)?.delete() } }
            deleted
        }
    }

    /** Deletes media generated for [decisionKey] (the claim was lost); bundled and reused files stay. */
    suspend fun discardFor(ref: MediaRef?, decisionKey: String) {
        val stored = ref as? MediaRef.Stored ?: return
        val row = store.get(stored.id).getOrNull() ?: return
        if (row.artifact.decisionKey == decisionKey) {
            delete(listOf(row.artifact.id)).onFailure { logger.w(COMPONENT, "discard failed", it) }
        }
    }

    /**
     * The daily pass (and the app-start pass) of R09 §9.4 steps 5-6: expired and over-quota files go (never pending or
     * young ones), the orphan sweep runs, and share copies older than [SHARE_COPY_AGE] are deleted.
     */
    suspend fun runMaintenance(): Outcome<MaintenanceReport> = store.all().flatMap { rows ->
        val now = clock.now()
        val pendingKeys = pending.decisionKeys()
        val evict = MediaEvictionPolicy.select(rows.map { EvictionCandidate.of(it, pendingKeys) }, quota, now)
        delete(evict).flatMap { evicted ->
            val survivors = rows.filter { it.artifact.id !in evict.toSet() }
            val plan = MediaReconciler.plan(withContext(io) { storedFiles() }, survivors, now)
            store.delete(plan.deleteRows).map { missingRows ->
                withContext(io) {
                    plan.deleteFiles.forEach { File(paths.base, it).delete() }
                    val shareCopies = deleteShareCopies(olderThan = now - SHARE_COPY_AGE)
                    MaintenanceReport(evicted, plan.deleteFiles.size, missingRows, shareCopies)
                }
            }
        }
    }.onFailure { logger.w(COMPONENT, "media maintenance failed", it) }

    /** Settings, "Delete generated media": every row, every file under the media root, every share copy. */
    suspend fun deleteAllGenerated(): Outcome<MaintenanceReport> = store.all().flatMap { rows ->
        store.delete(rows.map { it.artifact.id }).map { deletedRows ->
            withContext(io) {
                val files = paths.root.walkBottomUp().filter { it.isFile }.count { it.delete() }
                val shareCopies = deleteShareCopies(olderThan = null)
                MaintenanceReport(deletedRows, files, 0, shareCopies)
            }
        }
    }.onFailure { logger.w(COMPONENT, "delete generated media failed", it) }

    /** Every file under the media root with its path relative to `noBackupFilesDir`. */
    private fun storedFiles(): List<StoredFile> = paths.root.walkTopDown()
        .filter { it.isFile }
        .map { StoredFile(paths.relativePath(it), Instant.fromEpochMilliseconds(it.lastModified())) }
        .toList()

    private fun deleteShareCopies(olderThan: Instant?): Int = paths.shareDir.listFiles().orEmpty()
        .filter { it.isFile && (olderThan == null || Instant.fromEpochMilliseconds(it.lastModified()) < olderThan) }
        .count { it.delete() }

    private companion object {
        const val COMPONENT = "interventions.media"

        /** Share copies are only for the share sheet; a day later they go (R09 §9.1). */
        val SHARE_COPY_AGE: Duration = 1.days
    }
}

/**
 * What a maintenance pass or "delete generated media" removed: [rows] evicted or deleted, [files] orphans or files,
 * [missingRows] rows whose file was gone, [shareCopies] share-sheet copies.
 */
data class MaintenanceReport(val rows: Int, val files: Int, val missingRows: Int, val shareCopies: Int)

/** Process-local [MediaMetadataStore] for the staging graph and tests; rows are lost when the process dies. */
class InMemoryMediaMetadataStore : MediaMetadataStore {
    private val rows = MutableStateFlow<Map<String, MediaRecord>>(emptyMap())

    override suspend fun insert(record: MediaRecord): Outcome<Unit> {
        if (record.artifact.id in rows.value) return Outcome.failure(AppError.DatabaseError("duplicate media id"))
        rows.update { it + (record.artifact.id to record) }
        return Outcome.success(Unit)
    }

    override suspend fun get(id: String): Outcome<MediaRecord?> = Outcome.success(rows.value[id])

    override suspend fun all(): Outcome<List<MediaRecord>> = Outcome.success(rows.value.values.sortedBy { it.artifact.createdAt })

    override suspend fun touch(id: String, at: Instant): Outcome<Unit> {
        rows.update { current -> current[id]?.let { current + (id to it.copy(lastAccessedAt = at)) } ?: current }
        return Outcome.success(Unit)
    }

    override suspend fun delete(ids: Collection<String>): Outcome<Int> {
        var count = 0
        rows.update { current ->
            count = ids.count { it in current }
            current - ids.toSet()
        }
        return Outcome.success(count)
    }
}
