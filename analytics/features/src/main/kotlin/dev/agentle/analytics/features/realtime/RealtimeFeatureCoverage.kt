package dev.agentle.analytics.features.realtime

/**
 * What proves that a feature's window is fully observed (lifecycle-battery-01: coverage is mandatory). A window that is
 * not fully covered gives `Missing(COVERAGE_GAP)`, `Missing(COLLECTOR_INACTIVE)`, `Missing(NOT_SYNCED)` or `Stale`;
 * never a count of 0.
 */
public sealed interface FeatureCoverage {
    /** The clock, the calendar and Agentle's own decision records: nothing outside the app can leave a gap. */
    public data object Intrinsic : FeatureCoverage

    /** A device API read at evaluation time; a failed read is `Missing(API_UNAVAILABLE)`. */
    public data object LiveRead : FeatureCoverage

    /**
     * An on-device collector: `collector_coverage` of [collectorId] must cover the feature's whole window, otherwise
     * `Missing(COLLECTOR_INACTIVE)` (not healthy at the evaluation instant) or `Missing(COVERAGE_GAP)` (a gap inside).
     */
    public data class Collector(val collectorId: String) : FeatureCoverage

    /**
     * A synced health source: `coverageThrough(source, metric)` from [SourceCoveragePort] decides between `Known`,
     * `Stale` and `Missing(NOT_SYNCED)`.
     */
    public data class Source(val metric: HealthMetric) : FeatureCoverage

    /** Not collected in this build (the catalog marks the feature unavailable): always `Missing(API_UNAVAILABLE)`. */
    public data class NotCollected(val capabilityId: String) : FeatureCoverage
}

/** The coverage source of every catalog feature. The engine reads it; a test keeps it complete. */
public object RealtimeFeatureCoverage {
    private val usage = FeatureCoverage.Collector(CollectorIds.USAGE_EVENTS)

    public val byFeature: Map<String, FeatureCoverage> = mapOf(
        "local_time" to FeatureCoverage.Intrinsic,
        "day_of_week" to FeatureCoverage.Intrinsic,
        "day_type" to FeatureCoverage.Intrinsic,
        "engine_day_of_week" to FeatureCoverage.Intrinsic,
        "charging" to FeatureCoverage.LiveRead,
        "battery_pct" to FeatureCoverage.LiveRead,
        "device_interactive" to FeatureCoverage.LiveRead,
        "dnd_active" to FeatureCoverage.LiveRead,
        "in_call" to FeatureCoverage.LiveRead,
        "headphones_connected" to FeatureCoverage.LiveRead,
        "screen_minutes_last_60m" to usage,
        "screen_minutes_since" to usage,
        "app_minutes_last_60m" to usage,
        "app_minutes_since" to usage,
        "app_category_minutes_last_60m" to usage,
        "app_category_minutes_since" to usage,
        "app_opens_last_60m" to usage,
        "foreground_app" to usage,
        "notifications_last_60m" to FeatureCoverage.Collector(CollectorIds.NOTIFICATION_LISTENER),
        "location_class" to FeatureCoverage.NotCollected("location_background"),
        "activity_state" to FeatureCoverage.Collector(CollectorIds.ACTIVITY_TRANSITIONS),
        "activity_level_last_30m" to FeatureCoverage.Source(HealthMetric.STEPS),
        "steps_today" to FeatureCoverage.Source(HealthMetric.STEPS),
        "steps_last_60m" to FeatureCoverage.Source(HealthMetric.STEPS),
        "steps_last_30m" to FeatureCoverage.Source(HealthMetric.STEPS),
        "sleep_minutes_last_night" to FeatureCoverage.Source(HealthMetric.SLEEP),
        "bedtime_last_night" to FeatureCoverage.Source(HealthMetric.SLEEP),
        "wake_time_today" to FeatureCoverage.Source(HealthMetric.SLEEP),
        "resting_hr_today" to FeatureCoverage.Source(HealthMetric.RESTING_HEART_RATE),
        "resting_hr_delta_vs_28d" to FeatureCoverage.Source(HealthMetric.RESTING_HEART_RATE),
        "minutes_since_last_delivery" to FeatureCoverage.Intrinsic,
        "deliveries_today" to FeatureCoverage.Intrinsic,
        "deliveries_last_7d" to FeatureCoverage.Intrinsic,
        "last_response" to FeatureCoverage.Intrinsic,
        "consecutive_ignored" to FeatureCoverage.Intrinsic,
    )

    public operator fun get(featureId: String): FeatureCoverage? = byFeature[featureId]
}
