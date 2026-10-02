package dev.agentle.jitai.engine.scenario

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.SnoozeMode
import dev.agentle.jitai.dsl.model.SnoozeOption
import dev.agentle.jitai.dsl.model.SnoozePolicy
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.Leaves
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.Vector
import dev.agentle.jitai.engine.decision.DecisionKeys
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.JitaiResponse
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.decision.TriggerKind
import dev.agentle.jitai.engine.gates.Backoff
import dev.agentle.jitai.engine.int
import dev.agentle.jitai.engine.pipeline.DeliveryResult
import dev.agentle.jitai.engine.response.ResponseStatus
import dev.agentle.jitai.engine.row
import dev.agentle.jitai.engine.schedule.TimerKeys
import dev.agentle.jitai.engine.schedule.TimerKind
import dev.agentle.jitai.engine.seedCounted
import dev.agentle.jitai.engine.testing.CrashPoint
import dev.agentle.jitai.engine.testing.EngineHarness
import dev.agentle.jitai.engine.testing.expectCrash
import dev.agentle.jitai.engine.timer
import dev.agentle.jitai.engine.timerAt
import dev.agentle.jitai.engine.trace
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** R10 §12.N through the engine: snooze, pause, disable, expiry and engagement backoff (with jitai-correctness-13/14). */
class SnoozeLifecycleTest {
    @ParameterizedTest(quoteTextArguments = false, name = "{0}")
    @MethodSource("vectors")
    fun `R10 12_N snooze, pause, disable, expiry, backoff`(vector: Vector) = runTest { vector.body(this) }

    @ParameterizedTest(quoteTextArguments = false, name = "R10 N8 cooldown 60 with {0} consecutive ignored: {1} min")
    @CsvSource("0, 60", "2, 60", "3, 120", "4, 240", "5, 480", "12, 10080")
    fun `R10 N8 the effective cooldown doubles from 3 ignored and is capped at 7 days`(ignored: Int, minutes: Long) {
        assertThat(Backoff.effectiveCooldown(60.minutes, ignored)).isEqualTo(minutes.minutes)
    }

    @Test
    fun `a response with a wrong nonce or for an unknown key changes nothing`() = runTest {
        val harness = deliveredAt2200()

        val forged = harness.engine.recordResponse(SLOT_8, "0".repeat(32), JitaiResponse.SNOOZED, SnoozeOption.UNTIL_TOMORROW).getOrThrow()
        val unknown = harness.engine.recordResponse("v1|R1|I|2026-10-01|99", "x", JitaiResponse.OPENED).getOrThrow()

        assertThat(forged.status).isEqualTo(ResponseStatus.REJECTED)
        assertThat(unknown.status).isEqualTo(ResponseStatus.NOT_FOUND)
        assertThat(harness.row(SLOT_8).content.response).isEqualTo(JitaiResponse.NONE)
        assertThat(harness.store.runtimeOf("R1").snoozedUntil).isNull()
    }

    @Test
    fun `a snooze option outside the rule's policy is INVALID_OPTION and changes nothing, Not now is always allowed`() = runTest {
        val harness = deliveredAt2200()
        val nonce = harness.row(SLOT_8).nonce!!

        val invalid = harness.engine.recordResponse(SLOT_8, nonce, JitaiResponse.SNOOZED, SnoozeOption.MINUTES_30).getOrThrow()
        val missing = harness.engine.recordResponse(SLOT_8, nonce, JitaiResponse.SNOOZED, null).getOrThrow()
        assertThat(invalid.status).isEqualTo(ResponseStatus.INVALID_OPTION)
        assertThat(missing.status).isEqualTo(ResponseStatus.INVALID_OPTION)
        assertThat(harness.row(SLOT_8).content.response).isEqualTo(JitaiResponse.NONE)

        val notNow = harness.engine.recordResponse(SLOT_8, nonce, JitaiResponse.SNOOZED, SnoozeOption.UNTIL_WINDOW_END).getOrThrow()
        assertThat(notNow.status).isEqualTo(ResponseStatus.RECORDED)
        assertThat(harness.posted(SLOT_8).notNowOption).isEqualTo(SnoozeOption.UNTIL_WINDOW_END)
    }

