package dev.agentle.feature.insights.builder

import dev.agentle.jitai.dsl.nl.InstalledApp
import dev.agentle.jitai.dsl.render.RenderOptions
import dev.agentle.jitai.dsl.render.RuleRenderer
import dev.agentle.jitai.dsl.validation.IssueCode
import dev.agentle.jitai.dsl.validation.IssueSeverity
import dev.agentle.jitai.dsl.validation.RuleValidator
import dev.agentle.jitai.dsl.validation.ValidationContext
import dev.agentle.jitai.dsl.validation.ValidationIssue
import dev.agentle.jitai.dsl.validation.ValidationReport
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap

/**
 * One issue on a form field: a validator code with its params, or a [LocalIssue] the form found itself. The screen
 * turns it into a localized message.
 */
internal data class FieldIssue(
    val field: String,
    val severity: IssueSeverity,
    val code: IssueCode? = null,
    val local: LocalIssue? = null,
    val params: ImmutableMap<String, String> = persistentMapOf(),
)

/**
 * The validation of the current form: issues by field (errors first), issues without a field, the renderer's
 * sentence (also for an invalid rule, so the user sees what they are building) and whether saving is possible.
 *
 * @property canActivate no errors, no unresolved confirm item and no local input problem.
 * @property canSaveDraft no local input problem (a draft may still have validator errors).
 */
internal data class BuilderValidation(
    val byField: ImmutableMap<String, ImmutableList<FieldIssue>> = persistentMapOf(),
    val general: ImmutableList<FieldIssue> = persistentListOf(),
    val errorCount: Int = 0,
    val confirmCount: Int = 0,
    val preview: String = "",
    val canActivate: Boolean = false,
    val canSaveDraft: Boolean = false,
) {
    fun issuesFor(field: String): ImmutableList<FieldIssue> = byField[field] ?: persistentListOf()

    /** Issues of every part of one condition row (`cond.<key>.*`). */
    fun rowIssues(tree: String, key: Int): List<FieldIssue> =
        byField.filterKeys { it.startsWith("$tree.$key.") }.values.flatten()
}

/** Validates [built] and maps every issue to its field. */
internal fun validate(built: BuiltRule, context: ValidationContext, renderOptions: RenderOptions): Pair<ValidationReport, BuilderValidation> {
    val report = RuleValidator.validateDefinition(built.definition, context)
    val issues = buildList {
        built.localIssues.forEach { (field, issue) -> add(FieldIssue(field, IssueSeverity.ERROR, local = issue)) }
        (report.errors + report.confirmItems + report.warnings).forEach { issue -> add(issue.toFieldIssue(built.layout)) }
    }
    // A field with an input problem shows only that: the validator's message about the stand-in value would mislead.
    val localFields = built.localIssues.keys
    val shown = issues.filter { it.local != null || it.field !in localFields }
    val byField = shown.filter { it.field != Fields.GENERAL }
        .groupBy { it.field }
        .mapValues { (_, list) -> list.sortedBy { it.severity.ordinal }.toImmutableList() }
        .toImmutableMap()
    val validation = BuilderValidation(
        byField = byField,
        general = shown.filter { it.field == Fields.GENERAL }.sortedBy { it.severity.ordinal }.toImmutableList(),
        errorCount = report.errorCount + built.localIssues.size,
        confirmCount = report.confirmItems.size,
        preview = RuleRenderer.render(built.definition, renderOptions),
        canActivate = report.isValid && report.confirmItems.isEmpty() && built.localIssues.isEmpty(),
        canSaveDraft = built.localIssues.isEmpty(),
    )
    return report to validation
}

private fun ValidationIssue.toFieldIssue(layout: TreeLayout): FieldIssue =
    FieldIssue(fieldOf(path, layout), severity, code = code, params = params.toImmutableMap())

/** Render options for the builder's preview from the validation context. */
internal fun ValidationContext.renderOptions(apps: List<InstalledApp>): RenderOptions = RenderOptions(
    use24HourClock = settings.use24HourClock,
    zone = clock.zone(),
    appLabels = apps.associate { it.packageName to it.label },
    jitaiNames = existingJitais.associate { it.id to it.name },
)
