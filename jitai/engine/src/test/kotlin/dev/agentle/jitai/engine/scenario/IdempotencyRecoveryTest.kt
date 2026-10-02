package dev.agentle.jitai.engine.scenario

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiEventType
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.Leaves
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.bool
import dev.agentle.jitai.engine.decision.DecisionKeys
import dev.agentle.jitai.engine.decision.DecisionRecord
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.int
import dev.agentle.jitai.engine.pipeline.DeliveryResult
import dev.agentle.jitai.engine.pipeline.TraceCodec
import dev.agentle.jitai.engine.ports.PreparedDelivery
import dev.agentle.jitai.engine.seedCounted
import dev.agentle.jitai.engine.testing.CrashPoint
import dev.agentle.jitai.engine.testing.EngineHarness
import dev.agentle.jitai.engine.testing.expectCrash
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * R10 §12.P (R1, decision at 22:30, deadline 10 min, lease 2 min): idempotency and crash recovery on the virtual clock,
 * including a crash at every protocol step and passes running concurrently on the test dispatcher.
 */
class IdempotencyRecoveryTest {
    private val slot10 = "v1|R1|I|2026-10-01|10"
    private val slot11 = "v1|R1|I|2026-10-01|11"

    /** E1: an event rule on POWER_CONNECTED with no conditions, so a tick and an event pass compete. */
    private val e1 = Rules.rule("E1", Trigger.Event(listOf(JitaiEventType.POWER_CONNECTED)), category = JitaiCategory.STRESS_BREAK)

    private fun harness(vararg extra: dev.agentle.jitai.dsl.model.JitaiDefinition): EngineHarness {
        val harness = F0.harness(F0.local("2026-10-01T22:30"), Rules.R1, *extra)
        harness.features.set(Leaves.SCREEN, int(50, F0.local("2026-10-01T22:30")))
        harness.features.set("charging", bool(true, F0.local("2026-10-01T22:30")))
        return harness
    }

    private fun EngineHarness.row(key: String): DecisionRecord = store.row(key)!!

    private fun EngineHarness.counted(): List<DecisionRecord> = store.rows().filter { it.state.counted }

    @Test
    fun `R10 P1 crash before the commit - no row, a retry inside slot 10 decides again and delivers once`() = runTest {
        val harness = harness()
        harness.death.arm(CrashPoint.BEFORE_COMMIT)

        assertThat(expectCrash { harness.engine.runTimer() }.point).isEqualTo(CrashPoint.BEFORE_COMMIT)
        assertThat(harness.store.rows()).isEmpty()

        harness.clock.advanceTo(F0.local("2026-10-01T22:31"))
        harness.restart().runTimer().getOrThrow()

        assertThat(harness.row(slot10).state).isEqualTo(DecisionState.DELIVERED)
        assertThat(harness.delivery.posts).hasSize(1)
    }

    @Test
    fun `R10 P1 if the next run falls in slot 11, slot 10 is written as MISSED`() = runTest {
        val harness = harness()
        harness.death.arm(CrashPoint.BEFORE_COMMIT)
        expectCrash { harness.engine.runTimer() }

        harness.clock.advanceTo(F0.local("2026-10-01T22:45"))
        harness.restart().runTimer().getOrThrow()

        assertThat(harness.row(slot10).state).isEqualTo(DecisionState.MISSED)
        assertThat(harness.row(slot10).reason).isEqualTo(ReasonCode.SLOT_NOT_REACHED)
        assertThat(harness.row(slot11).state).isEqualTo(DecisionState.DELIVERED)
    }

    @Test
    fun `R10 P2 crash after the commit, recovery at 22_33 claims and posts - DELIVERED`() = runTest {
        val harness = harness()
        harness.death.arm(CrashPoint.AFTER_COMMIT)
        expectCrash { harness.engine.runTimer() }
        assertThat(harness.row(slot10).state).isEqualTo(DecisionState.DECIDED)

        harness.clock.advanceTo(F0.local("2026-10-01T22:33"))
        val recovery = harness.restart().recover().getOrThrow()

        assertThat(recovery.results).containsExactly(DeliveryResult.Delivered(slot10, recovered = false))
        assertThat(harness.row(slot10).state).isEqualTo(DecisionState.DELIVERED)
        assertThat(harness.delivery.activeTags()).containsExactly(slot10)
    }

