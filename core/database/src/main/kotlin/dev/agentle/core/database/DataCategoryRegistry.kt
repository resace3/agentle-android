package dev.agentle.core.database

import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventType
import dev.agentle.core.model.Lineage
import dev.agentle.core.model.SourceFamily

/**
 * One deletion action (docs/ARCHITECTURE.md §5.5; round 1 correction 5; round 2 correction 6): every stored row whose
 * data, or whose inputs, come from a source [Family] or belong to a data [Category]. "Delete wearable data" and "delete
 * Android-collected data" are families; "delete insights", "delete intervention history" and "delete generated media"
 * are the categories INSIGHTS, INTERVENTIONS and GENERATED_MEDIA.
 */
sealed interface DeletionScope {
    /** Stable text form, stored in the deletion marker: `F:<family>` or `C:<category>`. */
    val code: String

    /** The inputs being deleted, for the scrubber of derived JSON. */
    val deleted: Lineage

    /** The lineage token of derived rows that depend on this scope. */
    val token: String

    data class Family(val family: SourceFamily) : DeletionScope {
        override val code: String get() = "F:${family.name}"
        override val deleted: Lineage get() = Lineage(families = setOf(family))
        override val token: String get() = LineageCodec.token(family)
    }

    data class Category(val category: DataCategory) : DeletionScope {
        override val code: String get() = "C:${category.name}"
        override val deleted: Lineage get() = Lineage(categories = setOf(category))
        override val token: String get() = LineageCodec.token(category)
    }

    companion object {
        fun parse(code: String): DeletionScope? = when {
            code.startsWith("F:") -> SourceFamily.entries.firstOrNull { it.name == code.substring(2) }?.let(::Family)
            code.startsWith("C:") -> DataCategory.entries.firstOrNull { it.name == code.substring(2) }?.let(::Category)
            else -> null
        }
    }
}

/**
 * One bounded, resumable piece of a deletion (round 4 correction 3). Every step runs in chunks of at most
 * [DataCategoryRegistry.CHUNK_ROWS] rows per transaction; [pendingSql] counts the rows it still has to process, which
 * is 0 once it finished (the verification query).
 */
sealed interface DeletionStep {
    val table: String

    /** A boolean SQL expression over the table's columns, with `?` placeholders bound to [args]. */
    val predicate: String
    val args: List<Any?>

    val pendingSql: String get() = "SELECT COUNT(*) FROM $table WHERE $predicate"

    /** Deletes the rows. */
    data class Delete(override val table: String, override val predicate: String, override val args: List<Any?> = emptyList()) :
        DeletionStep

    /** Sets [assignments] on the rows; [predicate] must no longer match an updated row. */
    data class Update(
        override val table: String,
        val assignments: String,
        val assignmentArgs: List<Any?>,
        override val predicate: String,
        override val args: List<Any?> = emptyList(),
    ) : DeletionStep

    /**
     * Rewrites [jsonColumns] of the rows through the content scrubber (round 3 correction 3), sets [nullColumns] to NULL
     * and removes [token] from `lineage`, which ends the match.
     */
    data class Scrub(override val table: String, val jsonColumns: List<String>, val nullColumns: List<String>, val token: String) :
        DeletionStep {
        override val predicate: String get() = "instr(lineage, ?) > 0"
        override val args: List<Any?> get() = listOf(token)
    }

    /** Removes notification titles and texts from stored payloads (category NOTIFICATION_CONTENT). */
    data class ScrubNotificationText(val typeIds: List<Long>) : DeletionStep {
        override val table: String get() = "event"
        override val predicate: String
            get() = "type IN (${placeholders(typeIds.size)}) AND " +
                "(instr(payload_json, '\"title\":') > 0 OR instr(payload_json, '\"text\":') > 0)"
        override val args: List<Any?> get() = typeIds
    }
}

/** How one table takes part in deletions. Every table of the schema has exactly one entry (round 4 correction 2). */
data class TableRule(
    val table: String,
    /** Columns holding JSON documents; each is either deleted with its row, scrubbed, or covered by [exemption]. */
    val jsonColumns: Set<String> = emptySet(),
    /** Why no category or family deletion touches this table; null when [steps] can select rows. */
    val exemption: String? = null,
    val steps: (DeletionScope, TermIds) -> List<DeletionStep> = { _, _ -> emptyList() },
)

/** Term ids a deletion plan needs: the event types of a category and the sources of a family. */
class TermIds(private val types: Map<String, Long>, private val sources: Map<String, Long>) {
    fun typesOf(category: DataCategory): List<Long> = EventType.entries.filter { it.category == category }.mapNotNull { types[it.name] }

    fun typeIds(vararg types: EventType): List<Long> = types.mapNotNull { this.types[it.name] }

