package dev.agentle.jitai.engine.scenario

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.model.ActiveWindow
import dev.agentle.jitai.dsl.model.ContentStrategy
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.Tone
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.Leaves
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.JitaiResponse
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.int
import dev.agentle.jitai.engine.ports.DeliveryPrerequisite
import dev.agentle.jitai.engine.ports.InterruptionFilter
import dev.agentle.jitai.engine.ports.NotificationSystemState
import dev.agentle.jitai.engine.ports.PooledText
import dev.agentle.jitai.engine.response.ResponseStatus
import dev.agentle.jitai.engine.row
import dev.agentle.jitai.engine.testing.CrashPoint
import dev.agentle.jitai.engine.testing.EngineHarness
import dev.agentle.jitai.engine.testing.expectCrash
import dev.agentle.jitai.engine.timer
import dev.agentle.jitai.engine.timerAt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/** Regression tests for the review findings D-1..D-4 and E-1 on f93bf33. */
class ReviewRepairTest {
    private val key = "v1|R2|D|2026-10-01|17:00"
    private val ai = Rules.R2.copy(
        content = ContentStrategy.AiText("walk", Tone.WARM, ContentStrategy.Template("Walk", "A short walk helps.")),
    )

    private fun harness(definition: dev.agentle.jitai.dsl.model.JitaiDefinition = Rules.R2): EngineHarness {
        val harness = F0.harness(F0.local("2026-10-01T17:00"), definition)
        harness.features.set(Leaves.STEPS, int(2_000, F0.local("2026-10-01T16:50")))
        return harness
    }

    /** R2 with ai_text decided (crash after the commit), a matching pool item added, in a new process. */
    private suspend fun aiDecided(): EngineHarness {
        val harness = harness(ai)
        harness.death.arm(CrashPoint.AFTER_COMMIT)
        expectCrash { harness.engine.runTimer() }
        harness.aiTexts.add(
            PooledText(
                "p1", "R2",
                RuleCodec.contentHash(
                    ai,
                ),
                "AI", "AI body", harness.clock.now(), 0, emptySet(), harness.row(key).content.snapshotHash,
            ),
        )
        harness.restart()
        return harness
    }

    @Test
    fun `D-1 recovery of a crash after posting an AiPooled text marks the item used (DELIVERED)`() = runTest {
        val harness = aiDecided()
        harness.death.arm(CrashPoint.AFTER_POST)
        expectCrash { harness.engine.runTimer() }
        assertThat(harness.aiTexts.used).isEmpty()
        harness.restart()

        harness.timerAt(harness.clock.now() + 3.minutes)

        assertThat(harness.row(key).state).isEqualTo(DecisionState.DELIVERED)
        assertThat(harness.aiTexts.used).containsExactly("p1", key)
    }

    @Test
    fun `D-1 a DELIVERY_UNCERTAIN recovery marks the item used too`() = runTest {
        val harness = aiDecided()
        harness.death.arm(CrashPoint.AFTER_POST)
        expectCrash { harness.engine.runTimer() }
        harness.restart()
        harness.delivery.dismiss(harness.row(key).notificationTag)

        harness.timerAt(harness.clock.now() + 3.minutes)

        assertThat(harness.row(key).state).isEqualTo(DecisionState.DELIVERY_UNCERTAIN)
        assertThat(harness.aiTexts.used).containsExactly("p1", key)
    }

    @Test
    fun `D-3 a worker cancelled around the claim commit leaves the row DECIDED for the next run`() = runTest {
        val harness = harness()
        val run = async {
            val job = currentCoroutineContext().job
            harness.delivery.onPrepare = { job.cancel(CancellationException("test")) }
            harness.engine.runTimer()
        }
        runCatching { run.await() }
        harness.delivery.onPrepare = null

        assertThat(harness.row(key).state).isEqualTo(DecisionState.DECIDED)
        assertThat(harness.delivery.posts).isEmpty()
        harness.timer()
        assertThat(harness.row(key).state).isEqualTo(DecisionState.DELIVERED)
    }

    @Test
    fun `D-4 responses to a DECIDED row or an undisplayed card are rejected, a card of a deleted rule is cancelled`() = runTest {
        val decided = harness()
        decided.death.arm(CrashPoint.AFTER_COMMIT)
        expectCrash { decided.engine.runTimer() }
        decided.restart()
        val report = decided.engine.recordResponse(key, decided.row(key).nonce!!, JitaiResponse.OPENED).getOrThrow()
        assertThat(report.status).isEqualTo(ResponseStatus.NOT_DISPLAYED)
        assertThat(decided.row(key).content.response).isEqualTo(JitaiResponse.NONE)

        val card = harness()
        card.settings.settings = F0.SETTINGS.copy(inAppCards = true)
        card.delivery.setPrerequisite(JitaiCategory.PHYSICAL_ACTIVITY, DeliveryPrerequisite(false, false, false))
        card.delivery.onPrepare =
            { card.delivery.setPrerequisite(JitaiCategory.PHYSICAL_ACTIVITY, DeliveryPrerequisite(false, false, false)) }
        card.delivery.setPrerequisite(JitaiCategory.PHYSICAL_ACTIVITY, DeliveryPrerequisite.MET)
        card.timer()
        assertThat(card.row(key).state).isEqualTo(DecisionState.CARD_PENDING)
        val pending = card.engine.recordResponse(key, card.row(key).nonce!!, JitaiResponse.OPENED).getOrThrow()
        assertThat(pending.status).isEqualTo(ResponseStatus.NOT_DISPLAYED)

        card.repository.remove("R2")
        card.engine.markCardDisplayed(key).getOrThrow()
        assertThat(card.row(key).state).isEqualTo(DecisionState.CANCELLED)
    }

    @Test
    fun `E-1 a DND deferral stays inside the active window and ends SUPPRESSED(DND), not MISSED`() = runTest {
        val windowed = Rules.R2.copy(activeWindow = ActiveWindow("16:00", "17:15"))
        val harness = harness(windowed)
        harness.settings.notifications = NotificationSystemState(interruptionFilter = InterruptionFilter.NONE)

        harness.runTimerUntil(F0.local("2026-10-01T18:00"))

        assertThat(harness.row(key).state).isEqualTo(DecisionState.SUPPRESSED)
        assertThat(harness.row(key).reason).isEqualTo(ReasonCode.DND)
    }
}
