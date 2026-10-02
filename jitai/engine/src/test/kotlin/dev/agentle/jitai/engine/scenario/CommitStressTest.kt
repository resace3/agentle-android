package dev.agentle.jitai.engine.scenario

import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiEventType
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.Leaves
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.bool
import dev.agentle.jitai.engine.int
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * testing-build-01: N engines (separate worker processes on one database) run timer and event passes in parallel on
 * [Dispatchers.Default] behind a start barrier, 1,000 times. The commit is the only serialization point: per-rule and
 * global caps and the global minimum gap must hold in every repetition, and every key exists at most once.
 */
class CommitStressTest {
    private val e1 = Rules.rule("E1", Trigger.Event(listOf(JitaiEventType.POWER_CONNECTED)), category = JitaiCategory.STRESS_BREAK)
    private val e2 = Rules.rule("E2", Trigger.Event(listOf(JitaiEventType.POWER_CONNECTED)), category = JitaiCategory.GENERAL)

    @Test
    @Suppress("InjectDispatcher") // Real threads are the point of this test (testing-build-01).
    fun `parallel passes on real threads never break the caps or the minimum gap`() = runBlocking {
        repeat(REPETITIONS) { rep ->
            val start = F0.local("2026-10-01T22:30")
            val harness = F0.harness(start, Rules.R1, Rules.R3, e1, e2)
            harness.features.set(Leaves.SCREEN, int(50, start))
            harness.features.set("charging", bool(true, start))
            harness.events.emit(JitaiEventType.POWER_CONNECTED, F0.local("2026-10-01T22:29:30"))
            val barrier = CompletableDeferred<Unit>()
            val workers = (0 until WORKERS).map { index ->
                val engine = harness.newEngine()
                async(Dispatchers.Default) {
                    barrier.await()
                    if (index % 2 == 0) engine.runTimer() else engine.runEvents()
                }
            }
            barrier.complete(Unit)
            workers.awaitAll()

            val rows = harness.store.rows()
            val counted = rows.filter { it.state.counted && it.state.countsGlobally }
            assertWithMessage("rep $rep keys").that(rows.map { it.decisionKey }).containsNoDuplicates()
            // Every candidate is due at 22:30 and the global gap is 30 min: at most one counted delivery.
            assertWithMessage("rep $rep counted").that(counted.size).isAtMost(1)
            assertWithMessage("rep $rep posts").that(harness.delivery.posts.size).isAtMost(1)
            assertWithMessage("rep $rep alerts").that(harness.delivery.alerts).isEqualTo(harness.delivery.posts.size)
        }
    }

    private companion object {
        const val REPETITIONS = 1_000
        const val WORKERS = 4
    }
}
