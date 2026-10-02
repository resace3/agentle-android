package dev.agentle.jitai.dsl.validation

import dev.agentle.analytics.features.FeatureRef
import dev.agentle.jitai.dsl.analysis.RuleAnalysis
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.codec.SchemaWalker
import dev.agentle.jitai.dsl.codec.Schemas
import dev.agentle.jitai.dsl.codec.Spec
import dev.agentle.jitai.dsl.model.CreatedBy
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.Provenance
import dev.agentle.jitai.dsl.model.RuleOrigin
import dev.agentle.jitai.dsl.nl.DiscoveredProposal
import dev.agentle.jitai.dsl.nl.JitaiDraft
import dev.agentle.jitai.dsl.nl.JitaiProposal
import dev.agentle.jitai.dsl.nl.NlContract
import dev.agentle.jitai.dsl.nl.ProposalStatus
import dev.agentle.jitai.dsl.render.RenderOptions
import dev.agentle.jitai.dsl.render.RuleRenderer
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.RuleLiteral
import kotlinx.serialization.KSerializer

/**
 * One run of the pipeline of R10 §11.1 for one [ValidationRequest]. Text inputs start at S0 (model replies) or S1;
 * decoded inputs are encoded canonically and then take the same path from S1, so every input meets the same checks.
 */
internal class ValidationRun(private val request: ValidationRequest, private val context: ValidationContext) {
    private val sink = IssueSink()
    private var stage: Stage = Stage.S0
    private var origin: RuleOrigin = RuleOrigin.AI
    private var proposal: JitaiProposal? = null
    private var discovered: DiscoveredProposal? = null
    private var definition: JitaiDefinition? = null
    private var contentHash: String? = null
    private var rendering: String? = null
    private var dependencies: Set<FeatureRef> = emptySet()
    private var appChoices: List<AppChoice> = emptyList()

    fun execute(): ValidationReport {
        when (val input = request.input) {
            is ValidationInput.ProposalText -> proposalText(input.text, input.createdBy)
            is ValidationInput.Proposal -> decodedProposal(input.proposal, input.createdBy)
            is ValidationInput.DiscoveredText -> discoveredText(input.text)
            is ValidationInput.Discovered -> decodedDiscovered(input.proposal)
            is ValidationInput.DefinitionText -> definitionText(input.text)
            is ValidationInput.Definition -> decodedDefinition(input.definition)
        }
        return report()
    }

    /** E099 for an unexpected failure in the current stage; only the stage and the failure's class name are logged. */
    fun internalError(error: Throwable): ValidationReport {
        sink.add(IssueCode.E099, stage, "", mapOf("stage" to stage.name))
        definition = null
        contentHash = null
        rendering = null
        context.logger.e(
            RuleValidator.LOG_COMPONENT,
            "validation failed",
            fields = mapOf("stage" to stage.name, "failure" to error::class.simpleName),
        )
        return report()
    }

    // ------------------------------------------------------------------ inputs

    private fun proposalText(text: String, createdBy: CreatedBy) {
        origin = request.origin ?: RuleOrigin.of(createdBy)
        stage = Stage.S0
        val json = extract(text)
        if (json == null) {
            sink.add(IssueCode.E001, Stage.S0, "", mapOf("detail" to "text before or after the object"))
            return
        }
        val decoded = decode(json, Schemas.proposal, JitaiProposal.serializer()) ?: return
        proposal(decoded, createdBy)
    }

    private fun decodedProposal(value: JitaiProposal, createdBy: CreatedBy) {
        origin = request.origin ?: RuleOrigin.of(createdBy)
        stage = Stage.S4
        val draft = value.jitai
        if (draft != null && RuleValidator.tooDeep(sink, JITAI_BASE, origin, draft.conditions, draft.contextRequirements)) return
        val decoded = decode(RuleCodec.encodeProposal(value), Schemas.proposal, JitaiProposal.serializer()) ?: return
        proposal(decoded, createdBy)
    }

    private fun discoveredText(text: String) {
        origin = request.origin ?: RuleOrigin.AI
        val decoded = decode(text, Schemas.discovered, DiscoveredProposal.serializer()) ?: return
        discovered(decoded)
    }

