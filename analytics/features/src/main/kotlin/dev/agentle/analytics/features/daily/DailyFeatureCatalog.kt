package dev.agentle.analytics.features.daily

import dev.agentle.analytics.features.FeatureAvailability
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.EventType

/** Feature groups of the daily catalog (docs/ARCHITECTURE.md §10). */
public enum class DailyGroup { USAGE, NOTIFICATIONS, DEVICE, PLACE, ACTIVITY, SLEEP, HEART, TIME, HISTORY }

/**
 * A breakdown dimension. A feature with a subject produces one row per subject value, keyed like a feature ref:
 * `app_minutes{package=com.example}`. [argName] is the key used in that row key.
 */
public enum class SubjectKind(public val argName: String) {
    PACKAGE("package"),
    APP_CATEGORY("category"),
    HOUR("hour"),
    PLACE("place"),
    ACTIVITY("activity"),
}

/**
 * How the absence of data is read. Absence is never zero (docs/research/10 §5.3, docs/research/05 §5.4): a value of 0
 * exists only when something positively says so.
 */
public enum class NullSemantics {
    /** Clock, calendar and Agentle's own records: nothing recorded is a true 0. */
    ALWAYS_KNOWN,

    /**
     * An on-device collector. The window counts as observed only where the collector reported itself healthy
     * (`collector_coverage`); no events in an observed window is a true 0, an unobserved window is UNKNOWN.
     */
    COLLECTOR_COVERAGE,

    /** A synced source: a value exists only when the canonical source has at least one record (a true zero is one). */
    SOURCE_RECORDS,

    /**
     * Synced session records (exercise): inside a source's asserted coverage (`coverageThrough`, docs/research/10 §5.3)
     * no session is a true 0; a window no connected source covers is UNKNOWN.
     */
    SOURCE_COVERAGE,

    /** A sleep value: it exists only when the night has a main sleep session. */
    SESSION,
}

/** How rolling windows aggregate the daily rows of a feature. */
public enum class RollingAggregator {
    /** Mean over covered days. */
    MEAN,

    /** Total over the window; only defined when every day of the window is covered. */
    SUM,

    /** Sample standard deviation over covered days (at least two). */
    STDDEV,

    /** No rolling window (per-subject breakdowns). */
    NONE,
}

/**
 * One daily feature: a value per date `d` computed over [window] of `d`.
 *
 * @property unit `min`, `count`, `steps`, `bpm`, `bool` (0/1), `minute_of_day` (local minutes after 00:00) or
 *   `night_minute` (local minutes after 12:00 noon, so 23:30 = 690 and 00:30 = 750 and bedtimes average correctly).
 * @property sources capability ids (docs/research/capabilities.json) or connector ids that can supply the data.
 * @property coverageCollector the capability id whose `collector_coverage` decides [NullSemantics.COLLECTOR_COVERAGE].
 * @property metricFamily the canonical-source family for [NullSemantics.SOURCE_RECORDS] and [NullSemantics.SESSION].
 * @property category the data category of the value (lineage, consent); null for calendar features.
 * @property additive a sum or count: a partially observed window gives a lower bound of the true value.
 * @property availability whether the feature is computed in this build (unavailable features are skipped unless
 *   [DailyFeatureConfig.includeUnavailable]).
 */
