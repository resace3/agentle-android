package dev.agentle.jitai.engine.eval

import dev.agentle.analytics.features.FeatureRef
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.children

/** The feature dependencies of rules (R10 §4.8 item 3): what a pass must put into its snapshot. */
public object RuleRefs {
    /** Feature leaves of [condition] in pre-order (bounded by [RuleEvaluator.MAX_DEPTH], like evaluation). */
    public fun leaves(condition: Condition?): List<Condition.FeatureLeaf> {
        if (condition == null) return emptyList()
        val out = mutableListOf<Condition.FeatureLeaf>()
        collect(condition, depth = 1, out = out)
        return out
    }

    /** Every `(feature, args)` reference of [condition]. */
    public fun of(condition: Condition?): Set<FeatureRef> = leaves(condition).mapTo(linkedSetOf()) { it.ref }

    /** Every reference of a definition's `conditions` and `contextRequirements`. */
    public fun of(definition: JitaiDefinition): Set<FeatureRef> = of(definition.conditions) + of(definition.contextRequirements)

    /** The leaf a `{{feature_id}}` placeholder names: the first leaf on [featureId] in `conditions`, then `contextRequirements`. */
    public fun placeholderLeaf(definition: JitaiDefinition, featureId: String): Condition.FeatureLeaf? =
        (leaves(definition.conditions) + leaves(definition.contextRequirements)).firstOrNull { it.feature == featureId }

    private fun collect(condition: Condition, depth: Int, out: MutableList<Condition.FeatureLeaf>) {
        if (depth > RuleEvaluator.MAX_DEPTH) return
        if (condition is Condition.FeatureLeaf) out += condition
        condition.children.forEach { collect(it, depth + 1, out) }
    }
}