    @Test
    fun `sweepIgnored marks a delivery without a response IGNORED after its timeout or its engine day`() = runTest {
        val timed = Rules.R1.copy(id = "RT", delivery = Rules.R1.delivery.copy(notificationTimeoutMinutes = 30))
        val harness = F0.harness(F0.local("2026-10-01T22:00"), Rules.R1, timed)
        val r1 = harness.seedCounted("R1", F0.local("2026-10-01T21:00"))
        val rt = harness.seedCounted("RT", F0.local("2026-10-01T21:00"))

        assertThat(harness.engine.sweepIgnored().getOrThrow()).containsExactly(rt.decisionKey)
        harness.clock.advanceTo(F0.local("2026-10-02T04:00"))

        assertThat(harness.engine.sweepIgnored().getOrThrow()).containsExactly(r1.decisionKey)
        assertThat(harness.row(r1.decisionKey).content.response).isEqualTo(JitaiResponse.IGNORED)
        assertThat(harness.store.runtimeOf("R1").consecutiveIgnored).isEqualTo(1)
    }

    companion object {
        private const val SLOT_8 = "v1|R1|I|2026-10-01|8"

        /** R1 without its `local_time >= 22:00` leaf, so slots after midnight reach the gates. */
        private val screenOnly: JitaiDefinition = Rules.R1.copy(conditions = Leaves.gte(Leaves.SCREEN, 45))

        private fun slot(index: Int, date: String = "2026-10-01") = "v1|R1|I|$date|$index"

        /** R1 (or [definition]) delivered at 22:00 (slot 8), screen 50 from then on. */
        private suspend fun deliveredAt2200(definition: JitaiDefinition = Rules.R1): EngineHarness {
            val harness = F0.harness(F0.local("2026-10-01T22:00"), definition)
            harness.features.set(Leaves.SCREEN, int(50, F0.local("2026-10-01T22:00")))
            harness.timer()
            assertThat(harness.row(SLOT_8).state).isEqualTo(DecisionState.DELIVERED)
            return harness
        }

        private fun EngineHarness.posted(key: String) = delivery.posts.single { it.decisionKey == key }

        /** "[option]" tapped on the 22:00 delivery at 22:05 ends the snooze at [until]. */
        private fun snoozeEnd(id: String, title: String, option: SnoozeOption, until: String, definition: JitaiDefinition = Rules.R1) =
            Vector(id, title) {
                val harness = deliveredAt2200(definition)
                harness.clock.advanceTo(F0.local("2026-10-01T22:05"))

                val response = harness.engine.recordResponse(
                    SLOT_8,
                    harness.row(SLOT_8).nonce!!,
                    JitaiResponse.SNOOZED,
                    option,
                ).getOrThrow()

                assertWithMessage(id).that(response.status).isEqualTo(ResponseStatus.RECORDED)
                assertWithMessage(id).that(response.snoozedUntil!!.wall).isEqualTo(F0.local(until))
                assertThat(harness.store.runtimeOf("R1").snoozedUntil!!.wall).isEqualTo(F0.local(until))
                assertThat(response.followUpAt).isNull()
            }

        /** An unclaimed DECIDED row of R1 at 22:30 (the process died right after the commit); R1 is disabled in a new process. */
        private suspend fun decidedThenDisabled(): EngineHarness {
            val harness = F0.harness(F0.local("2026-10-01T22:30"), Rules.R1)
            harness.features.set(Leaves.SCREEN, int(50, F0.local("2026-10-01T22:30")))
            harness.death.arm(CrashPoint.AFTER_COMMIT)
            assertThat(expectCrash { harness.engine.runTimer() }.point).isEqualTo(CrashPoint.AFTER_COMMIT)
            assertThat(harness.row(slot(10)).state).isEqualTo(DecisionState.DECIDED)
            harness.restart()
            harness.repository.update("R1") { it.copy(enabled = false) }
            return harness
        }

        /** Four earlier deliveries of R1 ignored on 09-24..09-27 and a fifth on 09-28 that gets [response] now. */
        private suspend fun fourIgnoredThen(
            response: JitaiResponse,
        ): Pair<EngineHarness, dev.agentle.jitai.engine.response.ResponseReport> {
            val harness = F0.harness(F0.local("2026-10-01T23:00"), Rules.R1)
            (24..27).forEach { day -> harness.seedCounted("R1", F0.local("2026-09-${day}T22:00"), response = JitaiResponse.IGNORED) }
            val last = harness.seedCounted("R1", F0.local("2026-09-28T22:00"))
            return harness to harness.engine.recordResponse(last.decisionKey, last.nonce!!, response).getOrThrow()
        }

        @JvmStatic
        @Suppress("LongMethod") // One table: every row of R10 §12.N.
        fun vectors(): List<Vector> = listOf(
            Vector("N1", "R1 delivered 22:00, Snooze 60 min at 22:05: snoozedUntil 23:05; 23:00 SUPPRESSED(SNOOZED), 23:15 DELIVERED") {
                val harness = deliveredAt2200()
                harness.clock.advanceTo(F0.local("2026-10-01T22:05"))
                val response =
                    harness.engine.recordResponse(
                        SLOT_8,
                        harness.row(SLOT_8).nonce!!,
                        JitaiResponse.SNOOZED,
                        SnoozeOption.MINUTES_60,
                    ).getOrThrow()
                assertThat(response.snoozedUntil!!.wall).isEqualTo(F0.local("2026-10-01T23:05"))
                // SUPPRESS_ONLY (the interval default): no follow-up.
                assertThat(response.followUpAt).isNull()

                harness.timerAt(F0.local("2026-10-01T23:00"))
                assertThat(harness.row(slot(12)).reason).isEqualTo(ReasonCode.SNOOZED)
                harness.timerAt(F0.local("2026-10-01T23:15"))

                assertThat(harness.row(slot(13)).state).isEqualTo(DecisionState.DELIVERED)
                assertThat(harness.row(SLOT_8).content.response).isEqualTo(JitaiResponse.SNOOZED)
            },
            snoozeEnd(
                "N2",
                "Until tomorrow at 22:05: 04:00 is outside the window, so it ends at the next window start 10-02 20:00",
                SnoozeOption.UNTIL_TOMORROW,
                "2026-10-02T20:00",
            ),
            Vector("N2", "Until tomorrow at 22:05 on a rule without an active window (R2): snoozedUntil 2026-10-02 04:00") {
                val harness = F0.harness(F0.local("2026-10-01T17:00"), Rules.R2)
                harness.features.set(Leaves.STEPS, int(2_000, F0.local("2026-10-01T16:50")))
                harness.timer()
                val key = "v1|R2|D|2026-10-01|17:00"
                harness.clock.advanceTo(F0.local("2026-10-01T22:05"))

                val response =
                    harness.engine.recordResponse(
                        key,
                        harness.row(key).nonce!!,
                        JitaiResponse.SNOOZED,
                        SnoozeOption.UNTIL_TOMORROW,
                    ).getOrThrow()

                assertThat(response.snoozedUntil!!.wall).isEqualTo(F0.local("2026-10-02T04:00"))
                // A day-long snooze only suppresses: no follow-up, even under the daily_at default RE_EVALUATE_AFTER.
                assertThat(response.followUpAt).isNull()
            },
            snoozeEnd(
                "N3",
                "Until window end (Not now) at 22:05, window 20:00-02:00: snoozedUntil 2026-10-02 02:00",
                SnoozeOption.UNTIL_WINDOW_END,
                "2026-10-02T02:00",
            ),
            Vector("N4", "30 min then 60 min within one second: snoozedUntil 23:05 (the later value), SNOOZED recorded once") {
                val policy = SnoozePolicy(SnoozeMode.SUPPRESS_ONLY, listOf(SnoozeOption.MINUTES_30, SnoozeOption.MINUTES_60))
                val harness = deliveredAt2200(Rules.R1.copy(snooze = policy))
                val nonce = harness.row(SLOT_8).nonce!!
                harness.clock.advanceTo(F0.local("2026-10-01T22:05"))

                val first = harness.engine.recordResponse(SLOT_8, nonce, JitaiResponse.SNOOZED, SnoozeOption.MINUTES_30).getOrThrow()
                harness.clock.advanceBy(500.milliseconds)
                val second = harness.engine.recordResponse(SLOT_8, nonce, JitaiResponse.SNOOZED, SnoozeOption.MINUTES_60).getOrThrow()
                harness.clock.advanceBy(400.milliseconds)
                val third = harness.engine.recordResponse(SLOT_8, nonce, JitaiResponse.SNOOZED, SnoozeOption.MINUTES_30).getOrThrow()

                assertThat(first.status).isEqualTo(ResponseStatus.RECORDED)
                assertThat(second.status).isEqualTo(ResponseStatus.ALREADY_RESPONDED)
                assertThat(third.snoozedUntil!!.wall).isEqualTo(F0.local("2026-10-01T23:05") + 500.milliseconds)
                assertThat(harness.row(SLOT_8).content.respondedAt).isEqualTo(F0.local("2026-10-01T22:05"))
                assertThat(harness.store.responseLog().map { it.response }).containsExactly(JitaiResponse.SNOOZED, JitaiResponse.SNOOZED)
            },
            Vector("N5", "RE_EVALUATE_AFTER, 30 min at 22:05: one R decision at 22:35 that skips G09-G11 and passes G12; no second R") {
                val policy = SnoozePolicy(SnoozeMode.RE_EVALUATE_AFTER, listOf(SnoozeOption.MINUTES_30))
                val harness = deliveredAt2200(Rules.R1.copy(snooze = policy))
                harness.clock.advanceTo(F0.local("2026-10-01T22:05"))
                val response =
                    harness.engine.recordResponse(
                        SLOT_8,
                        harness.row(SLOT_8).nonce!!,
                        JitaiResponse.SNOOZED,
                        SnoozeOption.MINUTES_30,
                    ).getOrThrow()
                val key = DecisionKeys.snoozeFollowUp("R1", SLOT_8)
                assertThat(key).matches("v1\\|R1\\|R\\|[0-9a-f]{16}")
                assertThat(response.followUpAt).isEqualTo(F0.local("2026-10-01T22:35"))
                assertThat(harness.store.timer(TimerKeys.snooze(key))!!.dueAt).isEqualTo(F0.local("2026-10-01T22:35"))

                harness.runTimerUntil(F0.local("2026-10-01T22:35"))

                val row = harness.row(key)
                assertThat(row.state).isEqualTo(DecisionState.DELIVERED)
                assertThat(row.triggerKind).isEqualTo(TriggerKind.SNOOZE_FOLLOW_UP)
                assertThat(row.decisionPointAt).isEqualTo(F0.local("2026-10-01T22:35"))
                val gates = harness.trace(key).gates!!.associateBy { it.gate }
                listOf(ReasonCode.COOLDOWN, ReasonCode.DAILY_CAP, ReasonCode.WEEKLY_CAP).forEach { gate ->
                    assertWithMessage(gate.name).that(gates.getValue(gate).detail).isEqualTo("skipped:snooze_follow_up")
                }
                assertThat(gates.getValue(ReasonCode.GLOBAL_MIN_GAP).passed).isTrue()
                // Snoozing the follow-up plans no further R decision.
                val again = harness.engine.recordResponse(key, row.nonce!!, JitaiResponse.SNOOZED, SnoozeOption.MINUTES_30).getOrThrow()
                assertThat(again.followUpAt).isNull()
                assertThat(harness.store.timerRows().filter { it.kind == TimerKind.SNOOZE }).isEmpty()
                assertThat(harness.store.rows().count { it.triggerKind == TriggerKind.SNOOZE_FOLLOW_UP }).isEqualTo(1)
            },
            Vector("N6", "disabled while a row is DECIDED and unclaimed: the definition change cancels it, no notification") {
                val harness = decidedThenDisabled()

                val change = harness.engine.onDefinitionChanged("R1").getOrThrow()

                assertThat(
                    change.cancelled,
                ).containsExactly(DeliveryResult.Ended(slot(10), DecisionState.CANCELLED, ReasonCode.JITAI_DISABLED))
                assertThat(harness.row(slot(10)).state).isEqualTo(DecisionState.CANCELLED)
                assertThat(harness.delivery.posts).isEmpty()
                assertThat(harness.store.timerRows().filter { it.jitaiId == "R1" }).isEmpty()
            },
            Vector("N6", "disabled while a row is DECIDED and unclaimed: recovery cancels it as well, no notification") {
                val harness = decidedThenDisabled()

                val recovery = harness.engine.recover().getOrThrow()

                assertThat(
                    recovery.results,
                ).containsExactly(DeliveryResult.Ended(slot(10), DecisionState.CANCELLED, ReasonCode.JITAI_DISABLED))
                assertThat(harness.delivery.posts).isEmpty()
            },
            Vector("N7", "expiresAt 2026-10-28T23:00:00Z, evaluated 22:59:59Z: slot 15 passes, status ACTIVE") {
                val r1 = screenOnly.copy(expiresAt = Instant.parse("2026-10-28T23:00:00Z"))
                val harness = F0.harness(Instant.parse("2026-10-28T22:59:59Z"), r1)
                harness.features.set(Leaves.SCREEN, int(50, Instant.parse("2026-10-28T22:59:59Z")))

                harness.timer()

                assertThat(harness.row(slot(15, "2026-10-28")).state).isEqualTo(DecisionState.DELIVERED)
                assertThat(harness.repository["R1"]!!.status).isEqualTo(JitaiStatus.ACTIVE)
            },
            Vector("N7", "expiresAt 2026-10-28T23:00:00Z, evaluated 23:00:00Z: SUPPRESSED(EXPIRED), status EXPIRED, no timer rows left") {
                val r1 = screenOnly.copy(expiresAt = Instant.parse("2026-10-28T23:00:00Z"))
                val harness = F0.harness(Instant.parse("2026-10-28T23:00:00Z"), r1)
                harness.features.set(Leaves.SCREEN, int(50, Instant.parse("2026-10-28T23:00:00Z")))

                val report = harness.timer()

                assertThat(harness.row(slot(16, "2026-10-28")).reason).isEqualTo(ReasonCode.EXPIRED)
                assertThat(harness.repository["R1"]!!.status).isEqualTo(JitaiStatus.EXPIRED)
                assertThat(report.pass!!.expired).containsExactly("R1")
                assertThat(harness.store.timerRows().filter { it.jitaiId == "R1" }).isEmpty()
                assertThat(report.nextDueAt).isNull()
            },
            Vector("N7", "expiry moves the status even when the conditions are false at the last point") {
                val r1 = Rules.R1.copy(expiresAt = Instant.parse("2026-10-28T23:00:00Z"))
                val harness = F0.harness(Instant.parse("2026-10-28T23:00:00Z"), r1)
                harness.features.set(Leaves.SCREEN, int(10, Instant.parse("2026-10-28T23:00:00Z")))

                val report = harness.timer()

                assertThat(harness.row(slot(16, "2026-10-28")).state).isEqualTo(DecisionState.NOT_TRIGGERED)
                assertThat(report.pass!!.expired).containsExactly("R1")
                assertThat(harness.repository["R1"]!!.status).isEqualTo(JitaiStatus.EXPIRED)
            },
            Vector("N8", "cooldown 60, 3 consecutive ignored-or-dismissed: 23:30 SUPPRESSED(COOLDOWN, 120 min), 00:00 DELIVERED") {
                val harness = F0.harness(F0.local("2026-10-01T23:30"), screenOnly)
                harness.features.set(Leaves.SCREEN, int(50, F0.local("2026-10-01T23:30")))
                harness.seedCounted("R1", F0.local("2026-09-29T22:00"), response = JitaiResponse.IGNORED)
                harness.seedCounted("R1", F0.local("2026-09-30T22:00"), response = JitaiResponse.DISMISSED)
                val last = harness.seedCounted("R1", F0.local("2026-10-01T22:00"))
                val response = harness.engine.recordResponse(last.decisionKey, last.nonce!!, JitaiResponse.IGNORED).getOrThrow()
                assertThat(response.consecutiveIgnored).isEqualTo(3)

                harness.timer()
                val suppressed = harness.row(slot(14))
                assertThat(suppressed.reason).isEqualTo(ReasonCode.COOLDOWN)
                assertThat(suppressed.reasonDetail).isEqualTo("elapsed=5400s cooldown=120m")
                harness.timerAt(F0.local("2026-10-02T00:00"))

                assertThat(harness.row(slot(16)).state).isEqualTo(DecisionState.DELIVERED)
            },
            Vector("N9", "5 consecutive ignored: status PAUSED and the in-app question") {
                val (harness, response) = fourIgnoredThen(JitaiResponse.IGNORED)

                assertThat(response.consecutiveIgnored).isEqualTo(5)
                assertThat(response.paused).isTrue()
                assertThat(harness.repository["R1"]!!.status).isEqualTo(JitaiStatus.PAUSED)
                assertThat(harness.repository.backoffQuestions).containsExactly("R1", 5)
                assertThat(harness.store.timerRows().filter { it.jitaiId == "R1" }).isEmpty()
            },
            Vector("N9", "an OPENED response before the fifth resets the count to 0") {
                val (harness, response) = fourIgnoredThen(JitaiResponse.OPENED)

                assertThat(response.consecutiveIgnored).isEqualTo(0)
                assertThat(response.paused).isFalse()
                assertThat(harness.store.runtimeOf("R1").consecutiveIgnored).isEqualTo(0)
                assertThat(harness.repository["R1"]!!.status).isEqualTo(JitaiStatus.ACTIVE)
            },
            Vector("N9", "a HELPFUL response before the fifth resets the count to 0") {
                val (harness, response) = fourIgnoredThen(JitaiResponse.HELPFUL)

                assertThat(response.consecutiveIgnored).isEqualTo(0)
                assertThat(harness.repository["R1"]!!.status).isEqualTo(JitaiStatus.ACTIVE)
            },
        )
    }
}
