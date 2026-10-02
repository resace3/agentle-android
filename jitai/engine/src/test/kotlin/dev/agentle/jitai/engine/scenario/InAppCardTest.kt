package dev.agentle.jitai.engine.scenario

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.Leaves
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.int
import dev.agentle.jitai.engine.ports.DeliveryPrerequisite
import dev.agentle.jitai.engine.row
import dev.agentle.jitai.engine.testing.EngineHarness
import dev.agentle.jitai.engine.timer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/** In-app cards for the interventions team: keepAsCard exactly at CARD_PENDING, pendingCards after a restart, expiry. */
class InAppCardTest {
    private val blocked = DeliveryPrerequisite(notificationsEnabled = false, channelImportanceNone = false, notificationsPaused = false)

    private fun harness(definition: JitaiDefinition = Rules.R2, cards: Boolean = true): EngineHarness {
        val harness = F0.harness(F0.local("2026-10-01T17:00"), definition, settings = F0.SETTINGS.copy(inAppCards = cards))
        harness.features.set(Leaves.STEPS, int(2_000, F0.local("2026-10-01T16:50")))
        return harness
    }

    /** Notifications get blocked between the decision and the claim. */
    private fun EngineHarness.blockAtClaim() {
        delivery.onPrepare = { delivery.setPrerequisite(JitaiCategory.PHYSICAL_ACTIVITY, blocked) }
    }

    @Test
    fun `a row blocked at the claim becomes CARD_PENDING and keepAsCard is called once, never discard`() = runTest {
        val harness = harness()
        harness.blockAtClaim()

        harness.timer()

        assertThat(harness.row(KEY).state).isEqualTo(DecisionState.CARD_PENDING)
        assertThat(harness.delivery.keptAsCards.map { it.decisionKey }).containsExactly(KEY)
        assertThat(harness.delivery.discarded).isEmpty()
        assertThat(harness.delivery.posts).isEmpty()
    }

    @Test
    fun `a post reported Blocked with in-app cards on calls keepAsCard`() = runTest {
        val harness = harness()
        harness.delivery.blocked = true

        harness.timer()

        assertThat(harness.row(KEY).state).isEqualTo(DecisionState.CARD_PENDING)
        assertThat(harness.delivery.keptAsCards).hasSize(1)
        assertThat(harness.delivery.discarded).isEmpty()
    }

    @Test
    fun `refused or cancelled claims discard and never keep a card`() = runTest {
        val off = harness(cards = false)
        off.delivery.blocked = true
        off.timer()
        assertThat(off.row(KEY).state).isEqualTo(DecisionState.SUPPRESSED)

        val disabled = harness()
        disabled.delivery.onPrepare = { disabled.repository.update("R2") { it.copy(enabled = false) } }
        disabled.timer()
        assertThat(disabled.row(KEY).state).isEqualTo(DecisionState.CANCELLED)

        listOf(off, disabled).forEach {
            assertThat(it.delivery.keptAsCards).isEmpty()
            assertThat(it.delivery.discarded).hasSize(1)
        }
    }

    @Test
    fun `pendingCards after a restart returns the same in-app text`() = runTest {
        val harness = harness()
        harness.blockAtClaim()
        harness.timer()
        val kept = harness.delivery.keptAsCards.single()

        val card = harness.restart().pendingCards().getOrThrow().single()

        assertThat(card.decisionKey).isEqualTo(KEY)
        assertThat(card.jitaiId).isEqualTo("R2")
        assertThat(card.intervention.title).isEqualTo(kept.title)
        assertThat(card.intervention.body).isEqualTo("Only 2,000 steps so far today.")
        assertThat(card.decidedAt).isEqualTo(F0.local("2026-10-01T17:00"))
    }

    @Test
    fun `expired cards are excluded`() = runTest {
        val harness = harness(Rules.R2.copy(delivery = Rules.R2.delivery.copy(notificationTimeoutMinutes = 30)))
        harness.blockAtClaim()
        harness.timer()
        val card = harness.engine.pendingCards().getOrThrow().single()
        assertThat(card.expiresAt).isEqualTo(F0.local("2026-10-01T17:30"))

        harness.clock.advanceBy(29.minutes)
        assertThat(harness.engine.pendingCards().getOrThrow()).hasSize(1)
        harness.clock.advanceBy(1.minutes)
        assertThat(harness.engine.pendingCards().getOrThrow()).isEmpty()
    }

    private companion object {
        const val KEY = "v1|R2|D|2026-10-01|17:00"
    }
}
