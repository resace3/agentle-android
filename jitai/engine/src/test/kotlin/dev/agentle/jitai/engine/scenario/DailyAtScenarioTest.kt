package dev.agentle.jitai.engine.scenario

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.Leaves
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.Vector
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.int
import dev.agentle.jitai.engine.missing
import dev.agentle.jitai.engine.pipeline.SyncReason
import dev.agentle.jitai.engine.pipeline.SyncRequest
import dev.agentle.jitai.engine.ports.DisplaySettings
import dev.agentle.jitai.engine.ports.EvalOutcome
import dev.agentle.jitai.engine.row
import dev.agentle.jitai.engine.schedule.TimerKeys
import dev.agentle.jitai.engine.schedule.TimerKind
import dev.agentle.jitai.engine.stale
import dev.agentle.jitai.engine.testing.EngineHarness
import dev.agentle.jitai.engine.timer
import dev.agentle.jitai.engine.timerAt
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Instant

/** R10 §12.D (engine side: R2 `daily_at 17:00`, lateness 30) and the `daily_at` DST rows §12.O1-O2, through `jitai-timer`. */
class DailyAtScenarioTest {
    @ParameterizedTest(quoteTextArguments = false, name = "{0}")
    @MethodSource("vectors")
    fun `R10 12_D and 12_O daily_at`(vector: Vector) = runTest { vector.body(this) }

