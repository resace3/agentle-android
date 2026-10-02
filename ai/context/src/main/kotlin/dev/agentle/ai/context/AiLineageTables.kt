package dev.agentle.ai.context

import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.AiDataCategory.ACTIVITY
import dev.agentle.core.model.AiDataCategory.APP_IDENTITY
import dev.agentle.core.model.AiDataCategory.BODY
import dev.agentle.core.model.AiDataCategory.CALENDAR_BUSY
import dev.agentle.core.model.AiDataCategory.CALENDAR_TEXT
import dev.agentle.core.model.AiDataCategory.DEVICE_STATE
import dev.agentle.core.model.AiDataCategory.HEART
import dev.agentle.core.model.AiDataCategory.INTERVENTION_HISTORY
import dev.agentle.core.model.AiDataCategory.LOCATION_CLASS
import dev.agentle.core.model.AiDataCategory.NOTIFICATION_COUNTS
import dev.agentle.core.model.AiDataCategory.NOTIFICATION_TEXT
import dev.agentle.core.model.AiDataCategory.SCREEN_TIME_TOTALS
import dev.agentle.core.model.AiDataCategory.SELF_REPORTS
import dev.agentle.core.model.AiDataCategory.SETTINGS
import dev.agentle.core.model.AiDataCategory.SLEEP
import dev.agentle.core.model.AiDataCategory.STEPS
import dev.agentle.core.model.AiDataCategory.USER_TEXT
import dev.agentle.core.model.ConnectorIds
import dev.agentle.core.model.DataLineage
import dev.agentle.core.model.EventType
import dev.agentle.core.model.SourceFamily
import dev.agentle.core.model.sourceFamilyOfConnector

/**
 * The normative taxonomy tables of privacy-ai-04. They map every real-time feature id (`RealtimeFeatureCatalog`),
 * every capability id (docs/research/capabilities.json, plus the `googlehealth` connector that features name as a
 * source) and every [EventType] to the AI categories and source families their values derive from.
 *
 * Rules:
 * - The lineage of a derived value is the union of its inputs. For example, app-category minutes are computed from
 *   per-app events, so they carry [APP_IDENTITY] as well as [SCREEN_TIME_TOTALS].
 * - An id these tables do not know has [DataLineage.UNKNOWN] lineage (every category and family). It can never be sent.
 * - Capabilities that expose third-party names or content (accessibility, Wi-Fi network names, Bluetooth devices,
 *   media, calls, SMS, contacts) and debug-only sensor streams map to UNKNOWN.
 * - The categories reconcile with `DataCategory`: each mapped feature, capability event and [EventType] has its
 *   storage category among the storage categories of its AI categories, so deleting stored data revokes the matching
 *   grants (tested).
 *
 * Data producers use these tables to compute the lineage of the facts they hand to the [ContextSelectionEngine].
 */
public object AiLineageTables {
    private val OD = setOf(SourceFamily.ON_DEVICE)
    private val HC = setOf(SourceFamily.HEALTH_CONNECT)
    private val GH = setOf(SourceFamily.GH_API)
    private val WEARABLE = setOf(SourceFamily.GH_API, SourceFamily.HEALTH_CONNECT)
    private val STEP_SOURCES = setOf(SourceFamily.GH_API, SourceFamily.HEALTH_CONNECT, SourceFamily.ON_DEVICE)
    private val APPS = setOf(APP_IDENTITY, SCREEN_TIME_TOTALS)
    private val HEALTH_RECORDS = setOf(ACTIVITY, STEPS, SLEEP, HEART, BODY)

    private fun of(vararg categories: AiDataCategory, sources: Set<SourceFamily> = OD): DataLineage =
        DataLineage(categories.toSet(), sources)

    private fun of(categories: Set<AiDataCategory>, sources: Set<SourceFamily> = OD): DataLineage = DataLineage(categories, sources)

