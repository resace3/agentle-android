package dev.agentle.jitai.dsl.rule

import dev.agentle.analytics.features.FeatureDefinition
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureType
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import kotlinx.datetime.DayOfWeek

/** A rule literal converted by its feature's catalog type (R10 §4.4). Rules never hold floating point. */
public data class TypedLiteral(val scalar: FeatureScalar) {
    /** Position in the type's total order, or null for unordered types (see [orderKey]). */
    val orderKey: Long? get() = scalar.orderKey()

    override fun toString(): String = scalar.toString()
}

/**
 * Position of a value in its type's total order (R10 §4.3): INT by value, LOCAL_TIME by minute (00:00 < ... < 23:59),
 * NIGHT_TIME by night-clock minute (12:00 < ... < 23:59 < 00:00 < ... < 11:59). Null for BOOL, ENUM, DAY_OF_WEEK,
 * PACKAGE and the special values `NEVER` and `NONE`.
 */
public fun FeatureScalar.orderKey(): Long? = when (this) {
    is FeatureScalar.IntValue -> value
    is FeatureScalar.LocalTimeValue -> minuteOfDay.toLong()
    is FeatureScalar.NightTimeValue -> nightOrder.toLong()
    else -> null
}

/** Why a literal could not be converted; [code] is the R10 §11.2 error code. */
public enum class LiteralRejection(public val code: String) {
    /** Wrong JSON form for the type (`45.0`, `"45"`, `1` for a BOOL, other case for an ENUM). */
    TYPE_MISMATCH("E015"),

    /** An integer outside the catalog's literal range. */
    OUT_OF_RANGE("E016"),

    /** A time string that is not `HH:mm`. */
    INVALID_TIME("E024"),

    /** A string that is not an Android package name. */
    INVALID_PACKAGE("E081"),
}

/** Result of [TypedLiterals.convert]. */
public sealed interface LiteralConversion {
    public data class Converted(val literal: TypedLiteral) : LiteralConversion

    public data class Rejected(val reason: LiteralRejection) : LiteralConversion
}

/** Literal conversion (R10 §4.4) and operator legality (R10 §4.3) by catalog type. */
public object TypedLiterals {
    /** R10 §4.4 package regex; at most 255 characters. */
    private val PACKAGE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    private const val MAX_PACKAGE_LENGTH = 255

    private val DAYS: Map<String, DayOfWeek> = mapOf(
        "MON" to DayOfWeek.MONDAY,
        "TUE" to DayOfWeek.TUESDAY,
        "WED" to DayOfWeek.WEDNESDAY,
        "THU" to DayOfWeek.THURSDAY,
        "FRI" to DayOfWeek.FRIDAY,
        "SAT" to DayOfWeek.SATURDAY,
        "SUN" to DayOfWeek.SUNDAY,
    )

    private val ORDERED = setOf(Operator.GT, Operator.GTE, Operator.LT, Operator.LTE)
    private val ALLOWED: Map<FeatureType, Set<Operator>> = mapOf(
        FeatureType.INT to Operator.entries.toSet(),
        FeatureType.BOOL to setOf(Operator.EQ, Operator.NEQ),
        FeatureType.ENUM to setOf(Operator.EQ, Operator.NEQ, Operator.IN),
        FeatureType.DAY_OF_WEEK to setOf(Operator.EQ, Operator.NEQ, Operator.IN),
        FeatureType.LOCAL_TIME to ORDERED + setOf(Operator.EQ, Operator.NEQ, Operator.BETWEEN),
        FeatureType.NIGHT_TIME to ORDERED + Operator.BETWEEN,
        FeatureType.PACKAGE to setOf(Operator.EQ, Operator.NEQ, Operator.IN),
    )

    /** Operators allowed for [type], in R10 §4.3 order. */
    public fun allowedOperators(type: FeatureType): List<Operator> = Operator.entries.filter { it in ALLOWED.getValue(type) }

    public fun isAllowed(type: FeatureType, operator: Operator): Boolean = operator in ALLOWED.getValue(type)

    /** True for a valid Android package name (R10 §4.4). */
    public fun isPackageName(text: String): Boolean = text.length <= MAX_PACKAGE_LENGTH && PACKAGE.matches(text)

    /** The day for `MON`..`SUN`, else null. */
    public fun dayOfWeek(text: String): DayOfWeek? = DAYS[text]

