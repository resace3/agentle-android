package dev.agentle.feature.insights.review

import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.core.model.DataCategory
import dev.agentle.feature.insights.builder.renderOptions
import dev.agentle.feature.insights.port.BuilderEnvironment
import dev.agentle.feature.insights.port.ProposalReviewData
import dev.agentle.feature.insights.port.ProposalSource
import dev.agentle.feature.insights.port.ProposalState
import dev.agentle.jitai.dsl.model.CreatedBy
import dev.agentle.jitai.dsl.model.Delivery
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiKind
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.QuietHoursPolicy
import dev.agentle.jitai.dsl.model.SuppressionTarget
import dev.agentle.jitai.dsl.nl.DiscoveryTier
import dev.agentle.jitai.dsl.nl.InstalledApp
import dev.agentle.jitai.dsl.nl.JitaiDraft
import dev.agentle.jitai.dsl.render.RenderOptions
import dev.agentle.jitai.dsl.render.RenderedText
import dev.agentle.jitai.dsl.render.RuleRenderer
import dev.agentle.jitai.dsl.validation.AppChoice
import dev.agentle.jitai.dsl.validation.IssueCode
import dev.agentle.jitai.dsl.validation.RuleValidator
import dev.agentle.jitai.dsl.validation.ValidationInput
import dev.agentle.jitai.dsl.validation.ValidationIssue
import dev.agentle.jitai.dsl.validation.ValidationReport
import dev.agentle.jitai.dsl.validation.ValidationRequest
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

/** Where a reviewed proposal came from. */
internal enum class ReviewOrigin { NATURAL_LANGUAGE, DISCOVERED }

/**
 * Everything the review screen shows for one proposal, validated with the current environment and app choices.
 * Texts written by the model ([name], [description], [interpretation], assumption texts in [confirmItems],
 * [expectedOutcome]) are untrusted and shown as plain text only.
 *
 * @property rendering the renderer's sentence: of the normalized rule when it is valid ([renderingIsFinal]), otherwise
 *   of the proposal as written, so the user still sees what was proposed.
 * @property before the sentence of the rule this proposal changes; null for a new rule.
 * @property request the user's own words (natural language).
 * @property evidence the numbers behind a discovered pattern; null when they are not available.
 * @property whyProposed the discovered pattern's explanation (made on the device from a fixed template).
 * @property trialDays the trial length: the rule ends this many days after it is turned on; null for no end.
 * @property features the features the rule reads, in rule order (the "data this rule uses" list, R10 §4.8).
 * @property readCategories the personal-data categories of [features].
 * @property sourceCategories the categories the proposal was made from.
 * @property content every text of the content, with personal parts marked (shown in the app; a notification shows
 *   a general text instead unless detailed notifications are on).
 * @property appChoices C01 questions waiting for the user's choice of app.
 * @property allApps the launcher-visible apps, for a C01 without candidates.
 */
