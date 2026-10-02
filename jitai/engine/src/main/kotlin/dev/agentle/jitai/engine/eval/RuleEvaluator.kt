package dev.agentle.jitai.engine.eval

import dev.agentle.analytics.features.FeatureDefinition
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureSnapshot
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiKind
import dev.agentle.jitai.dsl.model.RuleOrigin
import dev.agentle.jitai.dsl.rule.ClockTime
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.LiteralConversion
import dev.agentle.jitai.dsl.rule.OnUnknown
import dev.agentle.jitai.dsl.rule.Operator
import dev.agentle.jitai.dsl.rule.OperatorSemantics
import dev.agentle.jitai.dsl.rule.TypedLiteral
import dev.agentle.jitai.dsl.rule.TypedLiterals
import dev.agentle.jitai.engine.time.LocalWindow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** What the root of a tree decides: an INTERVENTION fires only on TRUE, a SUPPRESSION blocks on TRUE or UNKNOWN (R10 §6.5). */
public enum class RootKind {
    INTERVENTION,
    SUPPRESSION,
    ;

    public companion object {
        public fun of(kind: JitaiKind): RootKind = if (kind == JitaiKind.SUPPRESSION) SUPPRESSION else INTERVENTION
    }
}

/**
 * Which `onUnknown` overrides the evaluator honours (R10 §6.4). Delivery-decreasing overrides always apply.
 * Delivery-increasing ones apply only to a USER rule whose user confirmed them (`userConfirmedUnknownOverrides`);
 * the validator rejects them elsewhere (E026), and the evaluator ignores them as a second line of defence, so an AI rule
 * stored by mistake still cannot fire on missing data.
 */
public data class OverridePolicy(val allowDeliveryIncreasing: Boolean) {
    public companion object {
        public val STRICT: OverridePolicy = OverridePolicy(allowDeliveryIncreasing = false)

        public fun of(definition: JitaiDefinition): OverridePolicy = OverridePolicy(
            allowDeliveryIncreasing = RuleOrigin.of(definition.createdBy) == RuleOrigin.USER && definition.userConfirmedUnknownOverrides,
        )

        /** Whether [override] at a leaf of polarity [positive] can increase deliveries for a tree of [kind] (R10 §6.4 table). */
        public fun isDeliveryIncreasing(override: OnUnknown, positive: Boolean, kind: RootKind): Boolean =
            (kind == RootKind.INTERVENTION) == ((override == OnUnknown.ASSUME_TRUE) == positive)
    }
}

/**
 * Three-valued evaluation of a rule tree against one [FeatureSnapshot] (R10 §6): Kleene AND/OR/NOT, leaf evaluation by
 * value state (Known compares, Missing is UNKNOWN, Stale is UNKNOWN except for the monotone lower-bound rule of
 * `monotoneNonDecreasing` features within the same local day), then `onUnknown` overrides. Every child is evaluated in
 * array order so the trace is complete and identical on every run (R10 §4.2).
 *
 * Pure and total: it never throws. Anything a validated rule cannot contain (unknown feature, bad literal, operator not
 * allowed for the type, an unresolved snapshot entry, an invalid zone, an empty group, excessive depth) evaluates to
 * UNKNOWN with a [TraceNote], which never fires an intervention.
 */
public class RuleEvaluator(private val catalog: (String) -> FeatureDefinition? = { RealtimeFeatureCatalog[it] }) {
    /** Evaluates [condition] (null = TRUE with an empty trace). */
    public fun evaluate(
        condition: Condition?,
        snapshot: FeatureSnapshot,
        kind: RootKind = RootKind.INTERVENTION,
        policy: OverridePolicy = OverridePolicy.STRICT,
    ): TreeTrace {
        if (condition == null) return TreeTrace(Tri.TRUE, emptyList())
        val context = Context(snapshot, kind, policy, zoneOf(snapshot.zoneId))
        val nodes = mutableListOf<TraceNode>()
        val result = node(condition, path = "", positive = true, depth = 1, context = context, out = nodes)
        return TreeTrace(result, nodes)
    }

