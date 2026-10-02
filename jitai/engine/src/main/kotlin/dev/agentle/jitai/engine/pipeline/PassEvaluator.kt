package dev.agentle.jitai.engine.pipeline

import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureSnapshot
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.Freshness
import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiKind
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.ImpliedState
import dev.agentle.jitai.engine.eval.OverridePolicy
import dev.agentle.jitai.engine.eval.RootKind
import dev.agentle.jitai.engine.eval.RuleEvaluator
import dev.agentle.jitai.engine.eval.RuleRefs
import dev.agentle.jitai.engine.eval.TreeTrace
import dev.agentle.jitai.engine.eval.Tri
import dev.agentle.jitai.engine.eval.ValueState
import dev.agentle.jitai.engine.schedule.Effectiveness
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * The rule-evaluation part of one decision point (R10 §8.1 step 4, §2.2): `conditions`, then `contextRequirements`
 * (only when the conditions are TRUE), the SUPPRESSION rules that block it (G08) and, for event points, whether the
 * live state the event implied holds in the snapshot.
 */
public data class CandidateEvaluation(
    val point: DecisionPoint,
    val conditions: TreeTrace,
    val context: TreeTrace?,
    val suppressedBy: List<String>,
    val impliedHolds: Boolean = true,
) {
    /** Delivery-eligible before the safety gates (R10 §6.5). */
    public val eligible: Boolean get() = conditions.result == Tri.TRUE && context?.result == Tri.TRUE && impliedHolds

    /**
     * The row state of a non-eligible point (R10 §2.2): conditions FALSE -> NOT_TRIGGERED, conditions UNKNOWN -> UNKNOWN,
     * context FALSE -> NOT_AVAILABLE, context UNKNOWN -> UNKNOWN, implied state gone -> NOT_TRIGGERED. Null when eligible.
     */
    public val outcome: DecisionState?
        get() = when {
            conditions.result == Tri.FALSE -> DecisionState.NOT_TRIGGERED
            conditions.result == Tri.UNKNOWN -> DecisionState.UNKNOWN
            context?.result == Tri.FALSE -> DecisionState.NOT_AVAILABLE
            context?.result != Tri.TRUE -> DecisionState.UNKNOWN
            !impliedHolds -> DecisionState.NOT_TRIGGERED
            else -> null
        }
}

/** Pure helpers of an evaluation pass: what to resolve, how to evaluate and what blocks. */
public object PassEvaluator {
    /** `device_interactive`, read for G06 (`ALLOW_WHEN_INTERACTIVE` needs a definite TRUE). */
    public val INTERACTIVE: FeatureRef = FeatureRef("device_interactive")

    /** Effective SUPPRESSION rules at [t] (R10 §6.5: armed and not expired). */
    public fun suppressions(definitions: List<JitaiDefinition>, t: Instant): List<JitaiDefinition> =
        definitions.filter { it.kind == JitaiKind.SUPPRESSION && Effectiveness.isEffective(it, t) }

    /**
     * Every reference a pass resolves once (R10 §8.1 step 3): the rules of [points], every effective SUPPRESSION rule,
     * `device_interactive` and the states events imply.
     */
    public fun refs(points: List<DecisionPoint>, suppressions: List<JitaiDefinition>): Set<FeatureRef> {
        val refs = linkedSetOf<FeatureRef>()
        points.forEach { point ->
            refs += RuleRefs.of(point.definition)
            point.impliedState?.let { refs += it.ref }
        }
        suppressions.forEach { refs += RuleRefs.of(it.conditions) }
        refs += INTERACTIVE
        return refs
    }

    /**
     * The SUPPRESSION rules that block at the snapshot's instant (R10 §6.5): window open and `K3(conditions)` TRUE or
     * UNKNOWN. Evaluated once per pass with the SUPPRESSION polarity table for `onUnknown`.
     */
    public fun blocking(
        suppressions: List<JitaiDefinition>,
        snapshot: FeatureSnapshot,
        zone: TimeZone,
        evaluator: RuleEvaluator,
    ): List<JitaiDefinition> = suppressions.filter { rule ->
        Effectiveness.windowOpen(rule, snapshot.at, zone) &&
            evaluator.evaluate(rule.conditions, snapshot, RootKind.SUPPRESSION, OverridePolicy.of(rule)).result != Tri.FALSE
    }

