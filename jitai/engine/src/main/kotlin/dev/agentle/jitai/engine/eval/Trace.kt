package dev.agentle.jitai.engine.eval

import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.Quality
import dev.agentle.core.model.DataCategory
import dev.agentle.jitai.dsl.rule.ClockTime
import dev.agentle.jitai.dsl.rule.OnUnknown
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * How a leaf's feature value looked in the snapshot. DELETED replaces the value once the user deleted its data category
 * ([dev.agentle.jitai.engine.content.CategoryScrubber], jitai-correctness-19).
 */
@Serializable
public enum class ValueState { KNOWN, STALE, MISSING, NOT_RESOLVED, DELETED }

/** The value part of a leaf trace (R10 §6.7): state, scalar as text, reason and `asOf`. */
@Serializable
public data class TraceValue(
    val state: ValueState,
    val scalar: String? = null,
    val reason: MissingReason? = null,
    val asOf: Instant? = null,
    val quality: Quality? = null,
) {
    public companion object {
        public val NOT_RESOLVED: TraceValue = TraceValue(ValueState.NOT_RESOLVED)

        /** The marker that replaces a value of a deleted data category. */
        public val DELETED: TraceValue = TraceValue(ValueState.DELETED)

        public fun of(value: FeatureValue): TraceValue = when (value) {
            is FeatureValue.Known -> TraceValue(ValueState.KNOWN, display(value.value), asOf = value.asOf, quality = value.quality)
            is FeatureValue.Stale -> TraceValue(ValueState.STALE, display(value.lastValue), value.reason, value.asOf)
            is FeatureValue.Missing -> TraceValue(ValueState.MISSING, reason = value.reason)
        }

        /** Content-free text of a scalar for traces and rendering checks (`45`, `22:30`, `HOME`, `MON`, `NEVER`). */
        public fun display(scalar: FeatureScalar): String = when (scalar) {
            is FeatureScalar.IntValue -> scalar.value.toString()
            is FeatureScalar.BoolValue -> scalar.value.toString()
            is FeatureScalar.EnumValue -> scalar.value
            is FeatureScalar.DayOfWeekValue -> scalar.value.name.take(DAY_ABBREVIATION)
            is FeatureScalar.LocalTimeValue -> ClockTime.format(scalar.minuteOfDay)
            is FeatureScalar.NightTimeValue -> ClockTime.format(scalar.minuteOfDay)
            is FeatureScalar.PackageValue -> scalar.packageName
            FeatureScalar.NoPackage -> "NONE"
            FeatureScalar.Never -> "NEVER"
        }

        private const val DAY_ABBREVIATION = 3
    }
}

/** Why a node evaluated to UNKNOWN for a reason other than the data itself; a stored rule should never produce these. */
@Serializable
public enum class TraceNote {
    UNKNOWN_FEATURE,
    INVALID_LITERAL,
    OPERATOR_NOT_ALLOWED,
    TYPE_MISMATCH,
    INVALID_VALUE,
    INVALID_TIME,
    INVALID_ZONE,
    EMPTY_GROUP,
    TOO_DEEP,
    STALE_OTHER_DAY,
}

/**
 * One evaluated node (R10 §6.7): its JSON-pointer [path] (`""` for the root, `/of/0` for the first child), wire [type]
 * and [result]; leaves add the feature, its data [category] (`FeatureDefinition.category`, null for clock and calendar
 * features; jitai-correctness-19), args, value, literals and whether the monotone lower bound or an `onUnknown` override
 * decided the result.
 */
@Serializable
public data class TraceNode(
    val path: String,
    val type: String,
    val result: Tri,
    val feature: String? = null,
    val category: DataCategory? = null,
    val args: Map<String, String>? = null,
    val value: TraceValue? = null,
    val literals: List<String>? = null,
    val lowerBound: Boolean? = null,
    @SerialName("override") val appliedOverride: OnUnknown? = null,
    val overrideIgnored: Boolean? = null,
    val note: TraceNote? = null,
)

/** The trace of one evaluated tree in pre-order. [truncated] marks a trace reduced to fit the row cap. */
@Serializable
public data class TreeTrace(val result: Tri, val nodes: List<TraceNode>, val truncated: Boolean = false) {
    /** The root and the first failing branch (R10 §6.7 deterministic truncation): from the root, repeatedly the first non-TRUE child. */
    public fun firstFailingBranch(): TreeTrace {
        if (nodes.isEmpty()) return copy(truncated = true)
        val kept = mutableListOf(nodes.first())
        var current = nodes.first()
        while (current.result != Tri.TRUE) {
            val prefix = if (current.path.isEmpty()) "/of" else "${current.path}/of"
            val child = nodes.firstOrNull { node -> isDirectChild(node.path, prefix) && node.result != Tri.TRUE } ?: break
            kept += child
            current = child
        }
        return TreeTrace(result, kept, truncated = true)
    }

    private fun isDirectChild(path: String, prefix: String): Boolean {
        if (!path.startsWith(prefix)) return false
        val rest = path.removePrefix(prefix)
        return rest.isEmpty() || (rest.startsWith("/") && rest.drop(1).all(Char::isDigit) && rest.length > 1)
    }
}
