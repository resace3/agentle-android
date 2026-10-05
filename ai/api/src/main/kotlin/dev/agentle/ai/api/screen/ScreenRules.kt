package dev.agentle.ai.api.screen

import dev.agentle.ai.api.validation.OutputCodes
import dev.agentle.ai.api.validation.OutputValidationContext
import dev.agentle.ai.api.validation.SchemaNode
import dev.agentle.ai.api.validation.SchemaWalker
import dev.agentle.ai.api.validation.TextField
import dev.agentle.ai.api.validation.TextRules
import dev.agentle.ai.api.validation.ValidationIssue
import dev.agentle.ai.api.validation.ValidationStage
import dev.agentle.ai.api.validation.textNode
import dev.agentle.ai.api.validation.textPolicyIssues
import kotlinx.serialization.json.Json

/**
 * The checks a [ScreenSpec] passes before it is saved or drawn. [node] is its schema (stage S4: known parts, closed
 * fields, metric codes, day ranges, sizes); [semanticIssues] adds what a schema cannot say (stage S6): unique ids, a
 * root, every child id naming exactly one other part, every part reachable from the root, at most
 * [ScreenCatalog.MAX_DEPTH] levels, no total of a heart rate, and the text policy without numbers on every text.
 */
public object ScreenRules {
    /** A screen's title: the text policy, one sentence and no number (the parts show their own day ranges). */
    public val TITLE: TextRules = TextRules(maxChars = 40, maxSentences = 1, numbersForbidden = true)

    /** A Text part: the text policy, at most two sentences and no number. */
    public val TEXT: TextRules = TextRules(maxChars = 120, maxSentences = 2, numbersForbidden = true)

    private val ID = SchemaNode.StringNode(pattern = ScreenCatalog.ID_PATTERN)
    private val METRIC = SchemaNode.StringNode(allowed = ScreenCatalog.METRICS.keys.toList())
    private val DAYS = SchemaNode.IntegerNode(minimum = 1, maximum = ScreenCatalog.MAX_DAYS.toLong())

    private val PART: SchemaNode.UnionNode = SchemaNode.UnionNode(
        discriminator = ScreenCatalog.TYPE_KEY,
        variants = linkedMapOf(
            part(
                ScreenCatalog.COLUMN,
                "children" to SchemaNode.ArrayNode(ID, minItems = 1, maxItems = ScreenCatalog.MAX_PARTS - 1, uniqueItems = true),
            ),
            part(
                ScreenCatalog.ROW,
                "children" to SchemaNode.ArrayNode(ID, minItems = 1, maxItems = ScreenCatalog.MAX_ROW_ITEMS, uniqueItems = true),
            ),
            part(ScreenCatalog.CARD, "child" to ID),
            part(ScreenCatalog.TEXT, "text" to textNode(TEXT), "variant" to SchemaNode.StringNode(allowed = ScreenCatalog.TEXT_VARIANTS)),
            part(ScreenCatalog.DIVIDER),
            part(
                ScreenCatalog.METRIC_TILE,
                "metric" to METRIC,
                "days" to DAYS,
                "show" to SchemaNode.StringNode(allowed = ScreenCatalog.TILE_SHOWS),
            ),
            part(
                ScreenCatalog.TREND_CHART,
                "metric" to METRIC,
                "days" to DAYS,
                "style" to SchemaNode.StringNode(allowed = ScreenCatalog.CHART_STYLES),
            ),
        ),
    )

    /** The schema of a screen as the model writes it. */
    public fun node(nullable: Boolean = false): SchemaNode.ObjectNode = SchemaNode.ObjectNode(
        properties = linkedMapOf(
            "title" to textNode(TITLE),
            "components" to SchemaNode.ArrayNode(PART, minItems = 1, maxItems = ScreenCatalog.MAX_PARTS),
        ),
        nullable = nullable,
    )

    /** S6 issues of a decoded [screen] found at [path] (a JSON pointer such as `/screen`). */
    public fun semanticIssues(screen: ScreenSpec, path: String, context: OutputValidationContext): List<ValidationIssue> =
        structureIssues(screen, path) + textPolicyIssues(textFields(screen, path), context)

