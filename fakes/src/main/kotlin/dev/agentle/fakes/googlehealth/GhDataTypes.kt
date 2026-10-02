package dev.agentle.fakes.googlehealth

/** Which time container a data type carries, and so which filter members it accepts (docs/research/05 §3.2, §5.1). */
public enum class GhCategory { INTERVAL, SAMPLE, DAILY, SESSION }

/** How a filter literal is written: RFC 3339 instant, civil `YYYY-MM-DD[THH:mm:ss]`, or a `YYYY-MM-DD` date. */
public enum class LiteralKind { PHYSICAL, CIVIL, DATE }

/**
 * One Google Health API v4 data type as the fake knows it (docs/research/05 §3.2). The fake encodes the documented
 * contract on its own and shares nothing with the client it tests.
 *
 * @property id kebab-case path id, for example `active-energy-burned`
 * @property unionKey camelCase value key of a data point, for example `activeEnergyBurned`
 * @property scope scope suffix that gates the type (8.1), for example `activity_and_fitness.readonly`
 * @property listable false for `floors` and `total-calories`, whose `list` returns E400-INVALID-ARGUMENT (V1)
 * @property rollUps whether `:rollUp` and `:dailyRollUp` exist for the type
 * @property identifiable whether points carry a `name` and `get` works (sleep, exercise, weight, body-fat, height)
 * @property members filter members the type accepts, with their literal kind (5.1)
 * @property valueField the field that holds a point's primary value (`count`, `kcal`, ...), if the type has one
 * @property int64 whether [valueField] is an int64, which JSON carries as a string (4.1)
 */
public data class GhDataType(
    val id: String,
    val unionKey: String,
    val category: GhCategory,
    val scope: String,
    val valueField: String? = null,
    val int64: Boolean = false,
    val listable: Boolean = true,
    val reconcilable: Boolean = true,
    val rollUps: Boolean = false,
    val identifiable: Boolean = false,
    val rollUpMaxDays: Int = 90,
    val members: Map<String, LiteralKind> = defaultMembers(category),
) {
    /** The type name used in filters (snake_case). */
    val snake: String get() = id.replace('-', '_')

    val maxPageSize: Int get() = if (category == GhCategory.SESSION) SESSION_PAGE else MAX_PAGE

    val defaultPageSize: Int get() = if (category == GhCategory.SESSION) SESSION_PAGE else DEFAULT_PAGE

    public companion object {
        public const val MAX_PAGE: Int = 10_000
        public const val DEFAULT_PAGE: Int = 1440
        public const val SESSION_PAGE: Int = 25

        private fun defaultMembers(category: GhCategory): Map<String, LiteralKind> = when (category) {
            GhCategory.INTERVAL -> mapOf("interval.start_time" to LiteralKind.PHYSICAL, "interval.civil_start_time" to LiteralKind.CIVIL)
            GhCategory.SAMPLE -> mapOf("sample_time.physical_time" to LiteralKind.PHYSICAL, "sample_time.civil_time" to LiteralKind.CIVIL)
            GhCategory.DAILY -> mapOf("date" to LiteralKind.DATE)
            GhCategory.SESSION -> mapOf("interval.civil_start_time" to LiteralKind.CIVIL)
        }
    }
}

/** Read scopes of the Google Health API (docs/research/05 §2.2), as suffixes of [PREFIX]. */
public object GhScopes {
    public const val PREFIX: String = "https://www.googleapis.com/auth/googlehealth."
    public const val ACTIVITY: String = "activity_and_fitness.readonly"
    public const val METRICS: String = "health_metrics_and_measurements.readonly"
    public const val SLEEP: String = "sleep.readonly"
    public const val SETTINGS: String = "settings.readonly"
    public const val PROFILE: String = "profile.readonly"
    public const val LOCATION: String = "location.readonly"

    /** What `fake-valid` grants: every v1 read scope, but not location (8.1). */
    public val V1: Set<String> = setOf(ACTIVITY, METRICS, SLEEP, SETTINGS, PROFILE)

    public fun full(suffix: String): String = PREFIX + suffix

    /** The suffix of a full scope URL, or the argument itself when it already is a suffix. */
    public fun suffix(scope: String): String = scope.removePrefix(PREFIX)
}

