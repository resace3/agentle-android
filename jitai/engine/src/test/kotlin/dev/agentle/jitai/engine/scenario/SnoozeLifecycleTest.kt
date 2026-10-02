package dev.agentle.jitai.engine.scenario

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.SnoozeMode
import dev.agentle.jitai.dsl.model.SnoozeOption
import dev.agentle.jitai.dsl.model.SnoozePolicy
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.Leaves
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.decision.DecisionKeys
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.JitaiResponse
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.decision.TriggerKind
import dev.agentle.jitai.engine.gates.Backoff
import dev.agentle.jitai.engine.int
import dev.agentle.jitai.engine.pipeline.DeliveryResult
import dev.agentle.jitai.engine.pipeline.TraceCodec
import dev.agentle.jitai.engine.response.ResponseStatus
import dev.agentle.jitai.engine.schedule.SchedulePlanner
import dev.agentle.jitai.engine.schedule.WorkInput
import dev.agentle.jitai.engine.schedule.WorkPolicy
import dev.agentle.jitai.engine.seedCounted
import dev.agentle.jitai.engine.testing.CrashPoint
import dev.agentle.jitai.engine.testing.EngineHarness
import dev.agentle.jitai.engine.testing.expectCrash
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** R10 §12.N: snooze, pause, disable, expiry and engagement backoff. */
class SnoozeLifecycleTest {
    private val slot8 = "v1|R1|I|2026-10-01|8"

    /** R1 without the `local_time >= 22:00` leaf, so slots after midnight reach the gates. */
    private val screenOnly = Rules.R1.copy(conditions = Leaves.gte(Leaves.SCREEN, 45))

    private fun deliveredAt2200(definition: dev.agentle.jitai.dsl.model.JitaiDefinition = Rules.R1): EngineHarness =
        runTestHarness(definition)

    private fun runTestHarness(definition: dev.agentle.jitai.dsl.model.JitaiDefinition): EngineHarness {
        val harness = F0.harness(F0.local("2026-10-01T22:00"), definition)
        harness.features.set(Leaves.SCREEN, int(50, F0.local("2026-10-01T22:00")))
        return harness
    }

    private suspend fun EngineHarness.deliverSlot8(): String {
        engine.runTick().getOrThrow()
        val row = store.row(slot8)!!
        assertThat(row.state).isEqualTo(DecisionState.DELIVERED)
        return row.nonce!!
    }

    @Test
    fun `N1 snooze 60 min at 22_05 - 23_00 SUPPRESSED(SNOOZED), 23_15 DELIVERED`() = runTest {
        val harness = deliveredAt2200()
        val nonce = harness.deliverSlot8()

        harness.clock.advanceTo(F0.local("2026-10-01T22:05"))
        val response = harness.engine.recordResponse(slot8, nonce, JitaiResponse.SNOOZED, SnoozeOption.MINUTES_60).getOrThrow()
        assertThat(response.status).isEqualTo(ResponseStatus.RECORDED)
        assertThat(response.snoozedUntil!!.wall).isEqualTo(F0.local("2026-10-01T23:05"))
        assertThat(response.followUp).isNull()

        harness.clock.advanceTo(F0.local("2026-10-01T23:00"))
        harness.engine.runTick().getOrThrow()
        assertThat(harness.store.row("v1|R1|I|2026-10-01|12")!!.reason).isEqualTo(ReasonCode.SNOOZED)

        harness.clock.advanceTo(F0.local("2026-10-01T23:15"))
        harness.engine.runTick().getOrThrow()
        assertThat(harness.store.row("v1|R1|I|2026-10-01|13")!!.state).isEqualTo(DecisionState.DELIVERED)
        assertThat(harness.store.row(slot8)!!.content.response).isEqualTo(JitaiResponse.SNOOZED)
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "N2 until tomorrow, UNTIL_TOMORROW, 2026-10-02T04:00",
        "N3 until window end, UNTIL_WINDOW_END, 2026-10-02T02:00",
        "snooze 30 min, MINUTES_30, 2026-10-01T22:35",
        "snooze 120 min, MINUTES_120, 2026-10-02T00:05",
    )
    fun `N2 N3 snooze options tapped at 22_05 end where R10 9_5 says`(id: String, option: SnoozeOption, until: String) = runTest {
        val harness = deliveredAt2200()
        val nonce = harness.deliverSlot8()
        harness.clock.advanceTo(F0.local("2026-10-01T22:05"))

        val response = harness.engine.recordResponse(slot8, nonce, JitaiResponse.SNOOZED, option).getOrThrow()

        assertThat(response.snoozedUntil!!.wall).isEqualTo(F0.local(until))
        assertThat(harness.store.runtimeOf("R1").snoozedUntil!!.wall).isEqualTo(F0.local(until))
    }

