package dev.agentle.analytics.features

import dev.agentle.core.model.DataCategory
import kotlin.time.Duration.Companion.minutes

/**
 * The closed set of features JITAI rules can reference (docs/research/10-jitai-engine-design.md §5.4). This is the
 * single versioned source: the rule validator, the evaluator, the natural-language prompt, the manual builder and
 * the "data required" list all read it. Changing an id, type, range or arg is a breaking change: bump [VERSION] and
 * migrate stored rules.
 *
 * v1 deviation from R10: `location_class` is [FeatureAvailability.Unavailable]. R10 derives it from geofences, which
 * need background location, and background location is deferred (docs/ARCHITECTURE.md §6.4). A foreground fix cannot
 * say where the user is while the app is closed, so rules may not reference it and the `LOCATION_CLASS_CHANGED`
 * trigger does not exist in v1 (architecture red team, lifecycle-battery-06).
 */
public object RealtimeFeatureCatalog {
    public const val VERSION: Int = 1

    private val since = FeatureArg("since", FeatureArgKind.SINCE)
    private val pkg = FeatureArg("package", FeatureArgKind.PACKAGE)
    private val appCategory = FeatureArg("category", FeatureArgKind.APP_CATEGORY)
    private val jitai = FeatureArg("jitai", FeatureArgKind.JITAI_REF)

    private const val USAGE = "app_usage_events"
    private const val STEPS_GH = "googlehealth"
    private const val HEALTH_CONNECT = "health_connect_records"
    private const val ON_DEVICE_STEPS = "health_connect_on_device_steps"
    private const val RECORDING_API_STEPS = "step_count_recording_api"
    private const val DAY_MINUTES = 1440L

    /** App categories for `app_category_*` (R10 §4.5). */
    public val APP_CATEGORIES: List<String> =
        listOf("SOCIAL", "VIDEO", "GAME", "AUDIO", "NEWS", "IMAGE", "MAPS", "PRODUCTIVITY", "ACCESSIBILITY", "UNDEFINED")

