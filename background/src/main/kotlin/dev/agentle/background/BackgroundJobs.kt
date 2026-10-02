package dev.agentle.background

import android.app.usage.UsageStatsManager
import dev.agentle.background.port.AttentionNotifier
import dev.agentle.background.port.Collectors
import dev.agentle.background.port.DeletionMarker
import dev.agentle.background.port.FeatureInvalidation
import dev.agentle.background.port.FeatureRefresher
import dev.agentle.background.port.GapSink
import dev.agentle.background.port.JitaiRunner
import dev.agentle.background.port.Maintenance
import dev.agentle.background.port.ReplanCause
import dev.agentle.background.port.SchedulerSettings
import dev.agentle.background.port.WearableConnection
import dev.agentle.background.port.WearableSync
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.errorOrNull
import dev.agentle.core.common.getOrNull
import dev.agentle.core.time.AgentleClock
import kotlinx.coroutines.flow.first
import kotlinx.datetime.offsetAt

/** What a worker run returns, before it becomes a WorkManager `Result`. */
public enum class JobResult { SUCCESS, RETRY, FAILURE }

/**
 * Every worker's body: a thin call to its port, then the result mapping (R02 §1.3, §4.4 rule 5):
 * - success records the run; a skipped precondition (no permission, not connected, essential-only bucket) is success;
 * - a transient error ([AppError.retryable]) retries with backoff while `runAttemptCount + 1 <` [MAX_ATTEMPTS] and the
 *   circuit breaker is closed; then the run fails with its code recorded (a periodic work runs again next period);
 * - a permanent error fails at once, no retry storm;
 * - Google authorization that needs the user (AuthenticationRequired, or the connector reports NEEDS_USER) returns
 *   success, skips Google Health work until the connector is connected again and posts one deduplicated notice;
 * - circuit breaker: after [BREAKER_THRESHOLD] consecutive failed runs a work stops retrying (each run still tries the
 *   port once, at its next period or on an explicit user action, which resets the count); the state shows in
 *   [WorkStats.consecutiveFailures].
 * Cancellation is never caught: the ports own their transactions, so a stopped run leaves nothing half-written and is
 * never recorded as a success. A background component deletes nothing itself and never starts an Activity.
 */
