package dev.agentle.background

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import dev.agentle.background.port.CollectionProfile
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A worker that stays RUNNING, so the unique-work states are observable. */
private object HangingFactory : WorkerFactory() {
    override fun createWorker(context: Context, workerClassName: String, params: WorkerParameters): ListenableWorker =
        object : CoroutineWorker(context, params) {
            override suspend fun doWork(): Result = awaitCancellation()
        }
}

/** Against the real WorkManager (test executor): unique-work semantics the fake gateway assumes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 37])
class WorkManagerIntegrationTest {
    private lateinit var wm: WorkManager
    private lateinit var scheduler: WorkScheduler

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(HangingFactory).build(),
        )
        wm = WorkManager.getInstance(context)
        val h = Harness()
        scheduler = WorkScheduler(WorkManagerGateway { wm }, h.store, h.deletion, h.runner, h.signals, h.clock)
    }

    private fun live(name: String) = wm.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }

    @Test
    fun `100 events become one work plus at most one follow-up`() = runBlocking {
        repeat(100) { scheduler.onTriggerEventIngested() }
        val states = live(WorkNames.JITAI_EVAL_EVENTS).map(WorkInfo::state)
        assertThat(states.size).isAtMost(2)
        assertThat(states.count { it == WorkInfo.State.RUNNING || it == WorkInfo.State.ENQUEUED }).isEqualTo(1)
    }

    @Test
    fun `follow-up path keeps one running and one pending, never a third`() = runBlocking {
        scheduler.onTriggerEventIngested()
        assertThat(live(WorkNames.JITAI_EVAL_EVENTS).map(WorkInfo::state)).containsExactly(WorkInfo.State.RUNNING)
        repeat(3) {
            scheduler.onTriggerEventIngested()
            scheduler.appendEventsFollowUp()
        }
        val states = live(WorkNames.JITAI_EVAL_EVENTS).map(WorkInfo::state)
        assertThat(states).hasSize(2)
        assertThat(states.count { it == WorkInfo.State.RUNNING }).isEqualTo(1)
        assertThat(states.count { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED }).isEqualTo(1)
    }

    @Test
    fun `ten reconciles keep one request per periodic name with the same id`() = runBlocking {
        scheduler.reconcilePeriodic(CollectionProfile.BALANCED)
        val ids = WorkNames.PERIODIC.associateWith { live(it).single().id }
        repeat(10) { scheduler.reconcilePeriodic(CollectionProfile.BALANCED) }
        WorkNames.PERIODIC.forEach { assertThat(live(it).single().id).isEqualTo(ids.getValue(it)) }
        scheduler.onProfileChanged(CollectionProfile.HIGH)
        WorkNames.PERIODIC.forEach { assertThat(live(it).single().id).isEqualTo(ids.getValue(it)) }
        assertThat(live(WorkNames.COLLECT_DEVICE).single().periodicityInfo!!.repeatIntervalMillis).isEqualTo(15 * 60_000L)
    }

    @Test
    fun `stop everything cancels all work`() = runBlocking {
        scheduler.reconcilePeriodic(CollectionProfile.BALANCED)
        scheduler.stopEverything()
        WorkNames.ALL.forEach { name -> assertThat(live(name).map(WorkInfo::state)).isEmpty() }
    }
}