    public val all: List<FeatureDefinition> = listOf(
        // A. Time and calendar
        FeatureDefinition(
            "local_time",
            FeatureGroup.TIME,
            FeatureType.LOCAL_TIME,
            "Local time of day, truncated to the minute",
            Freshness.AlwaysKnown,
            unit = "minute",
        ),
        FeatureDefinition("day_of_week", FeatureGroup.TIME, FeatureType.DAY_OF_WEEK, "Calendar day of week", Freshness.AlwaysKnown),
        FeatureDefinition(
            "day_type",
            FeatureGroup.TIME,
            FeatureType.ENUM,
            "Weekday or weekend (configured weekend days)",
            Freshness.AlwaysKnown,
            enumValues = listOf("WEEKDAY", "WEEKEND"),
        ),
        FeatureDefinition(
            "engine_day_of_week",
            FeatureGroup.TIME,
            FeatureType.DAY_OF_WEEK,
            "Day of week of the engine day (rolls over at 04:00)",
            Freshness.AlwaysKnown,
        ),
        // B. Device state (live reads)
        FeatureDefinition(
            "charging",
            FeatureGroup.DEVICE,
            FeatureType.BOOL,
            "Phone is charging",
            Freshness.LiveRead,
            sources = setOf("battery_state"),
            category = DataCategory.DEVICE_STATE,
        ),
        FeatureDefinition(
            "battery_pct", FeatureGroup.DEVICE, FeatureType.INT, "Battery level", Freshness.LiveRead, unit = "%",
            literalRange = 0L..100L, sources = setOf("battery_state"), category = DataCategory.DEVICE_STATE,
        ),
        FeatureDefinition(
            "device_interactive",
            FeatureGroup.DEVICE,
            FeatureType.BOOL,
            "Screen is on and interactive",
            Freshness.LiveRead,
            sources = setOf("screen_interactive_events"),
            category = DataCategory.SCREEN,
        ),
        FeatureDefinition(
            "dnd_active",
            FeatureGroup.DEVICE,
            FeatureType.BOOL,
            "Do Not Disturb is on",
            Freshness.LiveRead,
            sources = setOf("dnd_state"),
            category = DataCategory.DEVICE_STATE,
        ),
        FeatureDefinition(
            "in_call",
            FeatureGroup.DEVICE,
            FeatureType.BOOL,
            "A phone or VoIP call is active",
            Freshness.LiveRead,
            sources = setOf("call_state"),
            category = DataCategory.DEVICE_STATE,
        ),
        FeatureDefinition(
            "headphones_connected",
            FeatureGroup.DEVICE,
            FeatureType.BOOL,
            "Headphones are connected",
            Freshness.LiveRead,
            sources = setOf("audio_output_devices"),
            category = DataCategory.DEVICE_STATE,
        ),
        // C. Screen and app usage (usage access)
        FeatureDefinition(
            "screen_minutes_last_60m", FeatureGroup.USAGE, FeatureType.INT, "Screen-on minutes in the last 60 minutes",
            Freshness.CollectorCoverage, unit = "min", literalRange = 0L..60L, minApi = 28, sources = setOf(USAGE),
            category = DataCategory.SCREEN,
        ),
        FeatureDefinition(
            "screen_minutes_since", FeatureGroup.USAGE, FeatureType.INT, "Screen-on minutes since a time of day",
            Freshness.CollectorCoverage, unit = "min", args = listOf(since), literalRange = 0L..DAY_MINUTES, minApi = 28,
            sources = setOf(USAGE), category = DataCategory.SCREEN,
        ),
        FeatureDefinition(
            "app_minutes_last_60m", FeatureGroup.USAGE, FeatureType.INT, "Minutes an app was in use in the last 60 minutes",
            Freshness.CollectorCoverage, unit = "min", args = listOf(pkg), literalRange = 0L..60L, minApi = 29,
            sources = setOf(USAGE), category = DataCategory.APP_USAGE,
        ),
        FeatureDefinition(
            "app_minutes_since", FeatureGroup.USAGE, FeatureType.INT, "Minutes an app was in use since a time of day",
            Freshness.CollectorCoverage, unit = "min", args = listOf(pkg, since), literalRange = 0L..DAY_MINUTES, minApi = 29,
            sources = setOf(USAGE), category = DataCategory.APP_USAGE,
        ),
        FeatureDefinition(
            "app_category_minutes_last_60m", FeatureGroup.USAGE, FeatureType.INT,
            "Minutes apps of a category were in use in the last 60 minutes (overlaps counted once)",
            Freshness.CollectorCoverage, unit = "min", args = listOf(appCategory), literalRange = 0L..60L, minApi = 29,
            sources = setOf(USAGE), category = DataCategory.APP_USAGE,
        ),
        FeatureDefinition(
            "app_category_minutes_since", FeatureGroup.USAGE, FeatureType.INT,
            "Minutes apps of a category were in use since a time of day", Freshness.CollectorCoverage, unit = "min",
            args = listOf(appCategory, since), literalRange = 0L..DAY_MINUTES, minApi = 29, sources = setOf(USAGE),
            category = DataCategory.APP_USAGE,
        ),
        FeatureDefinition(
            "app_opens_last_60m", FeatureGroup.USAGE, FeatureType.INT, "Times an app was opened in the last 60 minutes",
            Freshness.CollectorCoverage, unit = "count", args = listOf(pkg), literalRange = 0L..500L, minApi = 29,
            sources = setOf(USAGE), category = DataCategory.APP_USAGE,
        ),
        FeatureDefinition(
            "foreground_app",
            FeatureGroup.USAGE,
            FeatureType.PACKAGE,
            "App in the foreground now",
            Freshness.CollectorCoverage,
            minApi = 29,
            sources = setOf(USAGE),
            category = DataCategory.APP_USAGE,
        ),
        // D. Notifications (notification access; content is never stored)
        FeatureDefinition(
            "notifications_last_60m", FeatureGroup.NOTIFICATIONS, FeatureType.INT,
            "Notifications from other apps in the last 60 minutes", Freshness.CollectorCoverage, unit = "count",
            literalRange = 0L..1000L, sources = setOf("notification_events_metadata"), category = DataCategory.NOTIFICATIONS,
        ),
        // E. Place
        FeatureDefinition(
            "location_class",
            FeatureGroup.PLACE,
            FeatureType.ENUM,
            "At home, at work or elsewhere (latest location fix)",
            Freshness.SourceLag(30.minutes),
            enumValues = listOf("HOME", "WORK", "OTHER"),
            sources = setOf("location_background"),
            category = DataCategory.LOCATION,
            availability = FeatureAvailability.Unavailable(
                capabilityId = "location_background",
                reason = "Background location is not collected in this version",
            ),
        ),
        // F. Activity and steps
        FeatureDefinition(
            "activity_state",
            FeatureGroup.ACTIVITY,
            FeatureType.ENUM,
            "Current detected activity",
            Freshness.CollectorCoverage,
            enumValues = listOf("STILL", "WALKING", "RUNNING", "ON_BICYCLE", "IN_VEHICLE"),
            sources = setOf("activity_recognition_transitions"),
            category = DataCategory.ACTIVITY,
        ),
        FeatureDefinition(
            "activity_level_last_30m",
            FeatureGroup.ACTIVITY,
            FeatureType.ENUM,
            "Activity level in the last 30 minutes from step cadence",
            Freshness.SourceLag(20.minutes),
            enumValues = listOf("SEDENTARY", "LIGHT", "MODERATE_OR_VIGOROUS"),
            sources = setOf(STEPS_GH, ON_DEVICE_STEPS, RECORDING_API_STEPS, HEALTH_CONNECT),
            category = DataCategory.ACTIVITY,
        ),
        FeatureDefinition(
            "steps_today", FeatureGroup.ACTIVITY, FeatureType.INT, "Steps since midnight", Freshness.SourceLag(30.minutes),
            // Valid values reject only the impossible: 300 steps/min for a 25-hour day. The 60/30-minute literal ranges
            // (333 steps/min) already exceed any sustainable cadence, so their default validRange stays.
            unit = "steps", literalRange = 0L..150_000L, validRange = 0L..450_000L, monotoneNonDecreasing = true,
            sources = setOf(STEPS_GH, ON_DEVICE_STEPS, RECORDING_API_STEPS, HEALTH_CONNECT), category = DataCategory.ACTIVITY,
        ),
        FeatureDefinition(
            "steps_last_60m", FeatureGroup.ACTIVITY, FeatureType.INT, "Steps in the last 60 minutes", Freshness.SourceLag(20.minutes),
            unit = "steps", literalRange = 0L..20_000L,
            sources = setOf(STEPS_GH, ON_DEVICE_STEPS, RECORDING_API_STEPS, HEALTH_CONNECT), category = DataCategory.ACTIVITY,
        ),
        FeatureDefinition(
            "steps_last_30m", FeatureGroup.ACTIVITY, FeatureType.INT, "Steps in the last 30 minutes", Freshness.SourceLag(20.minutes),
            unit = "steps", literalRange = 0L..10_000L,
            sources = setOf(STEPS_GH, ON_DEVICE_STEPS, RECORDING_API_STEPS, HEALTH_CONNECT), category = DataCategory.ACTIVITY,
        ),
        // G. Sleep
        FeatureDefinition(
            "sleep_minutes_last_night", FeatureGroup.SLEEP, FeatureType.INT, "Minutes asleep last night", Freshness.DailyValue,
            unit = "min", literalRange = 0L..DAY_MINUTES, sources = setOf(STEPS_GH, HEALTH_CONNECT), category = DataCategory.SLEEP,
        ),
        FeatureDefinition(
            "bedtime_last_night",
            FeatureGroup.SLEEP,
            FeatureType.NIGHT_TIME,
            "When you fell asleep last night",
            Freshness.DailyValue,
            sources = setOf(STEPS_GH, HEALTH_CONNECT),
            category = DataCategory.SLEEP,
        ),
        FeatureDefinition(
            "wake_time_today",
            FeatureGroup.SLEEP,
            FeatureType.LOCAL_TIME,
            "When you woke up today",
            Freshness.DailyValue,
            sources = setOf(STEPS_GH, HEALTH_CONNECT),
            category = DataCategory.SLEEP,
        ),
        // H. Heart
        FeatureDefinition(
            "resting_hr_today", FeatureGroup.HEART, FeatureType.INT, "Resting heart rate today", Freshness.DailyValue,
            unit = "bpm", literalRange = 25L..150L, validRange = 20L..220L, sources = setOf(STEPS_GH, HEALTH_CONNECT),
            category = DataCategory.HEART,
        ),
        FeatureDefinition(
            "resting_hr_delta_vs_28d", FeatureGroup.HEART, FeatureType.INT,
            "Resting heart rate today minus the median of the previous 28 days", Freshness.DailyValue, unit = "bpm",
            // R10 gives no valid-value limit; two valid resting rates (20..220) differ by at most 200 bpm.
            literalRange = -50L..50L, validRange = -200L..200L, sources = setOf(STEPS_GH, HEALTH_CONNECT),
            category = DataCategory.HEART,
        ),
        // I. Intervention history (always known)
        FeatureDefinition(
            "minutes_since_last_delivery", FeatureGroup.HISTORY, FeatureType.INT,
            "Minutes since a matching intervention was last delivered (never = infinitely long)", Freshness.AlwaysKnown,
            unit = "min", args = listOf(jitai), literalRange = 0L..525_600L, category = DataCategory.INTERVENTIONS,
        ),
        FeatureDefinition(
            "deliveries_today", FeatureGroup.HISTORY, FeatureType.INT, "Matching interventions delivered today",
            Freshness.AlwaysKnown, unit = "count", args = listOf(jitai), literalRange = 0L..50L,
            category = DataCategory.INTERVENTIONS,
        ),
        FeatureDefinition(
            "deliveries_last_7d", FeatureGroup.HISTORY, FeatureType.INT, "Matching interventions delivered in the last 7 days",
            Freshness.AlwaysKnown, unit = "count", args = listOf(jitai), literalRange = 0L..350L,
            category = DataCategory.INTERVENTIONS,
        ),
        FeatureDefinition(
            "last_response",
            FeatureGroup.HISTORY,
            FeatureType.ENUM,
            "Your response to the latest matching intervention",
            Freshness.AlwaysKnown,
            args = listOf(jitai),
            enumValues = listOf("NONE", "OPENED", "DISMISSED", "SNOOZED", "HELPFUL", "NOT_HELPFUL", "IGNORED"),
            category = DataCategory.INTERVENTIONS,
        ),
        FeatureDefinition(
            "consecutive_ignored", FeatureGroup.HISTORY, FeatureType.INT,
            "Latest matching interventions in a row that were ignored or dismissed", Freshness.AlwaysKnown, unit = "count",
            args = listOf(jitai), literalRange = 0L..1000L, category = DataCategory.INTERVENTIONS,
        ),
    )

    private val byId: Map<String, FeatureDefinition> = all.associateBy { it.id }

    init {
        require(byId.size == all.size) { "duplicate feature ids" }
    }

    public operator fun get(id: String): FeatureDefinition? = byId[id]

    public val ids: Set<String> get() = byId.keys
}
