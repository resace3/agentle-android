package dev.agentle.ai.api.validation

import dev.agentle.ai.api.OutputSchema
import dev.agentle.core.model.AiDataCategory
import kotlinx.serialization.Serializable

/** One supporting value of an AI insight; both parts are short display text ("Average sleep", "7 h 12 min"). */
@Serializable
public data class InsightSupport(val label: String, val value: String)

/**
 * An AI-written insight (InsightSchema v1). The model writes only text and names the AI categories its finding used;
 * the app owns everything else an [dev.agentle.core.model.Insight] holds (id, kind, period, strength, confidence,
 * origin, creation time, state), and a model that sends one of those fields is rejected with
 * [OutputCodes.FORBIDDEN_FIELD]. Every property is required; "not applicable" is an explicit `null`.
 */
@Serializable
public data class InsightOutput(
    val schemaVersion: Int,
    val title: String,
    val finding: String,
    val supportingData: List<InsightSupport>,
    val categories: List<AiDataCategory>,
    val caveat: String?,
)

/**
 * InsightSchema v1, a design of this module (the brief names the schema without defining it). Limits follow the
 * insight card (spec section 22) and the JITAI text limits of R10 section 11.2 where they overlap. Checks beyond the
 * schema: every category must be one the request sent ([OutputCodes.CATEGORY_NOT_IN_REQUEST]) and every string passes
 * [AiTextPolicy], including number provenance (L9).
 */
public object InsightSchema {
    public const val NAME: String = "InsightSchema"
    public const val VERSION: Int = 1
    public const val MAX_BYTES: Int = 8192
    public const val MAX_SUPPORT_ITEMS: Int = 4
    public const val MAX_CATEGORIES: Int = 6

    public val TITLE: TextRules = TextRules(maxChars = 60, maxSentences = 1)
    public val FINDING: TextRules = TextRules(maxChars = 280, maxSentences = 3)
    public val SUPPORT_LABEL: TextRules = TextRules(maxChars = 40, maxSentences = 1)
    public val SUPPORT_VALUE: TextRules = TextRules(maxChars = 40, maxSentences = 1)
    public val CAVEAT: TextRules = TextRules(maxChars = 200, maxSentences = 2)

    /** Fields of [dev.agentle.core.model.Insight] that the app sets. */
    public val APP_OWNED_FIELDS: Set<String> =
        setOf("id", "kind", "periodStart", "periodEnd", "strength", "confidence", "origin", "createdAt", "state")

    public val ROOT: SchemaNode.ObjectNode = SchemaNode.ObjectNode(
        properties = linkedMapOf(
            "schemaVersion" to SchemaNode.ConstNode(VERSION.toLong(), OutputCodes.UNSUPPORTED_SCHEMA_VERSION),
            "title" to textNode(TITLE),
            "finding" to textNode(FINDING),
            "supportingData" to SchemaNode.ArrayNode(
                SchemaNode.ObjectNode(linkedMapOf("label" to textNode(SUPPORT_LABEL), "value" to textNode(SUPPORT_VALUE))),
                maxItems = MAX_SUPPORT_ITEMS,
            ),
            "categories" to SchemaNode.ArrayNode(
                SchemaNode.StringNode(allowed = AiDataCategory.entries.map { it.name }),
                minItems = 1,
                maxItems = MAX_CATEGORIES,
                uniqueItems = true,
            ),
            "caveat" to textNode(CAVEAT, nullable = true),
        ),
        forbidden = APP_OWNED_FIELDS,
    )

    public val SCHEMA: OutputSchema =
        OutputSchema(NAME, VERSION, renderJsonSchema("urn:agentle:insight:v$VERSION", "$NAME v$VERSION", ROOT).toString())

    public val validator: SchemaValidator<InsightOutput> = Validator

    private object Validator : SchemaBackedValidator<InsightOutput>(SCHEMA, ROOT, InsightOutput.serializer(), MAX_BYTES) {
        override fun semanticIssues(value: InsightOutput, context: OutputValidationContext): List<ValidationIssue> {
            val issues = mutableListOf<ValidationIssue>()
            context.requestCategories?.let { sent ->
                value.categories.forEachIndexed { index, category ->
                    if (category !in sent) {
                        issues += ValidationIssue(OutputCodes.CATEGORY_NOT_IN_REQUEST, "/categories/$index", ValidationStage.S6_SEMANTIC)
                    }
                }
            }
            val fields = buildList {
                add(TextField("/title", value.title, TITLE))
                add(TextField("/finding", value.finding, FINDING))
                value.supportingData.forEachIndexed { index, item ->
                    add(TextField("/supportingData/$index/label", item.label, SUPPORT_LABEL))
                    add(TextField("/supportingData/$index/value", item.value, SUPPORT_VALUE))
                }
                value.caveat?.let { add(TextField("/caveat", it, CAVEAT)) }
            }
            return issues + textPolicyIssues(fields, context)
        }
    }
}
