package dev.agentle.background.port

import dev.agentle.core.common.Outcome
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/*
 * Ports of :background (docs/ARCHITECTURE.md §13). The modules they wrap are on team branches; the APP-WIRING team
 * implements each port with a thin adapter. Common contract for every suspend function here: main-safe, never throws
 * for expected failures (an AppError in Outcome.Failure), rethrows CancellationException, owns its own transactions
 * so a cancelled call leaves nothing half-written, and never puts an exception message into an error.
 * Retry hints: AppError.retryable (NetworkUnavailable, RateLimited, RemoteServerError >= 500, TokenExpired,
 * DatabaseError incl. a transient Keystore failure surfaced as DatabaseError) is transient; everything else
 * (AuthenticationRequired, NotEligible, ConsentViolation, UnsupportedFeature, PermissionDenied, ...) is permanent.
 */

/** Why the timer or a replan asks for a wearable sync (mirrors `jitai.engine.pipeline.SyncReason`). */
public enum class WearableSyncReason { PREFETCH, STALENESS_RETRY }

/** A sync of remote streams the JITAI timer asked for (mirrors `SyncRequest`; [streams] are connector stream ids). */
public data class WearableSyncRequest(val streams: Set<String>, val reason: WearableSyncReason)

/** Result of one timer pass (from `TimerReport`): where `jitai-timer` aims next and the syncs to run now. */
public data class TimerRun(val nextDueAt: Instant?, val syncRequests: List<WearableSyncRequest> = emptyList())

/** Result of the event worker loop (from `EventsReport`). */
public data class EventsRun(val followUpNeeded: Boolean, val nextDueAt: Instant?)

/** Replan reasons sent by the reconciler (the five external `ReplanReason`s). */
public enum class ReplanCause { CLOCK, TIMEZONE, OFFSET, BOOT, PACKAGE_REPLACED }

/**
 * The JITAI engine as the scheduler sees it. Wiring: `JitaiEngine` (team/jitai-engine) plus a feature-id -> stream
 * mapping for sync requests. Errors: whatever `JitaiEngine.guard` returns (AppError.Unexpected, permanent) or a
 * DatabaseError from the store (transient).
 */
public interface JitaiRunner {
    /** `JitaiEngine.runTimer()`: recovery, sweep, due rows, replan; [TimerRun.nextDueAt] from `TimerReport.nextDueAt`. */
    public suspend fun runTimer(): Outcome<TimerRun>

    /** `JitaiEngine.runEvents()`: drains event passes; `followUpNeeded` when the dirty flag was set again. */
    public suspend fun runEvents(): Outcome<EventsRun>

    /** `JitaiEngine.replan(reason)`; returns `TimerPlan.nextDueAt`. */
    public suspend fun replan(cause: ReplanCause): Outcome<Instant?>

    /** `JitaiEngine.nextDueAt()`: the minimum dueAt of the timer table (null: nothing to wake for). */
    public suspend fun nextDueAt(): Outcome<Instant?>

    /**
     * After an app update: `RuleValidator.revalidate` on every stored definition; failures are paused with a notice
     * (`JitaiLifecycle.PAUSE`) and `JitaiEngine.onDefinitionChanged` runs. Returns how many were paused. The engine has
     * no single entry point for this yet (requested from the integrator).
     */
    public suspend fun revalidateStoredDefinitions(): Outcome<Int>

    /** The zone offset (seconds) the last plan was made with, to detect DST changes below API 37; null if unknown. */
    public suspend fun plannedOffsetSeconds(): Outcome<Int?>
}

/** Connection of the wearable connector as the scheduler needs it. */
public enum class WearableConnection { CONNECTED, NOT_CONNECTED, NEEDS_USER }

/**
 * Google Health sync. Wiring: `GoogleHealthConnector.sync(SyncTrigger.SCHEDULED)` / `syncStream(stream)`, mapping
 * `SyncResult.status`/`error`; `clampFutureCursors(now)` from the event store runs before each sync.
 * NEEDS_REAUTH / NeedsResolution is reported as AppError.AuthenticationRequired (permanent) and
 * [connection] must then return NEEDS_USER until the user reconnects.
 */
public interface WearableSync {
    public suspend fun connection(): WearableConnection

    /** The periodic full sync. */
    public suspend fun syncAll(): Outcome<Unit>

    /** The single-stream "sync now"; empty [streams] means every stream (user pull-to-refresh). */
    public suspend fun syncStreams(streams: Set<String>): Outcome<Unit>

    /** `clampFutureCursors(now)`: cursors later than now + 5 min become now - overlap and a gap is recorded. */
    public suspend fun clampFutureCursors(now: Instant): Outcome<Unit>
}

/** On-device collectors (ANDROID-COLLECTORS). Missing permissions are a skipped success, never an error. */
public interface Collectors {
    /** Usage events sweep (`collect-usage`). */
    public suspend fun collectUsage(): Outcome<Unit>

    /** Device snapshots: battery (sticky intent), connectivity, audio, DND, storage (`collect-device`). */
    public suspend fun collectDevice(): Outcome<Unit>

    /** Re-requests Activity Recognition transitions with the same explicit mutable PendingIntent (idempotent). */
    public suspend fun reregisterActivityTransitions(): Outcome<Unit>
}

/** Why a bounded feature recompute is needed. */
public enum class FeatureInvalidation { CATALOG_CHANGED, ZONE_CHANGED }

/** The daily feature engine (REALTIME-FEATURES / ANALYTICS). */
public interface FeatureRefresher {
    /** Recomputes dirty days (`features-refresh`). */
    public suspend fun refreshDirtyDays(): Outcome<Unit>

    /** Marks a bounded window of days dirty (the adapter picks the bound, e.g. 14 days); cheap, no recompute. */
    public suspend fun invalidate(reason: FeatureInvalidation): Outcome<Unit>
}

/** Weekly insights, retention and media cleanup (ANALYTICS, ANDROID-DATA, INTERVENTIONS). */
public interface Maintenance {
    public suspend fun weeklyInsights(): Outcome<Unit>

    /** Applies the user's retention policy and `JitaiEngine.applyRetention()`; never the destructive reset. */
    public suspend fun applyRetention(): Outcome<Unit>

    /** Media cleanup policy (expired generated media). */
    public suspend fun cleanupMedia(): Outcome<Unit>
}

/** One process exit as `ApplicationExitInfo` reports it (API 30+); content-free. */
public data class ProcessExit(val atEpochMs: Long, val reason: Int)

/** Records process-death gaps (`CoverageRecorder`: closes intervals left open at their last heartbeat). */
public interface GapSink {
    public suspend fun recordProcessExits(exits: List<ProcessExit>): Outcome<Unit>
}

/** Collection profile (R02 §2.3); maps 1:1 to `core.datastore.CollectionProfile`. */
public enum class CollectionProfile { LOW, BALANCED, HIGH }

/** Settings the scheduler reads. Wiring: `SettingsStore` (core:datastore). */
public interface SchedulerSettings {
    /** The user's chosen profile; emits the current value first. */
    public val profile: Flow<CollectionProfile>
}

/** "Delete everything" marker (§5.5): while it exists nothing may be enqueued. Wiring: the marker file check. */
public interface DeletionMarker {
    public fun isDeletionInProgress(): Boolean
}

/**
 * User-visible notices from background work. Wiring: a system notification in an "Account status" channel whose tap
 * opens AppRoute.Wearable; deduplicated by a fixed id. Never starts an Activity.
 */
public interface AttentionNotifier {
    public suspend fun reconnectWearable()
}

