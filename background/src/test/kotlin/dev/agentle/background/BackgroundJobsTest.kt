package dev.agentle.background

import android.app.usage.UsageStatsManager
import androidx.work.ListenableWorker
import androidx.work.WorkInfo.State
import com.google.common.truth.Truth.assertThat
import dev.agentle.background.port.EventsRun
import dev.agentle.background.port.FeatureInvalidation
import dev.agentle.background.port.ProcessExit
import dev.agentle.background.port.ReplanCause
import dev.agentle.background.port.TimerRun
import dev.agentle.background.port.WearableConnection
import dev.agentle.background.port.WearableSyncReason
import dev.agentle.background.port.WearableSyncRequest
import dev.agentle.background.worker.AgentleWorker
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 37])
class BackgroundJobsTest {
    private val h = Harness()
    private val transient = AppError.NetworkUnavailable()
    private val permanent = AppError.UnsupportedFeature("x")

    @Test
    fun `success maps to success and records the run`() = runTest {
        assertThat(h.jobs.run(WorkNames.COLLECT_USAGE, 0)).isEqualTo(JobResult.SUCCESS)
        assertThat(h.store.stats.value.getValue(WorkNames.COLLECT_USAGE).runs).isEqualTo(1)
        assertThat(AgentleWorker.toResult(JobResult.SUCCESS)).isEqualTo(ListenableWorker.Result.success())
        assertThat(AgentleWorker.toResult(JobResult.RETRY)).isEqualTo(ListenableWorker.Result.retry())
        assertThat(AgentleWorker.toResult(JobResult.FAILURE)).isEqualTo(ListenableWorker.Result.failure())
    }

    @Test
    fun `transient error retries up to the attempt cap, permanent fails at once`() = runTest {
        h.collectors.result = Outcome.failure(transient)
        assertThat(h.jobs.run(WorkNames.COLLECT_USAGE, 0)).isEqualTo(JobResult.RETRY)
        assertThat(h.jobs.run(WorkNames.COLLECT_USAGE, BackgroundJobs.MAX_ATTEMPTS - 1)).isEqualTo(JobResult.FAILURE)
        h.collectors.result = Outcome.failure(permanent)
        assertThat(h.jobs.run(WorkNames.COLLECT_DEVICE, 0)).isEqualTo(JobResult.FAILURE)
        assertThat(h.store.stats.value.getValue(WorkNames.COLLECT_DEVICE).lastFailureCode).isEqualTo(permanent.code)
    }

    @Test
    fun `circuit breaker opens after N consecutive failed runs and closes on user action`() = runTest {
        h.wearable.result = Outcome.failure(transient)
        val last = BackgroundJobs.MAX_ATTEMPTS - 1
        repeat(BackgroundJobs.BREAKER_THRESHOLD) { h.jobs.run(WorkNames.SYNC_GOOGLEHEALTH, last) }
        assertThat(h.jobs.run(WorkNames.SYNC_GOOGLEHEALTH, 0)).isEqualTo(JobResult.FAILURE)
        val stats = h.store.stats.value.getValue(WorkNames.SYNC_GOOGLEHEALTH)
        assertThat(stats.lastFailureCode).endsWith(BackgroundJobs.CIRCUIT_OPEN)
        val state = BackgroundDiagnostics.merge(emptyList(), h.store.stats.value, null)
        assertThat(state.workers.first { it.name == WorkNames.SYNC_GOOGLEHEALTH }.circuitOpen).isTrue()
        h.scheduler.syncWearableNow(userInitiated = true)
        assertThat(h.jobs.run(WorkNames.SYNC_GOOGLEHEALTH, 0)).isEqualTo(JobResult.RETRY)
    }

    @Test
    fun `breaker counts failed runs, not retried attempts`() = runTest {
        h.wearable.result = Outcome.failure(transient)
        repeat(2) { repeat(BackgroundJobs.MAX_ATTEMPTS) { attempt -> h.jobs.run(WorkNames.SYNC_GOOGLEHEALTH, attempt) } }
        assertThat(h.store.stats.value.getValue(WorkNames.SYNC_GOOGLEHEALTH).consecutiveFailures).isEqualTo(2)
        assertThat(h.jobs.run(WorkNames.SYNC_GOOGLEHEALTH, 0)).isEqualTo(JobResult.RETRY)
    }

