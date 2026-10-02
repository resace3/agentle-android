package dev.agentle.connectors.api

import dev.agentle.core.model.CapabilityStatus
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
}