    @Test
    fun `R10 P3 the same recovery at 22_41 is past the 10 min deadline - EXPIRED, no notification`() = runTest {
        val harness = harness()
        harness.death.arm(CrashPoint.AFTER_COMMIT)
        expectCrash { harness.engine.runTimer() }

        harness.clock.advanceTo(F0.local("2026-10-01T22:41"))
        val recovery = harness.restart().recover().getOrThrow()

        assertThat(recovery.results).containsExactly(DeliveryResult.Ended(slot10, DecisionState.EXPIRED, ReasonCode.DEADLINE_PASSED))
        assertThat(harness.delivery.posts).isEmpty()
        // EXPIRED releases its reservation: slot 11 is not held back by a cooldown.
        harness.clock.advanceTo(F0.local("2026-10-01T22:45"))
        harness.engine.runTimer().getOrThrow()
        assertThat(harness.row(slot11).state).isEqualTo(DecisionState.DELIVERED)
    }

    @Test
    fun `R10 P4 crash after the claim, no active notification after the lease - DELIVERY_UNCERTAIN, never re-posted, counted`() = runTest {
        val harness = harness()
        harness.death.arm(CrashPoint.AFTER_CLAIM)
        expectCrash { harness.engine.runTimer() }
        assertThat(harness.row(slot10).state).isEqualTo(DecisionState.DELIVERING)
        assertThat(harness.row(slot10).leaseUntil!!.wall).isEqualTo(F0.local("2026-10-01T22:32"))

        // Inside the lease the row belongs to its (dead) worker.
        harness.clock.advanceTo(F0.local("2026-10-01T22:31"))
        val early = harness.restart().recover().getOrThrow()
        assertThat(early.results).containsExactly(DeliveryResult.Skipped(slot10, DecisionState.DELIVERING))

        harness.clock.advanceTo(F0.local("2026-10-01T22:33"))
        val recovery = harness.engine.recover().getOrThrow()
        harness.clock.advanceTo(F0.local("2026-10-01T22:34"))
        harness.engine.runTimer().getOrThrow()

        assertThat(
            recovery.results,
        ).containsExactly(DeliveryResult.Ended(slot10, DecisionState.DELIVERY_UNCERTAIN, ReasonCode.NOT_FOUND_AFTER_LEASE))
        assertThat(harness.delivery.posts).isEmpty()
        harness.clock.advanceTo(F0.local("2026-10-01T22:45"))
        harness.engine.runTimer().getOrThrow()
        assertThat(harness.row(slot11).reason).isEqualTo(ReasonCode.COOLDOWN)
    }

    @Test
    fun `R10 P5 crash after posting, the notification is still active - DELIVERED, deliveredAt = claimedAt, recovered`() = runTest {
        val harness = harness()
        harness.death.arm(CrashPoint.AFTER_POST)
        assertThat(expectCrash { harness.engine.runTimer() }.point).isEqualTo(CrashPoint.AFTER_POST)

        harness.clock.advanceTo(F0.local("2026-10-01T22:33"))
        val recovery = harness.restart().recover().getOrThrow()

        val row = harness.row(slot10)
        assertThat(recovery.results).containsExactly(DeliveryResult.Delivered(slot10, recovered = true))
        assertThat(row.state).isEqualTo(DecisionState.DELIVERED)
        assertThat(row.recovered).isTrue()
        assertThat(row.delivered).isEqualTo(row.claimed)
        assertThat(harness.delivery.posts).hasSize(1)
        assertThat(harness.delivery.alerts).isEqualTo(1)
    }

    @Test
    fun `R10 P6 as P5 but the user dismissed it before recovery - DELIVERY_UNCERTAIN`() = runTest {
        val harness = harness()
        harness.death.arm(CrashPoint.AFTER_POST)
        expectCrash { harness.engine.runTimer() }
        harness.delivery.dismiss(slot10)

        harness.clock.advanceTo(F0.local("2026-10-01T22:33"))
        harness.restart().recover().getOrThrow()

        assertThat(harness.row(slot10).state).isEqualTo(DecisionState.DELIVERY_UNCERTAIN)
        assertThat(harness.delivery.posts).hasSize(1)
    }