    @Test
    fun `N3 until window end outside the window falls back to the next engine-day rollover`() = runTest {
        val harness = deliveredAt2200()
        val nonce = harness.deliverSlot8()
        harness.clock.advanceTo(F0.local("2026-10-02T03:00"))

        val response = harness.engine.recordResponse(slot8, nonce, JitaiResponse.SNOOZED, SnoozeOption.UNTIL_WINDOW_END).getOrThrow()

        assertThat(response.snoozedUntil!!.wall).isEqualTo(F0.local("2026-10-02T04:00"))
    }

    @Test
    fun `N4 30 min then 60 min within one second keeps 23_05 and records SNOOZED once`() = runTest {
        val harness = deliveredAt2200()
        val nonce = harness.deliverSlot8()
        harness.clock.advanceTo(F0.local("2026-10-01T22:05"))

        val first = harness.engine.recordResponse(slot8, nonce, JitaiResponse.SNOOZED, SnoozeOption.MINUTES_30).getOrThrow()
        harness.clock.advanceBy(500.milliseconds)
        val second = harness.engine.recordResponse(slot8, nonce, JitaiResponse.SNOOZED, SnoozeOption.MINUTES_60).getOrThrow()
        harness.clock.advanceBy(400.milliseconds)
        val third = harness.engine.recordResponse(slot8, nonce, JitaiResponse.SNOOZED, SnoozeOption.MINUTES_30).getOrThrow()

        assertThat(first.status).isEqualTo(ResponseStatus.RECORDED)
        assertThat(second.status).isEqualTo(ResponseStatus.ALREADY_RESPONDED)
        assertThat(third.snoozedUntil!!.wall).isEqualTo(F0.local("2026-10-01T23:05") + 500.milliseconds)
        assertThat(harness.store.row(slot8)!!.content.respondedAt).isEqualTo(F0.local("2026-10-01T22:05"))
        assertThat(harness.store.responseLog().map { it.response }).containsExactly(JitaiResponse.SNOOZED, JitaiResponse.SNOOZED)
    }

    @Test
    fun `N5 RE_EVALUATE_AFTER schedules one R decision at 22_35 that skips G09-G11 and passes G12`() = runTest {
        val r1 = Rules.R1.copy(snooze = SnoozePolicy(SnoozeMode.RE_EVALUATE_AFTER, listOf(SnoozeOption.MINUTES_30)))
        val harness = deliveredAt2200(r1)
        val nonce = harness.deliverSlot8()
        harness.clock.advanceTo(F0.local("2026-10-01T22:05"))

        val response = harness.engine.recordResponse(slot8, nonce, JitaiResponse.SNOOZED, SnoozeOption.MINUTES_30).getOrThrow()
        val work = response.followUp!!
        assertThat(work.runAt).isEqualTo(F0.local("2026-10-01T22:35"))
        assertThat(work.input).isEqualTo(WorkInput.SnoozeFollowUp("R1", slot8))
        assertThat(work.policy).isEqualTo(WorkPolicy.REPLACE)
        assertThat(work.tags).containsExactly(SchedulePlanner.jitaiTag("R1"))

        // Too early: re-planned, nothing decided.
        harness.clock.advanceTo(F0.local("2026-10-01T22:20"))
        val early = harness.engine.runSnoozeFollowUp(slot8).getOrThrow()!!
        assertThat(early.written).isEmpty()
        assertThat(early.followUp.single().runAt).isEqualTo(F0.local("2026-10-01T22:35"))

        harness.clock.advanceTo(F0.local("2026-10-01T22:35"))
        val pass = harness.engine.runSnoozeFollowUp(slot8).getOrThrow()!!
        val key = DecisionKeys.snoozeFollowUp("R1", slot8)
        assertThat(key).matches("v1\\|R1\\|R\\|[0-9a-f]{16}")
        val row = harness.store.row(key)!!
        assertThat(row.state).isEqualTo(DecisionState.DELIVERED)
        assertThat(row.triggerKind).isEqualTo(TriggerKind.SNOOZE_FOLLOW_UP)
        assertThat(pass.stateOf(key)).isEqualTo(DecisionState.DELIVERED)
        val gates = TraceCodec.decode(row.content.traceJson!!)!!.gates!!.associateBy { it.gate }
        assertThat(gates.getValue(ReasonCode.COOLDOWN).detail).isEqualTo("skipped:snooze_follow_up")
        assertThat(gates.getValue(ReasonCode.DAILY_CAP).detail).isEqualTo("skipped:snooze_follow_up")
        assertThat(gates.getValue(ReasonCode.WEEKLY_CAP).detail).isEqualTo("skipped:snooze_follow_up")
        assertThat(gates.getValue(ReasonCode.GLOBAL_MIN_GAP).passed).isTrue()

        // Snoozing the follow-up creates no further R decision.
        val again = harness.engine.recordResponse(key, row.nonce!!, JitaiResponse.SNOOZED, SnoozeOption.MINUTES_30).getOrThrow()
        assertThat(again.followUp).isNull()
        assertThat(harness.engine.runSnoozeFollowUp(key).getOrThrow()).isNull()
        // A second run of the same follow-up is a no-op (key exists).
        assertThat(harness.engine.runSnoozeFollowUp(slot8).getOrThrow()!!.written).isEmpty()
    }

