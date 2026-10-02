package dev.agentle.jitai.engine.eval

import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.bindSelf
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.children

/**
 * The feature dependencies of rules (R10 §4.8 item 3): what a pass must put into its snapshot.
 *
 * Every reference is bound to its rule (REALTIME-FEATURES-R1-2): `{jitai: self}` stays in stored rules (R10 §11.4), but
 * a pass memoizes references by canonical key, so an unbound `self` in two rules would name one value. The resolver
 * answers an unbound `self` with `Missing(INVALID_VALUE)`; the engine binds before collecting and before every lookup.
 */
public object RuleRefs {
    /** The history features' rule selector argument (R10 §5.4). */
    public const val JITAI_ARG: String = "jitai"

    /** The selector value that names the rule that contains the leaf. */
    public const val SELF: String = "self"

    /**
     * [ref] with `{jitai: self}` replaced by [jitaiId], the canonical rule-id selector the DSL accepts (R10 §4.5); any
     * other reference unchanged. Null [jitaiId] leaves `self` unbound. Delegates to the shared [FeatureRef.bindSelf].
     */
    public fun bindSelf(ref: FeatureRef, jitaiId: String?): FeatureRef = if (jitaiId == null) ref else ref.bindSelf(jitaiId)

    /** The reference [leaf] reads inside the rule [jitaiId]. */
    public fun ref(leaf: Condition.FeatureLeaf, jitaiId: String?): FeatureRef = bindSelf(leaf.ref, jitaiId)

    /** Feature leaves of [condition] in pre-order (bounded by [RuleEvaluator.MAX_DEPTH], like evaluation). */
    public fun leaves(condition: Condition?): List<Condition.FeatureLeaf> {
        if (condition == null) return emptyList()
        val out = mutableListOf<Condition.FeatureLeaf>()
        collect(condition, depth = 1, out = out)
        return out
    }

    /** Every `(feature, args)` reference of [condition], bound to the rule [jitaiId]. */
    public fun of(condition: Condition?, jitaiId: String?): Set<FeatureRef> = leaves(condition).mapTo(linkedSetOf()) { ref(it, jitaiId) }

    /** Every reference of a definition's `conditions` and `contextRequirements`, bound to the definition. */
    public fun of(definition: JitaiDefinition): Set<FeatureRef> =
        of(definition.conditions, definition.id) + of(definition.contextRequirements, definition.id)

    /** The leaf a `{{feature_id}}` placeholder names: the first leaf on [featureId] in `conditions`, then `contextRequirements`. */
    public fun placeholderLeaf(definition: JitaiDefinition, featureId: String): Condition.FeatureLeaf? =
        (leaves(definition.conditions) + leaves(definition.contextRequirements)).firstOrNull { it.feature == featureId }

    private fun collect(condition: Condition, depth: Int, out: MutableList<Condition.FeatureLeaf>) {
        if (depth > RuleEvaluator.MAX_DEPTH) return
        if (condition is Condition.FeatureLeaf) out += condition
        condition.children.forEach { collect(it, depth + 1, out) }
    }
}