    @Test
    fun `R10 P6 a failing tag lookup leaves the row DELIVERING for the next recovery`() = runTest {
        val harness = harness()
        harness.death.arm(CrashPoint.AFTER_POST)
        expectCrash { harness.engine.runTimer() }
        harness.delivery.isActiveFails = true

        harness.clock.advanceTo(F0.local("2026-10-01T22:33"))
        val failed = harness.restart().recover().getOrThrow()
        harness.delivery.isActiveFails = false
        val retried = harness.engine.recover().getOrThrow()

        assertThat(failed.results.single()).isInstanceOf(DeliveryResult.Error::class.java)
        assertThat(retried.results).containsExactly(DeliveryResult.Delivered(slot10, recovered = true))
    }

    @Test
    fun `R10 P7 two workers insert the same key - one row, one notification`() = runTest {
        val harness = harness()
        val other = harness.newEngine()

        val first = async { harness.engine.runTimer().getOrThrow() }
        val second = async { other.runTimer().getOrThrow() }
        val reports = listOf(first.await(), second.await())

        assertThat(reports.count { report -> report.pass?.written.orEmpty().any { it.decisionKey == slot10 } }).isEqualTo(1)
        assertThat(harness.store.rows().count { it.decisionKey == slot10 }).isEqualTo(1)
        assertThat(harness.delivery.posts).hasSize(1)
        assertThat(harness.delivery.alerts).isEqualTo(1)
    }

    @Test
    fun `R10 P8 two workers claim the same DECIDED row - one conditional update wins, one notification`() = runTest {
        val harness = harness()
        harness.death.arm(CrashPoint.AFTER_COMMIT)
        expectCrash { harness.engine.runTimer() }
        harness.clock.advanceTo(F0.local("2026-10-01T22:33"))
        val a = harness.restart()
        val b = harness.newEngine()

        val first = async { a.recover().getOrThrow() }
        val second = async { b.recover().getOrThrow() }
        val results = first.await().results + second.await().results

        assertThat(results.filterIsInstance<DeliveryResult.Delivered>()).hasSize(1)
        assertThat(results.filterIsInstance<DeliveryResult.Skipped>()).hasSize(1)
        assertThat(harness.delivery.posts).hasSize(1)
        assertThat(harness.delivery.discarded).hasSize(1)
    }

    @Test
    fun `R10 P9 notify twice with the same tag - one notification, alerted once`() = runTest {
        val harness = harness()
        harness.engine.runTimer().getOrThrow()
        val shown = harness.delivery.activeNotification(slot10)!!

        harness.delivery.post(PreparedDelivery(shown))

        assertThat(harness.delivery.activeTags()).containsExactly(slot10)
        assertThat(harness.delivery.alerts).isEqualTo(1)
    }

    @ParameterizedTest(name = "P10 tick first = {0}")
    @ValueSource(booleans = [true, false])
    fun `R10 P10 two passes in parallel with one global delivery left - one DECIDED, the other SUPPRESSED`(tickFirst: Boolean) = runTest {
        val harness = harness(e1)
        (18..20).forEach { hour -> harness.seedCounted("X", F0.local("2026-10-01T$hour:00"), key = "v1|X|E|$hour") }
        (19..20).forEach { hour -> harness.seedCounted("Y", F0.local("2026-10-01T$hour:30"), key = "v1|Y|E|$hour") }
        harness.events.emit(JitaiEventType.POWER_CONNECTED, F0.local("2026-10-01T22:29:30"))
        val events = harness.newEngine()

        val passes = if (tickFirst) {
            listOf(async { harness.engine.runTimer().getOrThrow() }, async { events.runEvents().getOrThrow() })
        } else {
            listOf(async { events.runEvents().getOrThrow() }, async { harness.engine.runTimer().getOrThrow() })
        }
        passes.forEach { it.await() }

        val r1 = harness.row(slot10)
        val ev = harness.row(DecisionKeys.event("E1", F0.local("2026-10-01T22:29:30")))
        val (winner, loser) = if (r1.state == DecisionState.DELIVERED) r1 to ev else ev to r1
        assertThat(winner.state).isEqualTo(DecisionState.DELIVERED)
        assertThat(loser.state).isEqualTo(DecisionState.SUPPRESSED)
        // G12 precedes G13, so the recorded reason is the gap; the trace shows the global daily cap failing too.
        assertThat(loser.reason).isEqualTo(ReasonCode.GLOBAL_MIN_GAP)
        val gates = TraceCodec.decode(loser.content.traceJson!!)!!.gates!!.associateBy { it.gate }
        assertThat(gates.getValue(ReasonCode.GLOBAL_DAILY_CAP).passed).isFalse()
        assertThat(gates.getValue(ReasonCode.GLOBAL_DAILY_CAP).detail).isEqualTo("count=6 limit=6")
        assertThat(harness.counted().count { it.engineDay == r1.engineDay }).isEqualTo(6)
        assertThat(harness.delivery.posts).hasSize(1)
    }

