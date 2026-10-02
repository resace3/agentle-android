package dev.agentle.data.deletion

import dev.agentle.core.database.ChunkedDeleter
import dev.agentle.core.database.ContentScrubber
import dev.agentle.core.database.DataCategoryRegistry
import dev.agentle.core.database.DeletionScope
import dev.agentle.core.database.DeletionStep
import dev.agentle.core.database.EngineStateKeys
import dev.agentle.core.database.SourceGroup
import dev.agentle.core.database.SqlScope
import dev.agentle.core.database.TermIds
import dev.agentle.core.database.TermKind
import dev.agentle.core.datastore.AiConsentStore
import dev.agentle.core.model.DataCategory
import dev.agentle.core.time.AgentleClock
import dev.agentle.data.DataAccess
import dev.agentle.data.ingest.FloorScopes
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** What the user asked to delete (docs/ARCHITECTURE.md §5.5). */
sealed interface DeletionTarget {
    /** "Delete wearable data": Google Health API, then Health Connect. */
    data object Wearable : DeletionTarget

    /** "Delete Android-collected data". */
    data object AndroidCollected : DeletionTarget

    /** One data category, with everything derived from it. */
    data class Category(val category: DataCategory) : DeletionTarget
}

/** The outcome of a deletion: rows processed per table, and the verification (rows still matching, all 0 when done). */
data class DeletionReport(val processed: Map<String, Long>, val remaining: Map<String, Long>) {
    val verified: Boolean get() = remaining.values.all { it == 0L }
}

/** Deletes files of generated media rows; idempotent (a missing file counts as deleted). */
fun interface MediaFileDeleter {
    suspend fun delete(localUri: String)
}

/**
 * Category and family deletions (round 1 correction 5; round 2 correction 6; round 4 corrections 2 and 3).
 *
 * The sequence, each stage recorded in the marker (`engine_state.deletion_marker`) so a stopped worker resumes:
 * 1. marker + data epoch bump + import floors at now, in one transaction (in-flight sync batches are rejected and
 *    deleted data never comes back from a re-sync);
 * 2. AI consent revocation for the deleted categories (all grants for a source family);
 * 3. per scope, per registry step: media files first, then the rows in chunks of at most 500 per transaction, the
 *    step's progress written in the same transaction;
 * 4. verification: every step's pending count, which must be 0; then the marker is removed.
 */
interface DeletionService {
    suspend fun delete(target: DeletionTarget): DeletionReport

    /** Finishes a deletion a stopped worker left behind; null when none was pending. */
    suspend fun resumePending(): DeletionReport?
}

/** Progress of one deletion, stored as JSON in the marker. */
@Serializable
internal data class DeletionMarker(
    val scopes: List<String>,
    val categories: List<String>?,
    val consentRevoked: Boolean = false,
    val scopeIndex: Int = 0,
    val stepIndex: Int = 0,
    val processed: Map<String, Long> = emptyMap(),
)