    /** Every real-time feature id of `RealtimeFeatureCatalog` (catalog version 1). */
    public val FEATURES: Map<String, DataLineage> = buildMap {
        listOf("local_time", "day_of_week", "engine_day_of_week").forEach { put(it, DataLineage.NONE) }
        put("day_type", of(SETTINGS))
        listOf("charging", "battery_pct", "dnd_active", "in_call", "headphones_connected").forEach { put(it, of(DEVICE_STATE)) }
        listOf("device_interactive", "screen_minutes_last_60m", "screen_minutes_since").forEach { put(it, of(SCREEN_TIME_TOTALS)) }
        listOf(
            "app_minutes_last_60m",
            "app_minutes_since",
            "app_category_minutes_last_60m",
            "app_category_minutes_since",
            "app_opens_last_60m",
            "foreground_app",
        ).forEach { put(it, of(APPS)) }
        put("notifications_last_60m", of(NOTIFICATION_COUNTS))
        put("location_class", of(LOCATION_CLASS))
        put("activity_state", of(ACTIVITY))
        listOf("activity_level_last_30m", "steps_today", "steps_last_60m", "steps_last_30m").forEach {
            put(it, of(STEPS, sources = STEP_SOURCES))
        }
        listOf("sleep_minutes_last_night", "bedtime_last_night", "wake_time_today").forEach { put(it, of(SLEEP, sources = WEARABLE)) }
        listOf("resting_hr_today", "resting_hr_delta_vs_28d").forEach { put(it, of(HEART, sources = WEARABLE)) }
        listOf("minutes_since_last_delivery", "deliveries_today", "deliveries_last_7d", "last_response", "consecutive_ignored")
            .forEach { put(it, of(INTERVENTION_HISTORY)) }
    }

    /** Every capability id of docs/research/capabilities.json, plus the connector ids features name as sources. */
    public val SOURCES: Map<String, DataLineage> = buildMap {
        listOf("app_usage_events", "app_usage_aggregates").forEach { put(it, of(APPS)) }
        listOf("app_standby_bucket", "installed_apps_inventory").forEach { put(it, of(APP_IDENTITY)) }
        put("network_data_usage", of(APP_IDENTITY, DEVICE_STATE))
        listOf("screen_interactive_events", "unlock_keyguard_events").forEach { put(it, of(SCREEN_TIME_TOTALS)) }
        listOf(
            "display_state", "battery_state", "power_save_idle_state", "thermal_status", "storage_stats",
            "boot_shutdown_events", "network_connectivity", "wifi_connection_metadata", "airplane_mode",
            "bluetooth_adapter_state", "audio_volume_ringer", "audio_output_devices", "timezone_time_changes",
            "locale_time_format", "background_execution_exemption", "dnd_state", "call_state",
        ).forEach { put(it, of(DEVICE_STATE)) }
        put("next_alarm_clock", of(DEVICE_STATE, SLEEP))
        put("activity_recognition_transitions", of(ACTIVITY))
        listOf("step_count_recording_api", "step_counter_sensor").forEach { put(it, of(STEPS)) }
        listOf("location_foreground", "location_background").forEach { put(it, of(LOCATION_CLASS)) }
        put("notification_events_metadata", of(NOTIFICATION_COUNTS, APP_IDENTITY))
        put("notification_content", of(NOTIFICATION_TEXT))
        listOf("post_notifications_jitai", "exact_alarm_jitai_scheduling").forEach { put(it, of(INTERVENTION_HISTORY)) }
        put("calendar_events", of(CALENDAR_BUSY, CALENDAR_TEXT))
        listOf("health_connect_records", "health_connect_background_read", "health_connect_history_read")
            .forEach { put(it, of(HEALTH_RECORDS, HC)) }
        put("health_connect_on_device_steps", of(STEPS, sources = HC))
        put("body_sensor_heart_rate", of(HEART))
        listOf(
            "accessibility_event_stream", "wifi_network_identity", "bluetooth_connected_devices", "bluetooth_nearby_scan",
            "motion_sensors", "ambient_proximity_sensors", "media_sessions_now_playing", "media_images_video_metadata",
            "media_audio_metadata", "call_log_metadata", "sms_metadata", "contacts_metadata", "contacts_picker_selection",
        ).forEach { put(it, DataLineage.UNKNOWN) }
        put(ConnectorIds.GOOGLE_HEALTH, of(HEALTH_RECORDS, GH))
    }

