package dev.agentle.data.jitai

import dev.agentle.core.database.dao.CurrentDefinitionRow
import dev.agentle.core.database.entity.JitaiDefinitionEntity
import dev.agentle.core.database.entity.JitaiDefinitionHistoryEntity
import dev.agentle.core.time.AgentleClock
import dev.agentle.data.DataAccess
import dev.agentle.data.Tx
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** One stored definition at one version, with its JSON (the DSL's canonical form). */
data class StoredDefinition(
    val id: String,
    val version: Int,
    val enabled: Boolean,
    /** A JitaiStatus name: DRAFT, PROPOSED, ACTIVE, PAUSED, EXPIRED, DECLINED or ARCHIVED. */
    val state: String,
    /** INTERVENTION or SUPPRESSION. */
    val kind: String,
    val category: String?,
    val origin: String,
    val contentHash: String,
    val json: String,
    val createdMs: Long,
    val modifiedMs: Long,
    val expiresMs: Long?,
)

/** The definition metadata without its JSON (lists and badges). */
data class DefinitionSummary(
    val id: String,
    val version: Int,
    val enabled: Boolean,
    val state: String,
    val kind: String,
    val category: String?,
    val origin: String,
    val modifiedMs: Long,
    val expiresMs: Long?,
)

/** What [JitaiDefinitionStore.save] stores: a new version when [contentHash] differs from the current one. */
data class DefinitionDraft(
    val id: String,
    val json: String,
    val contentHash: String,
    val enabled: Boolean,
    val state: String,
    val kind: String,
    val category: String?,
    val origin: String,
    val expiresMs: Long?,
)

/** The JitaiStatus names the store writes itself. */
object DefinitionStates {
    const val ACTIVE: String = "ACTIVE"
    const val PAUSED: String = "PAUSED"
    const val EXPIRED: String = "EXPIRED"
    const val ARCHIVED: String = "ARCHIVED"
}

/**
 * The JITAI definitions (`jitai_definition` + `jitai_definition_history`; round 2 correction 4). Every change of a
 * definition (save of a new version, enable, disable, a status change, expiry, the backoff pause and deletion) deletes
 * that JITAI's `jitai_timer` rows in the same transaction as the change (round 3 correction 1), so no timer of an old
 * version can fire; the engine re-plans afterwards. A new version also purges the JITAI's pooled AI texts written for
 * other content, in the same transaction. History rows are inserted once and never updated.
 */
interface JitaiDefinitionStore {
    /** The current version of every definition that is not ARCHIVED, oldest first. */
    suspend fun definitions(): List<StoredDefinition>

    suspend fun definition(id: String): StoredDefinition?

    /** Every saved version of [id], oldest first. */
    suspend fun history(id: String): List<StoredDefinition>

    fun observeDefinitions(): Flow<List<DefinitionSummary>>

    /** Creates the definition or stores [draft] as its new state; returns the stored current version. */
    suspend fun save(draft: DefinitionDraft): StoredDefinition

    /** False when [id] does not exist. */
    suspend fun setEnabled(id: String, enabled: Boolean): Boolean

    suspend fun setState(id: String, state: String): Boolean

    /** G02: the definition's expiry passed; its status becomes EXPIRED. */
    suspend fun markExpired(id: String, atMs: Long): Boolean

    /** R10 §9.6: [consecutiveIgnored] reached the backoff limit; the JITAI is paused until the user decides in the app. */
    suspend fun pauseForBackoff(id: String, consecutiveIgnored: Int, atMs: Long): Boolean

    /**
     * Deletes the definition, its history, runtime state, timers and pooled texts. The content-free decision ledger
     * stays (used keys, caps and cooldowns survive, R10 §8.8).
     */
    suspend fun delete(id: String): Boolean
}

internal class RoomJitaiDefinitionStore(private val access: DataAccess, private val clock: AgentleClock) : JitaiDefinitionStore {
    override suspend fun definitions(): List<StoredDefinition> = access.read { db.jitaiDao().currentDefinitions().map(::storedOf) }

    override suspend fun definition(id: String): StoredDefinition? = access.read { current(this, id) }

    override suspend fun history(id: String): List<StoredDefinition> = access.read {
        val definition = db.jitaiDao().definition(id) ?: return@read emptyList()
        db.jitaiDao().historyOf(id).map { version -> storedOf(definition, version) }
    }

    override fun observeDefinitions(): Flow<List<DefinitionSummary>> = flow {
        emitAll(access.database().jitaiDao().observeDefinitions().map { rows -> rows.map(::summaryOf) })
    }

    override suspend fun save(draft: DefinitionDraft): StoredDefinition = access.write {
        val dao = db.jitaiDao()
        val now = nowMs()
        val stored = dao.definition(draft.id)
        val newContent = stored == null || stored.contentHash != draft.contentHash
        val version = when {
            stored == null -> 1
            newContent -> stored.currentVersion + 1
            else -> stored.currentVersion
        }
        if (newContent) {
            dao.insertHistory(JitaiDefinitionHistoryEntity(draft.id, version, draft.json, draft.contentHash, now))
            db.aiDao().purgePool(draft.id, draft.contentHash)
        }
        val row = JitaiDefinitionEntity(
            id = draft.id,
            currentVersion = version,
            enabled = draft.enabled,
            state = draft.state,
            kind = draft.kind,
            category = draft.category,
            origin = draft.origin,
            contentHash = draft.contentHash,
            createdMs = stored?.createdMs ?: now,
            modifiedMs = now,
            expiresMs = draft.expiresMs,
        )
        if (stored == null) dao.insertDefinition(row) else if (row != stored.copy(modifiedMs = now)) dao.updateDefinition(row)
        if (stored == null || row != stored.copy(modifiedMs = now)) dao.deleteTimersOf(draft.id)
        checkNotNull(current(this, draft.id))
    }

    override suspend fun setEnabled(id: String, enabled: Boolean): Boolean = change(id) { it.copy(enabled = enabled) }

    override suspend fun setState(id: String, state: String): Boolean = change(id) { it.copy(state = state) }

    override suspend fun markExpired(id: String, atMs: Long): Boolean = change(id, atMs) { it.copy(state = DefinitionStates.EXPIRED) }

    override suspend fun pauseForBackoff(id: String, consecutiveIgnored: Int, atMs: Long): Boolean =
        change(id, atMs) { it.copy(state = DefinitionStates.PAUSED) }

    override suspend fun delete(id: String): Boolean = access.write {
        val dao = db.jitaiDao()
        if (dao.definition(id) == null) return@write false
        dao.deleteTimersOf(id)
        dao.deleteRuntime(id)
        dao.deleteHistory(id)
        dao.deleteDefinition(id)
        db.aiDao().purgePool(id, null)
        true
    }

    /** Applies [transform] and deletes the JITAI's timers in the same transaction; a no-op change writes nothing. */
    private suspend fun change(id: String, atMs: Long? = null, transform: (JitaiDefinitionEntity) -> JitaiDefinitionEntity): Boolean =
        access.write {
            val dao = db.jitaiDao()
            val stored = dao.definition(id) ?: return@write false
            val next = transform(stored)
            if (next != stored) {
                dao.updateDefinition(next.copy(modifiedMs = atMs ?: nowMs()))
                dao.deleteTimersOf(id)
            }
            true
        }

    private suspend fun current(tx: Tx, id: String): StoredDefinition? {
        val definition = tx.db.jitaiDao().definition(id) ?: return null
        val version = tx.db.jitaiDao().history(id, definition.currentVersion) ?: return null
        return storedOf(definition, version)
    }

    private fun nowMs(): Long = clock.now().toEpochMilliseconds()

    companion object {
        fun storedOf(row: CurrentDefinitionRow): StoredDefinition {
            val d = row.definition
            return StoredDefinition(
                d.id, d.currentVersion, d.enabled, d.state, d.kind, d.category, d.origin, d.contentHash, row.json, d.createdMs,
                d.modifiedMs, d.expiresMs,
            )
        }

        fun storedOf(definition: JitaiDefinitionEntity, version: JitaiDefinitionHistoryEntity): StoredDefinition = StoredDefinition(
            id = definition.id,
            version = version.version,
            enabled = definition.enabled,
            state = definition.state,
            kind = definition.kind,
            category = definition.category,
            origin = definition.origin,
            contentHash = version.contentHash,
            json = version.json,
            createdMs = definition.createdMs,
            modifiedMs = if (version.version == definition.currentVersion) definition.modifiedMs else version.savedMs,
            expiresMs = definition.expiresMs,
        )

        fun summaryOf(row: JitaiDefinitionEntity): DefinitionSummary =
            DefinitionSummary(row.id, row.currentVersion, row.enabled, row.state, row.kind, row.category, row.origin, row.modifiedMs, row.expiresMs)
    }
}
