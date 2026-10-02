package dev.agentle.feature.settings.port

import dev.agentle.core.common.Outcome
import dev.agentle.core.model.PermissionState
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/** The global "pause all JITAIs" state (docs/research/10 s9.1 gate G04). */
public sealed interface JitaiPause {
    public data object NotPaused : JitaiPause

    /** Paused until [until]; deliveries resume by themselves then. */
    public data class Until(val until: Instant) : JitaiPause

    /** Paused until the user resumes. */
    public data object UntilResumed : JitaiPause
}

/** How long "pause all JITAIs" lasts; the implementation turns it into an instant with the engine's clock. */
public enum class PauseOption {
    ONE_HOUR,
    TWO_HOURS,

    /** Until the next engine-day rollover (04:00 by default). */
    UNTIL_TOMORROW,

    /** Until the user resumes. */
    UNTIL_RESUMED,
}

/** Quiet hours: half-open `[start, end)`, may cross midnight; `start == end` is invalid (R10 E025). */
public data class QuietHours(val enabled: Boolean, val start: LocalTime, val end: LocalTime)

/** Delivery channels with their own daily cap (docs/research/10 s9.2). */
public enum class InterventionChannel { NOTIFICATION, IMAGE, VOICE, VIDEO }

/** The global delivery limits the JITAI engine enforces (gates G12-G15). */
public data class DeliveryLimits(
    val dailyCap: Int,
    val weeklyCap: Int,
    val minGapMinutes: Int,
    /** Per-channel daily caps; a missing channel means "same as [dailyCap]". */
    val channelCaps: Map<InterventionChannel, Int>,
) {
    /** The cap in force for [channel]: never above [dailyCap]. */
    public fun effectiveChannelCap(channel: InterventionChannel): Int = minOf(channelCaps[channel] ?: dailyCap, dailyCap)

    public companion object {
        /** docs/research/10 s9.2 design defaults. */
        public val DEFAULT: DeliveryLimits = DeliveryLimits(
            dailyCap = 6,
            weeklyCap = 30,
            minGapMinutes = 30,
            channelCaps = mapOf(InterventionChannel.VOICE to 2, InterventionChannel.VIDEO to 1, InterventionChannel.IMAGE to 3),
        )
    }
}

/**
 * Hard ceilings of the delivery limits (docs/research/10 s9.2; red team: the user can lower the caps but never raise
 * them above these). They are not configurable; the engine enforces the same values, and [NotificationSettingsPort]
 * rejects anything outside them with `AppError.ValidationError`.
 */
public object DeliveryLimitBounds {
    public const val DAILY_CAP_MAX: Int = 12
    public const val WEEKLY_CAP_MAX: Int = 60
    public const val MIN_GAP_MINUTES_MIN: Int = 15
    public const val MIN_GAP_MINUTES_MAX: Int = 240

    public val dailyCapRange: IntRange = 0..DAILY_CAP_MAX
    public val weeklyCapRange: IntRange = 0..WEEKLY_CAP_MAX
    public val minGapRange: IntRange = MIN_GAP_MINUTES_MIN..MIN_GAP_MINUTES_MAX

    /** A channel cap is at most the daily cap in force (itself at most [DAILY_CAP_MAX]). */
    public fun channelCapRange(dailyCap: Int): IntRange = 0..dailyCap.coerceIn(dailyCapRange)

    /** True when [limits] is within every ceiling. */
    public fun contains(limits: DeliveryLimits): Boolean =
        limits.dailyCap in dailyCapRange &&
            limits.weeklyCap in weeklyCapRange &&
            limits.minGapMinutes in minGapRange &&
            limits.channelCaps.values.all { it in channelCapRange(limits.dailyCap) }
}

/** Whether Agentle can post notifications (the delivery prerequisite: red team jitai-correctness-13). */
public data class NotificationAccess(
    /**
     * `POST_NOTIFICATIONS`: `ALLOWED` (granted, or API < 33), `DENIED` (can be requested), `DENIED_PERMANENTLY` or
     * `REQUIRES_SETTINGS` (only the system settings page can grant it).
     */
    val permission: PermissionState,
    /** `NotificationManagerCompat.areNotificationsEnabled()`. */
    val appNotificationsEnabled: Boolean,
    /** `NotificationManager.areNotificationsPaused()`: the system paused the app (for example Digital Wellbeing). */
    val pausedBySystem: Boolean,
) {
    /** True when the app-level prerequisite holds (channel blocking is reported per channel). */
    val canPost: Boolean
        get() = permission == PermissionState.ALLOWED && appNotificationsEnabled && !pausedBySystem
}

/** One notification channel Agentle registered. */
public data class NotificationChannelInfo(
    val id: String,
    /** The channel's user-visible name as registered with `NotificationManager` (app-defined, never personal). */
    val name: String,
    /** Importance `NONE`: the user blocked the channel. */
    val blocked: Boolean,
)

/** What the notification-settings screen shows. */
public data class NotificationSettingsState(
    val pause: JitaiPause,
    val quietHours: QuietHours,
    val limits: DeliveryLimits,
    /** Posted text includes details (app names, values) only when true; off by default (red team jitai-correctness-18). */
    val detailedNotifications: Boolean,
    /** Notifications are bridged to connected wearables only when true; off by default (posts are local-only). */
    val showOnWearables: Boolean,
    /** Null when it could not be read. */
    val access: NotificationAccess?,
    /** Null when it could not be read. */
    val channels: List<NotificationChannelInfo>?,
)

/**
 * Notification settings (`AppRoute.NotificationSettings`): pausing, quiet hours, global and channel caps (lower only;
 * [DeliveryLimitBounds] are the maximum), detailed notifications, wearable bridging, and the notification access the
 * engine needs.
 */
public interface NotificationSettingsPort {
    /**
     * The current settings with the live notification access; emits again when any of them changes (including a
     * permission change made in system settings, re-checked when the app resumes). Fresh install: not paused, quiet
     * hours 22:00-07:00 on, [DeliveryLimits.DEFAULT], both switches off. A missing permission shows in
     * [NotificationSettingsState.access], never as a failure. Emits `Outcome.Failure` (`DatabaseError`,
     * `UnsupportedFeature`) when the settings cannot be read.
     */
    public val state: Flow<Outcome<NotificationSettingsState>>

    /** Pauses every INTERVENTION JITAI ([PauseOption] decides until when). Failures: `DatabaseError`. Main-safe. */
    public suspend fun pause(option: PauseOption): Outcome<Unit>

    /** Clears the pause. Failures: `DatabaseError`. Main-safe. */
    public suspend fun resume(): Outcome<Unit>

    /** Stores [quietHours]. Failures: `ValidationError` (start == end), `DatabaseError`. Main-safe. */
    public suspend fun setQuietHours(quietHours: QuietHours): Outcome<Unit>

    /** Stores [limits]. Failures: `ValidationError` when outside [DeliveryLimitBounds], `DatabaseError`. Main-safe. */
    public suspend fun setLimits(limits: DeliveryLimits): Outcome<Unit>

    /** Turns detailed notification text on or off. Failures: `DatabaseError`. Main-safe. */
    public suspend fun setDetailedNotifications(enabled: Boolean): Outcome<Unit>

    /** Allows or stops bridging notifications to connected wearables. Failures: `DatabaseError`. Main-safe. */
    public suspend fun setShowOnWearables(enabled: Boolean): Outcome<Unit>
}
