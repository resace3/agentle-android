package dev.agentle.core.database

import dev.agentle.core.database.dao.TermDao
import dev.agentle.core.database.entity.TermEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory view of the `term` dictionary (round 2 correction 8: event types, sources and accounts are stored as small
 * integers). Terms are created only inside write transactions; a rolled-back transaction or a deletion of terms
 * invalidates the whole cache, so it never hands out an id that is not in the database.
 */
class TermCache {
    private val ids = ConcurrentHashMap<Key, Long>()
    private val values = ConcurrentHashMap<Long, TermEntity>()

    @Volatile private var loaded = false

    private data class Key(val kind: Int, val value: String)

    /** The id of ([kind], [value]), creating the term. Call inside a write transaction. */
    suspend fun idOrCreate(dao: TermDao, kind: Int, value: String): Long {
        ensureLoaded(dao)
        ids[Key(kind, value)]?.let { return it }
        val existing = dao.find(kind, value)
        val id = existing?.id ?: dao.insert(TermEntity(kind = kind, value = value))
        remember(TermEntity(id = id, kind = kind, value = value))
        return id
    }

    /** The id of ([kind], [value]) if the term exists. */
    suspend fun idOf(dao: TermDao, kind: Int, value: String): Long? {
        ensureLoaded(dao)
        ids[Key(kind, value)]?.let { return it }
        return dao.find(kind, value)?.also(::remember)?.id
    }

    /** The term of [id], or null for an unknown id (or [TermKind.NO_ACCOUNT]). */
    suspend fun term(dao: TermDao, id: Long): TermEntity? {
        ensureLoaded(dao)
        values[id]?.let { return it }
        // A term created by another writer after the last load.
        loaded = false
        ensureLoaded(dao)
        return values[id]
    }

    /** Every term of [kind]. */
    suspend fun all(dao: TermDao, kind: Int): List<TermEntity> {
        ensureLoaded(dao)
        return values.values.filter { it.kind == kind }.sortedBy { it.id }
    }

    /** Forgets everything; the next use reloads from the database. */
    fun invalidate() {
        loaded = false
        ids.clear()
        values.clear()
    }

    private suspend fun ensureLoaded(dao: TermDao) {
        if (loaded) return
        val rows = dao.all()
        ids.clear()
        values.clear()
        rows.forEach(::remember)
        loaded = true
    }

    private fun remember(term: TermEntity) {
        ids[Key(term.kind, term.value)] = term.id
        values[term.id] = term
    }
}