/** The v1 data types (docs/research/05 §3.2). Unknown ids get E400-INVALID-ARGUMENT (V1). */
public object GhDataTypes {
    public val STEPS: GhDataType =
        GhDataType("steps", "steps", GhCategory.INTERVAL, GhScopes.ACTIVITY, "count", int64 = true, rollUps = true)
    public val DISTANCE: GhDataType =
        GhDataType("distance", "distance", GhCategory.INTERVAL, GhScopes.ACTIVITY, "millimeters", int64 = true, rollUps = true)
    public val FLOORS: GhDataType =
        GhDataType("floors", "floors", GhCategory.INTERVAL, GhScopes.ACTIVITY, "count", int64 = true, listable = false, rollUps = true)
    public val ACTIVE_ENERGY: GhDataType =
        GhDataType("active-energy-burned", "activeEnergyBurned", GhCategory.INTERVAL, GhScopes.ACTIVITY, "kcal", rollUps = true)
    public val TOTAL_CALORIES: GhDataType = GhDataType(
        "total-calories",
        "totalCalories",
        GhCategory.INTERVAL,
        GhScopes.ACTIVITY,
        "kcal",
        listable = false,
        reconcilable = false,
        rollUps = true,
        rollUpMaxDays = 14,
    )
    public val HEART_RATE: GhDataType =
        GhDataType(
            "heart-rate",
            "heartRate",
            GhCategory.SAMPLE,
            GhScopes.METRICS,
            "beatsPerMinute",
            int64 = true,
            rollUps = true,
            rollUpMaxDays = 14,
        )
    public val RESTING_HEART_RATE: GhDataType =
        GhDataType("daily-resting-heart-rate", "dailyRestingHeartRate", GhCategory.DAILY, GhScopes.METRICS, "beatsPerMinute", int64 = true)
    public val HRV: GhDataType =
        GhDataType("daily-heart-rate-variability", "dailyHeartRateVariability", GhCategory.DAILY, GhScopes.METRICS)
    public val SPO2: GhDataType =
        GhDataType("daily-oxygen-saturation", "dailyOxygenSaturation", GhCategory.DAILY, GhScopes.METRICS)
    public val SLEEP: GhDataType = GhDataType(
        "sleep",
        "sleep",
        GhCategory.SESSION,
        GhScopes.SLEEP,
        identifiable = true,
        members = mapOf("interval.end_time" to LiteralKind.PHYSICAL, "interval.civil_end_time" to LiteralKind.CIVIL),
    )
    public val EXERCISE: GhDataType = GhDataType("exercise", "exercise", GhCategory.SESSION, GhScopes.ACTIVITY, identifiable = true)
    public val WEIGHT: GhDataType =
        GhDataType("weight", "weight", GhCategory.SAMPLE, GhScopes.METRICS, "weightGrams", rollUps = true, identifiable = true)
    public val BODY_FAT: GhDataType =
        GhDataType("body-fat", "bodyFat", GhCategory.SAMPLE, GhScopes.METRICS, "percentage", rollUps = true, identifiable = true)
    public val HEIGHT: GhDataType =
        GhDataType("height", "height", GhCategory.SAMPLE, GhScopes.METRICS, "heightMillimeters", int64 = true, identifiable = true)

    public val ALL: List<GhDataType> = listOf(
        STEPS, DISTANCE, FLOORS, ACTIVE_ENERGY, TOTAL_CALORIES, HEART_RATE, RESTING_HEART_RATE, HRV, SPO2, SLEEP, EXERCISE,
        WEIGHT, BODY_FAT, HEIGHT,
    )

    private val byId = ALL.associateBy { it.id }
    private val byUnionKey = ALL.associateBy { it.unionKey }

    public fun byId(id: String): GhDataType? = byId[id]

    public fun byUnionKey(key: String): GhDataType? = byUnionKey[key]
}

/**
 * The bearer tokens the fake understands (docs/research/05 §8.1). Every token that is not listed here, including
 * [EXPIRED] and [REVOKED], gets E401-INVALID.
 */
public object FakeTokens {
    public const val VALID: String = "fake-valid"
    public const val EXPIRED: String = "fake-expired"
    public const val REVOKED: String = "fake-revoked"
    public const val NOT_LINKED: String = "fake-not-linked"
    public const val NO_PROFILE: String = "fake-no-profile"
    public const val LEGACY: String = "fake-legacy"
    public const val SCOPE_PREFIX: String = "fake-scope-"

    /** A token that grants only [scopes] (suffixes such as `sleep.readonly`, or full scope URLs). */
    public fun scoped(scopes: Collection<String>): String = SCOPE_PREFIX + scopes.joinToString(",") { GhScopes.suffix(it) }

    /** Scope suffixes [token] grants, or null when the fake rejects it with E401-INVALID. */
    public fun scopesOf(token: String): Set<String>? = when {
        token == VALID || token == NOT_LINKED || token == NO_PROFILE || token == LEGACY -> GhScopes.V1

        token.startsWith(SCOPE_PREFIX) -> token.removePrefix(SCOPE_PREFIX).split(',').map { GhScopes.suffix(it.trim()) }
            .filter { it.isNotEmpty() }.toSet()

        else -> null
    }

    /** The account-state error fixture [token] triggers on every routed call (8.1), if any. */
    public fun accountStateFixture(token: String): String? = when (token) {
        NOT_LINKED -> "E400-ACCOUNT-NOT-LINKED"
        NO_PROFILE -> "E412"
        LEGACY -> "E403-LEGACY-A"
        else -> null
    }
}