    /** Ids of the [blocking] rules that target [definition] by id or category. */
    public fun blockers(definition: JitaiDefinition, blocking: List<JitaiDefinition>): List<String> = blocking.filter { rule ->
        val target = rule.suppression ?: return@filter false
        definition.id in target.jitaiIds || definition.category in target.categories
    }.map { it.id }.sorted()

    /** Evaluates one decision point against the pass snapshot. */
    public fun evaluate(
        point: DecisionPoint,
        snapshot: FeatureSnapshot,
        blocking: List<JitaiDefinition>,
        evaluator: RuleEvaluator,
    ): CandidateEvaluation {
        val definition = point.definition
        val policy = OverridePolicy.of(definition)
        val conditions = evaluator.evaluate(definition.conditions, snapshot, RootKind.INTERVENTION, policy)
        val context = if (conditions.result == Tri.TRUE) {
            evaluator.evaluate(definition.contextRequirements, snapshot, RootKind.INTERVENTION, policy)
        } else {
            null
        }
        return CandidateEvaluation(
            point = point,
            conditions = conditions,
            context = context,
            suppressedBy = blockers(definition, blocking),
            impliedHolds = point.impliedState?.let { impliedHolds(it, snapshot) } ?: true,
        )
    }

    /** `device_interactive` as a three-valued result (G06). */
    public fun interactive(snapshot: FeatureSnapshot): Tri {
        val value = snapshot[INTERACTIVE] as? FeatureValue.Known ?: return Tri.UNKNOWN
        val scalar = value.value as? FeatureScalar.BoolValue ?: return Tri.UNKNOWN
        return Tri.of(scalar.value)
    }

    /** Whether [implied] holds in [snapshot]: only a Known equal value does (fail closed). */
    public fun impliedHolds(implied: ImpliedState, snapshot: FeatureSnapshot): Boolean {
        val value = snapshot[implied.ref]
        return value is FeatureValue.Known && value.value == implied.expected
    }

    /**
     * The remote features to sync when [evaluation] is UNKNOWN **only** because remote health data is Stale or
     * `NOT_SYNCED` (R10 §8.2 `daily_at` staleness retry); null when a retry cannot help.
     */
    public fun retryableStaleness(evaluation: CandidateEvaluation): Set<String>? {
        if (evaluation.outcome != DecisionState.UNKNOWN) return null
        val tree = evaluation.context?.takeIf { evaluation.conditions.result == Tri.TRUE } ?: evaluation.conditions
        val unknownLeaves = tree.nodes.filter { it.feature != null && it.result == Tri.UNKNOWN }
        if (unknownLeaves.isEmpty()) return null
        val retryable = unknownLeaves.all { node ->
            val remote = when (RealtimeFeatureCatalog[node.feature!!]?.freshness) {
                is Freshness.SourceLag, Freshness.DailyValue -> true
                else -> false
            }
            val state = node.value?.state
            remote && (state == ValueState.STALE || (state == ValueState.MISSING && node.value?.reason == MissingReason.NOT_SYNCED))
        }
        return if (retryable) unknownLeaves.mapNotNull { it.feature }.toSortedSet() else null
    }

    /** The subset of [snapshot] a row stores: the rule's references, `device_interactive` and the implied state. */
    public fun subset(snapshot: FeatureSnapshot, point: DecisionPoint): FeatureSnapshot {
        val keys = (RuleRefs.of(point.definition) + listOfNotNull(INTERACTIVE, point.impliedState?.ref)).map { it.key }.toSet()
        return snapshot.copy(values = snapshot.values.filterKeys { it in keys })
    }
}