    @Test
    fun `N5 a follow-up outside the window logs OUTSIDE_WINDOW and decides nothing`() = runTest {
        val r1 = Rules.R1.copy(snooze = SnoozePolicy(SnoozeMode.RE_EVALUATE_AFTER, listOf(SnoozeOption.UNTIL_WINDOW_END)))
        val harness = deliveredAt2200(r1)
        val nonce = harness.deliverSlot8()
        harness.clock.advanceTo(F0.local("2026-10-01T22:05"))
        val response = harness.engine.recordResponse(slot8, nonce, JitaiResponse.SNOOZED, SnoozeOption.UNTIL_WINDOW_END).getOrThrow()
        assertThat(response.followUp!!.runAt).isEqualTo(F0.local("2026-10-02T02:00"))

        harness.clock.advanceTo(F0.local("2026-10-02T02:00"))
        val pass = harness.engine.runSnoozeFollowUp(slot8).getOrThrow()!!

        assertThat(pass.written).isEmpty()
        assertThat(pass.evalLog.single().reason).isEqualTo(ReasonCode.OUTSIDE_WINDOW)
    }

    @Test
    fun `N6 disabling while a row is DECIDED and unclaimed cancels it - no notification`() = runTest {
        val harness = F0.harness(F0.local("2026-10-01T22:30"), Rules.R1)
        harness.features.set(Leaves.SCREEN, int(50, F0.local("2026-10-01T22:30")))
        harness.store.crashAt = CrashPoint.AFTER_COMMIT
        assertThat(expectCrash { harness.engine.runTick() }.point).isEqualTo(CrashPoint.AFTER_COMMIT)
        assertThat(harness.store.row("v1|R1|I|2026-10-01|10")!!.state).isEqualTo(DecisionState.DECIDED)

        harness.repository.update("R1") { it.copy(enabled = false) }
        val change = harness.restart().onDefinitionChanged("R1").getOrThrow()

        assertThat(
            change.cancelled,
        ).containsExactly(DeliveryResult.Ended("v1|R1|I|2026-10-01|10", DecisionState.CANCELLED, ReasonCode.JITAI_DISABLED))
        assertThat(change.plan.plan.cancelTags).containsExactly(SchedulePlanner.jitaiTag("R1"))
        assertThat(harness.store.row("v1|R1|I|2026-10-01|10")!!.state).isEqualTo(DecisionState.CANCELLED)
        assertThat(harness.delivery.posts).isEmpty()
    }