    @Test
    fun `failed timer pass ends as success and keeps exactly one re-arm across retries`() = runTest {
        h.gateway.setState(WorkNames.JITAI_TIMER, State.RUNNING)
        h.runner.due = h.clock.now() + 30.minutes
        h.runner.timerResult = Outcome.failure(transient)
        assertThat(h.jobs.run(WorkNames.JITAI_TIMER, 0)).isEqualTo(JobResult.RETRY)
        assertThat(h.jobs.run(WorkNames.JITAI_TIMER, 1)).isEqualTo(JobResult.RETRY)
        h.runner.timerResult = Outcome.failure(permanent)
        assertThat(h.jobs.run(WorkNames.JITAI_TIMER, 2)).isEqualTo(JobResult.SUCCESS)
        assertThat(h.gateway.live(WorkNames.JITAI_TIMER).map { it.second }).containsExactly(State.RUNNING, State.BLOCKED)
        assertThat(h.store.stats.value.getValue(WorkNames.JITAI_TIMER).lastFailureCode).isEqualTo(permanent.code)
    }

    @Test
    fun `failed events and sync-now passes end as success so follow-ups survive`() = runTest {
        h.runner.eventsResult = Outcome.failure(permanent)
        assertThat(h.jobs.run(WorkNames.JITAI_EVAL_EVENTS, 0)).isEqualTo(JobResult.SUCCESS)
        h.wearable.result = Outcome.failure(permanent)
        h.store.addSyncStreams(setOf("hr"))
        assertThat(h.jobs.run(WorkNames.SYNC_GOOGLEHEALTH_NOW, 0)).isEqualTo(JobResult.SUCCESS)
        assertThat(h.store.drainSyncStreams()).isEmpty()
        assertThat(h.store.stats.value.getValue(WorkNames.JITAI_EVAL_EVENTS).lastFailureCode).isEqualTo(permanent.code)
        val stats = BackgroundDiagnostics.merge(emptyList(), h.store.stats.value, null).workers
        assertThat(stats.first { it.name == WorkNames.SYNC_GOOGLEHEALTH_NOW }.lastFailureCode).isEqualTo(permanent.code)
    }

    @Test
    fun `sync-now keeps streams only for a retryable error`() = runTest {
        h.wearable.result = Outcome.failure(transient)
        h.store.addSyncStreams(setOf("hr"))
        assertThat(h.jobs.run(WorkNames.SYNC_GOOGLEHEALTH_NOW, 0)).isEqualTo(JobResult.RETRY)
        assertThat(h.store.drainSyncStreams()).containsExactly("hr")
    }

