package dev.agentle.jitai.dsl.analysis

import dev.agentle.analytics.features.FeatureDefinition
import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureType
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.jitai.dsl.model.ActiveWindow
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiKind
import dev.agentle.jitai.dsl.rule.ClockTime
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.OnUnknown
import dev.agentle.jitai.dsl.rule.Operator
import dev.agentle.jitai.dsl.rule.RuleLiteral
import dev.agentle.jitai.dsl.rule.TypedLiteral
import dev.agentle.jitai.dsl.rule.TypedLiterals
import dev.agentle.jitai.dsl.rule.children
import dev.agentle.jitai.dsl.rule.orderKey

/** Polarity of a leaf (R10 §4.8 item 1): even number of enclosing `not` is [POSITIVE]. */
public enum class Polarity { POSITIVE, NEGATIVE }

/** Whether an `onUnknown` override can make the rule deliver more or less often (R10 §6.4). */
public enum class OverrideEffect { DELIVERY_INCREASING, DELIVERY_DECREASING }

/**
 * One leaf of a condition tree.
 *
 * @property path JSON pointer relative to the tree root (`""` for a leaf at the root, `/of/1/of`, ...).
 * @property node a [Condition.FeatureLeaf] or a [Condition.LocalTimeIn].
 * @property determining reached from the root only through `all` nodes, polarity `+` and `onUnknown` null: TRUE
 *   whenever the tree is TRUE, so its value is in the snapshot whenever the rule fires (R10 §11.2 E065).
 * @property depth 1 for a leaf at the root.
 */
public data class LeafInfo(val path: String, val node: Condition, val polarity: Polarity, val determining: Boolean, val depth: Int) {
    /** The feature the leaf reads: its `feature`, or `local_time` for `local_time_in`. */
    val featureId: String
        get() = when (node) {
            is Condition.FeatureLeaf -> node.feature
            else -> LOCAL_TIME
        }

    /** The `(feature, args)` pair this leaf depends on. */
    val ref: FeatureRef
        get() = when (node) {
            is Condition.FeatureLeaf -> node.ref
            else -> FeatureRef(LOCAL_TIME)
        }

    private companion object {
        const val LOCAL_TIME = "local_time"
    }
}

/** A proof that a condition tree can never be TRUE (R10 §4.8 item 2, error E027). */
public data class Unsatisfiability(val path: String, val explanation: String)

/**
 * Static analysis of a rule (R10 §4.8): leaf polarity, provable unsatisfiability and the feature dependency list.
 *
 * Unsatisfiability is sound but incomplete. Inside one `all` node, leaves on the same `(feature, args)` are intersected
 * as integer intervals (INT, LOCAL_TIME, NIGHT_TIME in their type order, `NEVER` counted as +infinity) or as sets (BOOL,
 * ENUM, DAY_OF_WEEK, PACKAGE; `neq` removes members), and `local_time_in` nodes and `local_time` leaves are intersected
 * as 1,440-bit minute masks together with the active window. A finding is reported only when it makes the whole tree
 * never TRUE (an `any` needs every child to be never TRUE; a `not` is never treated as never TRUE), so a satisfiable rule
 * is never rejected. Leaves with `onUnknown: ASSUME_TRUE` are left out: they can be TRUE whatever the value.
 */
public object RuleAnalysis {
    private const val LOCAL_TIME = "local_time"

    /** Deeper trees are not analyzed (no finding is the sound answer); validation rejects them anyway (E020). */
    private const val MAX_ANALYZED_DEPTH = 64