    private class Context(val snapshot: FeatureSnapshot, val kind: RootKind, val policy: OverridePolicy, val zone: TimeZone?) {
        val local = zone?.let { snapshot.at.toLocalDateTime(it) }
        val localMinute: Int? = local?.let { it.hour * MINUTES_PER_HOUR + it.minute }
    }

    private fun node(
        condition: Condition,
        path: String,
        positive: Boolean,
        depth: Int,
        context: Context,
        out: MutableList<TraceNode>,
    ): Tri {
        val index = out.size
        if (depth > MAX_DEPTH) {
            out += TraceNode(path, typeOf(condition), Tri.UNKNOWN, note = TraceNote.TOO_DEEP)
            return Tri.UNKNOWN
        }
        out += TraceNode(path, typeOf(condition), Tri.UNKNOWN)
        val traced: TraceNode = when (condition) {
            is Condition.AllOf -> group(condition.of, path, positive, depth, context, out, all = true).let { result ->
                TraceNode(path, "all", result, note = if (condition.of.isEmpty()) TraceNote.EMPTY_GROUP else null)
            }

            is Condition.AnyOf -> group(condition.of, path, positive, depth, context, out, all = false).let { result ->
                TraceNode(path, "any", result, note = if (condition.of.isEmpty()) TraceNote.EMPTY_GROUP else null)
            }

            is Condition.Not -> TraceNode(path, "not", !node(condition.of, "$path/of", !positive, depth + 1, context, out))

            is Condition.FeatureLeaf -> leaf(condition, path, positive, context)

            is Condition.LocalTimeIn -> localTimeIn(condition, path, context)
        }
        out[index] = traced
        return traced.result
    }

    private fun group(
        children: List<Condition>,
        path: String,
        positive: Boolean,
        depth: Int,
        context: Context,
        out: MutableList<TraceNode>,
        all: Boolean,
    ): Tri {
        val results = children.mapIndexed { i, child -> node(child, "$path/of/$i", positive, depth + 1, context, out) }
        return if (all) Tri.all(results) else Tri.any(results)
    }

    private fun localTimeIn(condition: Condition.LocalTimeIn, path: String, context: Context): TraceNode {
        val base = TraceNode(path, "local_time_in", Tri.UNKNOWN, literals = listOf(condition.start, condition.end))
        val window = LocalWindow.of(condition.start, condition.end)?.takeIf { it.isValid }
            ?: return base.copy(note = TraceNote.INVALID_TIME)
        val minute = context.localMinute ?: return base.copy(note = TraceNote.INVALID_ZONE)
        val value = TraceValue(ValueState.KNOWN, ClockTime.format(minute), asOf = context.snapshot.at)
        return base.copy(result = Tri.of(window.containsMinute(minute)), value = value)
    }

    private fun leaf(leaf: Condition.FeatureLeaf, path: String, positive: Boolean, context: Context): TraceNode {
        val raw = rawLeaf(leaf, context)
        val base = TraceNode(
            path = path,
            type = leaf.operator.wire,
            result = raw.result,
            feature = leaf.feature,
            category = catalog(leaf.feature)?.category,
            args = leaf.args.takeIf { it.isNotEmpty() }?.toSortedMap(),
            value = raw.value,
            literals = leaf.literals.map { it.json },
            lowerBound = raw.lowerBound.takeIf { it },
            note = raw.note,
        )
        val override = leaf.onUnknown
        if (raw.result != Tri.UNKNOWN || override == null) return base
        val increasing = OverridePolicy.isDeliveryIncreasing(override, positive, context.kind)
        if (increasing && !context.policy.allowDeliveryIncreasing) return base.copy(overrideIgnored = true)
        val forced = if (override == OnUnknown.ASSUME_TRUE) Tri.TRUE else Tri.FALSE
        return base.copy(result = forced, appliedOverride = override)
    }

    private class Raw(
        val result: Tri = Tri.UNKNOWN,
        val value: TraceValue? = null,
        val lowerBound: Boolean = false,
        val note: TraceNote? = null,
    )

    private fun rawLeaf(leaf: Condition.FeatureLeaf, context: Context): Raw {
        val definition = catalog(leaf.feature) ?: return Raw(note = TraceNote.UNKNOWN_FEATURE)
        if (!TypedLiterals.isAllowed(definition.type, leaf.operator)) return Raw(note = TraceNote.OPERATOR_NOT_ALLOWED)
        val literals = typedLiterals(definition, leaf) ?: return Raw(note = TraceNote.INVALID_LITERAL)
        return valued(definition, leaf, literals, context)
    }