    @Test
    fun `two concurrent passes on the test dispatcher never deliver twice within the minimum gap`() = runTest {
        val harness = harness(e1)
        harness.events.emit(JitaiEventType.POWER_CONNECTED, F0.local("2026-10-01T22:29:30"))
        val events = harness.newEngine()

        val tick = async { harness.engine.runTimer().getOrThrow() }
        val pass = async { events.runEvents().getOrThrow() }
        tick.await()
        pass.await()

        val counted = harness.counted()
        assertThat(counted).hasSize(1)
        assertThat(harness.delivery.posts).hasSize(1)
        val other = harness.store.rows().single { it.state == DecisionState.SUPPRESSED }
        assertThat(other.reason).isEqualTo(ReasonCode.GLOBAL_MIN_GAP)
        assertThat(harness.store.commits).isAtLeast(2)
    }

    @Test
    fun `R10 P11 the periodic tick runs twice in one interval - the second finds the keys and is a no-op`() = runTest {
        val harness = harness()
        harness.engine.runTimer().getOrThrow()
        val rows = harness.store.rows()

        harness.clock.advanceTo(F0.local("2026-10-01T22:44:59"))
        val second = harness.engine.runTimer().getOrThrow()

        assertThat(second.pass?.written.orEmpty()).isEmpty()
        assertThat(harness.store.rows()).isEqualTo(rows)
        assertThat(harness.delivery.posts).hasSize(1)
    }

    @ParameterizedTest(name = "crash {0}")
    @EnumSource(CrashPoint::class)
    fun `a crash at every protocol step recovers without a double delivery or a lost cap`(point: CrashPoint) = runTest {
        val harness = harness()
        if (point == CrashPoint.AFTER_POST) harness.death.arm(CrashPoint.AFTER_POST) else harness.death.arm(point)
        assertThat(expectCrash { harness.engine.runTimer() }.point).isEqualTo(point)

        harness.clock.advanceTo(F0.local("2026-10-01T22:33"))
        val engine = harness.restart()
        engine.runTimer().getOrThrow()
        engine.runTimer().getOrThrow()

        val row = harness.row(slot10)
        val expected = when (point) {
            CrashPoint.AFTER_CLAIM -> DecisionState.DELIVERY_UNCERTAIN
            else -> DecisionState.DELIVERED
        }
        assertThat(row.state).isEqualTo(expected)
        assertThat(harness.delivery.posts.size).isAtMost(1)
        assertThat(harness.delivery.alerts).isAtMost(1)
        assertThat(harness.counted().map { it.decisionKey }).containsExactly(slot10)

        // The slot's budget is not lost: slot 11 at 22:45 is still in R1's cooldown.
        harness.clock.advanceTo(F0.local("2026-10-01T22:45"))
        engine.runTimer().getOrThrow()
        assertThat(harness.row(slot11).state).isEqualTo(DecisionState.SUPPRESSED)
        assertThat(harness.row(slot11).reason).isEqualTo(ReasonCode.COOLDOWN)
        assertThat(harness.delivery.posts.size).isAtMost(1)
    }

    @Test
    fun `a failed post leaves the row DELIVERING, recovery after the lease finds no notification`() = runTest {
        val harness = harness()
        harness.delivery.throwOnPost = true

        val report = harness.engine.runTimer().getOrThrow()

        assertThat(report.pass!!.deliveries.single()).isEqualTo(DeliveryResult.Error(slot10, "post:IllegalStateException"))
        assertThat(harness.row(slot10).state).isEqualTo(DecisionState.DELIVERING)
        harness.delivery.throwOnPost = false
        harness.clock.advanceTo(F0.local("2026-10-01T22:33"))
        harness.engine.recover().getOrThrow()
        assertThat(harness.row(slot10).state).isEqualTo(DecisionState.DELIVERY_UNCERTAIN)
        assertThat(harness.delivery.posts).isEmpty()
    }
}
