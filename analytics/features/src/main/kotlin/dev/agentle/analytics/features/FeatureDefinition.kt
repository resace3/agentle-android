package dev.agentle.analytics.features

import dev.agentle.core.model.DataCategory
import kotlinx.serialization.Serializable
import kotlin.time.Duration

/** Value types of real-time features; they decide operators and literal syntax (R10 §4.3-4.4). */
@Serializable
public enum class FeatureType { INT, BOOL, ENUM, DAY_OF_WEEK, LOCAL_TIME, NIGHT_TIME, PACKAGE }

/** Argument syntaxes (R10 §4.5). `appLabel` is accepted in AI proposals in place of `package` and resolved on device. */
@Serializable
public enum class FeatureArgKind { PACKAGE, APP_CATEGORY, SINCE, JITAI_REF }

public data class FeatureArg(val name: String, val kind: FeatureArgKind)

public enum class FeatureGroup { TIME, DEVICE, USAGE, NOTIFICATIONS, PLACE, ACTIVITY, SLEEP, HEART, HISTORY }

/** When a value counts as fresh (R10 §5.3). A value that is not fresh is `Stale` or `Missing`, never a guess. */
public sealed interface Freshness {
    /** Clock, calendar and intervention history: always known. */
    public data object AlwaysKnown : Freshness

    /** A device API read at evaluation time; an exception or "unknown" answer gives `Missing(API_UNAVAILABLE)`. */
    public data object LiveRead : Freshness

    /** An on-device collector must have been healthy for the whole window, otherwise `Missing(COVERAGE_GAP)`. */
    public data object CollectorCoverage : Freshness

    /** A synced source must assert completeness up to `t - maxLag` (`coverageThrough`), otherwise `Stale`. */
    public data class SourceLag(val maxLag: Duration) : Freshness

    /** A per-day value (sleep session, resting heart rate) that exists only once the source has computed it. */
    public data object DailyValue : Freshness
}

/**
 * One entry of the real-time feature catalog: the closed set of inputs a JITAI rule may reference.
 *
 * @property literalRange accepted range for rule literals (INT only).
 * @property validRange observed values outside it become `Missing(INVALID_VALUE)`; defaults to [literalRange].
 * @property monotoneNonDecreasing a stale value is a lower bound of the true value (R10 §6.3), e.g. `steps_today`.
 * @property sources capability ids (docs/research/capabilities.json) or connector ids that can supply the data;
 *   any one is enough. Drives permission checks and the "data required" list shown before a rule is saved.
 * @property category the personal-data category for consent and AI sharing; null for clock and calendar.
 */
public data class FeatureDefinition(
    val id: String,
    val group: FeatureGroup,
    val type: FeatureType,
    val description: String,
    val freshness: Freshness,
    val unit: String? = null,
    val args: List<FeatureArg> = emptyList(),
    val literalRange: LongRange? = null,
    val validRange: LongRange? = literalRange,
    val enumValues: List<String> = emptyList(),
    val monotoneNonDecreasing: Boolean = false,
    val minApi: Int? = null,
    val sources: Set<String> = emptySet(),
    val category: DataCategory? = null,
) {
    init {
        require(ID.matches(id)) { "feature id must be snake_case: $id" }
        require((type == FeatureType.INT) == (literalRange != null)) { "$id: INT features need a literal range, others none" }
        require((type == FeatureType.ENUM) == enumValues.isNotEmpty()) { "$id: ENUM features need values, others none" }
        require(enumValues.all { ENUM_MEMBER.matches(it) }) { "$id: enum members are UPPER_SNAKE" }
        require(args.map { it.name }.toSet().size == args.size) { "$id: duplicate arg names" }
    }

    public fun arg(name: String): FeatureArg? = args.firstOrNull { it.name == name }

    private companion object {
        val ID = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")
        val ENUM_MEMBER = Regex("^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$")
    }
}

/**
 * A feature with concrete args, the unit a snapshot memoizes (R10 §5.2). [key] is canonical: args sorted by name,
 * so `{package=a, since=22:00}` and `{since=22:00, package=a}` are the same reference.
 */
@Serializable
public data class FeatureRef(val featureId: String, val args: Map<String, String> = emptyMap()) {
    val key: String
        get() = if (args.isEmpty()) {
            featureId
        } else {
            featureId +
                args.toSortedMap().entries.joinToString(",", "{", "}") { "${it.key}=${it.value}" }
        }

    override fun toString(): String = key
}
