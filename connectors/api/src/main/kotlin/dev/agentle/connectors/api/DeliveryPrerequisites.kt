package dev.agentle.connectors.api

import dev.agentle.core.model.CapabilityStatus
import kotlinx.coroutines.flow.Flow

/**
 * Whether a JITAI notification can be shown right now (red team jitai-correctness-13). `:connectors:android` implements
 * it with live platform reads; the delivery side asks it before counting a delivery as possible.
 */
public interface NotificationDeliveryGate {
    /**
     * True only when notifications are enabled for Agentle (including `POST_NOTIFICATIONS` on API 33+), [channelId] is
     * not blocked (importance NONE, or its channel group is blocked) and notifications are not paused
     * (`NotificationManager.areNotificationsPaused()`). A channel that does not exist yet counts as not blocked: the
     * sender creates it before posting.
     */
    public fun canDeliver(channelId: String): Boolean
}

/**
 * Whether at least one JITAI is active, implemented by the JITAI side. The Permission Center offers delivery
 * prerequisites that only matter for JITAIs (app hibernation) only while this is true.
 */
public fun interface ActiveJitaiSignal {
    public fun hasActiveJitai(): Flow<Boolean>
}

/** App hibernation / unused-app restrictions as `PackageManagerCompat.getUnusedAppRestrictionsStatus()` reports them. */
public enum class UnusedAppRestrictions {
    /** The device has no unused-app restrictions (API 29 without the Google Play services backport). */
    NOT_AVAILABLE,

    /** Restrictions are off for Agentle: the user exempted it. */
    DISABLED,

    /** API 29 with the Google Play services backport: permissions of unused apps are revoked. */
    PERMISSION_REVOCATION_BACKPORT,

    /** API 30: permissions of unused apps are revoked. */
    PERMISSION_REVOCATION,

    /** API 31+: full hibernation of unused apps (permissions revoked; jobs, alarms and notifications stop). */
    HIBERNATION,

    /** The status could not be read (user locked, or an error). */
    UNKNOWN,
}

/**
 * The Permission Center's app-hibernation entry (red team jitai-correctness-13). It is an app-wide condition, not a
 * registry capability: [status] carries the state (ALLOWED when exempt, REQUIRES_SETTINGS with HIBERNATION_ENABLED while
 * restrictions apply, UNSUPPORTED_ON_DEVICE when the device has none, UNAVAILABLE when unreadable) under
 * [CONDITION_ID]. The Settings action is `IntentCompat.createManageUnusedAppRestrictionsIntent`.
 */
public data class UnusedAppRestrictionsStatus(
    val restrictions: UnusedAppRestrictions,
    val status: CapabilityStatus,
    /** Whether the Permission Center offers the Settings action: restrictions apply and at least one JITAI is active. */
    val offered: Boolean,
) {
    public companion object {
        public const val CONDITION_ID: String = "unused_app_restrictions"
    }
}
