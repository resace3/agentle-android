package dev.agentle.analytics.insights

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The fixed rule template of each hypothesis (docs/research/10 §14.5): a JitaiProposalSchema v1 draft (§13.3) in the
 * stored sparse `args` form, deterministic, with a mandatory expiry. The reminder addresses the exposure; the trial
 * measures the exposure right after the reminder (proximal) and the outcome (distal) where the outcome catalog (§15.1)
 * has a metric for it. H04 is the draft of §14.7; the other templates follow it (design: R10 shows only H04).
 */
public object RuleTemplates {
    public const val DEFAULT_EXPIRES_IN_DAYS: Int = 28

    /** The draft of [hypothesis] with [expiresInDays]. */
    public fun draft(hypothesis: Hypothesis, expiresInDays: Int = DEFAULT_EXPIRES_IN_DAYS): JsonObject {
        require(expiresInDays in 1..MAX_EXPIRY) { "expiresInDays must be 1-$MAX_EXPIRY" }
        val e = hypothesis.exposure
        return buildJsonObject {
            put("name", name(e))
            put("description", description(e))
            put("kind", "INTERVENTION")
            put("category", category(e))
            put("trigger", trigger(e))
            put("activeWindow", activeWindow(e))
            put("conditions", condition(e))
            put("contextRequirements", if (e == Exposure.STEPS_UNDER_5K) JsonNull else interactive())
            putJsonObject("delivery") {
                put("channel", "NOTIFICATION")
                put("quietHoursPolicy", if (e == Exposure.STEPS_UNDER_5K) "RESPECT" else "ALLOW_WHEN_INTERACTIVE")
                put("notificationTimeoutMinutes", NOTIFICATION_TIMEOUT)
            }
            putJsonObject("content") {
                put("type", "template")
                put("title", title(e))
                put("body", body(e))
            }
            put("cooldownMinutes", if (e == Exposure.STEPS_UNDER_5K) DAILY_COOLDOWN else EVENING_COOLDOWN)
            put("maxPerDay", 1)
            put("maxPerWeek", MAX_PER_WEEK)
            put("priority", PRIORITY)
            putJsonObject("snooze") {
                put("mode", "SUPPRESS_ONLY")
                put("options", JsonArray(listOf(JsonPrimitive("MINUTES_30"), JsonPrimitive("UNTIL_TOMORROW"))))
            }
            put("expiresInDays", expiresInDays)
            putJsonObject("outcome") {
                put("proximal", proximal(e))
                put("distal", distal(hypothesis.outcome))
            }
            put("suppression", JsonNull)
        }
    }

    /** What the trial measures, in one sentence; nothing is promised. */
    public fun expectedOutcome(hypothesis: Hypothesis): String {
        val proximal = when (hypothesis.exposure) {
            Exposure.SCREEN_30, Exposure.SCREEN_45, Exposure.SCREEN_60, Exposure.NOTIFICATIONS_20 ->
                "Fewer screen minutes in the 30 minutes after a reminder"

            Exposure.SOCIAL_20 -> "Fewer minutes in social apps in the 30 minutes after a reminder"

            Exposure.STEPS_UNDER_5K -> "More steps in the hour after a reminder"
        }
        val distal = when (hypothesis.outcome) {
            NightOutcome.LATE_BEDTIME -> ", and an earlier bedtime on reminder nights"
            NightOutcome.SHORT_SLEEP -> ", and longer sleep on reminder nights"
            NightOutcome.HIGH_RESTING_HR -> null
        }
        return PatternText.requireClean(
            proximal + distal.orEmpty() +
                if (distal != null) ". The trial measures both; nothing is promised." else ". The trial measures it; nothing is promised.",
        )
    }

    /** The data the rule and its trial need, as shown on the review card. */
    public fun dataRequired(hypothesis: Hypothesis): List<String> = buildList {
        when (hypothesis.exposure) {
            Exposure.SCREEN_30, Exposure.SCREEN_45, Exposure.SCREEN_60, Exposure.SOCIAL_20 -> add(USAGE)

            Exposure.NOTIFICATIONS_20 -> {
                add("Notification access (counts only)")
                add(USAGE)
            }

            Exposure.STEPS_UNDER_5K -> add("Steps from your phone or connected tracker")
        }
        if (hypothesis.outcome != NightOutcome.HIGH_RESTING_HR) add("Sleep from your connected tracker")
    }

    private fun name(e: Exposure): String = when (e) {
        Exposure.SCREEN_30, Exposure.SCREEN_45, Exposure.SCREEN_60 -> "Wind down after late screen time"
        Exposure.SOCIAL_20 -> "Wind down after late social apps"
        Exposure.NOTIFICATIONS_20 -> "Quiet a busy evening"
        Exposure.STEPS_UNDER_5K -> "Evening walk"
    }

    private fun description(e: Exposure): String = when (e) {
        Exposure.SCREEN_30, Exposure.SCREEN_45, Exposure.SCREEN_60 ->
            "On nights when I use my phone for ${screenMinutes(e)} minutes or more after 10 PM, remind me to start winding down."

        Exposure.SOCIAL_20 -> "On nights when I spend 20 minutes or more in social apps after 10 PM, remind me to start winding down."

        Exposure.NOTIFICATIONS_20 ->
            "On evenings when I get 10 or more notifications in an hour after 9 PM, suggest silencing them for the night."

        Exposure.STEPS_UNDER_5K -> "On days when I have walked fewer than 5,000 steps by 6 PM, suggest a short walk."
    }

