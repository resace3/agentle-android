package dev.agentle.analytics.features.realtime

import dev.agentle.core.common.Outcome
import dev.agentle.core.model.ActivityKind
import dev.agentle.core.model.TransitionKind
import dev.agentle.core.time.ClosedOpenRange
import kotlin.time.Instant

/** Usage event types the engine reads (R10 §5.1, `UsageEvents.Event`; screen and keyguard API 28, the rest API 29). */
public enum class UsageEventKind {
    SCREEN_INTERACTIVE,
    SCREEN_NON_INTERACTIVE,
    ACTIVITY_RESUMED,
    ACTIVITY_PAUSED,
    ACTIVITY_STOPPED,
    KEYGUARD_SHOWN,
    KEYGUARD_HIDDEN,
    DEVICE_SHUTDOWN,
    DEVICE_STARTUP,
}

/**
 * One usage event (`usage_event(ts, type, package, className)`). [packageName] and [className] are set for the
 * `ACTIVITY_*` kinds; an activity stream is the pair (package, class).
 */
public data class UsageEvent(val at: Instant, val kind: UsageEventKind, val packageName: String? = null, val className: String? = null)

/** Usage-access data (R10 §5.1, §5.5). Implemented by `:data` over the stored copy plus `UsageStatsManager`. */
public interface UsageEventPort {
    /**
     * Usage events with `at` in [range], ordered by time (ties in system order): the stored copy plus a live top-up
     * `queryEvents(lastStoredTs, range.end)` so the last minutes are included (R10 §5.5 step 1).
     *
     * Unavailable with `NO_PERMISSION` without usage access, `LOCKED_AFTER_BOOT` when `queryEvents` returns null
     * (user not unlocked yet), `COVERAGE_GAP` when [range] reaches beyond the system's retention and the local copy.
     */
    public suspend fun events(range: ClosedOpenRange): Outcome<InputAnswer<List<UsageEvent>>>

    /**
     * The effective category of each package: the user's override, else `ApplicationInfo.category` (API 26), else
     * `UNDEFINED` (R10 §5.4 C). Values are members of `RealtimeFeatureCatalog.APP_CATEGORIES`; others count as
     * `UNDEFINED`.
     */
    public suspend fun appCategories(packages: Set<String>): Outcome<InputAnswer<Map<String, String>>>
}

/**
 * One POSTED notification (`notification_event`, R10 §5.1). The collector writes one POSTED row per key and
 * lifetime: updates of a posted notification are folded into that row, never new posts (lifecycle-battery-02).
 *
 * @property at the first post time of this lifetime.
 * @property keyHash SHA-256 of `StatusBarNotification.key`.
 */
public data class NotificationPost(
    val at: Instant,
    val packageName: String,
    val keyHash: String,
    val ongoing: Boolean = false,
    val groupSummary: Boolean = false,
)

/** Notification-listener metadata (content is never stored). */
public interface NotificationEventPort {
    /** POSTED rows whose first post time is in [range]. Unavailable with `NO_PERMISSION` without notification access. */
    public suspend fun posted(range: ClosedOpenRange): Outcome<InputAnswer<List<NotificationPost>>>
}

/** One Activity Recognition transition (`activity_transition(ts, activity, ENTER/EXIT)`, R10 §5.1, R03 §6.2). */
public data class ActivityTransition(val at: Instant, val activity: ActivityKind, val transition: TransitionKind)

/** Activity Recognition Transition API data. */
public interface ActivityTransitionPort {
    /**
     * Transitions with `at` in [range], ordered by time. Unavailable with `NO_PERMISSION` without
     * `ACTIVITY_RECOGNITION`, `API_UNAVAILABLE` without Google Play services.
     */
    public suspend fun transitions(range: ClosedOpenRange): Outcome<InputAnswer<List<ActivityTransition>>>
}

/** `NotificationManager.getCurrentInterruptionFilter()` values (R10 §5.4 B). */
public enum class InterruptionFilter { ALL, PRIORITY, NONE, ALARMS, UNKNOWN }

/** `AudioManager.getMode()` values; [OTHER] for values this version does not know. */
public enum class AudioMode {
    NORMAL,
    RINGTONE,
    IN_CALL,
    IN_COMMUNICATION,
    CALL_SCREENING,
    CALL_REDIRECT,
    COMMUNICATION_REDIRECT,
    OTHER,
}

/** `AudioDeviceInfo` output types (`getDevices(GET_DEVICES_OUTPUTS)`); [OTHER] for the rest. */
public enum class AudioOutputType {
    BUILTIN_SPEAKER,
    BUILTIN_EARPIECE,
    WIRED_HEADPHONES,
    WIRED_HEADSET,
    BLUETOOTH_A2DP,
    BLUETOOTH_SCO,
    BLE_HEADSET,
    BLE_SPEAKER,
    USB_HEADSET,
    USB_DEVICE,
    HDMI,
    HEARING_AID,
    OTHER,
}

/**
 * Device APIs read at evaluation time (R10 §5.1 "Live reads"). A failed read, or an API without an answer, is
 * `Missing(API_UNAVAILABLE)` for the features that need it. The engine reads each value at most once per pass.
 */
public interface LiveDeviceReads {
    /** `BatteryManager.isCharging()`. */
    public suspend fun charging(): Outcome<InputAnswer<Boolean>>

    /** `BATTERY_PROPERTY_CAPACITY`, else the sticky `ACTION_BATTERY_CHANGED` `level * 100 / scale`, floored. */
    public suspend fun batteryPercent(): Outcome<InputAnswer<Int>>

    /** `PowerManager.isInteractive()`. */
    public suspend fun interactive(): Outcome<InputAnswer<Boolean>>

    /** `NotificationManager.getCurrentInterruptionFilter()`. */
    public suspend fun interruptionFilter(): Outcome<InputAnswer<InterruptionFilter>>

    /** `AudioManager.getMode()`. */
    public suspend fun audioMode(): Outcome<InputAnswer<AudioMode>>

    /** Types of the current audio output devices. */
    public suspend fun audioOutputs(): Outcome<InputAnswer<Set<AudioOutputType>>>

    /**
     * The package whose activity is resumed now according to the usage-event stream (looking back as far as needed),
     * or null when none is. Used for the state of an app stream that has no event inside a window (R10 §5.5 step 2).
     */
    public suspend fun foregroundApp(): Outcome<InputAnswer<String?>>

    /** `Settings.Global.BOOT_COUNT`, for the elapsed-time rule of R10 §8.6. */
    public suspend fun bootCount(): Outcome<InputAnswer<Int>>
}
