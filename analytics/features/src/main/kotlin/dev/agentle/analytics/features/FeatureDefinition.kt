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

/** Whether a rule may reference a feature in this build. Unavailable features stay in the catalog so the validator
 * can say why (R10 §11.2) and so stored rules keep decoding; the NL prompt catalog and schema enum leave them out. */
public sealed interface FeatureAvailability {
    public data object Available : FeatureAvailability

    /** @property capabilityId the capability (docs/research/capabilities.json) whose absence makes it unavailable. */
    public data class Unavailable(val capabilityId: String, val reason: String) : FeatureAvailability
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
 * @property availability whether rules may reference it in this build.
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
    val availability: FeatureAvailability = FeatureAvailability.Available,
) {
    init {
        require(ID.matches(id)) { "feature id must be snake_case: $id" }
        require((type == FeatureType.INT) == (literalRange != null)) { "$id: INT features need a literal range, others none" }
        require((type == FeatureType.ENUM) == enumValues.isNotEmpty()) { "$id: ENUM features need values, others none" }
        require(enumValues.all { ENUM_MEMBER.matches(it) }) { "$id: enum members are UPPER_SNAKE" }
        require(args.map { it.name }.toSet().size == args.size) { "$id: duplicate arg names" }
    }

    public fun arg(name: String): FeatureArg? = args.firstOrNull { it.name == name }

    public val isAvailable: Boolean get() = availability == FeatureAvailability.Available

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

/**
 * This ref with `jitai=self` replaced by [jitaiId], the id of the rule being evaluated (R10 §4.5, §11.4); any other ref
 * is returned unchanged. Stored rules keep `self`. The caller binds it per rule before resolving and looks values up
 * by the bound ref: a snapshot memoizes refs by [FeatureRef.key], so an unbound `self` in two rules would share one
 * value, and the resolver answers an unbound `self` with `Missing(INVALID_VALUE)` ([FeatureResolver]).
 */
public fun FeatureRef.bindSelf(jitaiId: String): FeatureRef =
    if (args[JITAI_ARG] == SELF) copy(args = args + (JITAI_ARG to jitaiId)) else this

private const val JITAI_ARG = "jitai"
private const val SELF = "self"