    companion object {
        private const val KEY = "v1|R2|D|2026-10-01|17:00"
        private val SLOT_ROW = TimerKeys.slot(KEY)
        private val AT_1631: Instant = F0.local("2026-10-01T16:31")
        private val AT_1615: Instant = F0.local("2026-10-01T16:15")

        private fun r2(steps: FeatureValue?, at: String = "2026-10-01T17:00"): EngineHarness {
            val harness = F0.harness(F0.local(at), Rules.R2)
            if (steps != null) harness.features.set(Leaves.STEPS, steps)
            return harness
        }

        /** The slot resolves at 17:00 into [expected]; a delivery posts the template filled from the stored snapshot. */
        private fun at1700(id: String, title: String, steps: FeatureValue, expected: DecisionState, body: String? = null) =
            Vector(id, title) {
                val harness = r2(steps)

                val report = harness.timer()

                assertWithMessage(id).that(harness.row(KEY).state).isEqualTo(expected)
                assertThat(report.syncRequests).isEmpty()
                assertThat(harness.store.timer(SLOT_ROW)).isNull()
                if (body == null) {
                    assertThat(harness.delivery.posts).isEmpty()
                } else {
                    val post = harness.delivery.posts.single()
                    assertThat(post.body).isEqualTo(body)
                    // Correction 10: the posted text is generic while detailed notifications are off (the default).
                    assertThat(post.postedBody).isEqualTo(DisplaySettings.DEFAULT_GENERIC_BODY)
                    assertThat(post.nonce).isEqualTo(harness.row(KEY).nonce)
                }
                // The next occurrence is planned, with its prefetch 10 minutes ahead (R2 reads remote steps).
                assertThat(harness.store.timer(TimerKeys.slot("v1|R2|D|2026-10-02|17:00"))!!.dueAt).isEqualTo(F0.local("2026-10-02T17:00"))
                assertThat(harness.store.timer(TimerKeys.prefetch("v1|R2|D|2026-10-02|17:00"))!!.dueAt)
                    .isEqualTo(F0.local("2026-10-02T16:50"))
            }

        /** U at 17:00 and 17:10 with a sync each time, then the row UNKNOWN at 17:20 without a notification. */
        private fun neverSynced(id: String, title: String, steps: FeatureValue) = Vector(id, title) {
            val harness = r2(steps)

            val first = harness.timer()
            assertThat(first.syncRequests).containsExactly(SyncRequest("R2", KEY, setOf(Leaves.STEPS), SyncReason.STALENESS_RETRY))
            assertThat(harness.store.timer(SLOT_ROW)!!.dueAt).isEqualTo(F0.local("2026-10-01T17:10"))
            val second = harness.timerAt(F0.local("2026-10-01T17:10"))
            assertThat(second.syncRequests).hasSize(1)
            assertThat(harness.store.timer(SLOT_ROW)!!.dueAt).isEqualTo(F0.local("2026-10-01T17:20"))
            assertThat(harness.store.row(KEY)).isNull()
            harness.timerAt(F0.local("2026-10-01T17:20"))

            val row = harness.row(KEY)
            assertWithMessage(id).that(row.state).isEqualTo(DecisionState.UNKNOWN)
            assertThat(row.decisionPointAt).isEqualTo(F0.local("2026-10-01T17:20"))
            assertThat(harness.delivery.posts).isEmpty()
            assertThat(harness.store.evalLog().map { it.outcome }).containsExactly(EvalOutcome.RETRY, EvalOutcome.RETRY)
        }

        @JvmStatic
        fun vectors(): List<Vector> = listOf(
            at1700(
                "D1",
                "coverage 16:31, 2,999 steps: Known T, DELIVERED",
                int(2_999, AT_1631),
                DecisionState.DELIVERED,
                "Only 2,999 steps so far today.",
            ),
            at1700("D2", "coverage 16:31, 3,000 steps: Known F, NOT_TRIGGERED", int(3_000, AT_1631), DecisionState.NOT_TRIGGERED),
            at1700(
                "D3",
                "stale 3,200 from today: the bound gives F, NOT_TRIGGERED without a retry",
                stale(3_200, AT_1615),
                DecisionState.NOT_TRIGGERED,
            ),
            Vector("D4", "stale 2,800: U at 17:00 and a sync; known 2,950 at the 17:10 retry: DELIVERED, decisionPointAt 17:10") {
                val harness = r2(stale(2_800, AT_1615))

                val first = harness.timer()

                assertThat(first.syncRequests).containsExactly(SyncRequest("R2", KEY, setOf(Leaves.STEPS), SyncReason.STALENESS_RETRY))
                assertThat(harness.store.row(KEY)).isNull()
                assertThat(harness.store.timer(SLOT_ROW)!!.dueAt).isEqualTo(F0.local("2026-10-01T17:10"))
                assertThat(first.nextDueAt).isEqualTo(F0.local("2026-10-01T17:10"))
                // A sync at 17:08 brings coverage to 17:05 with 2,950.
                harness.clock.advanceTo(F0.local("2026-10-01T17:08"))
                harness.features.set(Leaves.STEPS, int(2_950, F0.local("2026-10-01T17:05")))
                harness.timerAt(F0.local("2026-10-01T17:10"))

                val row = harness.row(KEY)
                assertThat(row.state).isEqualTo(DecisionState.DELIVERED)
                assertThat(row.decisionPointAt).isEqualTo(F0.local("2026-10-01T17:10"))
                assertThat(row.nominalAt).isEqualTo(F0.local("2026-10-01T17:00"))
                assertThat(harness.delivery.posts.single().body).isEqualTo("Only 2,950 steps so far today.")
            },
            neverSynced("D5", "as D4 but no sync arrives: U at 17:00, 17:10 and 17:20, row UNKNOWN at 17:20", stale(2_800, AT_1615)),
            neverSynced("D6", "no step interval today, Missing(NO_DATA): row UNKNOWN after the retries", missing(MissingReason.NO_DATA)),
            at1700(
                "D7",
                "true zeros up to 16:59, total 0: Known 0, DELIVERED",
                int(0, F0.local("2026-10-01T16:59")),
                DecisionState.DELIVERED,
                "Only 0 steps so far today.",
            ),
            Vector("D6", "a missing value no sync can fix (no permission) writes UNKNOWN at once") {
                val harness = r2(missing(MissingReason.NO_PERMISSION))

                val report = harness.timer()

                assertThat(harness.row(KEY).state).isEqualTo(DecisionState.UNKNOWN)
                assertThat(report.syncRequests).isEmpty()
            },
            Vector("D4", "the prefetch 10 minutes ahead becomes one sync request and is not planned again") {
                val harness = r2(int(2_000, AT_1631), at = "2026-10-01T16:00")

                val reports = harness.runTimerUntil(F0.local("2026-10-01T17:00"))

                val prefetch = reports.single { it.syncRequests.isNotEmpty() }
                assertThat(prefetch.at).isEqualTo(F0.local("2026-10-01T16:50"))
                assertThat(prefetch.syncRequests).containsExactly(SyncRequest("R2", KEY, setOf(Leaves.STEPS), SyncReason.PREFETCH))
                assertThat(harness.row(KEY).state).isEqualTo(DecisionState.DELIVERED)
                assertThat(harness.store.timerRows().filter { it.kind == TimerKind.PREFETCH }.map { it.decisionKey })
                    .containsExactly("v1|R2|D|2026-10-02|17:00")
            },
            Vector("D1", "a late run within the lateness still evaluates; past slot + 30 min it is MISSED(TOO_LATE)") {
                val harness = r2(int(2_999, AT_1631), at = "2026-10-01T17:29")
                harness.timer()
                assertThat(harness.row(KEY).state).isEqualTo(DecisionState.DELIVERED)

                val late = r2(int(2_999, AT_1631), at = "2026-10-01T17:31")
                late.timer()
                assertThat(late.row(KEY).state).isEqualTo(DecisionState.MISSED)
                assertThat(late.row(KEY).reason).isEqualTo(ReasonCode.TOO_LATE)
                assertThat(late.delivery.posts).isEmpty()
            },
            Vector("O1", "daily_at 02:30 on 2026-03-29 in Berlin (gap) runs at 03:30+02:00 (01:30Z), key D|2026-03-29|02:30") {
                val rule = Rules.rule("O1", Trigger.DailyAt(listOf("02:30")), createdAt = Instant.parse("2026-03-28T12:00:00Z"))
                val harness = F0.harness(Instant.parse("2026-03-29T00:00:00Z"), rule)

                harness.runTimerUntil(Instant.parse("2026-03-29T03:00:00Z"))

                val row = harness.row("v1|O1|D|2026-03-29|02:30")
                assertThat(row.state).isEqualTo(DecisionState.DELIVERED)
                assertThat(row.decisionPointAt).isEqualTo(Instant.parse("2026-03-29T01:30:00Z"))
                assertThat(harness.store.rows()).hasSize(1)
            },
            Vector("O2", "daily_at 02:30 on 2026-10-25 in Berlin (overlap) runs at 02:30+02:00 (00:30Z) and not again at 01:30Z") {
                val rule = Rules.rule("O2", Trigger.DailyAt(listOf("02:30")), createdAt = Instant.parse("2026-10-24T12:00:00Z"))
                val harness = F0.harness(Instant.parse("2026-10-24T23:00:00Z"), rule)

                val reports = harness.runTimerUntil(Instant.parse("2026-10-25T03:00:00Z"))

                val row = harness.row("v1|O2|D|2026-10-25|02:30")
                assertThat(row.decisionPointAt).isEqualTo(Instant.parse("2026-10-25T00:30:00Z"))
                assertThat(harness.store.rows()).hasSize(1)
                assertThat(harness.delivery.posts).hasSize(1)
                assertThat(reports.mapNotNull { it.pass }.flatMap { it.written }).hasSize(1)
            },
        )
    }
}