internal class RoomDeletionService(
    private val access: DataAccess,
    private val consent: AiConsentStore,
    private val clock: AgentleClock,
    private val scrubber: ContentScrubber,
    private val media: MediaFileDeleter,
    private val chunkRows: Int = DataCategoryRegistry.CHUNK_ROWS,
    /** Test hook: called at the start of each stage, so tests can crash a deletion at every stage. */
    private val checkpoint: suspend (String) -> Unit = {},
) : DeletionService {
    override suspend fun delete(target: DeletionTarget): DeletionReport {
        resumePending()
        val scopes = scopesOf(target)
        val categories = (target as? DeletionTarget.Category)?.let { listOf(it.category.name) }
        val marker = DeletionMarker(scopes.map { it.code }, categories)
        checkpoint(STAGE_MARKER)
        val now = clock.now().toEpochMilliseconds()
        access.write {
            val dao = db.stateDao()
            val epoch = dao.state(EngineStateKeys.DATA_EPOCH)?.intValue ?: 0L
            dao.putState(EngineStateKeys.DATA_EPOCH, epoch + 1, null)
            scopes.forEach { scope -> dao.raiseFloor(floorScope(scope), now, now) }
            dao.putState(EngineStateKeys.DELETION_MARKER, null, encode(marker))
        }
        return run(marker)
    }

    override suspend fun resumePending(): DeletionReport? = readMarker()?.let { run(it) }

    private suspend fun run(start: DeletionMarker): DeletionReport {
        var marker = start
        if (!marker.consentRevoked) {
            checkpoint(STAGE_CONSENT)
            consent.revokeCategories(marker.categories?.toSet())
            marker = save(marker.copy(consentRevoked = true))
        }
        val deleter = ChunkedDeleter(access.transactions(), scrubber, chunkRows)
        val scopes = marker.scopes.map { checkNotNull(DeletionScope.parse(it)) }
        while (marker.scopeIndex < scopes.size) {
            val scope = scopes[marker.scopeIndex]
            val steps = DataCategoryRegistry.plan(scope, termIds())
            while (marker.stepIndex < steps.size) {
                val step = steps[marker.stepIndex]
                checkpoint("$STAGE_STEP${marker.scopeIndex}.${marker.stepIndex}")
                if (step.table == MEDIA_TABLE && step is DeletionStep.Delete) {
                    deleter.values(step, MEDIA_URI).forEach { media.delete(it) }
                }
                marker = runStep(deleter, step, scope, marker)
                marker = save(marker.copy(stepIndex = marker.stepIndex + 1))
            }
            marker = save(marker.copy(scopeIndex = marker.scopeIndex + 1, stepIndex = 0))
        }
        checkpoint(STAGE_VERIFY)
        val remaining = scopes.flatMap { DataCategoryRegistry.plan(it, termIds()) }
            .groupBy { it.table }
            .mapValues { (_, steps) -> steps.sumOf { deleter.pending(it) } }
        access.write { db.stateDao().deleteState(EngineStateKeys.DELETION_MARKER) }
        return DeletionReport(marker.processed, remaining)
    }

    /** Runs one step; every chunk adds its row count to the marker in the chunk's own transaction. */
    private suspend fun runStep(deleter: ChunkedDeleter, step: DeletionStep, scope: DeletionScope, before: DeletionMarker): DeletionMarker {
        var marker = before
        deleter.run(
            step,
            scope.deleted,
            guard = { readMarker(this) != null },
            onChunk = { rows ->
                marker = marker.copy(processed = marker.processed + (step.table to (marker.processed[step.table] ?: 0L) + rows))
                execute("UPDATE engine_state SET text_value = ? WHERE name = ?", encode(marker), EngineStateKeys.DELETION_MARKER)
            },
        )
        return marker
    }

    private suspend fun save(marker: DeletionMarker): DeletionMarker {
        access.write { db.stateDao().putState(EngineStateKeys.DELETION_MARKER, null, encode(marker)) }
        return marker
    }

    private suspend fun readMarker(): DeletionMarker? =
        access.read { db.stateDao().state(EngineStateKeys.DELETION_MARKER)?.textValue }?.let(::decode)

    private suspend fun readMarker(scope: SqlScope): DeletionMarker? =
        scope.queryText("SELECT text_value FROM engine_state WHERE name = ?", EngineStateKeys.DELETION_MARKER)?.let(::decode)

    private suspend fun termIds(): TermIds = access.read {
        val terms = db.termDao().all()
        TermIds(
            terms.filter { it.kind == TermKind.TYPE }.associate { it.value to it.id },
            terms.filter { it.kind == TermKind.SOURCE }.associate { it.value to it.id },
        )
    }

    companion object {
        const val STAGE_MARKER: String = "marker"
        const val STAGE_CONSENT: String = "consent"
        const val STAGE_STEP: String = "step:"
        const val STAGE_VERIFY: String = "verify"
        private const val MEDIA_TABLE = "media_artifact"
        private const val MEDIA_URI = "local_uri"
        private val JSON = Json { ignoreUnknownKeys = true }

        fun scopesOf(target: DeletionTarget): List<DeletionScope> = when (target) {
            DeletionTarget.Wearable -> SourceGroup.WEARABLE.map(DeletionScope::Sources)
            DeletionTarget.AndroidCollected -> listOf(DeletionScope.Sources(SourceGroup.ANDROID))
            is DeletionTarget.Category -> listOf(DeletionScope.Category(target.category))
        }

        fun floorScope(scope: DeletionScope): String = when (scope) {
            is DeletionScope.Sources -> FloorScopes.group(scope.group)
            is DeletionScope.Category -> FloorScopes.category(scope.category)
        }

        fun encode(marker: DeletionMarker): String = JSON.encodeToString(DeletionMarker.serializer(), marker)

        /** An unreadable marker restarts nothing (the data stays; the user can run the deletion again). */
        fun decode(text: String): DeletionMarker? = try {
            JSON.decodeFromString(DeletionMarker.serializer(), text)
        } catch (expected: SerializationException) {
            null
        } catch (expected: IllegalArgumentException) {
            null
        }
    }
}