    @Test
    fun `N6 recovery also cancels an unclaimed DECIDED row of a disabled JITAI`() = runTest {
        val harness = F0.harness(F0.local("2026-10-01T22:30"), Rules.R1)
        harness.features.set(Leaves.SCREEN, int(50, F0.local("2026-10-01T22:30")))
        harness.store.crashAt = CrashPoint.AFTER_COMMIT
        assertThat(expectCrash { harness.engine.runTick() }.point).isEqualTo(CrashPoint.AFTER_COMMIT)
        harness.repository.update("R1") { it.copy(enabled = false) }

        val recovery = harness.restart().recover().getOrThrow()

        assertThat(recovery.results.single()).isInstanceOf(DeliveryResult.Ended::class.java)
        assertThat(harness.store.row("v1|R1|I|2026-10-01|10")!!.state).isEqualTo(DecisionState.CANCELLED)
    }

    @ParameterizedTest(name = "N7 evaluated {0}")
    @CsvSource(
        "2026-10-28T22:59:59Z, 15, DELIVERED, ACTIVE",
        "2026-10-28T23:00:00Z, 16, SUPPRESSED, EXPIRED",
    )
    fun `N7 expiresAt 2026-10-28T23_00Z`(at: String, slot: Int, state: DecisionState, status: JitaiStatus) = runTest {
        val r1 = screenOnly.copy(expiresAt = Instant.parse("2026-10-28T23:00:00Z"))
        val harness = F0.harness(Instant.parse(at), r1)
        harness.features.set(Leaves.SCREEN, int(50, Instant.parse(at)))

        val report = harness.engine.runTick().getOrThrow()

        val row = harness.store.row("v1|R1|I|2026-10-28|$slot")!!
        assertThat(row.state).isEqualTo(state)
        assertThat(harness.repository["R1"]!!.status).isEqualTo(status)
        if (state == DecisionState.SUPPRESSED) {
            assertThat(row.reason).isEqualTo(ReasonCode.EXPIRED)
            assertThat(report.pass.cancelTags).contains(SchedulePlanner.jitaiTag("R1"))
        }
    }

    @Test
    fun `N7 expiry moves the status and cancels work even when the conditions are false`() = runTest {
        val r1 = Rules.R1.copy(expiresAt = Instant.parse("2026-10-28T23:00:00Z"))
        val harness = F0.harness(Instant.parse("2026-10-28T23:00:00Z"), r1)
        harness.features.set(Leaves.SCREEN, int(10, Instant.parse("2026-10-28T23:00:00Z")))

        val report = harness.engine.runTick().getOrThrow()

        assertThat(harness.store.row("v1|R1|I|2026-10-28|16")!!.state).isEqualTo(DecisionState.NOT_TRIGGERED)
        assertThat(report.pass.expired).containsExactly("R1")
        assertThat(report.pass.cancelTags).containsExactly(SchedulePlanner.jitaiTag("R1"))
        assertThat(harness.repository["R1"]!!.status).isEqualTo(JitaiStatus.EXPIRED)
    }

    @ParameterizedTest(name = "N8 {0} consecutive ignored")
    @CsvSource("0, 60", "2, 60", "3, 120", "4, 240", "5, 480", "12, 10080")
    fun `N8 effective cooldown doubles from 3 ignored and is capped at 7 days`(ignored: Int, minutes: Long) {
        assertThat(Backoff.effectiveCooldown(60.minutes, ignored)).isEqualTo(minutes.minutes)
    }

    @Test
    fun `N8 three ignored deliveries stretch R1 cooldown to 120 minutes in a pass`() = runTest {
        val harness = F0.harness(F0.local("2026-10-01T23:30"), screenOnly)
        harness.features.set(Leaves.SCREEN, int(50, F0.local("2026-10-01T23:30")))
        harness.seedCounted("R1", F0.local("2026-09-29T22:00"), response = JitaiResponse.IGNORED)
        harness.seedCounted("R1", F0.local("2026-09-30T22:00"), response = JitaiResponse.DISMISSED)
        val last = harness.seedCounted("R1", F0.local("2026-10-01T22:00"))
        val response = harness.engine.recordResponse(last.decisionKey, last.nonce!!, JitaiResponse.IGNORED).getOrThrow()
        assertThat(response.consecutiveIgnored).isEqualTo(3)

        harness.engine.runTick().getOrThrow()
        val suppressed = harness.store.row("v1|R1|I|2026-10-01|14")!!
        assertThat(suppressed.reason).isEqualTo(ReasonCode.COOLDOWN)
        assertThat(suppressed.reasonDetail).isEqualTo("elapsed=5400s cooldown=120m")

        harness.clock.advanceTo(F0.local("2026-10-02T00:00"))
        harness.engine.runTick().getOrThrow()
        assertThat(harness.store.row("v1|R1|I|2026-10-01|16")!!.state).isEqualTo(DecisionState.DELIVERED)
    }

