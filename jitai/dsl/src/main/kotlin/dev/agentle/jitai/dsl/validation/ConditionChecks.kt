package dev.agentle.jitai.dsl.validation

import dev.agentle.analytics.features.FeatureAvailability
import dev.agentle.analytics.features.FeatureDefinition
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.jitai.dsl.analysis.OverrideEffect
import dev.agentle.jitai.dsl.analysis.RuleAnalysis
import dev.agentle.jitai.dsl.codec.JsonPointer
import dev.agentle.jitai.dsl.model.JitaiKind
import dev.agentle.jitai.dsl.model.RuleOrigin
import dev.agentle.jitai.dsl.render.FeatureLabels
import dev.agentle.jitai.dsl.rule.ClockTime
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.LiteralConversion
import dev.agentle.jitai.dsl.rule.LiteralRejection
import dev.agentle.jitai.dsl.rule.RuleLiteral
import dev.agentle.jitai.dsl.rule.TypedLiteral
import dev.agentle.jitai.dsl.rule.TypedLiterals

/** S6 checks of one condition tree (R10 §11.2 "Conditions", E010-E028, and C05). */
internal class ConditionChecks(
    private val sink: IssueSink,
    private val view: RuleView,
    private val limits: RuleLimits,
    private val isKnownId: (String) -> Boolean,
) {
    private val origin: RuleOrigin get() = limits.origin

    /** Checks [tree] stored under [treeName] (`conditions` or `contextRequirements`). */
    fun check(tree: Condition, treeName: String) {
        val root = view.path(treeName)
        val depth = RuleAnalysis.depth(tree)
        if (depth > limits.maxDepth) {
            add(IssueCode.E020, root, "tree" to treeName, "depth" to depth.toString(), "max" to limits.maxDepth.toString())
        }
        val nodes = RuleAnalysis.nodeCount(tree)
        if (nodes > limits.maxNodes) {
            add(IssueCode.E021, root, "tree" to treeName, "n" to nodes.toString(), "max" to limits.maxNodes.toString())
        }
        walk(tree, root)
        checkOverrides(tree, treeName, root)
        val window = view.activeWindow?.takeIf { ClockTime.isValid(it.start) && ClockTime.isValid(it.end) && it.start != it.end }
        RuleAnalysis.unsatisfiable(tree, window)?.let { finding ->
            add(IssueCode.E027, root + finding.path, "explanation" to finding.explanation)
        }
    }

    private fun walk(tree: Condition, rootPath: String) {
        val stack = ArrayDeque<Pair<Condition, String>>()
        stack.addLast(tree to rootPath)
        while (stack.isNotEmpty()) {
            val (node, path) = stack.removeLast()
            when (node) {
                is Condition.AllOf -> group("all", node.of, path, stack)
                is Condition.AnyOf -> group("any", node.of, path, stack)
                is Condition.Not -> stack.addLast(node.of to "$path/of")
                is Condition.LocalTimeIn -> timeWindow(node, path)
                is Condition.FeatureLeaf -> leaf(node, path)
            }
        }
    }

    private fun group(op: String, children: List<Condition>, path: String, stack: ArrayDeque<Pair<Condition, String>>) {
        if (children.isEmpty()) add(IssueCode.E022, path, "op" to op)
        if (children.size > limits.maxGroupWidth) {
            add(IssueCode.E023, path, "op" to op, "n" to children.size.toString(), "max" to limits.maxGroupWidth.toString())
        }
        for (index in children.indices.reversed()) stack.addLast(children[index] to "$path/of/$index")
    }

    private fun timeWindow(node: Condition.LocalTimeIn, path: String) {
        val startValid = ClockTime.isValid(node.start)
        val endValid = ClockTime.isValid(node.end)
        if (!startValid) add(IssueCode.E024, "$path/start", "value" to node.start)
        if (!endValid) add(IssueCode.E024, "$path/end", "value" to node.end)
        if (startValid && endValid && node.start == node.end) add(IssueCode.E025, path, "start" to node.start)
    }

    private fun leaf(leaf: Condition.FeatureLeaf, path: String) {
        val definition = RealtimeFeatureCatalog[leaf.feature]
        if (definition == null) {
            add(IssueCode.E010, "$path/feature", "feature" to leaf.feature)
            return
        }
        val availability = definition.availability
        if (availability is FeatureAvailability.Unavailable) {
            // Integrator correction (lifecycle-battery-06): the feature exists in the catalog but this build cannot
            // provide it. The other checks still run so the report lists every defect of the leaf.
            add(
                IssueCode.E028,
                "$path/feature",
                "kind" to "Feature",
                "name" to definition.id,
                "capability" to availability.capabilityId,
            )
        }
        checkArgs(leaf, definition, "$path/args")
        val operatorAllowed = TypedLiterals.isAllowed(definition.type, leaf.operator)
        if (!operatorAllowed) {
            add(
                IssueCode.E014,
                "$path/type",
                "op" to leaf.operator.wire,
                "feature" to definition.id,
                "valueType" to definition.type.name,
                "allowed" to TypeDescriptions.allowedOperators(definition),
            )
        }
        val typed = convertLiterals(leaf, definition, path)
        if (leaf is Condition.In) checkInList(leaf, typed, path)
        if (leaf is Condition.Between && operatorAllowed && typed != null) checkBetween(typed, path)
    }

    private fun checkArgs(leaf: Condition.FeatureLeaf, definition: FeatureDefinition, argsPath: String) {
        for (problem in ArgRules.check(definition.args, leaf.args, view.isDraft, origin, isKnownId)) {
            val path = JsonPointer.child(argsPath, problem.arg)
            when (problem.type) {
                ArgProblemType.MISSING -> add(IssueCode.E011, path, "feature" to definition.id, "arg" to problem.arg)

                ArgProblemType.UNEXPECTED -> add(IssueCode.E012, path, "feature" to definition.id, "arg" to problem.arg)

                ArgProblemType.INVALID -> add(
                    IssueCode.E013,
                    path,
                    "feature" to definition.id,
                    "arg" to problem.arg,
                    "reason" to problem.reason,
                )

                ArgProblemType.PACKAGE -> add(IssueCode.E081, path, "value" to problem.value)

                ArgProblemType.TIME -> add(IssueCode.E024, path, "value" to problem.value)
            }
        }
    }

    /** Converts every literal (R10 §4.4); returns the typed literals, or null when one is rejected. */
    private fun convertLiterals(leaf: Condition.FeatureLeaf, definition: FeatureDefinition, path: String): List<TypedLiteral>? {
        val paths = literalPaths(leaf, path)
        var allConverted = true
        val typed = ArrayList<TypedLiteral>(leaf.literals.size)
        leaf.literals.forEachIndexed { index, literal ->
            when (val conversion = TypedLiterals.convert(definition, literal)) {
                is LiteralConversion.Converted -> typed += conversion.literal

                is LiteralConversion.Rejected -> {
                    allConverted = false
                    reportLiteral(conversion.reason, literal, definition, paths[index])
                }
            }
        }
        return if (allConverted) typed else null
    }

    private fun literalPaths(leaf: Condition.FeatureLeaf, path: String): List<String> = when (leaf) {
        is Condition.Between -> listOf("$path/min", "$path/max")
        is Condition.In -> leaf.values.indices.map { "$path/values/$it" }
        else -> listOf("$path/value")
    }

    private fun reportLiteral(reason: LiteralRejection, literal: RuleLiteral, definition: FeatureDefinition, path: String) {
        when (reason) {
            LiteralRejection.TYPE_MISMATCH -> add(
                IssueCode.E015,
                path,
                "expected" to TypeDescriptions.literal(definition),
                "feature" to definition.id,
                "json" to literal.json,
            )

            LiteralRejection.OUT_OF_RANGE -> {
                val range = definition.literalRange
                add(
                    IssueCode.E016,
                    path,
                    "value" to literal.json,
                    "min" to range?.first.toString(),
                    "max" to range?.last.toString(),
                    "feature" to definition.id,
                )
            }

            LiteralRejection.INVALID_TIME -> add(IssueCode.E024, path, "value" to display(literal))

            LiteralRejection.INVALID_PACKAGE -> add(IssueCode.E081, path, "value" to display(literal))
        }
    }

    private fun checkInList(leaf: Condition.In, typed: List<TypedLiteral>?, path: String) {
        val n = leaf.values.size
        if (n !in 1..limits.maxInValues) {
            add(IssueCode.E018, "$path/values", "max" to limits.maxInValues.toString(), "n" to n.toString())
        }
        val seen = HashSet<Any>()
        val reported = HashSet<Any>()
        leaf.values.forEachIndexed { index, literal ->
            val key: Any = typed?.get(index)?.scalar ?: literal
            if (!seen.add(key) && reported.add(key)) {
                add(IssueCode.E019, "$path/values/$index", "value" to display(literal))
            }
        }
    }

    private fun checkBetween(typed: List<TypedLiteral>, path: String) {
        val min = typed[0]
        val max = typed[1]
        val minKey = min.orderKey
        val maxKey = max.orderKey
        if (minKey != null && maxKey != null && minKey > maxKey) {
            add(IssueCode.E017, path, "min" to scalarText(min.scalar), "max" to scalarText(max.scalar))
        }
    }

    private fun checkOverrides(tree: Condition, treeName: String, root: String) {
        // R10 §6.4 classifies overrides of INTERVENTION trees and of SUPPRESSION conditions; a SUPPRESSION with
        // contextRequirements is rejected separately (E053).
        if (view.kind == JitaiKind.SUPPRESSION && treeName != CONDITIONS) return
        for (info in RuleAnalysis.leaves(tree)) {
            val leaf = info.node as? Condition.FeatureLeaf ?: continue
            val onUnknown = leaf.onUnknown ?: continue
            if (RuleAnalysis.overrideEffect(view.kind, info.polarity, onUnknown) != OverrideEffect.DELIVERY_INCREASING) continue
            val path = "$root${info.path}/onUnknown"
            val needsError = origin == RuleOrigin.AI || !view.userConfirmedUnknownOverrides
            if (needsError) {
                val effect = if (view.kind == JitaiKind.INTERVENTION) "notify you" else "stop blocking"
                add(IssueCode.E026, path, "value" to onUnknown.name, "effect" to effect, "feature" to leaf.feature)
            }
            if (origin == RuleOrigin.USER && !view.userConfirmedUnknownOverrides) {
                add(IssueCode.C05, path, "feature" to FeatureLabels.labelOrId(leaf.feature))
            }
        }
    }

    private fun display(literal: RuleLiteral): String = (literal as? RuleLiteral.Text)?.value ?: literal.json

    private fun scalarText(scalar: FeatureScalar): String = when (scalar) {
        is FeatureScalar.IntValue -> scalar.value.toString()
        is FeatureScalar.LocalTimeValue -> ClockTime.format(scalar.minuteOfDay)
        is FeatureScalar.NightTimeValue -> ClockTime.format(scalar.minuteOfDay)
        else -> scalar.toString()
    }

    private fun add(code: IssueCode, path: String, vararg params: Pair<String, String>) {
        sink.add(code, Stage.S6, path, params.toMap())
    }

    private companion object {
        const val CONDITIONS = "conditions"
    }
}
