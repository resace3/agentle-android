package dev.agentle.jitai.dsl.rule

import dev.agentle.analytics.features.FeatureRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Leaf-level override of an UNKNOWN result (R10 §6.4); `null` on the wire means UNKNOWN propagates. */
@Serializable
public enum class OnUnknown { ASSUME_TRUE, ASSUME_FALSE }

/** Comparison operators of feature leaves (R10 §4.1-4.3). [wire] is the node `type`. */
public enum class Operator(public val wire: String, public val symbol: String) {
    GT("gt", ">"),
    GTE("gte", ">="),
    LT("lt", "<"),
    LTE("lte", "<="),
    EQ("eq", "="),
    NEQ("neq", "!="),
    BETWEEN("between", "between"),
    IN("in", "in"),
    ;

    public companion object {
        public fun fromWire(wire: String): Operator? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The rule DSL (R10 §4.1): one closed, sealed hierarchy decoded with discriminator `type`. There are no variables,
 * functions, loops, string operations or references to code; an unknown `type` rejects the rule (no fallback
 * serializer is registered, R10 §3.6). Evaluation semantics are in R10 §4.2 and §6 (see [OperatorSemantics] for
 * leaves on known values).
 *
 * Arg maps hold only non-null values; a proposal's `null` args are dropped when it is decoded (R10 §11.4 step 3) and
 * the map is written with sorted keys, so the canonical form is stable.
 */
@Serializable
public sealed interface Condition {
    /** `all`: strong Kleene AND over 1..maxWidth children. */
    @Serializable
    @SerialName("all")
    public data class AllOf(val of: List<Condition>) : Condition

    /** `any`: strong Kleene OR over 1..maxWidth children. */
    @Serializable
    @SerialName("any")
    public data class AnyOf(val of: List<Condition>) : Condition

    /** `not`: TRUE <-> FALSE, UNKNOWN stays UNKNOWN. */
    @Serializable
    @SerialName("not")
    public data class Not(val of: Condition) : Condition

    /** A leaf that reads one catalog feature with concrete [args]. */
    public sealed interface FeatureLeaf : Condition {
        public val feature: String
        public val args: Map<String, String>
        public val onUnknown: OnUnknown?
        public val operator: Operator

        /** Literals in wire order: `value`, or `min` + `max`, or `values`. */
        public val literals: List<RuleLiteral>

        /** The `(feature, args)` pair this leaf depends on (R10 §4.8 item 3). */
        public val ref: FeatureRef get() = FeatureRef(feature, args)
    }

    /** `gt`, `gte`, `lt`, `lte`, `eq`, `neq` against one literal. */
    public sealed interface Comparison : FeatureLeaf {
        public val value: RuleLiteral
        override val literals: List<RuleLiteral> get() = listOf(value)
    }

    @Serializable
    @SerialName("gt")
    public data class Gt(
        override val feature: String,
        @Serializable(with = ArgsSerializer::class) override val args: Map<String, String> = emptyMap(),
        override val value: RuleLiteral,
        override val onUnknown: OnUnknown? = null,
    ) : Comparison {
        override val operator: Operator get() = Operator.GT
    }

    @Serializable
    @SerialName("gte")
    public data class Gte(
        override val feature: String,
        @Serializable(with = ArgsSerializer::class) override val args: Map<String, String> = emptyMap(),
        override val value: RuleLiteral,
        override val onUnknown: OnUnknown? = null,
    ) : Comparison {
        override val operator: Operator get() = Operator.GTE
    }

    @Serializable
    @SerialName("lt")
    public data class Lt(
        override val feature: String,
        @Serializable(with = ArgsSerializer::class) override val args: Map<String, String> = emptyMap(),
        override val value: RuleLiteral,
        override val onUnknown: OnUnknown? = null,
    ) : Comparison {
        override val operator: Operator get() = Operator.LT
    }

    @Serializable
    @SerialName("lte")
    public data class Lte(
        override val feature: String,
        @Serializable(with = ArgsSerializer::class) override val args: Map<String, String> = emptyMap(),
        override val value: RuleLiteral,
        override val onUnknown: OnUnknown? = null,
    ) : Comparison {
        override val operator: Operator get() = Operator.LTE
    }

    @Serializable
    @SerialName("eq")
    public data class Eq(
        override val feature: String,
        @Serializable(with = ArgsSerializer::class) override val args: Map<String, String> = emptyMap(),
        override val value: RuleLiteral,
        override val onUnknown: OnUnknown? = null,
    ) : Comparison {
        override val operator: Operator get() = Operator.EQ
    }

    @Serializable
    @SerialName("neq")
    public data class Neq(
        override val feature: String,
        @Serializable(with = ArgsSerializer::class) override val args: Map<String, String> = emptyMap(),
        override val value: RuleLiteral,
        override val onUnknown: OnUnknown? = null,
    ) : Comparison {
        override val operator: Operator get() = Operator.NEQ
    }

    /** `min <= x <= max`, inclusive both ends, in the feature type's order (E017 when `min > max`). */
    @Serializable
    @SerialName("between")
    public data class Between(
        override val feature: String,
        @Serializable(with = ArgsSerializer::class) override val args: Map<String, String> = emptyMap(),
        val min: RuleLiteral,
        val max: RuleLiteral,
        override val onUnknown: OnUnknown? = null,
    ) : FeatureLeaf {
        override val operator: Operator get() = Operator.BETWEEN
        override val literals: List<RuleLiteral> get() = listOf(min, max)
    }

    /** `x` equals one of [values] (1..maxInValues, distinct). */
    @Serializable
    @SerialName("in")
    public data class In(
        override val feature: String,
        @Serializable(with = ArgsSerializer::class) override val args: Map<String, String> = emptyMap(),
        val values: List<RuleLiteral>,
        override val onUnknown: OnUnknown? = null,
    ) : FeatureLeaf {
        override val operator: Operator get() = Operator.IN
        override val literals: List<RuleLiteral> get() = values
    }

    /**
     * `local_time_in`: `[start, end)` on the local wall clock truncated to the minute, crossing midnight when
     * `end < start`; never UNKNOWN. Times are kept as wire strings so an invalid one can be reported (E024).
     */
    @Serializable
    @SerialName("local_time_in")
    public data class LocalTimeIn(val start: String, val end: String) : Condition

    public companion object {
        /** Builds the comparison leaf for [operator] (not BETWEEN or IN). */
        public fun compare(
            operator: Operator,
            feature: String,
            value: RuleLiteral,
            args: Map<String, String> = emptyMap(),
            onUnknown: OnUnknown? = null,
        ): Comparison = when (operator) {
            Operator.GT -> Gt(feature, args, value, onUnknown)
            Operator.GTE -> Gte(feature, args, value, onUnknown)
            Operator.LT -> Lt(feature, args, value, onUnknown)
            Operator.LTE -> Lte(feature, args, value, onUnknown)
            Operator.EQ -> Eq(feature, args, value, onUnknown)
            Operator.NEQ -> Neq(feature, args, value, onUnknown)
            Operator.BETWEEN, Operator.IN -> throw IllegalArgumentException("$operator is not a single-literal comparison")
        }
    }
}

/** Children of a group or `not` node, in wire order. */
public val Condition.children: List<Condition>
    get() = when (this) {
        is Condition.AllOf -> of
        is Condition.AnyOf -> of
        is Condition.Not -> listOf(of)
        is Condition.FeatureLeaf, is Condition.LocalTimeIn -> emptyList()
    }

/** The node `type` on the wire. */
public val Condition.wireType: String
    get() = when (this) {
        is Condition.AllOf -> "all"
        is Condition.AnyOf -> "any"
        is Condition.Not -> "not"
        is Condition.FeatureLeaf -> operator.wire
        is Condition.LocalTimeIn -> "local_time_in"
    }