    private fun category(e: Exposure): String = when (e) {
        Exposure.NOTIFICATIONS_20 -> "DIGITAL_WELLBEING"
        Exposure.STEPS_UNDER_5K -> "PHYSICAL_ACTIVITY"
        else -> "SLEEP_WIND_DOWN"
    }

    private fun trigger(e: Exposure): JsonObject = if (e == Exposure.STEPS_UNDER_5K) {
        buildJsonObject {
            put("type", "daily_at")
            put("times", JsonArray(listOf(JsonPrimitive("18:00"))))
            put("maxLatenessMinutes", DAILY_LATENESS)
        }
    } else {
        buildJsonObject {
            put("type", "interval")
            put("everyMinutes", EVERY_MINUTES)
        }
    }

    private fun activeWindow(e: Exposure) = when (e) {
        Exposure.STEPS_UNDER_5K -> JsonNull
        Exposure.NOTIFICATIONS_20 -> window("21:00", "00:00")
        else -> window("22:00", "01:00")
    }

    private fun window(start: String, end: String): JsonObject = buildJsonObject {
        put("start", start)
        put("end", end)
        put("days", JsonNull)
    }

    private fun condition(e: Exposure): JsonObject = when (e) {
        Exposure.SCREEN_30, Exposure.SCREEN_45, Exposure.SCREEN_60 ->
            compare("gte", "screen_minutes_since", mapOf("since" to "22:00"), screenMinutes(e))

        Exposure.SOCIAL_20 -> compare(
            "gte",
            "app_category_minutes_since",
            mapOf("category" to "SOCIAL", "since" to "22:00"),
            SOCIAL_MINUTES,
        )

        Exposure.NOTIFICATIONS_20 -> compare("gte", "notifications_last_60m", emptyMap(), NOTIFICATIONS_PER_HOUR)

        Exposure.STEPS_UNDER_5K -> compare("lt", "steps_today", emptyMap(), STEPS)
    }

    private fun interactive(): JsonObject = buildJsonObject {
        put("type", "eq")
        put("feature", "device_interactive")
        put("args", JsonObject(emptyMap()))
        put("value", true)
        put("onUnknown", JsonNull)
    }

    private fun compare(op: String, feature: String, args: Map<String, String>, value: Int): JsonObject = buildJsonObject {
        put("type", op)
        put("feature", feature)
        put("args", JsonObject(args.mapValues { JsonPrimitive(it.value) }))
        put("value", value)
        put("onUnknown", JsonNull)
    }

    private fun title(e: Exposure): String = when (e) {
        Exposure.NOTIFICATIONS_20 -> "Busy evening?"
        Exposure.STEPS_UNDER_5K -> "Time for a walk?"
        else -> "Winding down?"
    }

    private fun body(e: Exposure): String = when (e) {
        Exposure.SCREEN_30, Exposure.SCREEN_45, Exposure.SCREEN_60 ->
            "{{screen_minutes_since}} minutes of screen time since 10 PM. Want to start winding down now?"

        Exposure.SOCIAL_20 -> "{{app_category_minutes_since}} minutes in social apps since 10 PM. Want to put them away for the night?"

        Exposure.NOTIFICATIONS_20 -> "{{notifications_last_60m}} notifications in the last hour. Want to silence them for the night?"

        Exposure.STEPS_UNDER_5K -> "{{steps_today}} steps so far today. How about a short walk this evening?"
    }

    private fun proximal(e: Exposure): JsonObject = when (e) {
        Exposure.SOCIAL_20 -> metric("APP_CATEGORY_MINUTES_AFTER", mapOf("category" to "SOCIAL"), PROXIMAL_MINUTES)
        Exposure.STEPS_UNDER_5K -> metric("STEPS_AFTER", emptyMap(), STEPS_WINDOW_MINUTES)
        else -> metric("SCREEN_MINUTES_AFTER", emptyMap(), PROXIMAL_MINUTES)
    }

    private fun distal(o: NightOutcome) = when (o) {
        NightOutcome.LATE_BEDTIME -> metric("BEDTIME_NEXT", emptyMap(), null)
        NightOutcome.SHORT_SLEEP -> metric("SLEEP_MINUTES_NEXT", emptyMap(), null)
        NightOutcome.HIGH_RESTING_HR -> JsonNull
    }

    private fun metric(name: String, args: Map<String, String>, windowMinutes: Int?): JsonObject = buildJsonObject {
        put("metric", name)
        put("args", JsonObject(args.mapValues { JsonPrimitive(it.value) }))
        put("windowMinutes", windowMinutes?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private fun screenMinutes(e: Exposure): Int = when (e) {
        Exposure.SCREEN_30 -> Exposure.SCREEN_30_MIN.toInt()
        Exposure.SCREEN_60 -> Exposure.SCREEN_60_MIN.toInt()
        else -> Exposure.SCREEN_45_MIN.toInt()
    }

    private const val USAGE = "Usage access (screen time)"
    private const val MAX_EXPIRY = 90
    private const val NOTIFICATION_TIMEOUT = 60
    private const val EVENING_COOLDOWN = 120
    private const val DAILY_COOLDOWN = 720
    private const val MAX_PER_WEEK = 7
    private const val PRIORITY = 40
    private const val EVERY_MINUTES = 30
    private const val DAILY_LATENESS = 60
    private const val SOCIAL_MINUTES = 20
    private const val NOTIFICATIONS_PER_HOUR = 10
    private const val STEPS = 5_000
    private const val PROXIMAL_MINUTES = 30
    private const val STEPS_WINDOW_MINUTES = 60
}
