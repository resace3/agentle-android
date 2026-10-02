package dev.agentle.ai.api.validation

import dev.agentle.ai.api.OutputSchema
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome

/**
 * Error codes of AI output validation. Codes E001-E099 keep the meaning they have in the JITAI proposal validator
 * (docs/research/10-jitai-engine-design.md section 11.2), so one code means one defect in every schema; E101-E113 are
 * the codes of this module's own checks. Failures are recorded as codes and JSON-pointer paths only, never with the
 * model's text.
 */
public object OutputCodes {
    /** No single JSON object (S0), a repeated key (S2) or a syntax error (S3). */
    public const val MALFORMED_JSON: String = "E001"

    /** The UTF-8 text is larger than the schema's limit (S1). */
    public const val TOO_LARGE: String = "E002"

    /** Bracket nesting deeper than 20 (S2). */
    public const val TOO_DEEP: String = "E003"
    public const val UNSUPPORTED_SCHEMA_VERSION: String = "E004"

    /** A field owned by the app appears in the output. */
    public const val FORBIDDEN_FIELD: String = "E005"
    public const val UNKNOWN_FIELD: String = "E006"
    public const val MISSING_FIELD: String = "E007"
    public const val WRONG_JSON_TYPE: String = "E008"
    public const val INVALID_ENUM_VALUE: String = "E009"

    /** Text length in code points (after NFC) outside its range (check L12). */
    public const val TEXT_LENGTH: String = "E060"

    /** A link, e-mail address or phone number (checks L1-L3). */
    public const val TEXT_CONTAINS_CONTACT: String = "E061"

    /** Markup, medical wording or a causal claim (checks L4, L6, L7). */
    public const val TEXT_FORBIDDEN_CONTENT: String = "E062"

    /** A control or invisible character (check L5). */
    public const val CONTROL_OR_INVISIBLE_CHARS: String = "E066"
    public const val ENVELOPE_INCONSISTENT: String = "E090"
    public const val QUESTIONS_INVALID: String = "E091"
    public const val ASSUMPTIONS_INVALID: String = "E092"

    /** Decoding failed although the schema walk passed: a validator bug, so the output is rejected. */
    public const val INTERNAL: String = "E099"

    /** An insight names a data category the request did not contain. */
    public const val CATEGORY_NOT_IN_REQUEST: String = "E101"

    /** A number in the text appears neither in the request data nor in the template (check L9). */
    public const val NUMBER_NOT_IN_PROVENANCE: String = "E102"

    /** A `{{placeholder}}` in text that may not have one (check L8). */
    public const val PLACEHOLDER_NOT_ALLOWED: String = "E103"

    /** More sentences than the field allows (check L10). */
    public const val TOO_MANY_SENTENCES: String = "E104"

    /** A medical imperative such as "take melatonin" or "see a doctor" (check L11). */
    public const val MEDICAL_IMPERATIVE: String = "E105"
    public const val ARRAY_SIZE: String = "E106"
    public const val DUPLICATE_ITEMS: String = "E107"
    public const val NUMBER_OUT_OF_RANGE: String = "E108"
    public const val PATTERN_MISMATCH: String = "E109"

    /** No validator is registered for the output's schema: fail closed. */
    public const val SCHEMA_NOT_ROUTED: String = "E110"

    /** A media prompt's fields do not fit its kind. */
    public const val MEDIA_KIND_INCONSISTENT: String = "E111"

    /** A media prompt's narration is longer than a 90-second video allows. */
    public const val NARRATION_TOO_LONG: String = "E112"

    /** A digit or number word in text generated ahead of delivery, such as pooled JITAI `ai_text` (check L13). */
    public const val NUMBER_IN_POOLED_TEXT: String = "E113"
}

/** Pipeline stages, in order (R10 section 11.1). */
public enum class ValidationStage { S0_EXTRACT, S1_SIZE, S2_PRESCAN, S3_PARSE, S4_SCHEMA, S5_DECODE, S6_SEMANTIC }

/**
 * One defect. [path] is a JSON pointer built only from schema-defined keys and array indexes (an unknown key shows as
 * `*`), and [check] is the text-policy check id (`L1`..`L12`) when the defect came from [AiTextPolicy]. Neither holds
 * model text.
 */
public data class ValidationIssue(val code: String, val path: String, val stage: ValidationStage, val check: String? = null) :
    Comparable<ValidationIssue> {
    override fun compareTo(other: ValidationIssue): Int =
        compareValuesBy(this, other, { it.stage.ordinal }, { it.path }, { it.code }, { it.check ?: "" })
}

/** The result of validating one model output. */
public sealed interface OutputValidation<out T> {
    public val schema: OutputSchema?

    public data class Valid<out T>(val value: T, override val schema: OutputSchema) : OutputValidation<T>

    /** At most [MAX_ISSUES] issues are listed, sorted by stage, path and code; [omitted] counts the rest. */
    public data class Invalid(override val schema: OutputSchema?, val issues: List<ValidationIssue>, val omitted: Int = 0) :
        OutputValidation<Nothing> {
        init {
            require(issues.isNotEmpty()) { "an invalid result has at least one issue" }
        }

        public val codes: List<String> get() = issues.map { it.code }.distinct()

        public val checks: List<String> get() = issues.mapNotNull { it.check }.distinct()
    }

    public companion object {
        public const val MAX_ISSUES: Int = 50

        /** Deduplicates by (code, path, check), sorts and caps at [MAX_ISSUES]. */
        public fun invalid(schema: OutputSchema?, issues: Collection<ValidationIssue>): Invalid {
            val unique = issues.distinctBy { Triple(it.code, it.path, it.check) }.sorted()
            return Invalid(schema, unique.take(MAX_ISSUES), (unique.size - MAX_ISSUES).coerceAtLeast(0))
        }
    }
}

/** Maps to [Outcome]; a failure carries only the codes ([AppError.ValidationError] without detail). */
public fun <T> OutputValidation<T>.toOutcome(): Outcome<T> = when (this) {
    is OutputValidation.Valid -> Outcome.Success(value)
    is OutputValidation.Invalid -> Outcome.Failure(AppError.ValidationError(codes))
}

/** The `ai_result_meta` record (docs/ARCHITECTURE.md section 5.2): codes and check ids only, never content. */
public data class AiResultMeta(
    val requestId: String,
    val schemaName: String?,
    val schemaVersion: Int?,
    val valid: Boolean,
    val errorCodes: List<String>,
    val checkIds: List<String>,
)

public fun OutputValidation<*>.toResultMeta(requestId: String): AiResultMeta = when (this) {
    is OutputValidation.Valid -> AiResultMeta(requestId, schema.name, schema.version, true, emptyList(), emptyList())
    is OutputValidation.Invalid -> AiResultMeta(requestId, schema?.name, schema?.version, false, codes, checks)
}
