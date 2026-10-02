package dev.agentle.connectors.api

/**
 * Compile-time names for the 53 capability ids of docs/research/capabilities.json, so code never spells an id as a
 * free string. `CapabilityIdsTest` keeps this list and the bundled registry identical.
 */
public object CapabilityIds {
    public const val APP_USAGE_EVENTS: String = "app_usage_events"
    public const val APP_USAGE_AGGREGATES: String = "app_usage_aggregates"
    public const val APP_STANDBY_BUCKET: String = "app_standby_bucket"
    public const val INSTALLED_APPS_INVENTORY: String = "installed_apps_inventory"
    public const val NETWORK_DATA_USAGE: String = "network_data_usage"
    public const val ACCESSIBILITY_EVENT_STREAM: String = "accessibility_event_stream"
    public const val SCREEN_INTERACTIVE_EVENTS: String = "screen_interactive_events"
    public const val UNLOCK_KEYGUARD_EVENTS: String = "unlock_keyguard_events"
    public const val DISPLAY_STATE: String = "display_state"
    public const val BATTERY_STATE: String = "battery_state"
    public const val POWER_SAVE_IDLE_STATE: String = "power_save_idle_state"
    public const val THERMAL_STATUS: String = "thermal_status"
    public const val STORAGE_STATS: String = "storage_stats"
    public const val BOOT_SHUTDOWN_EVENTS: String = "boot_shutdown_events"
    public const val NETWORK_CONNECTIVITY: String = "network_connectivity"
    public const val WIFI_CONNECTION_METADATA: String = "wifi_connection_metadata"
    public const val WIFI_NETWORK_IDENTITY: String = "wifi_network_identity"
    public const val AIRPLANE_MODE: String = "airplane_mode"
    public const val BLUETOOTH_ADAPTER_STATE: String = "bluetooth_adapter_state"
    public const val BLUETOOTH_CONNECTED_DEVICES: String = "bluetooth_connected_devices"
    public const val BLUETOOTH_NEARBY_SCAN: String = "bluetooth_nearby_scan"
    public const val AUDIO_VOLUME_RINGER: String = "audio_volume_ringer"
    public const val AUDIO_OUTPUT_DEVICES: String = "audio_output_devices"
    public const val TIMEZONE_TIME_CHANGES: String = "timezone_time_changes"
    public const val LOCALE_TIME_FORMAT: String = "locale_time_format"
    public const val NEXT_ALARM_CLOCK: String = "next_alarm_clock"
    public const val BACKGROUND_EXECUTION_EXEMPTION: String = "background_execution_exemption"
    public const val ACTIVITY_RECOGNITION_TRANSITIONS: String = "activity_recognition_transitions"
    public const val STEP_COUNT_RECORDING_API: String = "step_count_recording_api"
    public const val STEP_COUNTER_SENSOR: String = "step_counter_sensor"
    public const val MOTION_SENSORS: String = "motion_sensors"
    public const val AMBIENT_PROXIMITY_SENSORS: String = "ambient_proximity_sensors"
    public const val LOCATION_FOREGROUND: String = "location_foreground"
    public const val LOCATION_BACKGROUND: String = "location_background"
    public const val NOTIFICATION_EVENTS_METADATA: String = "notification_events_metadata"
    public const val NOTIFICATION_CONTENT: String = "notification_content"
    public const val DND_STATE: String = "dnd_state"
    public const val POST_NOTIFICATIONS_JITAI: String = "post_notifications_jitai"
    public const val EXACT_ALARM_JITAI_SCHEDULING: String = "exact_alarm_jitai_scheduling"
    public const val MEDIA_SESSIONS_NOW_PLAYING: String = "media_sessions_now_playing"
    public const val MEDIA_IMAGES_VIDEO_METADATA: String = "media_images_video_metadata"
    public const val MEDIA_AUDIO_METADATA: String = "media_audio_metadata"
    public const val CALENDAR_EVENTS: String = "calendar_events"
    public const val HEALTH_CONNECT_RECORDS: String = "health_connect_records"
    public const val HEALTH_CONNECT_BACKGROUND_READ: String = "health_connect_background_read"
    public const val HEALTH_CONNECT_HISTORY_READ: String = "health_connect_history_read"
    public const val HEALTH_CONNECT_ON_DEVICE_STEPS: String = "health_connect_on_device_steps"
    public const val BODY_SENSOR_HEART_RATE: String = "body_sensor_heart_rate"
    public const val CALL_STATE: String = "call_state"
    public const val CALL_LOG_METADATA: String = "call_log_metadata"
    public const val SMS_METADATA: String = "sms_metadata"
    public const val CONTACTS_METADATA: String = "contacts_metadata"
    public const val CONTACTS_PICKER_SELECTION: String = "contacts_picker_selection"

    /** Every id above, in registry order. */
    public val ALL: List<String> = listOf(
        APP_USAGE_EVENTS, APP_USAGE_AGGREGATES, APP_STANDBY_BUCKET, INSTALLED_APPS_INVENTORY, NETWORK_DATA_USAGE,
        ACCESSIBILITY_EVENT_STREAM, SCREEN_INTERACTIVE_EVENTS, UNLOCK_KEYGUARD_EVENTS, DISPLAY_STATE, BATTERY_STATE,
        POWER_SAVE_IDLE_STATE, THERMAL_STATUS, STORAGE_STATS, BOOT_SHUTDOWN_EVENTS, NETWORK_CONNECTIVITY,
        WIFI_CONNECTION_METADATA, WIFI_NETWORK_IDENTITY, AIRPLANE_MODE, BLUETOOTH_ADAPTER_STATE,
        BLUETOOTH_CONNECTED_DEVICES, BLUETOOTH_NEARBY_SCAN, AUDIO_VOLUME_RINGER, AUDIO_OUTPUT_DEVICES,
        TIMEZONE_TIME_CHANGES, LOCALE_TIME_FORMAT, NEXT_ALARM_CLOCK, BACKGROUND_EXECUTION_EXEMPTION,
        ACTIVITY_RECOGNITION_TRANSITIONS, STEP_COUNT_RECORDING_API, STEP_COUNTER_SENSOR, MOTION_SENSORS,
        AMBIENT_PROXIMITY_SENSORS, LOCATION_FOREGROUND, LOCATION_BACKGROUND, NOTIFICATION_EVENTS_METADATA,
        NOTIFICATION_CONTENT, DND_STATE, POST_NOTIFICATIONS_JITAI, EXACT_ALARM_JITAI_SCHEDULING,
        MEDIA_SESSIONS_NOW_PLAYING, MEDIA_IMAGES_VIDEO_METADATA, MEDIA_AUDIO_METADATA, CALENDAR_EVENTS,
        HEALTH_CONNECT_RECORDS, HEALTH_CONNECT_BACKGROUND_READ, HEALTH_CONNECT_HISTORY_READ,
        HEALTH_CONNECT_ON_DEVICE_STEPS, BODY_SENSOR_HEART_RATE, CALL_STATE, CALL_LOG_METADATA, SMS_METADATA,
        CONTACTS_METADATA, CONTACTS_PICKER_SELECTION,
    )
}