    /**
     * Every issue of a screen that was stored or built by the app, checked again before it is drawn: the schema, the
     * structure and the text policy (without number provenance, which only the request that made it had).
     */
    public fun recheck(screen: ScreenSpec): List<ValidationIssue> =
        SchemaWalker.walk(node(), ScreenJson.encodeToJsonElement(ScreenSpec.serializer(), screen)) +
            semanticIssues(screen, "", OutputValidationContext.DISPLAY_RECHECK)

    /** Every AI-written text of [screen] with its limits. */
    public fun textFields(screen: ScreenSpec, path: String): List<TextField> = listOf(TextField("$path/title", screen.title, TITLE)) +
        screen.components.mapIndexedNotNull { index, part ->
            (part as? TextPart)?.let { TextField("$path/components/$index/text", it.text, TEXT) }
        }

    /** The ids [part] places inside itself. */
    public fun childIds(part: ScreenPart): List<String> = when (part) {
        is ColumnPart -> part.children
        is RowPart -> part.children
        is CardPart -> listOf(part.child)
        is TextPart, is DividerPart, is MetricTilePart, is TrendChartPart -> emptyList()
    }

    private fun structureIssues(screen: ScreenSpec, path: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val parts = "$path/components"
        val indexOf = HashMap<String, Int>()
        screen.components.forEachIndexed { index, part ->
            if (indexOf.putIfAbsent(part.id, index) != null) issues += issue(OutputCodes.SCREEN_DUPLICATE_ID, "$parts/$index/id")
        }
        if (ScreenCatalog.ROOT_ID !in indexOf) issues += issue(OutputCodes.SCREEN_ROOT_MISSING, parts)
        if (issues.isNotEmpty()) return issues

        // Each part other than the root has exactly one parent: the first part that lists it.
        val parentOf = HashMap<String, Int>()
        screen.components.forEachIndexed { index, part ->
            childIds(part).forEachIndexed { position, childId ->
                val at = if (part is CardPart) "$parts/$index/child" else "$parts/$index/children/$position"
                val unknown = childId !in indexOf || childId == ScreenCatalog.ROOT_ID || childId == part.id
                if (unknown || parentOf.putIfAbsent(childId, index) != null) issues += issue(OutputCodes.SCREEN_BAD_REFERENCE, at)
            }
        }

        val depth = depths(screen, indexOf, parentOf)
        screen.components.forEachIndexed { index, part ->
            val level = depth[part.id]
            when {
                level == null -> issues += issue(OutputCodes.SCREEN_UNREACHABLE, "$parts/$index")
                level > ScreenCatalog.MAX_DEPTH -> issues += issue(OutputCodes.SCREEN_TOO_DEEP, "$parts/$index")
            }
            if (part is MetricTilePart && part.show == TOTAL && part.metric in ScreenCatalog.RATE_METRICS) {
                issues += issue(OutputCodes.SCREEN_TOTAL_OF_RATE, "$parts/$index/show")
            }
        }
        return issues
    }

    /** The level of every part reachable from the root along first-parent links (the root is level 1). */
    private fun depths(screen: ScreenSpec, indexOf: Map<String, Int>, parentOf: Map<String, Int>): Map<String, Int> {
        val depth = hashMapOf(ScreenCatalog.ROOT_ID to 1)
        val queue = ArrayDeque(listOf(ScreenCatalog.ROOT_ID))
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val index = indexOf.getValue(id)
            childIds(screen.components[index]).forEach { childId ->
                if (parentOf[childId] == index && childId !in depth) {
                    depth[childId] = depth.getValue(id) + 1
                    queue += childId
                }
            }
        }
        return depth
    }

    private fun issue(code: String, path: String) = ValidationIssue(code, path, ValidationStage.S6_SEMANTIC)

    private fun part(type: String, vararg fields: Pair<String, SchemaNode>): Pair<String, SchemaNode.ObjectNode> =
        type to SchemaNode.ObjectNode(
            linkedMapOf<String, SchemaNode>(
                "id" to ID,
                ScreenCatalog.TYPE_KEY to SchemaNode.StringNode(allowed = listOf(type)),
            ).apply { putAll(fields) },
        )

    private const val TOTAL = "total"
}

/** The JSON of screens: the A2UI messages and the stored copies. */
public val ScreenJson: Json = Json {
    ignoreUnknownKeys = false
    explicitNulls = true
}