public data class DailyFeatureDefinition(
    val id: String,
    val group: DailyGroup,
    val unit: String,
    val description: String,
    val window: DailyWindow,
    val nullSemantics: NullSemantics,
    val eventTypes: Set<EventType>,
    val category: DataCategory?,
    val sources: Set<String> = emptySet(),
    val coverageCollector: String? = null,
    val metricFamily: MetricFamily? = null,
    val subject: SubjectKind? = null,
    val rolling: RollingAggregator = RollingAggregator.MEAN,
    val additive: Boolean = false,
    val availability: FeatureAvailability = FeatureAvailability.Available,
) {
    val isAvailable: Boolean get() = availability == FeatureAvailability.Available

    init {
        require(ID.matches(id)) { "daily feature id must be snake_case: $id" }
        require((nullSemantics == NullSemantics.COLLECTOR_COVERAGE) == (coverageCollector != null)) {
            "$id: collector features (and only they) name a coverage collector"
        }
        require(nullSemantics !in SOURCED || metricFamily != null) { "$id: sourced features need a metric family" }
        require(subject == null || rolling == RollingAggregator.NONE) { "$id: per-subject features have no rolling windows" }
    }

    private companion object {
        val ID = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")
        val SOURCED = setOf(NullSemantics.SOURCE_RECORDS, NullSemantics.SOURCE_COVERAGE, NullSemantics.SESSION)
    }
}

/**
 * A rolling-window feature stored in `derived_feature`: [aggregator] over the daily rows of [dailyFeatureId] for the
 * windows [windows] (days, ending at and including the anchor date).
 */
public data class RollingFeatureDefinition(
    val id: String,
    val dailyFeatureId: String,
    val aggregator: RollingAggregator,
    val description: String,
    val windows: List<Int> = DailyFeatureCatalog.WINDOWS,
)

/**
 * The daily feature catalog (single versioned source; docs/ARCHITECTURE.md §10). Ids are stored in `daily_summary` and
 * `derived_feature`: changing an id, unit, window or meaning is a breaking change that bumps [VERSION] and recomputes
 * stored rows.
 *
 * Windows: phone-usage features use the engine day (04:00 rollover, docs/research/10 §10.2) so a late night belongs to
 * the evening it started; steps, heart rate and exercise use the local calendar day like the upstream daily totals
 * (docs/research/05 §5.10, docs/research/10 §5.4 F and H); sleep belongs to the night that starts on `d` (the main
 * session that ends on local date `d + 1`, docs/research/10 §5.4 G and §14.2).
 */
public object DailyFeatureCatalog {
    public const val VERSION: Int = 1

    /** Rolling windows in days (docs/ARCHITECTURE.md §10). */
    public val WINDOWS: List<Int> = listOf(1, 3, 7, 14, 30, 90)

    private const val USAGE = "app_usage_events"
    private const val UNLOCKS = "unlock_keyguard_events"
    private const val NOTIFICATIONS = "notification_events_metadata"
    private const val BATTERY = "battery_state"
    private const val LOCATION = "location_background"
    private const val ACTIVITY = "activity_recognition_transitions"
    private val healthSources = setOf("googlehealth", "health_connect_records")
    private val stepSources = setOf("googlehealth", "health_connect_records", "health_connect_on_device_steps", "step_count_recording_api")
    private val jitaiEvents = setOf(EventType.JITAI_DELIVERED, EventType.JITAI_OPENED, EventType.JITAI_DISMISSED)

    private fun usage(id: String, window: DailyWindow, description: String, category: DataCategory, subject: SubjectKind? = null) =
        DailyFeatureDefinition(
            id = id, group = DailyGroup.USAGE, unit = "min", description = description, window = window,
            nullSemantics = NullSemantics.COLLECTOR_COVERAGE,
            eventTypes = if (category == DataCategory.SCREEN) setOf(EventType.SCREEN_SESSION) else setOf(EventType.APP_SESSION),
            category = category, sources = setOf(USAGE), coverageCollector = USAGE, subject = subject,
            rolling = if (subject == null) RollingAggregator.MEAN else RollingAggregator.NONE, additive = true,
        )

    private fun notifications(id: String, window: DailyWindow, description: String, subject: SubjectKind? = null) = DailyFeatureDefinition(
        id = id, group = DailyGroup.NOTIFICATIONS, unit = "count", description = description, window = window,
        nullSemantics = NullSemantics.COLLECTOR_COVERAGE, eventTypes = setOf(EventType.NOTIFICATION_POSTED),
        category = DataCategory.NOTIFICATIONS, sources = setOf(NOTIFICATIONS), coverageCollector = NOTIFICATIONS,
        subject = subject, rolling = if (subject == null) RollingAggregator.MEAN else RollingAggregator.NONE, additive = true,
    )

