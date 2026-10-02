package dev.agentle.analytics.insights

/**
 * Exposures of the pre-registered hypothesis family (docs/research/10 §14.3), in table order. Each is read from one
 * daily feature of `daily_summary` for night `d` (the engine day `d`, or the calendar date `d` for steps).
 *
 * @property id the identifier used in proposals and evidence (`E_screen45`).
 * @property dailyFeatureId the daily feature the exposure is read from.
 */
public enum class Exposure(public val id: String, public val dailyFeatureId: String) {
    /** Screen-on minutes in `[22:00, 24:00)` of date `d` >= 30. */
    SCREEN_30("E_screen30", "screen_minutes_22_24"),

    /** Screen-on minutes in `[22:00, 24:00)` >= 45. */
    SCREEN_45("E_screen45", "screen_minutes_22_24"),

    /** Screen-on minutes in `[22:00, 24:00)` >= 60. */
    SCREEN_60("E_screen60", "screen_minutes_22_24"),

    /** SOCIAL-category minutes in `[22:00, 24:00)` >= 20. */
    SOCIAL_20("E_social20", "social_minutes_22_24"),

    /** Notifications posted in `[21:00, 24:00)` >= 20. */
    NOTIFICATIONS_20("E_notif20", "notifications_21_24"),

    /** Steps on local date `d` < 5,000. */
    STEPS_UNDER_5K("E_steps5k", "steps"),
    ;

    /** Whether a night with daily value [value] is exposed. */
    public fun isExposed(value: Double): Boolean = when (this) {
        SCREEN_30 -> value >= SCREEN_30_MIN
        SCREEN_45 -> value >= SCREEN_45_MIN
        SCREEN_60 -> value >= SCREEN_60_MIN
        SOCIAL_20 -> value >= SOCIAL_MIN
        NOTIFICATIONS_20 -> value >= NOTIFICATIONS_MIN
        STEPS_UNDER_5K -> value < STEPS_LIMIT
    }

    public companion object {
        public const val SCREEN_30_MIN: Double = 30.0
        public const val SCREEN_45_MIN: Double = 45.0
        public const val SCREEN_60_MIN: Double = 60.0
        public const val SOCIAL_MIN: Double = 20.0
        public const val NOTIFICATIONS_MIN: Double = 20.0
        public const val STEPS_LIMIT: Double = 5_000.0

        public fun byId(id: String): Exposure? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Outcomes of the hypothesis family (docs/research/10 §14.3), in table order.
 *
 * @property id the identifier used in proposals and evidence (`O_late`).
 * @property dailyFeatureId the daily feature the outcome is read from.
 */
public enum class NightOutcome(public val id: String, public val dailyFeatureId: String) {
    /** Bedtime of the night's main sleep >= the personal median bedtime over the window (night clock) + 30 min. */
    LATE_BEDTIME("O_late", "bedtime"),

    /** Asleep minutes of the night's main sleep <= the personal median - 45 min. */
    SHORT_SLEEP("O_short", "sleep_minutes"),

    /**
     * Resting heart rate on date `d+1` >= the lower median of the previous 28 dates + 3 bpm (needs >= 14 baseline
     * values).
     */
    HIGH_RESTING_HR("O_rhr", "resting_hr"),
    ;

    public companion object {
        public const val LATE_MARGIN_MINUTES: Double = 30.0
        public const val SHORT_MARGIN_MINUTES: Double = 45.0
        public const val RHR_MARGIN_BPM: Double = 3.0
        public const val RHR_BASELINE_DAYS: Int = 28
        public const val RHR_MIN_BASELINE: Int = 14

        public fun byId(id: String): NightOutcome? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One hypothesis of the family: an [exposure] together with an [outcome], numbered `3 x (exposure row - 1) + outcome
 * row` (so `E_screen45` with `O_late` is H04).
 */
public data class Hypothesis(val exposure: Exposure, val outcome: NightOutcome) {
    public val number: Int get() = NightOutcome.entries.size * exposure.ordinal + outcome.ordinal + 1

    /** `H01` to `H18`. */
    public val id: String get() = "H" + number.toString().padStart(2, '0')

    /** The pattern id of this hypothesis with the sign of [riskDifference] (`H04:+`). */
    public fun patternId(riskDifference: Double): String = id + if (riskDifference > 0) ":+" else ":-"
}

/**
 * The fixed hypothesis family H01-H18 (docs/research/10 §14.3). All 18 count toward the multiple-comparison family in
 * every run, even when they cannot be tested. Adding a hypothesis is a versioned app change ([VERSION]).
 */
public object HypothesisFamily {
    public const val VERSION: Int = 1

    public val all: List<Hypothesis> = Exposure.entries.flatMap { e -> NightOutcome.entries.map { o -> Hypothesis(e, o) } }

    public val size: Int get() = all.size

    public fun byId(id: String): Hypothesis? = all.firstOrNull { it.id == id }
}