    private fun decodedDiscovered(value: DiscoveredProposal) {
        origin = request.origin ?: RuleOrigin.AI
        stage = Stage.S4
        if (RuleValidator.tooDeep(sink, JITAI_BASE, origin, value.jitai.conditions, value.jitai.contextRequirements)) return
        val decoded = decode(RuleCodec.encodeDiscovered(value), Schemas.discovered, DiscoveredProposal.serializer()) ?: return
        discovered(decoded)
    }

    private fun definitionText(text: String) {
        origin = request.origin ?: RuleOrigin.AI
        val decoded = decode(text, Schemas.definition, JitaiDefinition.serializer()) ?: return
        definition(decoded)
    }

    private fun decodedDefinition(value: JitaiDefinition) {
        origin = request.origin ?: RuleOrigin.of(value.createdBy)
        stage = Stage.S4
        if (RuleValidator.tooDeep(sink, "", origin, value.conditions, value.contextRequirements)) return
        val decoded = decode(RuleCodec.encodeDefinition(value), Schemas.definition, JitaiDefinition.serializer()) ?: return
        definition(decoded)
    }

    // ------------------------------------------------------------------ S0-S5

    /**
     * S0 (R10 §11.1): trim; if the whole text is one fenced block (three backticks, optional `json`, newline ... newline,
     * three backticks) take its inside; the result must begin with `{` and end with `}`.
     */
    private fun extract(text: String): String? {
        var body = text.trim()
        val fence = FENCE.matchEntire(body)
        if (fence != null) body = fence.groupValues[1].trim()
        return body.takeIf { it.startsWith("{") && it.endsWith("}") }
    }

    private fun <T> decode(text: String, spec: Spec, serializer: KSerializer<T>): T? {
        stage = Stage.S1
        val element = RuleCodec.readStrict(text, sink) ?: return null
        stage = Stage.S4
        return RuleCodec.decodeElement(element, spec, serializer, sink, Stage.S4)
    }

    // ------------------------------------------------------------------ S4 envelope, S6-S9

    private fun proposal(value: JitaiProposal, createdBy: CreatedBy) {
        proposal = value
        stage = Stage.S4
        EnvelopeChecks(sink, origin).proposal(value)
        val draft = value.jitai ?: return
        if (value.status != ProposalStatus.OK) return
        rule(RuleView.of(draft, createdBy, JITAI_BASE)) { apps ->
            val provenance = Provenance(
                nlRequest = request.nlRequest?.let(TextRules::normalize),
                promptVersion = NlContract.PROMPT_VERSION.takeIf { createdBy == CreatedBy.AI_NATURAL_LANGUAGE },
                catalogVersion = NlContract.catalogVersion.takeIf { createdBy == CreatedBy.AI_NATURAL_LANGUAGE },
                appLabels = apps.labelsByPackage,
                expiresInDays = draft.expiresInDays,
            )
            fromDraft(draft, createdBy, apps, provenance)
        }
    }

    private fun discovered(value: DiscoveredProposal) {
        discovered = value
        stage = Stage.S4
        EnvelopeChecks(sink, origin).discovered(value)
        val draft = value.jitai
        rule(RuleView.of(draft, CreatedBy.AI_DISCOVERED, JITAI_BASE)) { apps ->
            val provenance = Provenance(
                proposalId = value.proposalId,
                patternId = value.patternId,
                evidence = value.whyProposed.evidence,
                appLabels = apps.labelsByPackage,
                expiresInDays = draft.expiresInDays,
            )
            fromDraft(draft, CreatedBy.AI_DISCOVERED, apps, provenance)
        }
    }

    private fun definition(value: JitaiDefinition) {
        origin = request.origin ?: RuleOrigin.of(value.createdBy)
        rule(RuleView.of(value)) { apps -> RuleNormalizer.definition(value, apps.packages) }
    }

    private fun fromDraft(draft: JitaiDraft, createdBy: CreatedBy, apps: AppResolutionStep, provenance: Provenance): JitaiDefinition =
        RuleNormalizer.fromDraft(draft, createdBy, context.ids.newId(), context.clock.now(), apps.packages, provenance)