    private fun charging(id: String, unit: String, description: String, additive: Boolean) = DailyFeatureDefinition(
        id = id, group = DailyGroup.DEVICE, unit = unit, description = description, window = DailyWindow.ENGINE_DAY,
        nullSemantics = NullSemantics.COLLECTOR_COVERAGE, eventTypes = setOf(EventType.CHARGING_STARTED, EventType.CHARGING_STOPPED),
        category = DataCategory.DEVICE_STATE, sources = setOf(BATTERY), coverageCollector = BATTERY, additive = additive,
    )

    private fun steps(id: String, unit: String, window: DailyWindow, description: String, subject: SubjectKind? = null, additive: Boolean) =
        DailyFeatureDefinition(
            id = id, group = DailyGroup.ACTIVITY, unit = unit, description = description, window = window,
            nullSemantics = NullSemantics.SOURCE_RECORDS, eventTypes = setOf(EventType.STEP_SAMPLE), category = DataCategory.ACTIVITY,
            sources = stepSources, metricFamily = MetricFamily.STEPS, subject = subject,
            rolling = if (subject == null) RollingAggregator.MEAN else RollingAggregator.NONE, additive = additive,
        )

    private fun sleep(id: String, unit: String, description: String) = DailyFeatureDefinition(
        id = id, group = DailyGroup.SLEEP, unit = unit, description = description, window = DailyWindow.SLEEP_NIGHT,
        nullSemantics = NullSemantics.SESSION, eventTypes = setOf(EventType.SLEEP_SESSION), category = DataCategory.SLEEP,
        sources = healthSources, metricFamily = MetricFamily.SLEEP,
    )

    private fun heart(id: String, family: MetricFamily, description: String) = DailyFeatureDefinition(
        id = id, group = DailyGroup.HEART, unit = "bpm", description = description, window = DailyWindow.CALENDAR_DAY,
        nullSemantics = NullSemantics.SOURCE_RECORDS, eventTypes = family.eventTypes, category = DataCategory.HEART,
        sources = healthSources, metricFamily = family,
    )

    private fun exercise(id: String, unit: String, description: String, rolling: RollingAggregator, additive: Boolean) =
        DailyFeatureDefinition(
            id = id, group = DailyGroup.ACTIVITY, unit = unit, description = description, window = DailyWindow.CALENDAR_DAY,
            nullSemantics = NullSemantics.SOURCE_COVERAGE, eventTypes = setOf(EventType.EXERCISE_SESSION),
            category = DataCategory.ACTIVITY, sources = healthSources, metricFamily = MetricFamily.EXERCISE, rolling = rolling,
            additive = additive,
        )

    private fun history(id: String, type: EventType, description: String) = DailyFeatureDefinition(
        id = id, group = DailyGroup.HISTORY, unit = "count", description = description, window = DailyWindow.ENGINE_DAY,
        nullSemantics = NullSemantics.ALWAYS_KNOWN, eventTypes = setOf(type), category = DataCategory.INTERVENTIONS,
        sources = setOf("agentle"), rolling = RollingAggregator.SUM, additive = true,
    )

