package dev.agentle.jitai.dsl.property

import dev.agentle.analytics.features.FeatureArgKind
import dev.agentle.analytics.features.FeatureDefinition
import dev.agentle.analytics.features.FeatureType
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.jitai.dsl.analysis.RuleAnalysis
import dev.agentle.jitai.dsl.rule.ClockTime
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.LiteralConversion
import dev.agentle.jitai.dsl.rule.OnUnknown
import dev.agentle.jitai.dsl.rule.Operator
import dev.agentle.jitai.dsl.rule.RuleLiteral
import dev.agentle.jitai.dsl.rule.TypedLiterals
import dev.agentle.jitai.dsl.testing.Fixtures
import kotlin.random.Random

/**
 * Seeded generator of valid stored condition trees (R10 §4): every available catalog feature with an allowed operator,
 * literals of its type and range, valid args, `local_time_in` windows, and `all`/`any`/`not` groups within the USER
 * limits of R10 §4.6 (depth 6, 32 nodes, 12 children, 20 `in` values). `since` args fall 1 minute to 12 hours before
 * 17:00, the daily time of the rule the trees are placed in (E029). A tree can still be provably unsatisfiable (E027).
 */
internal class RuleTreeGenerator(private val random: Random) {
    private val features: List<FeatureDefinition> = RealtimeFeatureCatalog.all.filter { it.isAvailable }

    /** A tree of 1 to [maxNodes] nodes. */
    fun tree(maxNodes: Int = MAX_NODES): Condition = node(depth = 1, budget = random.nextInt(1, maxNodes + 1))

    /** One random leaf of any available feature. */
    fun leaf(): Condition.FeatureLeaf {
        val definition = features[random.nextInt(features.size)]
        val operators = TypedLiterals.allowedOperators(definition.type)
        val operator = operators[random.nextInt(operators.size)]
        val args = args(definition)
        val onUnknown = ON_UNKNOWN[random.nextInt(ON_UNKNOWN.size)]
        val id = definition.id
        return when (operator) {
            Operator.GT -> Condition.Gt(id, args, literal(definition), onUnknown)
            Operator.GTE -> Condition.Gte(id, args, literal(definition), onUnknown)
            Operator.LT -> Condition.Lt(id, args, literal(definition), onUnknown)
            Operator.LTE -> Condition.Lte(id, args, literal(definition), onUnknown)
            Operator.EQ -> Condition.Eq(id, args, literal(definition), onUnknown)
            Operator.NEQ -> Condition.Neq(id, args, literal(definition), onUnknown)
            Operator.BETWEEN -> ordered(definition).let { (min, max) -> Condition.Between(id, args, min, max, onUnknown) }
            Operator.IN -> Condition.In(id, args, distinct(definition), onUnknown)
        }
    }

    /** A valid `HH:mm` time. */
    fun time(): String = ClockTime.format(random.nextInt(ClockTime.MINUTES_PER_DAY))

    private fun node(depth: Int, budget: Int): Condition {
        val choice = if (depth < MAX_DEPTH && budget >= 2) random.nextInt(CHOICES) else LEAF
        return when (choice) {
            0, 1 -> group(depth, budget, all = true)
            2 -> group(depth, budget, all = false)
            3 -> Condition.Not(node(depth + 1, budget - 1))
            4 -> window()
            else -> leaf()
        }
    }

    /** Each child gets the budget left after reserving one node for every later sibling. */
    private fun group(depth: Int, budget: Int, all: Boolean): Condition {
        var left = budget - 1
        val width = random.nextInt(1, minOf(MAX_WIDTH, left) + 1)
        val children = ArrayList<Condition>(width)
        for (index in 0 until width) {
            val child = node(depth + 1, left - (width - index - 1))
            left -= RuleAnalysis.nodeCount(child)
            children += child
        }
        return if (all) Condition.AllOf(children) else Condition.AnyOf(children)
    }

    private fun window(): Condition {
        val start = random.nextInt(ClockTime.MINUTES_PER_DAY)
        val end = (start + random.nextInt(1, ClockTime.MINUTES_PER_DAY)) % ClockTime.MINUTES_PER_DAY
        return Condition.LocalTimeIn(ClockTime.format(start), ClockTime.format(end))
    }

    private fun args(definition: FeatureDefinition): Map<String, String> = definition.args.associate { arg ->
        arg.name to when (arg.kind) {
            FeatureArgKind.PACKAGE -> Fixtures.INSTALLED[random.nextInt(Fixtures.INSTALLED.size)].packageName
            FeatureArgKind.SINCE -> SINCE[random.nextInt(SINCE.size)]
            FeatureArgKind.APP_CATEGORY -> RealtimeFeatureCatalog.APP_CATEGORIES[random.nextInt(RealtimeFeatureCatalog.APP_CATEGORIES.size)]
            FeatureArgKind.JITAI_REF -> JITAI_REFS[random.nextInt(JITAI_REFS.size)]
        }
    }

    private fun literal(definition: FeatureDefinition): RuleLiteral = when (definition.type) {
        FeatureType.INT -> checkNotNull(definition.literalRange).let { RuleLiteral.of(random.nextLong(it.first, it.last + 1)) }
        FeatureType.BOOL -> RuleLiteral.of(random.nextBoolean())
        FeatureType.ENUM -> RuleLiteral.of(definition.enumValues[random.nextInt(definition.enumValues.size)])
        FeatureType.DAY_OF_WEEK -> RuleLiteral.of(DAYS[random.nextInt(DAYS.size)])
        FeatureType.LOCAL_TIME, FeatureType.NIGHT_TIME -> RuleLiteral.of(time())
        FeatureType.PACKAGE -> RuleLiteral.of(PACKAGES[random.nextInt(PACKAGES.size)])
    }

    /** `between` bounds in the type's order (night order for NIGHT_TIME), so min <= max (E017). */
    private fun ordered(definition: FeatureDefinition): Pair<RuleLiteral, RuleLiteral> {
        val a = literal(definition)
        val b = literal(definition)
        return if (orderKey(definition, a) <= orderKey(definition, b)) a to b else b to a
    }

    private fun orderKey(definition: FeatureDefinition, literal: RuleLiteral): Long {
        val converted = TypedLiterals.convert(definition, literal) as LiteralConversion.Converted
        return checkNotNull(converted.literal.orderKey)
    }

    /** 1-5 values that are distinct as typed values (E019). */
    private fun distinct(definition: FeatureDefinition): List<RuleLiteral> {
        val wanted = random.nextInt(1, MAX_IN_VALUES + 1)
        return List(wanted) { literal(definition) }.distinctBy { TypedLiterals.convert(definition, it) }
    }

    companion object {
        const val MAX_DEPTH = 6
        const val MAX_NODES = 32
        const val MAX_WIDTH = 12
        private const val MAX_IN_VALUES = 5
        private const val CHOICES = 10
        private const val LEAF = 9
        private val ON_UNKNOWN = listOf(null, null, null, OnUnknown.ASSUME_TRUE, OnUnknown.ASSUME_FALSE)
        private val DAYS = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
        private val SINCE = listOf("05:00", "09:15", "12:00", "16:59")
        private val JITAI_REFS = listOf("self", "any", "category:PHYSICAL_ACTIVITY", "category:GENERAL")
        private val PACKAGES = Fixtures.INSTALLED.map { it.packageName } + "org.example.reader"
    }
}