internal data class ReviewModel(
    val proposalId: String,
    val state: ProposalState,
    val origin: ReviewOrigin,
    val name: String,
    val description: String,
    val rendering: String,
    val renderingIsFinal: Boolean,
    val before: String?,
    val request: String?,
    val interpretation: String?,
    val whyProposed: String?,
    val expectedOutcome: String?,
    val evidence: EvidenceSummary?,
    val tier: DiscoveryTier?,
    val trialDays: Int?,
    val kind: JitaiKind?,
    val channel: DeliveryChannel?,
    val quietHours: QuietHoursPolicy?,
    val cooldownMinutes: Int?,
    val maxPerDay: Int?,
    val maxPerWeek: Int?,
    val features: ImmutableList<String>,
    val readCategories: ImmutableList<DataCategory>,
    val sourceCategories: ImmutableList<DataCategory>,
    val content: ImmutableList<RenderedText>,
    val errors: ImmutableList<ValidationIssue>,
    val errorCount: Int,
    val confirmItems: ImmutableList<ValidationIssue>,
    val warnings: ImmutableList<ValidationIssue>,
    val appChoices: ImmutableList<AppChoice>,
    val allApps: ImmutableList<InstalledApp>,
    val report: ValidationReport,
    val environment: BuilderEnvironment,
) {
    val isPending: Boolean get() = state == ProposalState.PENDING

    /** Confirm items the user resolves with a checkbox; C01 is resolved by choosing an app. */
    val acknowledgeable: List<ValidationIssue> get() = confirmItems.filter { it.code in ACKNOWLEDGED_CODES }

    /**
     * "Turn on" is possible only for a pending proposal without errors whose normalized rule exists and whose confirm
     * items are all resolved: every C02-C04 acknowledged and no C01 left (R10 §11.5).
     */
    fun canActivate(acknowledged: Set<String>): Boolean =
        isPending &&
            report.isValid &&
            report.definition != null &&
            confirmItems.all { it.code in ACKNOWLEDGED_CODES && it.reviewKey in acknowledged }

    companion object {
        /** Validates [data] with [environment] and the user's C01 [selections] (label -> package). */
        fun of(data: ProposalReviewData, environment: BuilderEnvironment, selections: Map<String, String>): ReviewModel {
            val context = environment.context
            val source = data.source
            val (input, request) = when (source) {
                is ProposalSource.NaturalLanguage ->
                    ValidationInput.Proposal(source.proposal, CreatedBy.AI_NATURAL_LANGUAGE) to source.request
                is ProposalSource.Discovered -> ValidationInput.Discovered(source.proposal) to null
            }
            val report = RuleValidator.validate(
                ValidationRequest(input, appSelections = selections, nlRequest = request),
                context,
            )
            val apps = context.apps?.launcherApps().orEmpty().sortedBy { it.label.lowercase() }
            val options = context.renderOptions(apps)
            val draft = source.draft
            val definition = report.definition
            val features = report.dependencies.map { it.featureId }.distinct()
            return ReviewModel(
                proposalId = data.proposalId,
                state = data.state,
                origin = if (source is ProposalSource.Discovered) ReviewOrigin.DISCOVERED else ReviewOrigin.NATURAL_LANGUAGE,
                name = definition?.name ?: draft?.name.orEmpty(),
                description = definition?.description ?: draft?.description.orEmpty(),
                rendering = report.rendering ?: draft?.let { previewSentence(it, source.createdBy, data.proposalId, context.clock.now(), options) }
                    .orEmpty(),
                renderingIsFinal = report.rendering != null,
                before = data.replaces?.let { RuleRenderer.render(it, options) },
                request = request,
                interpretation = (source as? ProposalSource.NaturalLanguage)?.interpretation?.takeIf { it.isNotBlank() },
                whyProposed = (source as? ProposalSource.Discovered)?.proposal?.whyProposed?.text,
                expectedOutcome = (source as? ProposalSource.Discovered)?.proposal?.expectedOutcome,
                evidence = (source as? ProposalSource.Discovered)?.proposal?.whyProposed?.evidence?.let(EvidenceSummary::of),
                tier = (source as? ProposalSource.Discovered)?.proposal?.tier,
                trialDays = definition?.provenance?.expiresInDays ?: draft?.expiresInDays,
                kind = definition?.kind ?: draft?.kind,
                channel = definition?.delivery?.channel ?: draft?.delivery?.channel,
                quietHours = definition?.delivery?.quietHoursPolicy ?: draft?.delivery?.quietHoursPolicy,
                cooldownMinutes = definition?.cooldownMinutes ?: draft?.cooldownMinutes,
                maxPerDay = definition?.maxPerDay ?: draft?.maxPerDay,
                maxPerWeek = definition?.maxPerWeek ?: draft?.maxPerWeek,
                features = features.toImmutableList(),
                readCategories = features.mapNotNull { RealtimeFeatureCatalog[it]?.category }.distinct().toImmutableList(),
                sourceCategories = data.sourceCategories.sortedBy { it.ordinal }.toImmutableList(),
                content = definition?.let { RuleRenderer.content(it, options) }.orEmpty().toImmutableList(),
                errors = report.errors.toImmutableList(),
                errorCount = report.errorCount,
                confirmItems = report.confirmItems.toImmutableList(),
                warnings = report.warnings.toImmutableList(),
                appChoices = report.appChoices.toImmutableList(),
                allApps = apps.toImmutableList(),
                report = report,
                environment = environment,
            )
        }
    }
}

