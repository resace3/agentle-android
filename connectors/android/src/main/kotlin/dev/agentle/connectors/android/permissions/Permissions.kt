package dev.agentle.connectors.android.permissions

/** Permission names as strings, so constants newer than minSdk 29 need no inlined-API workarounds. */
public object Permissions {
    public const val POST_NOTIFICATIONS: String = "android.permission.POST_NOTIFICATIONS"
    public const val ACTIVITY_RECOGNITION: String = "android.permission.ACTIVITY_RECOGNITION"
    public const val ACCESS_COARSE_LOCATION: String = "android.permission.ACCESS_COARSE_LOCATION"
    public const val ACCESS_FINE_LOCATION: String = "android.permission.ACCESS_FINE_LOCATION"
    public const val BLUETOOTH_CONNECT: String = "android.permission.BLUETOOTH_CONNECT"
    public const val READ_CALENDAR: String = "android.permission.READ_CALENDAR"
    public const val READ_PHONE_STATE: String = "android.permission.READ_PHONE_STATE"
    public const val READ_MEDIA_IMAGES: String = "android.permission.READ_MEDIA_IMAGES"
    public const val READ_MEDIA_VIDEO: String = "android.permission.READ_MEDIA_VIDEO"
    public const val READ_MEDIA_VISUAL_USER_SELECTED: String = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    public const val READ_EXTERNAL_STORAGE: String = "android.permission.READ_EXTERNAL_STORAGE"
}

/** Health Connect permissions Agentle requests (the record types it reads, plus background and history access). */
public object HealthPermissions {
    public const val READ_STEPS: String = "android.permission.health.READ_STEPS"
    public const val READ_DISTANCE: String = "android.permission.health.READ_DISTANCE"
    public const val READ_EXERCISE: String = "android.permission.health.READ_EXERCISE"
    public const val READ_ACTIVE_CALORIES_BURNED: String = "android.permission.health.READ_ACTIVE_CALORIES_BURNED"
    public const val READ_TOTAL_CALORIES_BURNED: String = "android.permission.health.READ_TOTAL_CALORIES_BURNED"
    public const val READ_HEART_RATE: String = "android.permission.health.READ_HEART_RATE"
    public const val READ_RESTING_HEART_RATE: String = "android.permission.health.READ_RESTING_HEART_RATE"
    public const val READ_SLEEP: String = "android.permission.health.READ_SLEEP"
    public const val READ_WEIGHT: String = "android.permission.health.READ_WEIGHT"
    public const val READ_HEALTH_DATA_IN_BACKGROUND: String = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    public const val READ_HEALTH_DATA_HISTORY: String = "android.permission.health.READ_HEALTH_DATA_HISTORY"

    /**
     * The record permissions for the v1 event types (docs/ARCHITECTURE.md §6.4). The registry also lists heart rate
     * variability, oxygen saturation and respiratory rate; v1 has no event type for them, so they are not requested.
     */
    public val RECORDS: List<String> = listOf(
        READ_STEPS,
        READ_DISTANCE,
        READ_EXERCISE,
        READ_ACTIVE_CALORIES_BURNED,
        READ_TOTAL_CALORIES_BURNED,
        READ_HEART_RATE,
        READ_RESTING_HEART_RATE,
        READ_SLEEP,
        READ_WEIGHT,
    )
}

/** `specialAccess` values of the capability registry. */
public object SpecialAccess {
    public const val USAGE_ACCESS: String = "usage_access"
    public const val NOTIFICATION_LISTENER: String = "notification_listener"
    public const val EXACT_ALARM: String = "exact_alarm"
    public const val BATTERY_OPTIMIZATION: String = "battery_optimization"
    public const val HEALTH_CONNECT: String = "health_connect"
}