    fun sourcesOf(family: SourceFamily): List<Long> = sources.filter { (value, _) -> familyOf(value) == family }.values.sorted()

    private fun familyOf(source: String): SourceFamily =
        runCatching { SourceFamily.of(DataSourceId(source)) }.getOrDefault(SourceFamily.OTHER)
}

/**
 * Which rows each deletion scope removes, clears or scrubs, table by table (R04 SEC-DEL-05; round 4 correction 2). A
 * test fails when a table of the schema, or a JSON column, is missing here. Steps run in list order: primary data first,
 * then derived rows by lineage, then orphans.
 */
object DataCategoryRegistry {
    const val CHUNK_ROWS: Int = 500

    val rules: List<TableRule> = listOf(
        TableRule("event", jsonColumns = setOf("payload_json", "provenance_json")) { scope, ids -> eventSteps(scope, ids) },
        TableRule("event_tombstone") { scope, ids ->
            val selection = primarySelection(scope, ids)
            if (selection == null) emptyList() else listOf(DeletionStep.Delete("event_tombstone", selection.first, selection.second))
        },
        TableRule("dedup_collision") { _, _ ->
            listOf(DeletionStep.Delete("dedup_collision", "dedup_hash NOT IN (SELECT dedup_hash FROM event)"))
        },
        TableRule("term") { _, _ ->
            listOf(DeletionStep.Delete("term", "kind = ${TermKind.ACCOUNT} AND id NOT IN (SELECT DISTINCT account FROM event)"))
        },
        TableRule("engine_state", exemption = "engine counters, the database generation and the deletion marker; no personal values"),
        TableRule("dirty_day", exemption = "engine days waiting for recomputation; no values"),
        TableRule("ingest_floor", exemption = "deletion watermarks; no values"),
        TableRule("sync_cursor", exemption = "opaque sync positions; deletions bump their generation and import floor instead"),
        TableRule("source_coverage", exemption = "completeness instants of synced streams; no values"),
        TableRule("collector_coverage") { scope, _ -> collectorCoverageSteps(scope) },
        TableRule("connector_state", jsonColumns = setOf("stream_permissions"), exemption = "connection state; deleting never disconnects"),
        TableRule("google_health_state", exemption = "connection state and granted scopes; deleting never disconnects"),
        TableRule("engine_day_summary") { scope, _ -> listOf(byLineage("engine_day_summary", scope)) },
        TableRule("metric_source_policy", exemption = "source priorities (configuration)"),
        TableRule("derived_feature") { scope, _ -> listOf(byLineage("derived_feature", scope)) },
        TableRule("insight", jsonColumns = setOf("support_json")) { scope, _ -> allOrLineage("insight", scope, DataCategory.INSIGHTS) },
        TableRule("jitai_definition", exemption = "the user's rules; removed only by delete-all"),
        TableRule(
            "jitai_definition_history",
            jsonColumns = setOf("json"),
            exemption = "versions of the user's rules; removed only by delete-all",
        ),
        TableRule("jitai_runtime") { _, _ ->
            listOf(
                DeletionStep.Update(
                    "jitai_runtime",
                    "pending_change_seq = NULL, pending_event_type = NULL, pending_event_ms = NULL, pending_activity_state = NULL",
                    emptyList(),
                    "pending_change_seq IS NOT NULL",
                ),
            )
        },
        TableRule(
            "jitai_decision",
            jsonColumns = setOf("snapshot_json", "trace_json", "trace_summary_json", "implied_state_json"),
        ) { scope, _ -> decisionSteps(scope) },
        TableRule("intervention_outcome", jsonColumns = setOf("metric_json")) { scope, _ ->
            if (scope.isCategory(DataCategory.INTERVENTIONS)) {
                listOf(DeletionStep.Delete("intervention_outcome", "1 = 1"))
            } else {
                listOf(DeletionStep.Scrub("intervention_outcome", listOf("metric_json"), listOf("outcome_value"), scope.token))
            }
        },
        TableRule("jitai_response_log") { scope, _ ->
            if (scope.isCategory(DataCategory.INTERVENTIONS)) listOf(DeletionStep.Delete("jitai_response_log", "1 = 1")) else emptyList()
        },
        TableRule("jitai_eval_log", jsonColumns = setOf("trace_json")) { scope, _ ->
            allOrLineage("jitai_eval_log", scope, DataCategory.INTERVENTIONS)
        },
        TableRule("jitai_timer", exemption = "wake-up schedule of the user's rules; no personal values"),
        TableRule("ai_request", exemption = "AI audit metadata: purpose, category names and sizes, never values (R04 SEC-AI-06)"),
        TableRule("ai_result_meta", exemption = "AI audit metadata: schema, validity and closed error codes"),
        TableRule("ai_text_pool") { _, _ -> listOf(DeletionStep.Delete("ai_text_pool", "1 = 1")) },
        TableRule("media_artifact") { scope, _ -> allOrLineage("media_artifact", scope, DataCategory.GENERATED_MEDIA) },
        TableRule("user_goal") { scope, _ ->
            if (scope.isCategory(DataCategory.GOALS)) listOf(DeletionStep.Delete("user_goal", "1 = 1")) else emptyList()
        },
        TableRule("user_log") { scope, _ ->
            val all = scope.isCategory(DataCategory.USER_LOGS) || (scope as? DeletionScope.Family)?.family == SourceFamily.USER
            if (all) listOf(DeletionStep.Delete("user_log", "1 = 1")) else emptyList()
        },
        TableRule("permission_snapshot", exemption = "capability states; no personal values"),
        TableRule("diagnostic_log", jsonColumns = setOf("fields_json")) { scope, _ -> listOf(byLineage("diagnostic_log", scope)) },
    )

