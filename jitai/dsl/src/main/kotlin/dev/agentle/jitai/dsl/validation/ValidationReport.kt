package dev.agentle.jitai.dsl.validation

import dev.agentle.analytics.features.FeatureRef
import dev.agentle.jitai.dsl.model.CreatedBy
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.RuleOrigin
import dev.agentle.jitai.dsl.nl.DiscoveredProposal
import dev.agentle.jitai.dsl.nl.InstalledApp
import dev.agentle.jitai.dsl.nl.JitaiProposal

/** What [RuleValidator.validate] reads. Text inputs go through every stage; decoded inputs start at the schema walk. */
public sealed interface ValidationInput {
    /** A model reply (natural-language path, R10 §13.1 step 5): S0 extract onwards. */
    public data class ProposalText(val text: String, val createdBy: CreatedBy = CreatedBy.AI_NATURAL_LANGUAGE) : ValidationInput

    /** An already decoded proposal (for example after the user edited an assumption value). */
    public data class Proposal(val proposal: JitaiProposal, val createdBy: CreatedBy = CreatedBy.AI_NATURAL_LANGUAGE) : ValidationInput

    /** An AI-discovered proposal as stored JSON (R10 §14.7); `createdBy` is AI_DISCOVERED. */
    public data class DiscoveredText(val text: String) : ValidationInput

    public data class Discovered(val proposal: DiscoveredProposal) : ValidationInput

    /** A stored definition as JSON (re-validation after an app update, R10 §7.5). */
    public data class DefinitionText(val text: String) : ValidationInput

    /** A definition from the rule editor or storage. */
    public data class Definition(val definition: JitaiDefinition) : ValidationInput
}

/**
 * One validation run.
 *
 * @property origin the limit column (R10 §11); null means "from `createdBy`" (USER for USER_MANUAL and RULE_TEMPLATE,
 *   AI otherwise).
 * @property appSelections answers to C01: the `appLabel` (or uninstalled `package`) the model wrote -> the package the
 *   user picked. Applied during normalization; a resolved label raises no C01.
 * @property nlRequest the user's own request text, stored in `provenance.nlRequest` (NL path only).
 */
public data class ValidationRequest(
    val input: ValidationInput,
    val origin: RuleOrigin? = null,
    val appSelections: Map<String, String> = emptyMap(),
    val nlRequest: String? = null,
)

/** A C01 question: which installed app the label means, with up to 8 candidates (empty: show the full picker). */
public data class AppChoice(val appLabel: String, val path: String, val candidates: List<InstalledApp>)

/**
 * The validator's result (R10 §11.1): errors, confirm items and warnings, each `{code, path, message, params}` and sorted
 * by stage, path and code; at most [MAX_LISTED_ERRORS] errors are listed and [errorCount] counts all of them.
 *
 * @property definition the normalized stored definition (S7, re-validated in S8); null when there are errors or an
 *   `appLabel` still waits for the user's choice (C01).
 * @property rendering the plain-language sentence of [definition] (S9).
 * @property dependencies the "data this rule uses" list (R10 §4.8 item 3).
 */
public data class ValidationReport(
    val errors: List<ValidationIssue>,
    val errorCount: Int,
    val confirmItems: List<ValidationIssue>,
    val warnings: List<ValidationIssue>,
    val origin: RuleOrigin,
    val proposal: JitaiProposal? = null,
    val discovered: DiscoveredProposal? = null,
    val definition: JitaiDefinition? = null,
    val contentHash: String? = null,
    val rendering: String? = null,
    val dependencies: Set<FeatureRef> = emptySet(),
    val appChoices: List<AppChoice> = emptyList(),
) {
    /** No errors. */
    val isValid: Boolean get() = errorCount == 0

    /** Every code in the report, errors first. */
    val codes: List<IssueCode> get() = (errors + confirmItems + warnings).map { it.code }

    /**
     * The `VALIDATION_ERRORS` lines for the single repair round (R10 §13.1 step 6): `code path: message`. E099 is
     * never sent to the model.
     */
    val repairLines: List<String> get() = errors.filter { it.code != IssueCode.E099 }.map { it.line }

    public companion object {
        public const val MAX_LISTED_ERRORS: Int = 50
    }
}
