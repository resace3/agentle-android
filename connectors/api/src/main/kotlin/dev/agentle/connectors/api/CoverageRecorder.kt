package dev.agentle.connectors.api

import kotlin.time.Instant

/**
 * Why a collector's coverage interval ended (red team lifecycle-battery-01). The `PROCESS_*` causes come from the
 * last `ApplicationExitInfo` (API 30+) when an interval left open by a dead process is closed at its last heartbeat.
 */
public enum class CoverageEndCause {
    /** The collector stopped normally (shutdown requested, collection paused by the app). */
    STOPPED,

    /** The user turned the connector off. */
    DISABLED_BY_USER,

    /** A grant the collector needs was revoked or never given. */
    PERMISSION_LOST,

    /** The notification listener was unbound by the system. */
    LISTENER_DISCONNECTED,

    /** Activity-transition updates were unregistered or could not be registered. */
    REGISTRATION_LOST,

    /** A deletion changed the data epoch; data collected before it was dropped. */
    DATA_DELETED,

    /** The database was transiently unavailable (for example a Keystore error) and a write was skipped. */
    DATABASE_UNAVAILABLE,

    /** The wall clock jumped (a high-water mark in the future was clamped back to now). */
    CLOCK_CHANGED,

    /** A live source went over its write budget and rows were dropped (red team lifecycle-battery-17). */
    RATE_LIMITED,
    PROCESS_EXITED,
    PROCESS_LOW_MEMORY,
    PROCESS_CRASHED,
    PROCESS_ANR,
    PROCESS_KILLED_BY_USER,
    PROCESS_KILLED_BY_SYSTEM,
    PROCESS_PERMISSION_CHANGE,
    PROCESS_PACKAGE_UPDATED,
    UNKNOWN,
}

/**
 * Records the intervals during which an on-device collector was known to be working (red team lifecycle-battery-01).
 * ANDROID-DATA implements it on the `collector_coverage(collector, from_ms, to_ms, cause)` table; features read it so
 * that a window a collector did not cover yields UNKNOWN instead of 0.
 *
 * Collectors call [open] when collection (re)starts (process start, listener connected, activity-transition
 * registration, a permission regained), [heartbeat] on every sweep and live callback batch, and [close] when it stops
 * (listener disconnected, permission lost, disabled, a skipped write). Calls are idempotent: [open] on an open
 * interval and [close] on a closed one change nothing. Implementations keep the last heartbeat of each open interval
 * so that a process that died without closing can be closed there later ([openIntervals]).
 *
 * [collector] is the collector's capability id ([CapabilityIds]), for example [CapabilityIds.APP_USAGE_EVENTS],
 * [CapabilityIds.NOTIFICATION_EVENTS_METADATA] or [CapabilityIds.ACTIVITY_RECOGNITION_TRANSITIONS]: feature engines read
 * coverage under these ids. Each capability id has exactly one collector that opens and heartbeats it, so one
 * collector never closes an interval another one keeps open.
 */
public interface CoverageRecorder {
    public suspend fun open(collector: String, at: Instant)

    public suspend fun heartbeat(collector: String, at: Instant)

    public suspend fun close(collector: String, at: Instant, cause: CoverageEndCause)

    /** Collectors with an open interval, each with its last heartbeat (or its start if it never had one). */
    public suspend fun openIntervals(): Map<String, Instant>
}