    val tables: Set<String> get() = rules.map { it.table }.toSet()

    fun rule(table: String): TableRule? = rules.firstOrNull { it.table == table }

    /** The steps of [scope], in execution order. */
    fun plan(scope: DeletionScope, ids: TermIds): List<DeletionStep> = rules.flatMap { it.steps(scope, ids) }

    /** The `event` selection of [scope] (also used for tombstones), or null when no stored event can match. */
    private fun primarySelection(scope: DeletionScope, ids: TermIds): Pair<String, List<Any?>>? {
        val list = when (scope) {
            is DeletionScope.Family -> ids.sourcesOf(scope.family).takeIf { it.isNotEmpty() }?.let { "source" to it }
            is DeletionScope.Category -> ids.typesOf(scope.category).takeIf { it.isNotEmpty() }?.let { "type" to it }
        } ?: return null
        return "${list.first} IN (${placeholders(list.second.size)})" to list.second
    }

    private fun eventSteps(scope: DeletionScope, ids: TermIds): List<DeletionStep> {
        val steps = mutableListOf<DeletionStep>()
        primarySelection(scope, ids)?.let { (predicate, args) -> steps += DeletionStep.Delete("event", predicate, args) }
        if (scope.isCategory(DataCategory.NOTIFICATION_CONTENT)) {
            val types = ids.typeIds(EventType.NOTIFICATION_POSTED, EventType.NOTIFICATION_REMOVED)
            if (types.isNotEmpty()) steps += DeletionStep.ScrubNotificationText(types)
        }
        return steps
    }

    private fun collectorCoverageSteps(scope: DeletionScope): List<DeletionStep> {
        if ((scope as? DeletionScope.Family)?.family != SourceFamily.ANDROID) return emptyList()
        return listOf(DeletionStep.Delete("collector_coverage", "to_ms IS NOT NULL"))
    }

    private fun decisionSteps(scope: DeletionScope): List<DeletionStep> = if (scope.isCategory(DataCategory.INTERVENTIONS)) {
        // "Delete intervention history": content goes, the content-free ledger stays for caps and cooldowns.
        listOf(
            DeletionStep.Update(
                "jitai_decision",
                "snapshot_json = NULL, snapshot_hash = NULL, trace_json = NULL, trace_summary_json = NULL, content_ref = NULL, " +
                    "response = 'NONE', responded_ms = NULL, lineage = ?",
                listOf(LineageCodec.EMPTY),
                "snapshot_json IS NOT NULL OR snapshot_hash IS NOT NULL OR trace_json IS NOT NULL OR trace_summary_json IS NOT NULL " +
                    "OR content_ref IS NOT NULL OR response != 'NONE' OR responded_ms IS NOT NULL",
            ),
        )
    } else {
        listOf(DeletionStep.Scrub("jitai_decision", listOf("snapshot_json", "trace_json", "trace_summary_json"), emptyList(), scope.token))
    }

    private fun byLineage(table: String, scope: DeletionScope): DeletionStep =
        DeletionStep.Delete(table, "instr(lineage, ?) > 0", listOf(scope.token))

    /** Every row when [scope] is [category] itself, otherwise the rows whose lineage includes [scope]. */
    private fun allOrLineage(table: String, scope: DeletionScope, category: DataCategory): List<DeletionStep> =
        if (scope.isCategory(category)) listOf(DeletionStep.Delete(table, "1 = 1")) else listOf(byLineage(table, scope))

    private fun DeletionScope.isCategory(category: DataCategory): Boolean = (this as? DeletionScope.Category)?.category == category
}

internal fun placeholders(count: Int): String = List(count) { "?" }.joinToString(", ")
