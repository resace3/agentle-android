package dev.agentle.background

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo.State
import com.google.common.truth.Truth.assertThat
import dev.agentle.background.port.CollectionProfile
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 37])
class WorkSchedulerTest {
    private val h = Harness()

    @Test
    fun `periodic works use the documented names, KEEP, constraints and tags`() = runTest {
        h.scheduler.reconcilePeriodic(CollectionProfile.BALANCED)
        val calls = h.gateway.calls.filter { it.op == "periodic" }
        assertThat(calls.map { it.name }).containsExactlyElementsIn(WorkNames.PERIODIC)
        assertThat(calls.map { it.policy }.toSet()).containsExactly("KEEP")
        val gh = calls.first { it.name == WorkNames.SYNC_GOOGLEHEALTH }.request!!
        assertThat(gh.workSpec.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(gh.workSpec.intervalDuration).isEqualTo(1.hours.inWholeMilliseconds)
        assertThat(gh.tags).containsAtLeast(WorkNames.TAG, WorkNames.SYNC_GOOGLEHEALTH)
        val insights = calls.first { it.name == WorkNames.INSIGHTS_WEEKLY }.request!!
        assertThat(insights.workSpec.constraints.requiresCharging()).isTrue()
        assertThat(insights.workSpec.intervalDuration).isEqualTo(7.days.inWholeMilliseconds)
    }

    @Test
    fun `low profile uses unmetered sync and charging dailies`() {
        val low = Cadences.periodic(CollectionProfile.LOW).associateBy { it.name }
        assertThat(low.getValue(WorkNames.SYNC_GOOGLEHEALTH).network).isEqualTo(NetworkType.UNMETERED)
        assertThat(low.getValue(WorkNames.FEATURES_REFRESH).charging).isTrue()
        val high = Cadences.periodic(CollectionProfile.HIGH).associateBy { it.name }
        assertThat(high.getValue(WorkNames.COLLECT_DEVICE).interval).isEqualTo(15.minutes)
        Cadences.periodic(CollectionProfile.HIGH).forEach { assertThat(it.interval).isAtLeast(15.minutes) }
    }

    @Test
    fun `reconcile ten times is idempotent`() = runTest {
        repeat(10) { h.scheduler.reconcilePeriodic(CollectionProfile.BALANCED) }
        WorkNames.PERIODIC.forEach { assertThat(h.gateway.live(it)).hasSize(1) }
        assertThat(h.gateway.calls.map { it.policy }.toSet()).containsExactly("KEEP")
    }

    @Test
    fun `profile change UPDATEs every periodic work, same profile does nothing`() = runTest {
        h.scheduler.reconcilePeriodic(CollectionProfile.BALANCED)
        h.gateway.calls.clear()
        h.scheduler.onProfileChanged(CollectionProfile.BALANCED)
        assertThat(h.gateway.calls).isEmpty()
        h.scheduler.onProfileChanged(CollectionProfile.HIGH)
        assertThat(h.gateway.calls.map { it.policy }.toSet()).containsExactly(ExistingPeriodicWorkPolicy.UPDATE.name)
        assertThat(h.gateway.calls).hasSize(WorkNames.PERIODIC.size)
    }

    @Test
    fun `battery saver degrades to LOW after 30 minutes and reverts 30 minutes after it ends`() = runTest {
        h.scheduler.reconcilePeriodic(CollectionProfile.HIGH)
        h.signals.saver = true
        assertThat(h.scheduler.onProfileChanged(CollectionProfile.HIGH)).isEqualTo(CollectionProfile.HIGH)
        h.clock.advanceBy(31.minutes)
        assertThat(h.scheduler.onProfileChanged(CollectionProfile.HIGH)).isEqualTo(CollectionProfile.LOW)
        h.signals.saver = false
        assertThat(h.scheduler.onProfileChanged(CollectionProfile.HIGH)).isEqualTo(CollectionProfile.LOW)
        h.clock.advanceBy(31.minutes)
        assertThat(h.scheduler.onProfileChanged(CollectionProfile.HIGH)).isEqualTo(CollectionProfile.HIGH)
    }

    @Test
    fun `timer with nothing pending is REPLACEd with the due delay`() = runTest {
        h.runner.due = h.clock.now() + 20.minutes
        h.scheduler.armJitaiTimer()
        val call = h.gateway.calls.single()
        assertThat(call.policy).isEqualTo("REPLACE")
        assertThat(call.request!!.workSpec.initialDelay).isEqualTo(20.minutes.inWholeMilliseconds)
    }

    @Test
    fun `timer re-arm never cancels a running pass`() = runTest {
        h.runner.due = h.clock.now() + 5.minutes
        h.gateway.setState(WorkNames.JITAI_TIMER, State.RUNNING)
        h.scheduler.armJitaiTimer()
        assertThat(h.gateway.calls).isEmpty()
        h.runner.due = null
        h.scheduler.armJitaiTimer()
        assertThat(h.gateway.calls).isEmpty()
    }

    @Test
    fun `running timer with a blocked re-arm is updated in place`() = runTest {
        h.gateway.setState(WorkNames.JITAI_TIMER, State.RUNNING, State.BLOCKED)
        val childId = h.gateway.works.getValue(WorkNames.JITAI_TIMER)[1].first
        h.runner.due = h.clock.now() + 3.minutes
        h.scheduler.armJitaiTimer()
        val call = h.gateway.calls.single()
        assertThat(call.op).isEqualTo("update")
        assertThat(call.request!!.id).isEqualTo(childId)
    }

    @Test
    fun `no due row cancels only an enqueued timer`() = runTest {
        h.gateway.setState(WorkNames.JITAI_TIMER, State.ENQUEUED)
        h.scheduler.armJitaiTimer()
        assertThat(h.gateway.calls.single().op).isEqualTo("cancel")
    }

    @Test
    fun `running timer appends its own re-arm`() = runTest {
        h.gateway.setState(WorkNames.JITAI_TIMER, State.RUNNING)
        h.runner.due = h.clock.now() + 15.minutes
        h.scheduler.armFromRunningTimer()
        assertThat(h.gateway.calls.single().policy).isEqualTo("APPEND_OR_REPLACE")
        assertThat(h.gateway.live(WorkNames.JITAI_TIMER).map { it.second }).containsExactly(State.RUNNING, State.BLOCKED)
    }

    @Test
    fun `a burst of 100 events coalesces into one work`() = runTest {
        repeat(100) { h.scheduler.onTriggerEventIngested() }
        assertThat(h.gateway.live(WorkNames.JITAI_EVAL_EVENTS)).hasSize(1)
        assertThat(h.gateway.enqueues()).hasSize(1)
    }

    @Test
    fun `events while running get exactly one follow-up`() = runTest {
        h.gateway.setState(WorkNames.JITAI_EVAL_EVENTS, State.RUNNING)
        repeat(50) { h.scheduler.onTriggerEventIngested() }
        h.scheduler.appendEventsFollowUp()
        assertThat(h.gateway.live(WorkNames.JITAI_EVAL_EVENTS).map { it.second }).containsExactly(State.RUNNING, State.BLOCKED)
    }

    @Test
    fun `debounced reasons coalesce with delay, boot is immediate`() = runTest {
        repeat(5) { h.scheduler.requestReconcile(setOf(ReconcileReason.CLOCK)) }
        assertThat(h.gateway.live(WorkNames.RECONCILE)).hasSize(1)
        assertThat(h.gateway.calls.first().request!!.workSpec.initialDelay).isEqualTo(Cadences.RECONCILE_DEBOUNCE.inWholeMilliseconds)
        assertThat(h.gateway.calls.map { it.policy }.toSet()).containsExactly("KEEP")
        h.scheduler.requestReconcile(setOf(ReconcileReason.BOOT))
        assertThat(h.gateway.calls.last().policy).isEqualTo("REPLACE")
        assertThat(h.gateway.calls.last().request!!.workSpec.initialDelay).isEqualTo(0)
        assertThat(h.store.drainReconcileReasons()).containsExactly(ReconcileReason.CLOCK, ReconcileReason.BOOT)
    }

    @Test
    fun `process start enqueues reconcile only when something changed`() = runTest {
        h.scheduler.onProcessStart()
        assertThat(h.gateway.calls).hasSize(1)
        h.scheduler.recordReconciled()
        h.gateway.calls.clear()
        h.gateway.works.clear()
        h.scheduler.onProcessStart()
        assertThat(h.gateway.calls).isEmpty()
        h.clock.advanceBy(25.hours)
        h.scheduler.onProcessStart()
        assertThat(h.gateway.calls).hasSize(1)
    }

    @Test
    fun `deletion in progress stops every enqueue`() = runTest {
        h.scheduler.reconcilePeriodic(CollectionProfile.BALANCED)
        h.deletion.inProgress = true
        h.scheduler.stopEverything()
        h.gateway.calls.clear()
        h.runner.due = h.clock.now() + 1.hours
        h.scheduler.reconcilePeriodic(CollectionProfile.HIGH)
        h.scheduler.onTriggerEventIngested()
        h.scheduler.syncWearableNow(userInitiated = true)
        h.scheduler.armJitaiTimer()
        h.scheduler.requestReconcile(setOf(ReconcileReason.BOOT))
        h.scheduler.onProcessStart()
        assertThat(h.gateway.calls).isEmpty()
        WorkNames.PERIODIC.forEach { assertThat(h.gateway.live(it)).isEmpty() }
    }

    @Test
    fun `sync now coalesces and user action resets breakers`() = runTest {
        repeat(6) { h.store.recordFailure(WorkNames.SYNC_GOOGLEHEALTH_NOW, "x") }
        h.scheduler.syncWearableNow(setOf("steps"))
        h.scheduler.syncWearableNow(setOf("sleep"))
        assertThat(h.gateway.live(WorkNames.SYNC_GOOGLEHEALTH_NOW)).hasSize(1)
        assertThat(h.store.stats.value.getValue(WorkNames.SYNC_GOOGLEHEALTH_NOW).consecutiveFailures).isEqualTo(6)
        h.scheduler.syncWearableNow(userInitiated = true)
        assertThat(h.store.stats.value.getValue(WorkNames.SYNC_GOOGLEHEALTH_NOW).consecutiveFailures).isEqualTo(0)
    }

    @Test
    fun `urgent reason updates a debounced pending child in place with zero delay`() = runTest {
        h.gateway.setState(WorkNames.RECONCILE, State.RUNNING)
        h.scheduler.requestReconcile(setOf(ReconcileReason.CLOCK))
        val child = h.gateway.live(WorkNames.RECONCILE).single { it.second == State.BLOCKED }.first
        h.scheduler.requestReconcile(setOf(ReconcileReason.BOOT))
        val call = h.gateway.calls.last()
        assertThat(call.op).isEqualTo("update")
        assertThat(call.request!!.id).isEqualTo(child)
        assertThat(call.request.workSpec.initialDelay).isEqualTo(0)
    }

    @Test
    fun `event-triggered passes are capped per day by profile`() = runTest {
        h.scheduler.reconcilePeriodic(CollectionProfile.LOW)
        repeat(100) {
            h.scheduler.onTriggerEventIngested()
            h.gateway.works.remove(WorkNames.JITAI_EVAL_EVENTS)
        }
        val events = { h.gateway.calls.count { it.name == WorkNames.JITAI_EVAL_EVENTS } }
        assertThat(events()).isEqualTo(WorkScheduler.EVENTS_CAP_LOW)
        h.clock.advanceBy(1.days)
        h.scheduler.onTriggerEventIngested()
        assertThat(events()).isEqualTo(WorkScheduler.EVENTS_CAP_LOW + 1)
    }
}
