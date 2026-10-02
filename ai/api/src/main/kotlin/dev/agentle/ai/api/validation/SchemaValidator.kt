package dev.agentle.ai.api.validation

import dev.agentle.ai.api.AiRequestEnvelope
import dev.agentle.ai.api.OutputSchema
import dev.agentle.core.model.AiDataCategory
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * What the output is checked against besides its schema. [requestCategories] are the AI categories the request sent
 * (an output may only name those, [OutputCodes.CATEGORY_NOT_IN_REQUEST]); [provenance] holds the numbers AI text may
 * use (check L9). A null value skips that check: use it only to re-check output that already passed with the request
 * context, for example before display.
 */
public data class OutputValidationContext(val requestCategories: Set<AiDataCategory>? = null, val provenance: NumberProvenance? = null) {
    public companion object {
        /** Neither the category check nor L9 (display-time re-check of stored output). */
        public val DISPLAY_RECHECK: OutputValidationContext = OutputValidationContext()

        /**
         * The context of the response to [envelope]: its categories, and the numbers of its data, of the user's text and
         * of [templates] (the app's own template text for this output, which may include the envelope's instructions).
         */
        public fun forEnvelope(envelope: AiRequestEnvelope, templates: Iterable<String> = emptyList()): OutputValidationContext =
            OutputValidationContext(envelope.categories, NumberProvenance.fromEnvelope(envelope) + NumberProvenance.fromTexts(templates))
    }
}

/**
 * Validates one parsed document of one [schema] (stages S4-S6). The shared stages S0-S3 run in [AiOutputValidator]
 * first. Implementations never evaluate anything from the document and report codes and paths only.
 */
public interface SchemaValidator<out T : Any> {
    public val schema: OutputSchema

    /** Limit for the UTF-8 size of the extracted JSON text (S1). */
    public val maxBytes: Int

    public fun validateDocument(document: JsonObject, context: OutputValidationContext): OutputValidation<T>
}

/** One AI-written string, where it is ([path], a JSON pointer) and its limits. */
public data class TextField(val path: String, val text: String, val rules: TextRules)

/** The decoder used after the schema walk: no unknown keys, no coercion, no lenient syntax. */
internal val StrictJson: Json = Json {
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
    explicitNulls = true
    allowSpecialFloatingPointValues = false
    allowStructuredMapKeys = false
}

/** [AiTextPolicy] over every field; each failed check becomes one S6 issue carrying the check id. */
public fun textPolicyIssues(fields: Iterable<TextField>, provenance: NumberProvenance?): List<ValidationIssue> = fields.flatMap { field ->
    AiTextPolicy.check(field.text, field.rules, provenance).map { check ->
        ValidationIssue(check.code, field.path, ValidationStage.S6_SEMANTIC, check.id)
    }
}

/**
 * A validator whose whole document is described by [root]: S4 walks [root] and stops on any issue, S5 decodes with the
 * strict decoder (a failure is [OutputCodes.INTERNAL]), S6 runs [semanticIssues].
 */
public abstract class SchemaBackedValidator<T : Any>(
    final override val schema: OutputSchema,
    public val root: SchemaNode,
    private val serializer: KSerializer<T>,
    final override val maxBytes: Int,
) : SchemaValidator<T> {
    final override fun validateDocument(document: JsonObject, context: OutputValidationContext): OutputValidation<T> {
        val structural = SchemaWalker.walk(root, document)
        if (structural.isNotEmpty()) return OutputValidation.invalid(schema, structural)
        val value = try {
            StrictJson.decodeFromJsonElement(serializer, document)
        } catch (expected: IllegalArgumentException) {
            // The message of a decoding exception can quote the input; only the code is kept (privacy-ai-11).
            return OutputValidation.invalid(schema, listOf(ValidationIssue(OutputCodes.INTERNAL, "", ValidationStage.S5_DECODE)))
        }
        val semantic = semanticIssues(value, context)
        return if (semantic.isEmpty()) OutputValidation.Valid(value, schema) else OutputValidation.invalid(schema, semantic)
    }

    /** S6 checks of a decoded value. Must cover every AI-written string with [textPolicyIssues]. */
    protected abstract fun semanticIssues(value: T, context: OutputValidationContext): List<ValidationIssue>
}