    public val all: List<DailyFeatureDefinition> = listOf(
        // Screen and apps (usage access).
        usage("screen_minutes", DailyWindow.ENGINE_DAY, "Screen-on minutes in the engine day", DataCategory.SCREEN),
        usage(
            "screen_minutes_late_night",
            DailyWindow.LATE_NIGHT,
            "Screen-on minutes between 22:00 and 04:00",
            DataCategory.SCREEN,
        ),
        usage(
            "screen_minutes_22_24",
            DailyWindow.EVENING_22_24,
            "Screen-on minutes between 22:00 and midnight (R10 E_screen30/45/60)",
            DataCategory.SCREEN,
        ),
        usage(
            "app_minutes",
            DailyWindow.ENGINE_DAY,
            "Minutes an app was in use in the engine day",
            DataCategory.APP_USAGE,
            SubjectKind.PACKAGE,
        ),
        usage(
            "app_category_minutes",
            DailyWindow.ENGINE_DAY,
            "Minutes apps of a category were in use (overlaps counted once)",
            DataCategory.APP_USAGE,
            SubjectKind.APP_CATEGORY,
        ),
        usage(
            "social_minutes_22_24",
            DailyWindow.EVENING_22_24,
            "SOCIAL-category minutes between 22:00 and midnight (R10 E_social20)",
            DataCategory.APP_USAGE,
        ),
        DailyFeatureDefinition(
            id = "unlocks", group = DailyGroup.USAGE, unit = "count", description = "Unlocks (keyguard hidden) in the engine day",
            window = DailyWindow.ENGINE_DAY, nullSemantics = NullSemantics.COLLECTOR_COVERAGE, eventTypes = setOf(EventType.DEVICE_UNLOCK),
            category = DataCategory.SCREEN, sources = setOf(UNLOCKS, USAGE), coverageCollector = UNLOCKS, additive = true,
        ),
        // Notifications (metadata only).
        notifications("notifications", DailyWindow.ENGINE_DAY, "Notifications posted by other apps in the engine day"),
        notifications("notifications_late_night", DailyWindow.LATE_NIGHT, "Notifications posted between 22:00 and 04:00"),
        notifications("notifications_21_24", DailyWindow.EVENING_21_24, "Notifications posted between 21:00 and midnight (R10 E_notif20)"),
        notifications("app_notifications", DailyWindow.ENGINE_DAY, "Notifications posted by an app in the engine day", SubjectKind.PACKAGE),
        // Charging.
        charging("charging_minutes", "min", "Minutes the phone was charging", additive = true),
        charging("charging_starts", "count", "Times charging started", additive = true),
        charging("charging_last_start", "night_minute", "Local time charging last started in the engine day", additive = false),
        charging("charging_first_end", "minute_of_day", "Local time charging first stopped in the engine day", additive = false),
        // Place (foreground location visits).
        DailyFeatureDefinition(
            id = "place_minutes", group = DailyGroup.PLACE, unit = "min", description = "Minutes at a place class (HOME, WORK, GYM, OTHER)",
            window = DailyWindow.ENGINE_DAY, nullSemantics = NullSemantics.COLLECTOR_COVERAGE, eventTypes = setOf(EventType.LOCATION_VISIT),
            category = DataCategory.LOCATION, sources = setOf(LOCATION), coverageCollector = LOCATION, subject = SubjectKind.PLACE,
            rolling = RollingAggregator.NONE, additive = true,
            availability = FeatureAvailability.Unavailable(LOCATION, "Background location is not collected in this version"),
        ),
        // Activity, steps, exercise.
        DailyFeatureDefinition(
            id = "activity_minutes", group = DailyGroup.ACTIVITY, unit = "min",
            description = "Minutes in a detected activity (WALKING, RUNNING, ON_BICYCLE, IN_VEHICLE, STILL)",
            window = DailyWindow.ENGINE_DAY, nullSemantics = NullSemantics.COLLECTOR_COVERAGE, eventTypes = setOf(EventType.ACTIVITY),
            category = DataCategory.ACTIVITY, sources = setOf(ACTIVITY), coverageCollector = ACTIVITY, subject = SubjectKind.ACTIVITY,
            rolling = RollingAggregator.NONE, additive = true,
        ),
        steps("steps", "steps", DailyWindow.CALENDAR_DAY, "Steps on the local calendar day (canonical source)", additive = true),
        steps("steps_by_hour", "steps", DailyWindow.CALENDAR_DAY, "Steps in one local hour of the day", SubjectKind.HOUR, additive = true),
        steps(
            "active_minutes",
            "min",
            DailyWindow.CALENDAR_DAY,
            "Minutes with a cadence of at least 100 steps per minute",
            additive = true,
        ),
        steps("sedentary_minutes", "min", DailyWindow.DAYTIME_08_21, "Step-free minutes between 08:00 and 21:00", additive = false),
        steps(
            "sedentary_bouts",
            "count",
            DailyWindow.DAYTIME_08_21,
            "Step-free periods of 60 minutes or more, 08:00-21:00",
            additive = false,
        ),
        steps("longest_sedentary_minutes", "min", DailyWindow.DAYTIME_08_21, "Longest step-free period, 08:00-21:00", additive = false),
        exercise("exercise_minutes", "min", "Minutes of recorded exercise sessions", RollingAggregator.MEAN, additive = true),
        exercise("exercise_sessions", "count", "Exercise sessions that started on the day", RollingAggregator.MEAN, additive = true),
        exercise("exercise_day", "bool", "1 when the day has at least 10 minutes of exercise", RollingAggregator.SUM, additive = false),
        // Sleep (the night that starts on d).
        sleep("sleep_minutes", "min", "Minutes asleep in the night's main sleep"),
        sleep("bedtime", "night_minute", "Sleep onset of the night's main sleep"),
        sleep("wake_time", "minute_of_day", "End of the night's main sleep"),
        sleep("sleep_midpoint", "night_minute", "Midpoint between sleep onset and the end of the main sleep"),
        // Heart.
        heart("resting_hr", MetricFamily.RESTING_HEART_RATE, "Resting heart rate of the date"),
        heart("hr_mean", MetricFamily.HEART_RATE, "Mean heart rate of the day's samples"),
        heart("hr_max", MetricFamily.HEART_RATE, "Highest heart rate sample of the day"),
        // Calendar.
        DailyFeatureDefinition(
            id = "is_weekend",
            group = DailyGroup.TIME,
            unit = "bool",
            description = "1 on configured weekend days",
            window = DailyWindow.CALENDAR_DAY,
            nullSemantics = NullSemantics.ALWAYS_KNOWN,
            eventTypes = emptySet(),
            category = null,
        ),
        // Intervention history (Agentle's own records).
        history("interventions_delivered", EventType.JITAI_DELIVERED, "Interventions delivered in the engine day"),
        history("interventions_opened", EventType.JITAI_OPENED, "Interventions opened in the engine day"),
        history("interventions_dismissed", EventType.JITAI_DISMISSED, "Interventions dismissed in the engine day"),
    )