    /** Converts [literal] by the catalog type of [definition]. */
    public fun convert(definition: FeatureDefinition, literal: RuleLiteral): LiteralConversion = when (definition.type) {
        FeatureType.INT -> convertInt(definition, literal)

        FeatureType.BOOL -> (literal as? RuleLiteral.Bool)?.let { converted(FeatureScalar.BoolValue(it.value)) } ?: mismatch()

        FeatureType.ENUM -> text(literal)?.takeIf { it in definition.enumValues }?.let { converted(FeatureScalar.EnumValue(it)) }
            ?: mismatch()

        FeatureType.DAY_OF_WEEK -> text(literal)?.let { DAYS[it] }?.let { converted(FeatureScalar.DayOfWeekValue(it)) } ?: mismatch()

        FeatureType.LOCAL_TIME -> convertTime(literal) { FeatureScalar.LocalTimeValue(it) }

        FeatureType.NIGHT_TIME -> convertTime(literal) { FeatureScalar.NightTimeValue(it) }

        FeatureType.PACKAGE -> when (val value = text(literal)) {
            null -> mismatch()
            else -> if (isPackageName(value)) converted(FeatureScalar.PackageValue(value)) else rejected(LiteralRejection.INVALID_PACKAGE)
        }
    }

    /** Typed literals of [leaf] in wire order, or null when its feature is unknown or a literal does not convert. */
    public fun typedLiterals(leaf: Condition.FeatureLeaf): List<TypedLiteral>? {
        val definition = RealtimeFeatureCatalog[leaf.feature] ?: return null
        return leaf.literals.map { (convert(definition, it) as? LiteralConversion.Converted)?.literal ?: return null }
    }

    private fun convertInt(definition: FeatureDefinition, literal: RuleLiteral): LiteralConversion {
        val token = (literal as? RuleLiteral.NumberToken)?.takeIf { it.isInteger }
        val range = definition.literalRange
        if (token == null || range == null) return mismatch()
        // An integer token too large for a Long is out of every catalog range.
        val value = token.token.toLongOrNull()
        return if (value != null && value in range) converted(FeatureScalar.IntValue(value)) else rejected(LiteralRejection.OUT_OF_RANGE)
    }

    private fun convertTime(literal: RuleLiteral, make: (Int) -> FeatureScalar): LiteralConversion {
        val value = text(literal) ?: return mismatch()
        val minute = ClockTime.minuteOfDay(value) ?: return rejected(LiteralRejection.INVALID_TIME)
        return converted(make(minute))
    }

    private fun text(literal: RuleLiteral): String? = (literal as? RuleLiteral.Text)?.value

    private fun converted(scalar: FeatureScalar): LiteralConversion = LiteralConversion.Converted(TypedLiteral(scalar))

    private fun mismatch(): LiteralConversion = rejected(LiteralRejection.TYPE_MISMATCH)

    private fun rejected(reason: LiteralRejection): LiteralConversion = LiteralConversion.Rejected(reason)
}

/**
 * Leaf semantics on a known value (R10 §4.2, §5.4): the comparison a leaf makes once its feature value is `Known`.
 * Three-valued evaluation, staleness and `onUnknown` are the evaluator's (R10 §6); this is the shared, pure core.
 *
 * Special values: `NEVER` (`minutes_since_last_delivery` with no delivery) compares as +infinity, so `gt`, `gte`, `neq`
 * are true and `lt`, `lte`, `eq`, `between`, `in` false; `NONE` (`foreground_app` with nothing on screen) equals no
 * package (`eq` false, `neq` true, `in` false).
 */
public object OperatorSemantics {
    /** Whether `value operator literals` holds; null when the value and literals are not comparable (a type mismatch). */
    public fun holds(operator: Operator, value: FeatureScalar, literals: List<TypedLiteral>): Boolean? {
        if (literals.isEmpty()) return null
        return when (value) {
            FeatureScalar.Never -> operator in NEVER_TRUE

            FeatureScalar.NoPackage -> when (operator) {
                Operator.EQ, Operator.IN -> false
                Operator.NEQ -> true
                else -> null
            }

            else -> compare(operator, value, literals)
        }
    }

    private val NEVER_TRUE = setOf(Operator.GT, Operator.GTE, Operator.NEQ)

    private fun compare(operator: Operator, value: FeatureScalar, literals: List<TypedLiteral>): Boolean? {
        if (literals.any { it.scalar::class != value::class }) return null
        val x = value.orderKey()
        val first = literals.first()
        return when (operator) {
            Operator.EQ -> value == first.scalar

            Operator.NEQ -> value != first.scalar

            Operator.IN -> literals.any { it.scalar == value }

            Operator.GT -> ordered(x, first) { a, b -> a > b }

            Operator.GTE -> ordered(x, first) { a, b -> a >= b }

            Operator.LT -> ordered(x, first) { a, b -> a < b }

            Operator.LTE -> ordered(x, first) { a, b -> a <= b }

            Operator.BETWEEN -> {
                val min = first.orderKey
                val max = literals.getOrNull(1)?.orderKey
                if (x == null || min == null || max == null) null else x in min..max
            }
        }
    }

    private inline fun ordered(x: Long?, literal: TypedLiteral, test: (Long, Long) -> Boolean): Boolean? {
        val y = literal.orderKey
        return if (x == null || y == null) null else test(x, y)
    }
}
