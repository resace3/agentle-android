package dev.agentle.connectors.api

import dev.agentle.core.model.CapabilityStatus
import dev.agentle.core.model.PlaceClass
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/** System broadcasts that invalidate schedules or clock-derived state. */
public enum class SystemChange {
    BOOT_COMPLETED,
    LOCKED_BOOT_COMPLETED,
    PACKAGE_REPLACED,
    TIMEZONE_CHANGED,
    TIME_SET,
    LOCALE_CHANGED,
    SHUTDOWN,
}

/**
 * Notified by the on-device system receivers (`:connectors:android`); the background scheduler (ScheduleReconciler)
 * implements it to re-plan work. Bursts of [SystemChange.TIMEZONE_CHANGED], [SystemChange.TIME_SET] and
 * [SystemChange.LOCALE_CHANGED] are debounced before delivery; [SystemChange.BOOT_COMPLETED] and
 * [SystemChange.PACKAGE_REPLACED] are delivered at once (red team oauth-security-12). Implementations must return
 * quickly: delivery runs inside a broadcast receiver's `goAsync()` window.
 */
public fun interface SystemChangeListener {
    public suspend fun onSystemChange(change: SystemChange, at: Instant)
}

/**
 * The Permission Center's live state of every registry capability (docs/research/01 §5), implemented by
 * `:connectors:android`. UI modules read it through this JVM port.
 */
public interface CapabilityStatusProvider {
    /** Latest status per capability id; contains every registry id after the first refresh. */
    public val statuses: StateFlow<Map<String, CapabilityStatus>>

    /** Re-evaluates every capability. */
    public suspend fun refresh(): Map<String, CapabilityStatus>

    /** Re-evaluates the given capabilities only (before a collection run). */
    public suspend fun refresh(capabilityIds: Collection<String>): Map<String, CapabilityStatus>

    /**
     * App hibernation / unused-app restrictions (red team jitai-correctness-13), refreshed with [refresh]; null before
     * the first evaluation.
     */
    public val unusedAppRestrictions: StateFlow<UnusedAppRestrictionsStatus?>

    /**
     * The UI reports a finished runtime-permission request ([results]: permission to granted; an empty result means the
     * request was cancelled and is ignored). The permissions are marked as requested and re-evaluated with the resumed
     * Activity, the only place where DENIED vs DENIED_PERMANENTLY is derived (red team testing-build-15).
     */
    public suspend fun onPermissionResult(results: Map<String, Boolean>)

    /** The user came back from the Settings screen of [specialAccess] (ECM heuristic, docs/research/01 §5.3 E). */
    public suspend fun onReturnedFromSettings(specialAccess: String)

    /**
     * Runtime permissions the UI may request for [capabilityId] now. Empty for opt-in capabilities the user has not
     * enabled (`call_state` never asks for READ_PHONE_STATE before that, red team lifecycle-battery-19) and for
     * capabilities not in this build.
     */
    public fun requestablePermissions(capabilityId: String): List<String>
}

/**
 * Delete-all coordination (red team database-sync, round 2 item 5). The deletion flow calls [suspendForDeletion] before
 * it wipes data and [resumeAfterDeletion] after the new data epoch is in place. `:connectors:android` disables its
 * notification listener component and drops every pending live batch while suspended; collectors tolerate being
 * disabled and re-enabled and never write after the data epoch changed.
 */
public interface LiveCollectionControl {
    public suspend fun suspendForDeletion()

    public suspend fun resumeAfterDeletion()
}

/**
 * The place class of the last foreground location fix (red team lifecycle-battery-06): it only updates what the UI
 * displays. It never creates an event, a trigger or dwell time, and rules cannot read it (location_class is unavailable
 * in v1).
 */
public interface CurrentPlaceProvider {
    /** Null until a fix was classified in this process. */
    public val currentPlace: StateFlow<PlaceClass?>

    /** Takes one foreground fix (coarse by default) while an Agentle screen is visible; a no-op otherwise. */
    public suspend fun refresh()
}
