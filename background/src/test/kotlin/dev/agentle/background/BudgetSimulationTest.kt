package dev.agentle.background

import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo.State
import com.google.common.truth.Truth.assertThat
import dev.agentle.background.port.CollectionProfile
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Drives the real scheduler and jobs for 7 simulated days under a fake clock and a fake WorkManager (due times,
 * periods, retries with exponential backoff, appended children), with a trigger event every 5 minutes. Constraints are
 * ignored (worst case: always met). The average background runtime per day must stay inside the R02 §2.3 targets
 * (Low about 2-3 min, Balanced about 8 min, High about 20 min), using the §2.2 per-run targets, also when every
 * collector and sync fails transiently (worst-case retries until the circuit breaker opens).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class BudgetSimulationTest {
    private val perRunSeconds = mapOf(
        WorkNames.COLLECT_USAGE to 4.0, WorkNames.COLLECT_DEVICE to 1.0, WorkNames.SYNC_GOOGLEHEALTH to 5.0,
        WorkNames.SYNC_GOOGLEHEALTH_NOW to 5.0, WorkNames.FEATURES_REFRESH to 30.0, WorkNames.RETENTION to 10.0,
        WorkNames.MEDIA_CLEANUP to 10.0, WorkNames.INSIGHTS_WEEKLY to 60.0, WorkNames.JITAI_TIMER to 1.0,
        WorkNames.JITAI_EVAL_EVENTS to 1.0, WorkNames.RECONCILE to 1.0,
    )
    private val budget = mapOf(CollectionProfile.LOW to 180.0, CollectionProfile.BALANCED to 480.0, CollectionProfile.HIGH to 1200.0)
    private val tick = mapOf(
        CollectionProfile.LOW to 60.minutes,
        CollectionProfile.BALANCED to 30.minutes,
        CollectionProfile.HIGH to 15.minutes,
    )
    private val cap = mapOf(
        CollectionProfile.LOW to WorkScheduler.EVENTS_CAP_LOW,
        CollectionProfile.BALANCED to WorkScheduler.EVENTS_CAP_BALANCED,
        CollectionProfile.HIGH to WorkScheduler.EVENTS_CAP_HIGH,
    )

    private class Result(val secondsPerDay: Double, val runs: Map<String, Int>)

    private suspend fun simulate(profile: CollectionProfile, failing: Boolean, days: Int = 7): Result {
        val h = Harness()
        h.profile.value = profile
        if (failing) {
            val error = Outcome.failure(AppError.NetworkUnavailable())
            h.collectors.result = error
            h.wearable.result = error
        }
        val due = mutableMapOf<UUID, Instant>()
        val attempts = mutableMapOf<UUID, Int>()
        val runs = mutableMapOf<String, Int>()
        var seconds = 0.0
        h.scheduler.reconcilePeriodic(profile)
        h.runner.due = h.clock.now() + tick.getValue(profile)
        h.scheduler.armJitaiTimer()
        val end = h.clock.now() + days.days
        var minute = 0
        while (h.clock.now() < end) {
            val now = h.clock.now()
            if (h.runner.due!! <= now) h.runner.due = now + tick.getValue(profile)
            if (minute % 5 == 0) h.scheduler.onTriggerEventIngested()
            for (name in h.gateway.works.keys.toList()) {
                val list = h.gateway.works.getValue(name)
                list.forEach { (id, state) ->
                    if (state == State.ENQUEUED && id !in due) due[id] = now + initialDelay(h, id)
                }
                val index = list.indexOfFirst { it.second == State.ENQUEUED && due.getValue(it.first) <= now }
                if (index < 0) continue
                val id = list[index].first
                list[index] = id to State.RUNNING
                val attempt = attempts[id] ?: 0
                seconds += perRunSeconds.getValue(name)
                runs[name] = (runs[name] ?: 0) + 1
                val result = h.jobs.run(name, attempt)
                val after = h.gateway.works.getValue(name)
                val i = after.indexOfFirst { it.first == id }
                if (i < 0) continue
                val request = h.gateway.requests.getValue(id)
                when {
                    result == JobResult.RETRY -> {
                        after[i] = id to State.ENQUEUED
                        attempts[id] = attempt + 1
                        due[id] = now + Cadences.BACKOFF * (1 shl attempt)
                    }

                    request is PeriodicWorkRequest -> {
                        after[i] = id to State.ENQUEUED
                        attempts.remove(id)
                        due[id] = now + request.workSpec.intervalDuration.milliseconds
                    }

                    else -> {
                        val ok = result == JobResult.SUCCESS
                        after[i] = id to if (ok) State.SUCCEEDED else State.FAILED
                        val child = after.indexOfFirst { it.second == State.BLOCKED }
                        if (child >= 0) {
                            val childId = after[child].first
                            after[child] = childId to if (ok) State.ENQUEUED else State.FAILED
                            due[childId] = now + initialDelay(h, childId)
                        }
                    }
                }
            }
            h.clock.advanceBy(1.minutes)
            minute++
        }
        return Result(seconds / days, runs)
    }

    private fun initialDelay(h: Harness, id: UUID): Duration = h.gateway.requests.getValue(id).workSpec.initialDelay.milliseconds

    @Test
    fun `healthy week stays inside the per-profile daily budget`() = runTest {
        CollectionProfile.entries.forEach { profile ->
            val result = simulate(profile, failing = false)
            assertThat(result.secondsPerDay).isAtMost(budget.getValue(profile))
            assertThat(result.runs.getValue(WorkNames.JITAI_EVAL_EVENTS)).isAtMost(cap.getValue(profile) * 7)
            // The timer chain survives the whole week (one pass per tick, give or take the first).
            val ticks = (7.days / tick.getValue(profile)).toInt()
            assertThat(result.runs.getValue(WorkNames.JITAI_TIMER)).isAtLeast(ticks - 2)
        }
    }

    @Test
    fun `worst-case retries until the breaker opens still fit the budget`() = runTest {
        CollectionProfile.entries.forEach { profile ->
            val result = simulate(profile, failing = true)
            assertThat(result.secondsPerDay).isAtMost(budget.getValue(profile))
        }
    }
}
