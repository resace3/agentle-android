package dev.agentle.ai.api.validation

import dev.agentle.ai.api.AiStructuredResult
import dev.agentle.ai.api.AiTextResult
import dev.agentle.ai.api.OutputSchema
import kotlinx.serialization.json.JsonObject

/**
 * Validates model output (docs/ARCHITECTURE.md section 9, R10 section 11.1): the shared stages S0-S3 on the raw text,
 * then the validator registered for the output's schema name and version (S4-S6). An output whose schema has no
 * validator fails closed with [OutputCodes.SCHEMA_NOT_ROUTED]. Nothing in an output is ever executed: it is parsed into
 * a tree, compared with a closed schema and decoded into fixed data classes. Results carry codes and paths only.
 */
public class AiOutputValidator(validators: Iterable<SchemaValidator<*>>) {
    private val routes: Map<Pair<String, Int>, SchemaValidator<*>>

    init {
        val list = validators.toList()
        val keys = list.map { it.schema.name to it.schema.version }
        require(keys.size == keys.toSet().size) { "one validator per schema name and version" }
        routes = list.associateBy { it.schema.name to it.schema.version }
    }

    /** The schemas this validator accepts. */
    public val schemas: List<OutputSchema> get() = routes.values.map { it.schema }

    public fun validate(result: AiStructuredResult, context: OutputValidationContext): OutputValidation<Any> =
        validate(result.json, result.schema, context)

    public fun validate(text: String, schema: OutputSchema, context: OutputValidationContext): OutputValidation<Any> {
        val validator = routes[schema.name to schema.version] ?: return notRouted(schema)
        return validateWith(text, validator, context)
    }

    public companion object {
        /** The schema reported for free text checked by [validateText]. */
        public val PLAIN_TEXT: OutputSchema = OutputSchema("PlainText", 1, null)

        /** A router with the insight, media prompt and chat reply validators plus [extra] (for example the JITAI proposal validator). */
        public fun withBuiltIns(vararg extra: SchemaValidator<*>): AiOutputValidator =
            AiOutputValidator(listOf(InsightSchema.validator, MediaPromptSchema.validator, ChatReplySchema.validator) + extra)

        /** S0 extract, S1 size, S2 pre-scan and S3 parse of [text], then [validator]. */
        public fun <T : Any> validateWith(
            text: String,
            validator: SchemaValidator<T>,
            context: OutputValidationContext,
        ): OutputValidation<T> = when (val parsed = parse(text, validator.maxBytes)) {
            is JsonObject -> validator.validateDocument(parsed, context)
            else -> OutputValidation.invalid(validator.schema, listOf(parsed as ValidationIssue))
        }

        /** S0-S3: the parsed object, or the issue of the first stage that failed. */
        private fun parse(text: String, maxBytes: Int): Any {
            val extracted = JsonText.extract(text) ?: return ValidationIssue(OutputCodes.MALFORMED_JSON, "", ValidationStage.S0_EXTRACT)
            if (utf8Length(extracted) > maxBytes) return ValidationIssue(OutputCodes.TOO_LARGE, "", ValidationStage.S1_SIZE)
            JsonText.preScan(extracted)?.let { code -> return ValidationIssue(code, "", ValidationStage.S2_PRESCAN) }
            return JsonText.parseObject(extracted) ?: ValidationIssue(OutputCodes.MALFORMED_JSON, "", ValidationStage.S3_PARSE)
        }

        /** [result] with [validator], which must own the result's schema (otherwise [OutputCodes.SCHEMA_NOT_ROUTED]). */
        public fun <T : Any> validate(
            result: AiStructuredResult,
            validator: SchemaValidator<T>,
            context: OutputValidationContext,
        ): OutputValidation<T> {
            val sameSchema = result.schema.name == validator.schema.name && result.schema.version == validator.schema.version
            return if (sameSchema) validateWith(result.json, validator, context) else notRouted(result.schema)
        }

        /** Free text (an [dev.agentle.ai.api.AiProvider.analyze] reply): [AiTextPolicy] with [rules], reported at path "". */
        public fun validateText(result: AiTextResult, rules: TextRules, context: OutputValidationContext): OutputValidation<String> {
            val issues = textPolicyIssues(listOf(TextField("", result.text, rules)), context)
            return if (issues.isEmpty()) OutputValidation.Valid(result.text, PLAIN_TEXT) else OutputValidation.invalid(PLAIN_TEXT, issues)
        }

        /** UTF-8 length of [text] without encoding it. */
        public fun utf8Length(text: String): Int {
            var bytes = 0
            var index = 0
            while (index < text.length) {
                val char = text[index]
                bytes += when {
                    char.code < ONE_BYTE_LIMIT -> 1

                    char.code < TWO_BYTE_LIMIT -> 2

                    char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> {
                        index++
                        SURROGATE_PAIR_BYTES
                    }

                    else -> THREE_BYTES
                }
                index++
            }
            return bytes
        }

        private const val ONE_BYTE_LIMIT = 0x80
        private const val TWO_BYTE_LIMIT = 0x800
        private const val THREE_BYTES = 3
        private const val SURROGATE_PAIR_BYTES = 4

        private fun notRouted(schema: OutputSchema): OutputValidation.Invalid =
            failure(schema, OutputCodes.SCHEMA_NOT_ROUTED, ValidationStage.S0_EXTRACT)

        private fun failure(schema: OutputSchema, code: String, stage: ValidationStage): OutputValidation.Invalid =
            OutputValidation.invalid(schema, listOf(ValidationIssue(code, "", stage)))
    }
}
