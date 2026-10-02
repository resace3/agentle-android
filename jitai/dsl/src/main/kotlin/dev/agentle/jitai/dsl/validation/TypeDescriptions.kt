package dev.agentle.jitai.dsl.validation

import dev.agentle.analytics.features.FeatureDefinition
import dev.agentle.analytics.features.FeatureType
import dev.agentle.jitai.dsl.rule.TypedLiterals

/** Fixed English descriptions of what a literal must look like, used in E015 messages (R10 §4.4). */
internal object TypeDescriptions {
    private val DAYS = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

    /** "an integer", "true or false", `one of "WEEKDAY", "WEEKEND"`, ... */
    fun literal(definition: FeatureDefinition): String = when (definition.type) {
        FeatureType.INT -> "an integer without decimals"
        FeatureType.BOOL -> "true or false"
        FeatureType.ENUM -> "one of " + definition.enumValues.joinToString(", ") { "\"$it\"" }
        FeatureType.DAY_OF_WEEK -> "one of " + DAYS.joinToString(", ") { "\"$it\"" }
        FeatureType.LOCAL_TIME, FeatureType.NIGHT_TIME -> "an \"HH:mm\" string"
        FeatureType.PACKAGE -> "an Android package name string"
    }

    /** Operators allowed for the feature's type, for E014 (`gt, gte, lt, lte, eq, neq, between, in`). */
    fun allowedOperators(definition: FeatureDefinition): String =
        TypedLiterals.allowedOperators(definition.type).joinToString(", ") { it.wire }
}