    @Test
    fun `cancellation re-adds drained streams and reasons`() = runTest {
        h.store.addSyncStreams(setOf("hr"))
        h.wearable.syncThrows = CancellationException()
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { h.jobs.run(WorkNames.SYNC_GOOGLEHEALTH_NOW, 0) }
        }
        assertThat(h.store.drainSyncStreams()).containsExactly("hr")
        h.store.addReconcileReasons(setOf(ReconcileReason.BOOT))
        h.runner.replanThrows = CancellationException()
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { h.jobs.run(WorkNames.RECONCILE, 0) }
        }
        assertThat(h.store.drainReconcileReasons()).containsExactly(ReconcileReason.BOOT)
    }

    @Test
    fun `permanent reconcile failure keeps its reasons`() = runTest {
        h.runner.replanResult = Outcome.failure(permanent)
        h.store.addReconcileReasons(setOf(ReconcileReason.PACKAGE_REPLACED))
        assertThat(h.jobs.run(WorkNames.RECONCILE, 0)).isEqualTo(JobResult.SUCCESS)
        assertThat(h.store.drainReconcileReasons()).containsExactly(ReconcileReason.PACKAGE_REPLACED)
    }

    @Test
    fun `reconnect resets both sync breakers`() = runTest {
        repeat(6) {
            h.store.recordFailure(WorkNames.SYNC_GOOGLEHEALTH, "x")
            h.store.recordFailure(WorkNames.SYNC_GOOGLEHEALTH_NOW, "x")
        }
        h.scheduler.onWearableConnected(h.profile.value)
        assertThat(h.store.stats.value.getValue(WorkNames.SYNC_GOOGLEHEALTH).consecutiveFailures).isEqualTo(0)
        assertThat(h.store.stats.value.getValue(WorkNames.SYNC_GOOGLEHEALTH_NOW).consecutiveFailures).isEqualTo(0)
    }

    @Test
    fun `authorization needing the user is success, one notice, no Google work until reconnected`() = runTest {
        h.wearable.result = Outcome.failure(AppError.AuthenticationRequired("googlehealth"))
        assertThat(h.jobs.run(WorkNames.SYNC_GOOGLEHEALTH, 0)).isEqualTo(JobResult.SUCCESS)
        h.wearable.connection = WearableConnection.NEEDS_USER
        repeat(3) { assertThat(h.jobs.run(WorkNames.SYNC_GOOGLEHEALTH, 0)).isEqualTo(JobResult.SUCCESS) }
        assertThat(h.wearable.syncAllCount).isEqualTo(1)
        assertThat(h.notifier.count).isEqualTo(1)
        h.wearable.connection = WearableConnection.CONNECTED
        h.wearable.result = Outcome.success(Unit)
        h.scheduler.onWearableConnected(h.profile.value)
        assertThat(h.jobs.run(WorkNames.SYNC_GOOGLEHEALTH, 0)).isEqualTo(JobResult.SUCCESS)
        assertThat(h.wearable.syncAllCount).isEqualTo(2)
    }

    @Test
    fun `not connected skips sync as success`() = runTest {
        h.wearable.connection = WearableConnection.NOT_CONNECTED
        assertThat(h.jobs.run(WorkNames.SYNC_GOOGLEHEALTH, 0)).isEqualTo(JobResult.SUCCESS)
        assertThat(h.wearable.syncAllCount).isEqualTo(0)
    }

    @Test
    fun `cancellation propagates and is never recorded as success`() = runTest {
        val throwing = object : dev.agentle.background.port.Collectors by h.collectors {
            override suspend fun collectUsage(): Outcome<Unit> = throw CancellationException()
        }
        val jobs = BackgroundJobs(
            h.scheduler, h.store, h.deletion, h.runner, h.wearable, throwing, h.features, h.maintenance, h.gaps,
            h.settings, h.notifier, h.signals, h.clock,
        )
        assertThrows(CancellationException::class.java) { kotlinx.coroutines.runBlocking { jobs.run(WorkNames.COLLECT_USAGE, 0) } }
        assertThat(h.store.stats.value[WorkNames.COLLECT_USAGE]).isNull()
    }

    @Test
    fun `deletion in progress makes every worker a no-op`() = runTest {
        h.deletion.inProgress = true
        WorkNames.ALL.forEach { assertThat(h.jobs.run(it, 0)).isEqualTo(JobResult.SUCCESS) }
        assertThat(h.collectors.usage + h.wearable.syncAllCount + h.maintenance.calls).isEqualTo(0)
        assertThat(h.gateway.calls).isEmpty()
    }

    @Test
    fun `rare bucket runs essential work only`() = runTest {
        h.signals.bucket = UsageStatsManager.STANDBY_BUCKET_RARE
        h.jobs.run(WorkNames.FEATURES_REFRESH, 0)
        h.jobs.run(WorkNames.INSIGHTS_WEEKLY, 0)
        h.jobs.run(WorkNames.COLLECT_USAGE, 0)
        assertThat(h.features.refreshes).isEqualTo(0)
        assertThat(h.maintenance.calls).isEqualTo(0)
        assertThat(h.collectors.usage).isEqualTo(1)
        val state = BackgroundDiagnostics.merge(emptyList(), h.store.stats.value, h.store.getLong(BackgroundJobs.KEY_BUCKET)?.toInt())
        assertThat(state.standbyBucket).isEqualTo(UsageStatsManager.STANDBY_BUCKET_RARE)
    }

    @Test
    fun `timer pass coalesces its sync requests and re-arms as a child`() = runTest {
        h.gateway.setState(WorkNames.JITAI_TIMER, State.RUNNING)
        h.runner.due = h.clock.now() + 30.minutes
        h.runner.timerResult = Outcome.success(
            TimerRun(
                h.runner.due,
                listOf(
                    WearableSyncRequest(setOf("steps"), WearableSyncReason.PREFETCH),
                    WearableSyncRequest(setOf("sleep"), WearableSyncReason.STALENESS_RETRY),
                ),
            ),
        )
        assertThat(h.jobs.run(WorkNames.JITAI_TIMER, 0)).isEqualTo(JobResult.SUCCESS)
        assertThat(h.gateway.live(WorkNames.SYNC_GOOGLEHEALTH_NOW)).hasSize(1)
        assertThat(h.store.drainSyncStreams()).containsExactly("steps", "sleep")
        assertThat(h.gateway.calls.last { it.name == WorkNames.JITAI_TIMER }.policy).isEqualTo("APPEND_OR_REPLACE")
    }

    @Test
    fun `timer run detects a DST offset change and replans`() = runTest {
        h.clock.setZone(TimeZone.of("Europe/Berlin"))
        h.clock.setWallClock(Instant.parse("2026-10-25T00:30:00Z"))
        h.runner.plannedOffset = 7200
        h.clock.advanceBy(60.minutes)
        h.jobs.run(WorkNames.JITAI_TIMER, 0)
        assertThat(h.runner.replans).containsExactly(ReplanCause.OFFSET)
        assertThat(h.features.invalidations).containsExactly(FeatureInvalidation.ZONE_CHANGED)
        h.jobs.run(WorkNames.JITAI_TIMER, 0)
        assertThat(h.runner.replans).hasSize(1)
    }

    @Test
    fun `events follow-up is appended, never lost`() = runTest {
        h.gateway.setState(WorkNames.JITAI_EVAL_EVENTS, State.RUNNING)
        h.runner.eventsResults.add(Outcome.success(EventsRun(followUpNeeded = true, nextDueAt = null)))
        h.jobs.run(WorkNames.JITAI_EVAL_EVENTS, 0)
        assertThat(h.gateway.live(WorkNames.JITAI_EVAL_EVENTS).map { it.second }).containsExactly(State.RUNNING, State.BLOCKED)
    }

    @Test
    fun `reconcile after package replaced revalidates, clamps, replans, re-registers and records gaps`() = runTest {
        h.signals.exits = listOf(ProcessExit(1_000, 10), ProcessExit(2_000, 3))
        h.store.addReconcileReasons(setOf(ReconcileReason.PACKAGE_REPLACED, ReconcileReason.TIMEZONE))
        assertThat(h.jobs.run(WorkNames.RECONCILE, 0)).isEqualTo(JobResult.SUCCESS)
        assertThat(h.runner.replans).containsExactly(ReplanCause.PACKAGE_REPLACED)
        assertThat(h.runner.revalidations).isEqualTo(1)
        assertThat(h.wearable.clamps).isEqualTo(1)
        assertThat(h.collectors.reregisters).isEqualTo(1)
        assertThat(h.features.invalidations).containsExactly(FeatureInvalidation.CATALOG_CHANGED, FeatureInvalidation.ZONE_CHANGED)
        assertThat(h.gaps.recorded).hasSize(2)
        WorkNames.PERIODIC.forEach { assertThat(h.gateway.live(it)).hasSize(1) }
        // Second run: same works, no duplicate gaps.
        repeat(9) { h.jobs.run(WorkNames.RECONCILE, 0) }
        assertThat(h.gaps.recorded).hasSize(2)
        WorkNames.PERIODIC.forEach { assertThat(h.gateway.live(it)).hasSize(1) }
    }

    @Test
    fun `transient reconcile failure keeps its reasons for the retry`() = runTest {
        h.runner.replanResult = Outcome.failure(transient)
        h.store.addReconcileReasons(setOf(ReconcileReason.BOOT))
        assertThat(h.jobs.run(WorkNames.RECONCILE, 0)).isEqualTo(JobResult.RETRY)
        assertThat(h.store.drainReconcileReasons()).containsExactly(ReconcileReason.BOOT)
    }

    @Test
    fun `process death between runs loses no pending sync streams`() = runTest {
        h.scheduler.syncWearableNow(setOf("hr"))
        // A new process: new scheduler and jobs over the same persisted store and WorkManager state.
        val jobs2 = run {
            BackgroundJobs(
                WorkScheduler(h.gateway, h.store, h.deletion, h.runner, h.signals, h.clock), h.store, h.deletion,
                h.runner, h.wearable, h.collectors, h.features, h.maintenance, h.gaps, h.settings, h.notifier,
                h.signals, h.clock,
            )
        }
        jobs2.run(WorkNames.SYNC_GOOGLEHEALTH_NOW, 0)
        assertThat(h.wearable.synced.single()).containsExactly("hr")
    }
}