public class BackgroundJobs(
    private val scheduler: WorkScheduler,
    private val store: SchedulerStore,
    private val deletion: DeletionMarker,
    private val runner: JitaiRunner,
    private val wearable: WearableSync,
    private val collectors: Collectors,
    private val features: FeatureRefresher,
    private val maintenance: Maintenance,
    private val gaps: GapSink,
    private val settings: SchedulerSettings,
    private val notifier: AttentionNotifier,
    private val signals: DeviceSignals,
    private val clock: AgentleClock,
    private val logger: Logger = Logger.NONE,
) {
    public suspend fun run(work: String, runAttemptCount: Int): JobResult {
        if (deletion.isDeletionInProgress()) return JobResult.SUCCESS
        val essentialOnly = signals.standbyBucket()?.let { it >= UsageStatsManager.STANDBY_BUCKET_RARE } == true
        store.putLong(KEY_BUCKET, signals.standbyBucket()?.toLong())
        val outcome: Outcome<*> = when (work) {
            WorkNames.COLLECT_USAGE -> collectors.collectUsage()
            WorkNames.COLLECT_DEVICE -> collectors.collectDevice().also { scheduler.onProfileChanged(settings.profile.first()) }
            WorkNames.SYNC_GOOGLEHEALTH -> wearableGate(work) ?: syncAll()
            WorkNames.SYNC_GOOGLEHEALTH_NOW -> wearableGate(work) ?: syncNow()
            WorkNames.FEATURES_REFRESH -> if (essentialOnly) SKIPPED else features.refreshDirtyDays()
            WorkNames.RETENTION -> maintenance.applyRetention()
            WorkNames.MEDIA_CLEANUP -> maintenance.cleanupMedia()
            WorkNames.INSIGHTS_WEEKLY -> if (essentialOnly) SKIPPED else maintenance.weeklyInsights()
            WorkNames.JITAI_TIMER -> timer()
            WorkNames.JITAI_EVAL_EVENTS -> events()
            WorkNames.RECONCILE -> reconcile()
            else -> Outcome.failure(AppError.UnsupportedFeature("work", work))
        }
        return finish(work, outcome, runAttemptCount)
    }

    private suspend fun finish(work: String, outcome: Outcome<*>, runAttemptCount: Int): JobResult {
        val error = outcome.errorOrNull()
        if (error == null) {
            store.recordSuccess(work, clock.now().toEpochMilliseconds())
            return JobResult.SUCCESS
        }
        if (error is AppError.AuthenticationRequired && work in WEARABLE_WORKS) {
            reconnectNeeded()
            store.recordFailure(work, error.code)
            return JobResult.SUCCESS
        }
        val breakerOpen = (store.stats.value[work]?.consecutiveFailures ?: 0) >= BREAKER_THRESHOLD
        store.recordFailure(work, if (breakerOpen) "${error.code}|$CIRCUIT_OPEN" else error.code)
        logger.w(COMPONENT, "work failed", error, mapOf("work" to work, "attempt" to runAttemptCount))
        return when {
            error.retryable && !breakerOpen && runAttemptCount + 1 < MAX_ATTEMPTS -> JobResult.RETRY
            else -> JobResult.FAILURE
        }
    }

    private suspend fun wearableGate(work: String): Outcome<Unit>? = when (wearable.connection()) {
        WearableConnection.CONNECTED -> null
        WearableConnection.NOT_CONNECTED -> SKIPPED
        WearableConnection.NEEDS_USER -> {
            reconnectNeeded()
            store.recordFailure(work, AppError.AuthenticationRequired("googlehealth").code)
            SKIPPED
        }
    }

    private suspend fun reconnectNeeded() {
        if (store.getLong(WorkScheduler.KEY_REAUTH_NOTIFIED) != null) return
        notifier.reconnectWearable()
        store.putLong(WorkScheduler.KEY_REAUTH_NOTIFIED, clock.now().toEpochMilliseconds())
    }

    private suspend fun syncAll(): Outcome<Unit> {
        wearable.clampFutureCursors(clock.now())
        return wearable.syncAll()
    }

    private suspend fun syncNow(): Outcome<Unit> {
        val streams = store.drainSyncStreams()
        wearable.clampFutureCursors(clock.now())
        val result = wearable.syncStreams(streams)
        if (result.errorOrNull()?.retryable == true) store.addSyncStreams(streams)
        return result
    }

    /** `jitai-timer`: the pass, its sync requests (one coalesced sync-now), the DST offset check, then the re-arm. */
    private suspend fun timer(): Outcome<*> {
        val result = runner.runTimer()
        result.getOrNull()?.let { run ->
            val streams = run.syncRequests.flatMap { it.streams }.toSet()
            if (run.syncRequests.isNotEmpty()) scheduler.syncWearableNow(streams)
        }
        checkOffset()
        scheduler.armFromRunningTimer()
        return result
    }

    /** Below API 37 no broadcast reports a DST offset change: compare with the offset of the last plan (§13). */
    private suspend fun checkOffset() {
        val planned = runner.plannedOffsetSeconds().getOrNull() ?: return
        val now = clock.now()
        if (clock.zone().offsetAt(now).totalSeconds != planned) {
            runner.replan(ReplanCause.OFFSET)
            features.invalidate(FeatureInvalidation.ZONE_CHANGED)
        }
    }

    /** `jitai-eval-events`: the engine drains passes; a follow-up still needed at the end is appended, never lost. */
    private suspend fun events(): Outcome<*> {
        val result = runner.runEvents()
        if (result.getOrNull()?.followUpNeeded == true) scheduler.appendEventsFollowUp()
        scheduler.armJitaiTimer()
        return result
    }

    /** `reconcile` (R02 §5.3, §13): idempotent; any number of runs leaves the same works and timers. */
    private suspend fun reconcile(): Outcome<*> {
        val reasons = store.drainReconcileReasons().ifEmpty { setOf(ReconcileReason.PROCESS_START) }
        val failures = mutableListOf<AppError>()
        fun Outcome<*>.track() = errorOrNull()?.let { failures += it }

        if (reasons.any { it != ReconcileReason.LOCALE }) wearable.clampFutureCursors(clock.now()).track()
        replanCause(reasons)?.let { runner.replan(it).track() }
        if (ReconcileReason.PACKAGE_REPLACED in reasons) {
            runner.revalidateStoredDefinitions().track()
            features.invalidate(FeatureInvalidation.CATALOG_CHANGED).track()
        }
        if (ReconcileReason.TIMEZONE in reasons || ReconcileReason.OFFSET in reasons) {
            features.invalidate(FeatureInvalidation.ZONE_CHANGED).track()
        }
        if (reasons.any { it in REREGISTER }) collectors.reregisterActivityTransitions().track()
        scheduler.reconcilePeriodic(settings.profile.first())
        scheduler.armJitaiTimer()
        recordExits().track()

        val error = failures.firstOrNull { it.retryable } ?: failures.firstOrNull()
        if (error == null) {
            scheduler.recordReconciled()
            return Outcome.success(Unit)
        }
        if (error.retryable) store.addReconcileReasons(reasons)
        return Outcome.failure(error)
    }

    private suspend fun recordExits(): Outcome<Unit> {
        val since = store.getLong(KEY_EXITS) ?: Long.MIN_VALUE
        val exits = signals.processExits().filter { it.atEpochMs > since }
        if (exits.isEmpty()) return Outcome.success(Unit)
        val result = gaps.recordProcessExits(exits)
        if (result.errorOrNull() == null) store.putLong(KEY_EXITS, exits.maxOf { it.atEpochMs })
        return result
    }

    public companion object {
        /** In-run attempts of a transient failure before the run fails (R02 §4.4 rule 6). */
        public const val MAX_ATTEMPTS: Int = 3

        /** Consecutive failed runs that open a work's circuit breaker (oauth-security-08, R02 §4.4 rule 5). */
        public const val BREAKER_THRESHOLD: Int = 5
        public const val CIRCUIT_OPEN: String = "circuit_open"
        internal const val KEY_BUCKET = "diag.standby_bucket"
        private const val KEY_EXITS = "record.exits"
        private const val COMPONENT = "background"
        private val SKIPPED: Outcome<Unit> = Outcome.success(Unit)
        private val WEARABLE_WORKS = setOf(WorkNames.SYNC_GOOGLEHEALTH, WorkNames.SYNC_GOOGLEHEALTH_NOW)
        private val REREGISTER = setOf(ReconcileReason.BOOT, ReconcileReason.PACKAGE_REPLACED, ReconcileReason.PROCESS_START)

        /** One replan carries every external reason (all five rebuild every row; R10/§11.7). */
        internal fun replanCause(reasons: Set<ReconcileReason>): ReplanCause? = when {
            ReconcileReason.PACKAGE_REPLACED in reasons -> ReplanCause.PACKAGE_REPLACED
            ReconcileReason.BOOT in reasons -> ReplanCause.BOOT
            ReconcileReason.TIMEZONE in reasons -> ReplanCause.TIMEZONE
            ReconcileReason.OFFSET in reasons -> ReplanCause.OFFSET
            ReconcileReason.CLOCK in reasons -> ReplanCause.CLOCK
            else -> null
        }
    }
}
