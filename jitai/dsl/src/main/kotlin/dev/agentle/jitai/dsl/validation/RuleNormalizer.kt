package dev.agentle.jitai.dsl.validation

import dev.agentle.jitai.dsl.model.ContentStrategy
import dev.agentle.jitai.dsl.model.CreatedBy
import dev.agentle.jitai.dsl.model.Delivery
import dev.agentle.jitai.dsl.model.ExperimentSpec
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiKind
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.OutcomeMetricRef
import dev.agentle.jitai.dsl.model.OutcomeSpec
import dev.agentle.jitai.dsl.model.Provenance
import dev.agentle.jitai.dsl.model.SnoozePolicy
import dev.agentle.jitai.dsl.model.SuppressionTarget
import dev.agentle.jitai.dsl.model.TextPair
import dev.agentle.jitai.dsl.nl.JitaiDraft
import dev.agentle.jitai.dsl.rule.Condition
import kotlin.time.Instant

/**
 * S7 normalization (R10 §11.4): app-owned fields, `appLabel` -> `package`, sparse args, defaults, NFC-trimmed strings.
 * The condition tree is never restructured (step 6): only leaf args change.
 */
internal object RuleNormalizer {
    /**
     * The stored definition of a proposal draft.
     *
     * @param packages `appLabel` (or a replaced `package`) -> the installed package to store.
     */
    fun fromDraft(
        draft: JitaiDraft,
        createdBy: CreatedBy,
        id: String,
        now: Instant,
        packages: Map<String, String>,
        provenance: Provenance?,
    ): JitaiDefinition {
        val at = Instant.fromEpochSeconds(now.epochSeconds)
        return JitaiDefinition(
            id = id,
            name = TextRules.normalize(draft.name),
            description = TextRules.normalize(draft.description),
            kind = draft.kind,
            category = draft.category,
            status = JitaiStatus.PROPOSED,
            enabled = false,
            trigger = draft.trigger,
            activeWindow = draft.activeWindow,
            conditions = draft.conditions?.let { resolve(it, packages) },
            contextRequirements = draft.contextRequirements?.let { resolve(it, packages) },
            delivery = Delivery(
                channel = draft.delivery.channel,
                quietHoursPolicy = draft.delivery.quietHoursPolicy,
                notificationTimeoutMinutes = draft.delivery.notificationTimeoutMinutes,
            ),
            content = draft.content?.let(::content),
            cooldownMinutes = draft.cooldownMinutes,
            maxPerDay = draft.maxPerDay,
            maxPerWeek = draft.maxPerWeek,
            priority = draft.priority,
            snooze = draft.snooze ?: defaultSnooze(draft.kind, draft.trigger),
            expiresAt = null,
            createdBy = createdBy,
            createdAt = at,
            modifiedAt = at,
            outcome = draft.outcome?.let { outcome(it, packages) },
            suppression = draft.suppression?.let { SuppressionTarget(categories = it.categories) },
            experiment = ExperimentSpec(),
            userConfirmedUnknownOverrides = false,
            provenance = provenance,
        )
    }

    /** A definition from the editor or storage: strings, default snooze and chosen replacement packages. */
    fun definition(definition: JitaiDefinition, packages: Map<String, String>): JitaiDefinition = definition.copy(
        name = TextRules.normalize(definition.name),
        description = TextRules.normalize(definition.description),
        conditions = definition.conditions?.let { resolve(it, packages) },
        contextRequirements = definition.contextRequirements?.let { resolve(it, packages) },
        content = definition.content?.let(::content),
        snooze = definition.snooze ?: defaultSnooze(definition.kind, definition.trigger),
        outcome = definition.outcome?.let { outcome(it, packages) },
        provenance = definition.provenance?.let { it.copy(nlRequest = it.nlRequest?.let(TextRules::normalize)) },
    )

    private fun defaultSnooze(kind: JitaiKind, trigger: dev.agentle.jitai.dsl.model.Trigger?): SnoozePolicy? =
        if (kind == JitaiKind.INTERVENTION) SnoozePolicy.defaultFor(trigger) else null

    /** Replaces `appLabel` with the chosen `package` in every leaf; other args are kept (null args are already gone). */
    fun resolve(tree: Condition, packages: Map<String, String>): Condition = when (tree) {
        is Condition.AllOf -> Condition.AllOf(tree.of.map { resolve(it, packages) })
        is Condition.AnyOf -> Condition.AnyOf(tree.of.map { resolve(it, packages) })
        is Condition.Not -> Condition.Not(resolve(tree.of, packages))
        is Condition.LocalTimeIn -> tree
        is Condition.FeatureLeaf -> withArgs(tree, resolveArgs(tree.args, packages))
    }

    fun resolveArgs(args: Map<String, String>, packages: Map<String, String>): Map<String, String> {
        val label = args[ArgRules.APP_LABEL]
        val pkg = args[ArgRules.PACKAGE]
        val resolved = when {
            label != null -> packages[label]
            pkg != null -> packages[pkg] ?: pkg
            else -> null
        }
        val result = args.filterKeys { it != ArgRules.APP_LABEL && it != ArgRules.PACKAGE }.toSortedMap()
        if (resolved != null) result[ArgRules.PACKAGE] = resolved
        return result
    }

    private fun outcome(outcome: OutcomeSpec, packages: Map<String, String>): OutcomeSpec = OutcomeSpec(
        proximal = metric(outcome.proximal, packages),
        distal = outcome.distal?.let { metric(it, packages) },
    )

    private fun metric(ref: OutcomeMetricRef, packages: Map<String, String>): OutcomeMetricRef =
        ref.copy(args = resolveArgs(ref.args, packages))

    private fun withArgs(leaf: Condition.FeatureLeaf, args: Map<String, String>): Condition.FeatureLeaf = when (leaf) {
        is Condition.Gt -> leaf.copy(args = args)
        is Condition.Gte -> leaf.copy(args = args)
        is Condition.Lt -> leaf.copy(args = args)
        is Condition.Lte -> leaf.copy(args = args)
        is Condition.Eq -> leaf.copy(args = args)
        is Condition.Neq -> leaf.copy(args = args)
        is Condition.Between -> leaf.copy(args = args)
        is Condition.In -> leaf.copy(args = args)
    }

    private fun content(content: ContentStrategy): ContentStrategy = when (content) {
        is ContentStrategy.Static -> ContentStrategy.Static(norm(content.title), norm(content.body))
        is ContentStrategy.Template -> template(content)
        is ContentStrategy.Variants -> content.copy(items = content.items.map { TextPair(norm(it.title), norm(it.body)) })
        is ContentStrategy.AiText -> content.copy(goal = norm(content.goal), fallback = template(content.fallback))
        is ContentStrategy.LocalMedia -> content.copy(caption = template(content.caption))
    }

    private fun template(template: ContentStrategy.Template) = ContentStrategy.Template(norm(template.title), norm(template.body))

    private fun norm(text: String): String = TextRules.normalize(text)
}
