package dev.agentle.jitai.dsl.validation

/**
 * One validator finding (R10 §11.1): `{code, path, message, params}`.
 *
 * @property path JSON pointer into the validated document (`/jitai/conditions/of/1/value`; `""` is the root).
 * @property message the code's fixed template filled with [params]; safe to show and to send back to the model in the
 *   repair round (it contains nothing the rule itself does not contain).
 */
public data class ValidationIssue(
    val code: IssueCode,
    val path: String,
    val message: String,
    val params: Map<String, String> = emptyMap(),
    val stage: Stage = Stage.S6,
) {
    val severity: IssueSeverity get() = code.severity

    /** `E042 /jitai/maxPerDay: maxPerDay must be 1-3 for AI rules; got 4.` (the repair-round line, R10 §13.1). */
    val line: String get() = "${code.name} $path: $message"

    override fun toString(): String = line
}

/** Collects issues; applies the precedence rules and the ordering of R10 §11.1. */
internal class IssueSink {
    private val items = ArrayList<ValidationIssue>()

    val hasErrors: Boolean get() = items.any { it.severity == IssueSeverity.ERROR }

    fun add(code: IssueCode, stage: Stage, path: String, params: Map<String, String> = emptyMap(), variant: Int = 0) {
        val all = if (params.containsKey("path")) params else params + ("path" to path)
        items.add(ValidationIssue(code, path, code.format(all, variant), params, stage))
    }

    fun addAll(other: IssueSink) {
        items.addAll(other.items)
    }

    fun codes(): List<IssueCode> = items.map { it.code }

    /** Issues of [severity], with less specific duplicates removed, sorted by stage, path and code. */
    fun sorted(severity: IssueSeverity): List<ValidationIssue> {
        val selected = items.filter { it.severity == severity }
        val byPath = selected.groupBy { it.path }
        return selected
            .filterNot { issue ->
                val dominatedBy = MORE_SPECIFIC[issue.code].orEmpty()
                byPath[issue.path].orEmpty().any { it.code in dominatedBy }
            }
            .distinct()
            .sortedWith(compareBy<ValidationIssue>({ it.stage }, { it.path }, { it.code.name }))
    }

    private companion object {
        /** code -> the more specific codes that replace it at the same path (R10 §11.1). */
        val MORE_SPECIFIC: Map<IssueCode, Set<IssueCode>> = mapOf(
            IssueCode.E006 to setOf(IssueCode.E005),
            IssueCode.E013 to setOf(IssueCode.E024, IssueCode.E081),
            IssueCode.E015 to setOf(IssueCode.E024, IssueCode.E081),
            IssueCode.E009 to setOf(IssueCode.E030),
        )
    }
}