    private fun valued(definition: FeatureDefinition, leaf: Condition.FeatureLeaf, literals: List<TypedLiteral>, context: Context): Raw {
        val value = context.snapshot[leaf.ref] ?: return Raw(value = TraceValue.NOT_RESOLVED)
        val traced = TraceValue.of(value)
        return when (value) {
            is FeatureValue.Known -> OperatorSemantics.holds(leaf.operator, value.value, literals)
                ?.let { Raw(Tri.of(it), traced) }
                ?: Raw(value = traced, note = TraceNote.TYPE_MISMATCH)

            is FeatureValue.Stale -> stale(definition, leaf.operator, value, literals, traced, context)

            is FeatureValue.Missing -> Raw(value = traced)
        }
    }

    private fun typedLiterals(definition: FeatureDefinition, leaf: Condition.FeatureLeaf): List<TypedLiteral>? {
        val converted = leaf.literals.map { (TypedLiterals.convert(definition, it) as? LiteralConversion.Converted)?.literal }
        return if (converted.isEmpty() || converted.any { it == null }) null else converted.filterNotNull()
    }

    /** R10 §6.3: a stale value is UNKNOWN unless it is a same-day lower bound of a monotone non-decreasing feature. */
    private fun stale(
        definition: FeatureDefinition,
        operator: Operator,
        value: FeatureValue.Stale,
        literals: List<TypedLiteral>,
        traced: TraceValue,
        context: Context,
    ): Raw {
        val zone = context.zone
        val bound = (value.lastValue as? FeatureScalar.IntValue)?.value
        val ints = literals.mapNotNull { (it.scalar as? FeatureScalar.IntValue)?.value }
        return when {
            !definition.monotoneNonDecreasing -> Raw(value = traced)
            zone == null -> Raw(value = traced, note = TraceNote.INVALID_ZONE)
            value.asOf.toLocalDateTime(zone).date != context.local?.date -> Raw(value = traced, note = TraceNote.STALE_OTHER_DAY)
            bound == null || ints.size != literals.size -> Raw(value = traced, note = TraceNote.TYPE_MISMATCH)
            else -> lowerBound(operator, bound, ints).let { Raw(it, traced, lowerBound = it != Tri.UNKNOWN) }
        }
    }

    public companion object {
        /** Deeper trees than any validated rule (USER depth 6) evaluate to UNKNOWN instead of recursing further. */
        public const val MAX_DEPTH: Int = 32
        private const val MINUTES_PER_HOUR = 60

        /**
         * The monotone lower-bound table of R10 §6.3: with a same-day stale value `vs` of a feature whose true value can
         * only be `>= vs`, some comparisons are already decided.
         */
        public fun lowerBound(operator: Operator, vs: Long, literals: List<Long>): Tri {
            val k = literals.first()
            return when (operator) {
                Operator.GTE -> if (vs >= k) Tri.TRUE else Tri.UNKNOWN
                Operator.GT -> if (vs > k) Tri.TRUE else Tri.UNKNOWN
                Operator.LT -> if (vs >= k) Tri.FALSE else Tri.UNKNOWN
                Operator.LTE, Operator.EQ -> if (vs > k) Tri.FALSE else Tri.UNKNOWN
                Operator.NEQ -> if (vs > k) Tri.TRUE else Tri.UNKNOWN
                Operator.BETWEEN -> if (vs > literals.last()) Tri.FALSE else Tri.UNKNOWN
                Operator.IN -> if (vs > literals.max()) Tri.FALSE else Tri.UNKNOWN
            }
        }

        private fun zoneOf(id: String): TimeZone? = try {
            TimeZone.of(id)
        } catch (expected: IllegalArgumentException) {
            null
        }

        private fun typeOf(condition: Condition): String = when (condition) {
            is Condition.AllOf -> "all"
            is Condition.AnyOf -> "any"
            is Condition.Not -> "not"
            is Condition.FeatureLeaf -> condition.operator.wire
            is Condition.LocalTimeIn -> "local_time_in"
        }
    }
}
