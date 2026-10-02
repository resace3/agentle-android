package dev.agentle.feature.settings.port

import android.content.Intent
import dev.agentle.core.common.Outcome

/** A system Settings page that fixes something a settings screen reports. */
public sealed interface SystemSettingsTarget {
    /**
     * The app's own system page (Settings > Apps > Agentle): battery usage and optimization, permissions, pausing.
     * The app never requests `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (docs/research/02 s2.5); this page is where the
     * user changes it, if they want to.
     */
    public data object AppDetails : SystemSettingsTarget

    /** The app's notification settings: the app-level switch and, on API 33+, the `POST_NOTIFICATIONS` grant. */
    public data object AppNotifications : SystemSettingsTarget

    /** One notification channel's settings; [channelId] is the id registered with `NotificationManager`. */
    public data class NotificationChannel(val channelId: String) : SystemSettingsTarget
}

/**
 * System Settings intents for the settings screens (docs/ARCHITECTURE.md §6: `:connectors:android` owns the
 * Settings intents; the wiring team adapts them here).
 */
public interface SystemSettingsPort {
    /**
     * An explicit intent that opens [target] for this app, ready for `Context.startActivity` from the activity:
     * - [SystemSettingsTarget.AppDetails]: `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` with a `package:` URI;
     * - [SystemSettingsTarget.AppNotifications]: `Settings.ACTION_APP_NOTIFICATION_SETTINGS` with `EXTRA_APP_PACKAGE`;
     * - [SystemSettingsTarget.NotificationChannel]: `Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS` with
     *   `EXTRA_APP_PACKAGE` and `EXTRA_CHANNEL_ID`.
     *
     * Failures: `AppError.UnsupportedFeature` when this build or device cannot open the page. The screen still catches
     * `ActivityNotFoundException` when it starts the intent. Not suspending, no I/O: callable from the main thread.
     */
    public fun intentFor(target: SystemSettingsTarget): Outcome<Intent>
}