    @Test
    fun `N9 five consecutive ignored pause the JITAI and ask in-app`() = runTest {
        val harness = F0.harness(F0.local("2026-10-01T23:00"), Rules.R1)
        (24..27).forEach { day -> harness.seedCounted("R1", F0.local("2026-09-${day}T22:00"), response = JitaiResponse.IGNORED) }
        val last = harness.seedCounted("R1", F0.local("2026-09-28T22:00"))

        val response = harness.engine.recordResponse(last.decisionKey, last.nonce!!, JitaiResponse.IGNORED).getOrThrow()

        assertThat(response.consecutiveIgnored).isEqualTo(5)
        assertThat(response.paused).isTrue()
        assertThat(harness.repository["R1"]!!.status).isEqualTo(JitaiStatus.PAUSED)
        assertThat(harness.repository.backoffQuestions).containsExactly("R1", 5)
    }

    @Test
    fun `N9 an OPENED or HELPFUL response before the fifth resets the count to 0`() = runTest {
        val harness = F0.harness(F0.local("2026-10-01T23:00"), Rules.R1)
        (24..27).forEach { day -> harness.seedCounted("R1", F0.local("2026-09-${day}T22:00"), response = JitaiResponse.IGNORED) }
        val opened = harness.seedCounted("R1", F0.local("2026-09-28T22:00"))

        val response = harness.engine.recordResponse(opened.decisionKey, opened.nonce!!, JitaiResponse.OPENED).getOrThrow()

        assertThat(response.consecutiveIgnored).isEqualTo(0)
        assertThat(response.paused).isFalse()
        assertThat(harness.store.runtimeOf("R1").consecutiveIgnored).isEqualTo(0)
        assertThat(harness.repository["R1"]!!.status).isEqualTo(JitaiStatus.ACTIVE)
    }

    @Test
    fun `a response with a wrong nonce or for an unknown key changes nothing`() = runTest {
        val harness = deliveredAt2200()
        harness.deliverSlot8()

        val forged = harness.engine.recordResponse(slot8, "0".repeat(32), JitaiResponse.SNOOZED, SnoozeOption.UNTIL_TOMORROW).getOrThrow()
        val unknown = harness.engine.recordResponse("v1|R1|I|2026-10-01|99", "x", JitaiResponse.OPENED).getOrThrow()

        assertThat(forged.status).isEqualTo(ResponseStatus.REJECTED)
        assertThat(unknown.status).isEqualTo(ResponseStatus.NOT_FOUND)
        assertThat(harness.store.row(slot8)!!.content.response).isEqualTo(JitaiResponse.NONE)
        assertThat(harness.store.runtimeOf("R1").snoozedUntil).isNull()
    }

    @Test
    fun `sweepIgnored marks a delivery without response IGNORED after its timeout or engine day`() = runTest {
        val timed = Rules.R1.copy(id = "RT", delivery = Rules.R1.delivery.copy(notificationTimeoutMinutes = 30))
        val harness = F0.harness(F0.local("2026-10-01T22:00"), Rules.R1, timed)
        val r1 = harness.seedCounted("R1", F0.local("2026-10-01T21:00"))
        val rt = harness.seedCounted("RT", F0.local("2026-10-01T21:00"))

        harness.clock.advanceTo(F0.local("2026-10-01T22:00"))
        assertThat(harness.engine.sweepIgnored().getOrThrow()).containsExactly(rt.decisionKey)

        harness.clock.advanceTo(F0.local("2026-10-02T04:00"))
        assertThat(harness.engine.sweepIgnored().getOrThrow()).containsExactly(r1.decisionKey)
        assertThat(harness.store.row(r1.decisionKey)!!.content.response).isEqualTo(JitaiResponse.IGNORED)
        assertThat(harness.store.runtimeOf("R1").consecutiveIgnored).isEqualTo(1)
    }
}