    /** The AI categories of each [EventType]; types that may hold third-party or generated content map to UNKNOWN. */
    public val EVENT_CATEGORIES: Map<EventType, Set<AiDataCategory>> = EventType.entries.associateWith(::eventCategories)

    private fun eventCategories(type: EventType): Set<AiDataCategory> = when (type) {
        EventType.APP_FOREGROUND, EventType.APP_BACKGROUND, EventType.APP_SESSION -> APPS

        EventType.SCREEN_ON, EventType.SCREEN_OFF, EventType.SCREEN_SESSION, EventType.DEVICE_UNLOCK, EventType.DEVICE_LOCK ->
            setOf(SCREEN_TIME_TOTALS)

        EventType.NOTIFICATION_POSTED, EventType.NOTIFICATION_REMOVED -> setOf(NOTIFICATION_COUNTS)

        EventType.LOCATION_SAMPLE, EventType.LOCATION_VISIT -> setOf(LOCATION_CLASS)

        EventType.STEP_SAMPLE, EventType.DISTANCE_SAMPLE, EventType.FLOORS_SAMPLE, EventType.CALORIES_SAMPLE, EventType.DAILY_TOTAL ->
            setOf(STEPS)

        EventType.ACTIVITY, EventType.EXERCISE_SESSION -> setOf(ACTIVITY)

        EventType.HEART_RATE, EventType.RESTING_HEART_RATE -> setOf(HEART)

        EventType.SLEEP_SESSION -> setOf(SLEEP)

        EventType.WEIGHT, EventType.BODY_FAT -> setOf(BODY)

        EventType.CALENDAR_EVENT -> setOf(CALENDAR_BUSY)

        EventType.USER_LOG -> setOf(USER_TEXT, SELF_REPORTS)

        EventType.JITAI_TRIGGERED, EventType.JITAI_DELIVERED, EventType.JITAI_OPENED, EventType.JITAI_DISMISSED ->
            setOf(INTERVENTION_HISTORY)

        EventType.WEARABLE_DEVICE, EventType.BATTERY_SAMPLE, EventType.CHARGING_STARTED, EventType.CHARGING_STOPPED,
        EventType.POWER_STATE_CHANGED, EventType.CONNECTIVITY_CHANGED, EventType.AIRPLANE_MODE_CHANGED,
        EventType.BLUETOOTH_STATE_CHANGED, EventType.BLUETOOTH_CONNECTED, EventType.BLUETOOTH_DISCONNECTED, EventType.AUDIO_STATE,
        EventType.HEADSET_CONNECTED, EventType.HEADSET_DISCONNECTED, EventType.DND_CHANGED, EventType.TIMEZONE_CHANGED,
        EventType.TIME_CHANGED, EventType.LOCALE_CHANGED, EventType.BOOT_COMPLETED, EventType.SHUTDOWN,
        EventType.NEXT_ALARM_CHANGED, EventType.STANDBY_BUCKET_CHANGED, EventType.STORAGE_SAMPLE,
        -> setOf(DEVICE_STATE)

        // Calls, media, insights and generated media may hold third-party or AI-written content; types appended later
        // stay unknown until this table maps them.
        else -> AiDataCategory.entries.toSet()
    }

    /** The lineage of a real-time feature value; UNKNOWN for an id the table does not know. */
    public fun forFeature(featureId: String): DataLineage = FEATURES[featureId] ?: DataLineage.UNKNOWN

    /** The lineage of data from a capability or connector id; UNKNOWN for an id the table does not know. */
    public fun forSource(sourceId: String): DataLineage = SOURCES[sourceId] ?: DataLineage.UNKNOWN

    /**
     * The lineage of one stored event of [type] from [connectorId]. The categories come from [EVENT_CATEGORIES], the
     * family from [sourceFamilyOfConnector]. An unknown connector gives UNKNOWN.
     */
    public fun forEvent(type: EventType, connectorId: String): DataLineage {
        val family = sourceFamilyOfConnector(connectorId) ?: return DataLineage.UNKNOWN
        val categories = EVENT_CATEGORIES.getValue(type)
        return if (categories.size == AiDataCategory.entries.size) DataLineage.UNKNOWN else DataLineage(categories, setOf(family))
    }
}