    /** Leaves of [tree] in wire order (pre-order, children left to right). Iterative, so any depth is safe. */
    public fun leaves(tree: Condition): List<LeafInfo> {
        val result = ArrayList<LeafInfo>()
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(tree, "", Polarity.POSITIVE, onlyAll = true, depth = 1))
        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            when (val node = frame.node) {
                is Condition.FeatureLeaf -> result += LeafInfo(
                    frame.path,
                    node,
                    frame.polarity,
                    frame.onlyAll && frame.polarity == Polarity.POSITIVE && node.onUnknown == null,
                    frame.depth,
                )

                is Condition.LocalTimeIn -> result += LeafInfo(frame.path, node, frame.polarity, frame.onlyAll, frame.depth)

                is Condition.AllOf -> pushChildren(stack, frame, node.of, frame.polarity, frame.onlyAll)

                is Condition.AnyOf -> pushChildren(stack, frame, node.of, frame.polarity, onlyAll = false)

                is Condition.Not -> stack.addLast(Frame(node.of, frame.path + "/of", frame.polarity.flip(), false, frame.depth + 1))
            }
        }
        return result
    }

    /** Depth of [tree]: the root is level 1 and leaves count (R10 §4.6). Iterative. */
    public fun depth(tree: Condition): Int {
        var max = 0
        val stack = ArrayDeque<Pair<Condition, Int>>()
        stack.addLast(tree to 1)
        while (stack.isNotEmpty()) {
            val (node, level) = stack.removeLast()
            if (level > max) max = level
            node.children.forEach { stack.addLast(it to level + 1) }
        }
        return max
    }

    /** Number of nodes (groups, `not`, leaves) in [tree]. Iterative. */
    public fun nodeCount(tree: Condition): Int {
        var count = 0
        val stack = ArrayDeque<Condition>()
        stack.addLast(tree)
        while (stack.isNotEmpty()) {
            count++
            stack.removeLast().children.forEach { stack.addLast(it) }
        }
        return count
    }

    /**
     * The feature dependency list (R10 §4.8 item 3): every `(feature, args)` pair read by the trees, in wire order,
     * `local_time` for `local_time_in` nodes. Outcome metrics are not dependencies.
     */
    public fun dependencies(vararg trees: Condition?): Set<FeatureRef> {
        val result = LinkedHashSet<FeatureRef>()
        trees.filterNotNull().forEach { tree -> leaves(tree).forEach { result += it.ref } }
        return result
    }

    /** [dependencies] of a definition's `conditions` and `contextRequirements`. */
    public fun dependencies(definition: JitaiDefinition): Set<FeatureRef> =
        dependencies(definition.conditions, definition.contextRequirements)

    /** How an override at [polarity] changes delivery for a rule of [kind] (R10 §6.4 table); null without override. */
    public fun overrideEffect(kind: JitaiKind, polarity: Polarity, onUnknown: OnUnknown?): OverrideEffect? {
        if (onUnknown == null) return null
        val assumeTrue = onUnknown == OnUnknown.ASSUME_TRUE
        val positive = polarity == Polarity.POSITIVE
        // INTERVENTION fires on TRUE: making a + leaf TRUE (or a - leaf FALSE) adds deliveries.
        // SUPPRESSION blocks on TRUE or UNKNOWN: making a + leaf FALSE (or a - leaf TRUE) blocks less.
        val increasing = when (kind) {
            JitaiKind.INTERVENTION -> assumeTrue == positive
            JitaiKind.SUPPRESSION -> assumeTrue != positive
        }
        return if (increasing) OverrideEffect.DELIVERY_INCREASING else OverrideEffect.DELIVERY_DECREASING
    }

    /**
     * Minutes of the day at which [node] can be TRUE when it is a `local_time_in` node or a valid `local_time` leaf;
     * null for any other node or when a time is invalid.
     */
    public fun timeMask(node: Condition): MinuteMask? = when (node) {
        is Condition.LocalTimeIn -> if (node.start == node.end) null else MinuteMask.window(node.start, node.end)
        is Condition.FeatureLeaf -> if (node.feature == LOCAL_TIME) localTimeLeafMask(node) else null
        else -> null
    }

    /**
     * A proof that [tree] can never be TRUE, alone or together with [activeWindow], or null when none is found.
     * [Unsatisfiability.path] is relative to the tree root.
     */
    public fun unsatisfiable(tree: Condition, activeWindow: ActiveWindow? = null): Unsatisfiability? {
        if (depth(tree) > MAX_ANALYZED_DEPTH) return null
        val root = analyze(tree, "")
        if (root.neverTrue) return root.conflict
        val window = activeWindow?.takeIf { it.start != it.end }?.let { MinuteMask.window(it.start, it.end) } ?: return null
        if ((root.mask and window).isEmpty) {
            val subject = root.timeText ?: return null
            return Unsatisfiability("", "$subject is outside the active window ${activeWindow.start}-${activeWindow.end}")
        }
        return null
    }

    // ------------------------------------------------------------------------------------------ implementation

    private class Frame(val node: Condition, val path: String, val polarity: Polarity, val onlyAll: Boolean, val depth: Int)

    private fun Polarity.flip(): Polarity = if (this == Polarity.POSITIVE) Polarity.NEGATIVE else Polarity.POSITIVE

    private fun pushChildren(stack: ArrayDeque<Frame>, parent: Frame, children: List<Condition>, polarity: Polarity, onlyAll: Boolean) {
        for (index in children.indices.reversed()) {
            stack.addLast(Frame(children[index], "${parent.path}/of/$index", polarity, onlyAll, parent.depth + 1))
        }
    }

    /** What is known about one node: never TRUE (with the first proof), and the minutes at which it can be TRUE. */
    private class NodeResult(val neverTrue: Boolean, val conflict: Unsatisfiability?, val mask: MinuteMask, val timeText: String?)

    private val UNCONSTRAINED = NodeResult(false, null, MinuteMask.ALL, null)

    private fun analyze(node: Condition, path: String): NodeResult = when (node) {
        is Condition.LocalTimeIn -> timeLeaf(node, path)
        is Condition.FeatureLeaf -> if (node.feature == LOCAL_TIME) timeLeaf(node, path) else UNCONSTRAINED
        is Condition.Not -> analyzeNot(node)
        is Condition.AllOf -> analyzeAll(node, path)
        is Condition.AnyOf -> analyzeAny(node, path)
    }

    private fun timeLeaf(node: Condition, path: String): NodeResult {
        val mask = timeMask(node) ?: return UNCONSTRAINED
        if (node is Condition.FeatureLeaf && node.onUnknown == OnUnknown.ASSUME_TRUE) return UNCONSTRAINED
        val text = timeText(node)
        return if (mask.isEmpty) {
            NodeResult(true, Unsatisfiability(path, "$text is never true"), mask, text)
        } else {
            NodeResult(false, null, mask, text)
        }
    }

    private fun analyzeNot(node: Condition.Not): NodeResult {
        val child = node.of
        val mask = timeMask(child) ?: return UNCONSTRAINED
        if (child is Condition.FeatureLeaf && child.onUnknown != null) return UNCONSTRAINED
        // local_time is never UNKNOWN, so the negation of a time leaf is exactly its complement.
        return NodeResult(false, null, !mask, "not (${timeText(child)})")
    }

    private fun analyzeAll(node: Condition.AllOf, path: String): NodeResult {
        if (node.of.isEmpty()) return UNCONSTRAINED
        val results = node.of.mapIndexed { index, child -> analyze(child, "$path/of/$index") }
        var mask = MinuteMask.ALL
        results.forEach { mask = mask and it.mask }
        val texts = results.mapNotNull { it.timeText }
        val timeText = texts.takeIf { it.isNotEmpty() }?.joinToString(" and ")
        val conflict = results.firstOrNull { it.neverTrue }?.conflict
            ?: intersectLeaves(node, path)
            ?: if (mask.isEmpty) Unsatisfiability(path, timeText ?: "the time conditions") else null
        return NodeResult(conflict != null, conflict, mask, timeText)
    }

    private fun analyzeAny(node: Condition.AnyOf, path: String): NodeResult {
        if (node.of.isEmpty()) return UNCONSTRAINED
        val results = node.of.mapIndexed { index, child -> analyze(child, "$path/of/$index") }
        var mask = MinuteMask.NONE
        results.forEach { mask = mask or it.mask }
        val neverTrue = results.all { it.neverTrue }
        val timeText = if (results.all { it.timeText != null }) results.joinToString(" or ", "(", ")") { it.timeText.orEmpty() } else null
        return NodeResult(neverTrue, if (neverTrue) results.first().conflict else null, mask, timeText)
    }

    /** One leaf usable for interval and set reasoning: catalog type known, operator allowed, literals converted. */
    private class TypedLeaf(val leaf: Condition.FeatureLeaf, val definition: FeatureDefinition, val literals: List<TypedLiteral>)

    private fun intersectLeaves(node: Condition.AllOf, path: String): Unsatisfiability? {
        val groups = LinkedHashMap<String, MutableList<TypedLeaf>>()
        for (child in node.of) {
            val typed = typedLeaf(child) ?: continue
            groups.getOrPut(typed.leaf.ref.key) { ArrayList() } += typed
        }
        for (group in groups.values) {
            if (group.size < 2) continue
            val definition = group.first().definition
            val empty = when (definition.type) {
                FeatureType.INT, FeatureType.LOCAL_TIME, FeatureType.NIGHT_TIME -> intervalEmpty(group)
                else -> setEmpty(group, domainOf(definition))
            }
            if (empty) return Unsatisfiability(path, group.joinToString(" and ") { leafText(it.leaf) })
        }
        return null
    }

    private fun typedLeaf(node: Condition): TypedLeaf? {
        val leaf = node as? Condition.FeatureLeaf ?: return null
        if (leaf.onUnknown == OnUnknown.ASSUME_TRUE) return null
        val definition = RealtimeFeatureCatalog[leaf.feature] ?: return null
        if (!TypedLiterals.isAllowed(definition.type, leaf.operator)) return null
        val literals = TypedLiterals.typedLiterals(leaf) ?: return null
        if (literals.isEmpty()) return null
        if (leaf is Condition.Between) {
            val min = literals[0].orderKey
            val max = literals[1].orderKey
            if (min != null && max != null && min > max) return null
        }
        return TypedLeaf(leaf, definition, literals)
    }

    /** Interval reasoning on the type's total order; `NEVER` is +infinity (only `gt`, `gte` and `neq` admit it). */
    private fun intervalEmpty(group: List<TypedLeaf>): Boolean {
        var lo = Long.MIN_VALUE
        var hi = Long.MAX_VALUE
        var members: Set<Long>? = null
        val excluded = HashSet<Long>()
        for (typed in group) {
            val keys = typed.literals.map { it.orderKey ?: return false }
            val k = keys.first()
            when (typed.leaf.operator) {
                Operator.GT -> lo = maxOf(lo, k + 1)

                Operator.GTE -> lo = maxOf(lo, k)

                Operator.LT -> hi = minOf(hi, k - 1)

                Operator.LTE -> hi = minOf(hi, k)

                Operator.BETWEEN -> {
                    lo = maxOf(lo, keys[0])
                    hi = minOf(hi, keys[1])
                }

                Operator.EQ -> members = (members ?: setOf(k)) intersect setOf(k)

                Operator.IN -> members = (members ?: keys.toSet()) intersect keys.toSet()

                Operator.NEQ -> excluded += k
            }
        }
        if (lo > hi) return true
        val known = members
        if (known != null) return known.none { it in lo..hi && it !in excluded }
        if (lo == Long.MIN_VALUE || hi == Long.MAX_VALUE) return false
        val size = hi - lo + 1
        return size <= excluded.size && (lo..hi).all { it in excluded }
    }

    /** Set reasoning for unordered types; [domain] is the finite set of possible values, or null when unbounded. */
    private fun setEmpty(group: List<TypedLeaf>, domain: Set<FeatureScalar>?): Boolean {
        var members: Set<FeatureScalar>? = null
        val excluded = HashSet<FeatureScalar>()
        for (typed in group) {
            val values = typed.literals.map { it.scalar }
            when (typed.leaf.operator) {
                Operator.EQ, Operator.IN -> members = (members ?: values.toSet()) intersect values.toSet()
                Operator.NEQ -> excluded += values.first()
                else -> return false
            }
        }
        val candidates = members ?: domain ?: return false
        return (candidates - excluded).isEmpty()
    }

    private fun domainOf(definition: FeatureDefinition): Set<FeatureScalar>? = when (definition.type) {
        FeatureType.BOOL -> setOf(FeatureScalar.BoolValue(true), FeatureScalar.BoolValue(false))
        FeatureType.ENUM -> definition.enumValues.map { FeatureScalar.EnumValue(it) }.toSet()
        FeatureType.DAY_OF_WEEK -> kotlinx.datetime.DayOfWeek.entries.map { FeatureScalar.DayOfWeekValue(it) }.toSet()
        else -> null
    }

    private fun localTimeLeafMask(leaf: Condition.FeatureLeaf): MinuteMask? {
        val minutes = leaf.literals.map { literal ->
            val text = (literal as? RuleLiteral.Text)?.value ?: return null
            ClockTime.minuteOfDay(text) ?: return null
        }
        val v = minutes.first()
        val last = ClockTime.MINUTES_PER_DAY - 1
        return when (leaf.operator) {
            Operator.GT -> MinuteMask.range(v + 1, last)
            Operator.GTE -> MinuteMask.range(v, last)
            Operator.LT -> MinuteMask.range(0, v - 1)
            Operator.LTE -> MinuteMask.range(0, v)
            Operator.EQ -> MinuteMask.of(v)
            Operator.NEQ -> !MinuteMask.of(v)
            Operator.BETWEEN -> if (minutes[1] < v) null else MinuteMask.range(v, minutes[1])
            Operator.IN -> null
        }
    }

    private fun timeText(node: Condition): String = when (node) {
        is Condition.LocalTimeIn -> "local_time ${node.start}-${node.end}"
        is Condition.FeatureLeaf -> leafText(node)
        else -> "the time conditions"
    }

    /** `steps_today < 3000`, `day_type in [WEEKEND, WEEKDAY]`, `steps_today between 45 and 50` (E027 explanations). */
    internal fun leafText(leaf: Condition.FeatureLeaf): String {
        val subject = leaf.ref.key
        val values = leaf.literals.map { literal -> (literal as? RuleLiteral.Text)?.value ?: literal.json }
        return when (leaf.operator) {
            Operator.BETWEEN -> "$subject between ${values[0]} and ${values[1]}"
            Operator.IN -> "$subject in ${values.joinToString(", ", "[", "]")}"
            else -> "$subject ${leaf.operator.symbol} ${values.first()}"
        }
    }
}