    /** S6 checks, then (without errors and unresolved app labels) S7 normalize, S8 re-validate, W01 and S9 render. */
    private fun rule(view: RuleView, normalize: (AppResolutionStep) -> JitaiDefinition) {
        stage = Stage.S6
        RuleChecks(sink, view, origin, context).check()
        ReviewChecks(sink, view, context).check()
        val apps = AppResolutionStep(sink, view, context, request.appSelections)
        apps.run()
        appChoices = apps.choices
        dependencies = RuleAnalysis.dependencies(view.conditions, view.contextRequirements)
        if (sink.hasErrors || apps.unresolved) return
        stage = Stage.S7
        val normalized = normalize(apps)
        val hash = RuleCodec.contentHash(normalized)
        stage = Stage.S8
        if (!recheck(normalized)) return
        ReviewChecks.duplicates(sink, normalized, hash, context, view.base)
        stage = Stage.S9
        dependencies = RuleAnalysis.dependencies(normalized)
        rendering = RuleRenderer.render(normalized, renderOptions(normalized))
        contentHash = hash
        definition = normalized
    }

    /** S8: the stored definition through the stored schema (S4) and the S6 checks; any error is a normalizer bug. */
    private fun recheck(normalized: JitaiDefinition): Boolean {
        val check = IssueSink()
        val element = RuleCodec.readStrict(RuleCodec.encodeDefinition(normalized), check)
        if (element != null) SchemaWalker(check, Stage.S8).walk(element, Schemas.definition, "")
        RuleChecks(check, RuleView.of(normalized), origin, context).check()
        if (!check.hasErrors) return true
        sink.add(IssueCode.E099, Stage.S8, "", mapOf("stage" to Stage.S8.name))
        val codes = check.sorted(IssueSeverity.ERROR).joinToString(",") { it.code.name }
        context.logger.e(
            RuleValidator.LOG_COMPONENT,
            "normalized rule failed re-validation",
            fields = mapOf(
                "stage" to "S8",
                "codes" to codes,
            ),
        )
        return false
    }

    private fun renderOptions(definition: JitaiDefinition): RenderOptions {
        val labels = LinkedHashMap<String, String>()
        definition.provenance?.appLabels?.let(labels::putAll)
        val apps = context.apps
        if (apps != null) packagesOf(definition).forEach { pkg -> apps.labelOf(pkg)?.let { labels[pkg] = it } }
        return RenderOptions(
            use24HourClock = context.settings.use24HourClock,
            zone = context.clock.zone(),
            appLabels = labels,
            jitaiNames = context.existingJitais.associate { it.id to it.name },
        )
    }

    private fun packagesOf(definition: JitaiDefinition): Set<String> = buildSet {
        listOfNotNull(definition.conditions, definition.contextRequirements).forEach { tree ->
            for (info in RuleAnalysis.leaves(tree)) {
                val leaf = info.node as? Condition.FeatureLeaf ?: continue
                leaf.args[ArgRules.PACKAGE]?.let(::add)
                if (leaf.feature == FOREGROUND_APP) leaf.literals.forEach { (it as? RuleLiteral.Text)?.let { text -> add(text.value) } }
            }
        }
        definition.outcome?.let { outcome ->
            listOfNotNull(outcome.proximal, outcome.distal).forEach { metric -> metric.args[ArgRules.PACKAGE]?.let(::add) }
        }
    }

    private fun report(): ValidationReport {
        val errors = sink.sorted(IssueSeverity.ERROR)
        val valid = errors.isEmpty()
        return ValidationReport(
            errors = errors.take(ValidationReport.MAX_LISTED_ERRORS),
            errorCount = errors.size,
            confirmItems = sink.sorted(IssueSeverity.CONFIRM),
            warnings = sink.sorted(IssueSeverity.WARNING),
            origin = origin,
            proposal = proposal,
            discovered = discovered,
            definition = definition.takeIf { valid },
            contentHash = contentHash.takeIf { valid },
            rendering = rendering.takeIf { valid },
            dependencies = dependencies,
            appChoices = appChoices,
        )
    }

    private companion object {
        const val JITAI_BASE = "/jitai"
        const val FOREGROUND_APP = "foreground_app"

        /** The whole text is one fenced block: three backticks, optional `json`, newline, body, newline, three backticks. */
        val FENCE = Regex("^```(?:json)?\\r?\\n(.*)\\r?\\n```$", RegexOption.DOT_MATCHES_ALL)
    }
}