    private val byId: Map<String, DailyFeatureDefinition> = all.associateBy { it.id }

    /** Rolling features: every daily feature with an aggregator, plus derived-only features. */
    public val rolling: List<RollingFeatureDefinition> = all.filter { it.rolling != RollingAggregator.NONE }
        .map { RollingFeatureDefinition(it.id, it.id, it.rolling, it.description) } +
        listOf(
            RollingFeatureDefinition(
                id = "sleep_regularity",
                dailyFeatureId = "sleep_midpoint",
                aggregator = RollingAggregator.STDDEV,
                description = "Standard deviation of the sleep midpoint (minutes); lower is more regular",
                windows = WINDOWS.filter { it > 1 },
            ),
        )

    private val rollingById: Map<String, RollingFeatureDefinition> = rolling.associateBy { it.id }

    init {
        require(byId.size == all.size) { "duplicate daily feature ids" }
        require(rollingById.size == rolling.size) { "duplicate rolling feature ids" }
        require(rolling.all { it.dailyFeatureId in byId }) { "rolling feature over an unknown daily feature" }
        require(jitaiEvents.all { type -> all.any { type in it.eventTypes } }) { "intervention history incomplete" }
    }

    public operator fun get(id: String): DailyFeatureDefinition? = byId[id]

    public fun rollingFeature(id: String): RollingFeatureDefinition? = rollingById[id]

    public val ids: Set<String> get() = byId.keys
}
