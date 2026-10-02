@file:Suppress("SpreadOperator") // SQL bind arguments are passed as varargs; the arrays are small.

package dev.agentle.core.database

import dev.agentle.core.model.EventCodec
import dev.agentle.core.model.Lineage
import kotlinx.serialization.json.JsonObject

/** A chunk found that its deletion is no longer the current one (another deletion or delete-all took over). */
class DeletionAbortedException : Exception("deletion aborted")

/**
 * Rewrites derived JSON (decision snapshots and traces, outcome metrics) so that the values of a deleted family or
 * category disappear (round 3 correction 3). Pure; the JITAI engine provides the precise one.
 */
fun interface ContentScrubber {
    fun scrub(json: String, deleted: Lineage): String
}

/**
 * Runs [DeletionStep]s in bounded chunks (round 4 correction 3): at most [chunkRows] rows per IMMEDIATE transaction,
 * each guarded and each recording its progress in the same transaction, so a stopped worker resumes where it left off
 * and no transaction holds the writer for long.
 */
class ChunkedDeleter(
    private val transactions: DatabaseTransactions,
    private val scrubber: ContentScrubber,
    private val chunkRows: Int = DataCategoryRegistry.CHUNK_ROWS,
) {
    init {
        require(chunkRows in 1..DataCategoryRegistry.CHUNK_ROWS) { "chunkRows must be 1..${DataCategoryRegistry.CHUNK_ROWS}" }
    }

    /**
     * Runs [step] until nothing matches. Each chunk transaction starts with [guard] (false throws
     * [DeletionAbortedException] and changes nothing) and ends with [onChunk] (the rows processed by that chunk).
     * Returns the rows processed in total.
     */
    suspend fun run(
        step: DeletionStep,
        deleted: Lineage,
        guard: suspend SqlScope.() -> Boolean,
        onChunk: suspend SqlScope.(rows: Int) -> Unit,
    ): Long {
        var total = 0L
        while (true) {
            val rows = transactions.write {
                if (!guard()) throw DeletionAbortedException()
                val processed = chunk(step, deleted)
                if (processed > 0) onChunk(processed)
                processed
            }
            total += rows
            if (rows == 0) return total
        }
    }

    /** Rows [step] still has to process: 0 once it has run (the verification query). */
    suspend fun pending(step: DeletionStep): Long = transactions.read { queryLong(step.pendingSql, *step.args.toTypedArray()) ?: 0L }

    /** Values of [column] in the rows [step] will process (for example the files of media rows, deleted first). */
    suspend fun values(step: DeletionStep, column: String): List<String> = transactions.read {
        query("SELECT $column FROM ${step.table} WHERE ${step.predicate}", *step.args.toTypedArray()) { it.textOrNull(0) }.filterNotNull()
    }

    private suspend fun SqlScope.chunk(step: DeletionStep, deleted: Lineage): Int = when (step) {
        is DeletionStep.Delete -> {
            execute(
                "DELETE FROM ${step.table} WHERE rowid IN (SELECT rowid FROM ${step.table} WHERE ${step.predicate} LIMIT $chunkRows)",
                *step.args.toTypedArray(),
            )
            changes().toInt()
        }

        is DeletionStep.Update -> {
            execute(
                "UPDATE ${step.table} SET ${step.assignments} WHERE rowid IN " +
                    "(SELECT rowid FROM ${step.table} WHERE ${step.predicate} LIMIT $chunkRows)",
                *(step.assignmentArgs + step.args).toTypedArray(),
            )
            changes().toInt()
        }

        is DeletionStep.Scrub -> scrubChunk(step, deleted)

        is DeletionStep.ScrubNotificationText -> notificationTextChunk(step)
    }

    private class ScrubRow(val rowid: Long, val json: List<String?>, val lineage: String)

    private suspend fun SqlScope.scrubChunk(step: DeletionStep.Scrub, deleted: Lineage): Int {
        val columns = step.jsonColumns
        val rows = query(
            "SELECT rowid, ${columns.joinToString(", ")}, lineage FROM ${step.table} WHERE ${step.predicate} LIMIT $chunkRows",
            *step.args.toTypedArray(),
        ) { row -> ScrubRow(row.long(0), columns.indices.map { row.textOrNull(it + 1) }, row.text(columns.size + 1)) }
        val assignments = (columns.map { "$it = ?" } + step.nullColumns.map { "$it = NULL" } + "lineage = ?").joinToString(", ")
        rows.forEach { row ->
            val scrubbed = row.json.map { json -> json?.let { scrubber.scrub(it, deleted) } }
            val args: List<Any?> = scrubbed + LineageCodec.without(row.lineage, step.token) + row.rowid
            execute("UPDATE ${step.table} SET $assignments WHERE rowid = ?", *args.toTypedArray())
        }
        return rows.size
    }

    private suspend fun SqlScope.notificationTextChunk(step: DeletionStep.ScrubNotificationText): Int {
        val rows = query("SELECT rowid, payload_json FROM event WHERE ${step.predicate} LIMIT $chunkRows", *step.args.toTypedArray()) {
            it.long(0) to it.text(1)
        }
        rows.forEach { (rowid, json) -> execute("UPDATE event SET payload_json = ? WHERE rowid = ?", withoutNotificationText(json), rowid) }
        return rows.size
    }

    private fun withoutNotificationText(json: String): String {
        val tree = runCatching { EventCodec.json.parseToJsonElement(json) }.getOrNull() as? JsonObject
            // An unreadable document cannot be scrubbed field by field: keep only its kind.
            ?: return """{"kind":"notification","packageName":"","removed":"content"}"""
        return JsonObject(tree.filterKeys { it != "title" && it != "text" }).toString()
    }
}
