package dev.agentle.jitai.dsl.validation

import dev.agentle.jitai.dsl.model.RuleOrigin

/**
 * The limit column of R10 §2.1, §4.6 and §9.2 for one [origin]. The rule editor can read the same numbers to bound its
 * inputs.
 */
public data class RuleLimits(
    val origin: RuleOrigin,
    val maxDepth: Int,
    val maxNodes: Int,
    val maxGroupWidth: Int,
    val maxInValues: Int,
    val minIntervalMinutes: Int,
    val minCooldownMinutes: Int,
    val maxPerDay: Int,
    val maxPerWeek: Int,
    val maxPriority: Int,
    val minDescriptionLength: Int,
) {
    /** `AI` or `USER`, as written in messages ("for AI rules"). */
    val label: String get() = origin.name

    public companion object {
        public const val MAX_INTERVAL_MINUTES: Int = 1440
        public const val INTERVAL_STEP_MINUTES: Int = 15
        public const val MAX_COOLDOWN_MINUTES: Int = 10_080
        public const val MAX_EVENTS: Int = 8
        public const val MAX_DEBOUNCE_SECONDS: Int = 600
        public const val MAX_DAILY_TIMES: Int = 6
        public val LATENESS_MINUTES: IntRange = 5..120
        public val NOTIFICATION_TIMEOUT_MINUTES: IntRange = 5..1440
        public val DELIVERY_DEADLINE_MINUTES: IntRange = 1..60
        public val EXPIRES_IN_DAYS: IntRange = 1..90
        public const val MIN_DELIVER_PROBABILITY: Double = 0.3
        public const val MAX_DELIVER_PROBABILITY: Double = 0.7
        public const val MAX_SNOOZE_OPTIONS: Int = 3
        public const val MIN_VARIANTS: Int = 2
        public const val MAX_VARIANTS: Int = 8
        public const val MAX_SUPPRESSION_JITAI_IDS: Int = 20
        public const val MAX_QUESTIONS: Int = 3
        public val QUESTION_OPTIONS: IntRange = 2..4
        public const val MAX_ASSUMPTIONS: Int = 5
        public const val MAX_ASSUMPTION_PATH_LENGTH: Int = 200

        public val USER: RuleLimits = RuleLimits(
            origin = RuleOrigin.USER,
            maxDepth = 6,
            maxNodes = 32,
            maxGroupWidth = 12,
            maxInValues = 20,
            minIntervalMinutes = 15,
            minCooldownMinutes = 15,
            maxPerDay = 12,
            maxPerWeek = 60,
            maxPriority = 100,
            minDescriptionLength = 0,
        )

        public val AI: RuleLimits = RuleLimits(
            origin = RuleOrigin.AI,
            maxDepth = 4,
            maxNodes = 16,
            maxGroupWidth = 8,
            maxInValues = 10,
            minIntervalMinutes = 30,
            minCooldownMinutes = 60,
            maxPerDay = 3,
            maxPerWeek = 14,
            maxPriority = 60,
            minDescriptionLength = 1,
        )

        public fun of(origin: RuleOrigin): RuleLimits = if (origin == RuleOrigin.AI) AI else USER
    }
}