/**
 * The key a confirm item is acknowledged under: stable across re-validation (the code, the path and the params, which
 * name what is confirmed).
 */
internal val ValidationIssue.reviewKey: String get() = "${code.name}|$path|${params.toSortedMap()}"

/** Confirm items resolved with a checkbox (C01 is resolved by choosing an app; C05 never applies to AI rules). */
internal val ACKNOWLEDGED_CODES: Set<IssueCode> = setOf(IssueCode.C02, IssueCode.C03, IssueCode.C04)

/**
 * The evidence table of a discovered pattern (R10 §14.7, §14.8): nights with and without the exposure, how often the
 * outcome happened in each, and the approximate range of the difference.
 */
internal data class EvidenceSummary(
    val exposedNights: Int,
    val exposedWithOutcome: Int,
    val unexposedNights: Int,
    val unexposedWithOutcome: Int,
    val rateExposed: Double,
    val rateUnexposed: Double,
    val rangeLow: Double?,
    val rangeHigh: Double?,
) {
    companion object {
        /** Reads the evidence object; null when a required number is missing or malformed. */
        fun of(evidence: JsonObject): EvidenceSummary? {
            val exposed = evidence["exposed"] as? JsonObject ?: return null
            val unexposed = evidence["unexposed"] as? JsonObject ?: return null
            val range = evidence["riskDifferenceCi95"] as? JsonArray
            return EvidenceSummary(
                exposedNights = exposed.int("nights") ?: return null,
                exposedWithOutcome = exposed.int("withOutcome") ?: return null,
                unexposedNights = unexposed.int("nights") ?: return null,
                unexposedWithOutcome = unexposed.int("withOutcome") ?: return null,
                rateExposed = evidence.double("rateExposed") ?: return null,
                rateUnexposed = evidence.double("rateUnexposed") ?: return null,
                rangeLow = range?.getOrNull(0)?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() },
                rangeHigh = range?.getOrNull(1)?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() },
            )
        }

        private fun JsonObject.int(key: String): Int? = runCatching { get(key)?.jsonPrimitive?.intOrNull }.getOrNull()

        private fun JsonObject.double(key: String): Double? = runCatching { get(key)?.jsonPrimitive?.doubleOrNull }.getOrNull()
    }
}

private val ProposalSource.draft: JitaiDraft?
    get() = when (this) {
        is ProposalSource.NaturalLanguage -> proposal.jitai
        is ProposalSource.Discovered -> proposal.jitai
    }

private val ProposalSource.createdBy: CreatedBy
    get() = if (this is ProposalSource.Discovered) CreatedBy.AI_DISCOVERED else CreatedBy.AI_NATURAL_LANGUAGE

/**
 * The sentence of a proposal that did not pass validation, rendered from the draft as written (the renderer does not
 * need a valid rule). Only for display: it is never stored or approved.
 */
private fun previewSentence(draft: JitaiDraft, createdBy: CreatedBy, id: String, now: Instant, options: RenderOptions): String? {
    val preview = JitaiDefinition(
        id = id,
        name = draft.name,
        description = draft.description,
        kind = draft.kind,
        category = draft.category,
        status = JitaiStatus.PROPOSED,
        trigger = draft.trigger,
        activeWindow = draft.activeWindow,
        conditions = draft.conditions,
        contextRequirements = draft.contextRequirements,
        delivery = Delivery(draft.delivery.channel, draft.delivery.quietHoursPolicy, draft.delivery.notificationTimeoutMinutes),
        content = draft.content,
        cooldownMinutes = draft.cooldownMinutes,
        maxPerDay = draft.maxPerDay,
        maxPerWeek = draft.maxPerWeek,
        priority = draft.priority,
        snooze = draft.snooze,
        createdBy = createdBy,
        createdAt = now,
        modifiedAt = now,
        outcome = draft.outcome,
        suppression = draft.suppression?.let { SuppressionTarget(it.categories) },
    )
    return runCatching { RuleRenderer.render(preview, options) }.getOrNull()
}
