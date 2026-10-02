package dev.agentle.jitai.engine.scenario

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.Leaves
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.Vector
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.eval.Tri
import dev.agentle.jitai.engine.int
import dev.agentle.jitai.engine.missing
import dev.agentle.jitai.engine.pipeline.DeliveryResult
import dev.agentle.jitai.engine.row
import dev.agentle.jitai.engine.schedule.TimerKeys
import dev.agentle.jitai.engine.schedule.TimerKind
import dev.agentle.jitai.engine.schedule.TimerRow
import dev.agentle.jitai.engine.schedule.TimerSlot
import dev.agentle.jitai.engine.testing.EngineHarness
import dev.agentle.jitai.engine.timer
import dev.agentle.jitai.engine.timerAt
import dev.agentle.jitai.engine.trace
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration.Companion.milliseconds

/** R10 §12.A: the task's canonical rule R1 end to end through the `jitai-timer` work. */
class CanonicalRuleTest {
    @ParameterizedTest(quoteTextArguments = false, name = "{0}")
    @MethodSource("vectors")
    fun `R10 12_A canonical rule R1`(vector: Vector) = runTest { vector.body(this) }

    companion object {
        private fun key(slot: Int) = "v1|R1|I|2026-10-01|$slot"

        private fun r1(at: String, screen: FeatureValue?): EngineHarness {
            val harness = F0.harness(F0.local(at), Rules.R1)
            if (screen != null) harness.features.set(Leaves.SCREEN, screen)
            return harness
        }

        /** The slot of [at] resolves to [expected]; a delivery carries one notification tagged with the key. */
        private fun slotRow(id: String, title: String, at: String, screen: FeatureValue, slot: Int, expected: DecisionState) =
            Vector(id, title) {
                val harness = r1(at, screen)

                val report = harness.timer()

                assertWithMessage(id).that(harness.row(key(slot)).state).isEqualTo(expected)
                assertThat(report.pass?.stateOf(key(slot))).isEqualTo(expected)
                if (expected == DecisionState.DELIVERED) {
                    assertThat(harness.delivery.activeTags()).containsExactly(key(slot))
                    assertThat(harness.delivery.alerts).isEqualTo(1)
                } else {
                    assertThat(harness.delivery.posts).isEmpty()
                }
            }

        private val at2230 = F0.local("2026-10-01T22:30")

        @JvmStatic
        fun vectors(): List<Vector> = listOf(
            slotRow("A1", "screen 44 at 22:30 is NOT_TRIGGERED", "2026-10-01T22:30", int(44, at2230), 10, DecisionState.NOT_TRIGGERED),
            Vector("A1", "leaf 1 F, leaf 2 T, root F in the trace and key v1|R1|I|2026-10-01|10") {
                val harness = r1("2026-10-01T22:30", int(44, at2230))

                harness.timer()

                val row = harness.row(key(10))
                assertThat(row.conditionsResult).isEqualTo(Tri.FALSE)
                assertThat(row.contextResult).isNull()
                val nodes = checkNotNull(harness.trace(key(10)).conditions).nodes
                assertThat(nodes.map { it.result }).containsExactly(Tri.FALSE, Tri.FALSE, Tri.TRUE).inOrder()
            },
            slotRow("A2", "screen 45 at 22:30 is DELIVERED", "2026-10-01T22:30", int(45, at2230), 10, DecisionState.DELIVERED),
            slotRow("A3", "screen 46 at 22:30 is DELIVERED", "2026-10-01T22:30", int(46, at2230), 10, DecisionState.DELIVERED),
            // The resolver floors 44 min 59.999 s to 44 (R10 §5.5): the engine sees 44.
            slotRow(
                "A4",
                "44 min 59.999 s floored to 44 is NOT_TRIGGERED",
                "2026-10-01T22:30",
                int(44, at2230),
                10,
                DecisionState.NOT_TRIGGERED,
            ),
            slotRow(
                "A5",
                "screen 50 at 21:59 (slot 7) is NOT_TRIGGERED",
                "2026-10-01T21:59",
                int(50, at2230),
                7,
                DecisionState.NOT_TRIGGERED,
            ),
            slotRow("A6", "screen 50 at 22:00 (slot 8) is DELIVERED", "2026-10-01T22:00", int(50, at2230), 8, DecisionState.DELIVERED),
            slotRow(
                "A7",
                "usage access revoked is UNKNOWN",
                "2026-10-01T22:30",
                missing(MissingReason.NO_PERMISSION),
                10,
                DecisionState.UNKNOWN,
            ),
            Vector("A7", "the leaf is U with NO_PERMISSION in the trace") {
                val harness = r1("2026-10-01T22:30", missing(MissingReason.NO_PERMISSION))

                harness.timer()

                val leaf = checkNotNull(harness.trace(key(10)).conditions).nodes[1]
                assertThat(leaf.result).isEqualTo(Tri.UNKNOWN)
                assertThat(leaf.value?.reason).isEqualTo(MissingReason.NO_PERMISSION)
            },
            slotRow(
                "A8",
                "collector gap at 21:59: leaf 1 U, leaf 2 F, root F",
                "2026-10-01T21:59",
                missing(MissingReason.COVERAGE_GAP),
                7,
                DecisionState.NOT_TRIGGERED,
            ),
            Vector("A9", "after the 22:00 delivery slot 11 at 22:45 is SUPPRESSED(COOLDOWN)") {
                val harness = r1("2026-10-01T22:00", int(50, at2230))
                harness.timer()
                assertThat(harness.row(key(8)).state).isEqualTo(DecisionState.DELIVERED)

                harness.timerAt(F0.local("2026-10-01T22:45"))

                assertThat(harness.row(key(11)).state).isEqualTo(DecisionState.SUPPRESSED)
                assertThat(harness.row(key(11)).reason).isEqualTo(ReasonCode.COOLDOWN)
            },
            Vector("A10", "without an evaluation at 22:45, slot 11 at 22:59:59.999 is still SUPPRESSED(COOLDOWN)") {
                val harness = r1("2026-10-01T22:00", int(50, at2230))
                harness.timer()

                harness.timerAt(F0.local("2026-10-01T23:00") - 1.milliseconds)

                assertThat(harness.row(key(11)).reason).isEqualTo(ReasonCode.COOLDOWN)
                // Slots 9 and 10 were never reached: MISSED, so every slot has exactly one row (R10 §7.4).
                assertThat(harness.row(key(9)).state).isEqualTo(DecisionState.MISSED)
                assertThat(harness.row(key(10)).reason).isEqualTo(ReasonCode.SLOT_NOT_REACHED)
            },
            Vector("A11", "slot 12 at exactly 23:00:00.000 passes G09 (60 min) and is DELIVERED") {
                val harness = r1("2026-10-01T22:00", int(50, at2230))
                harness.timer()
                harness.timerAt(F0.local("2026-10-01T22:45"))

                harness.timerAt(F0.local("2026-10-01T23:00"))

                assertThat(harness.row(key(12)).state).isEqualTo(DecisionState.DELIVERED)
                assertThat(harness.delivery.activeTags()).containsExactly(key(8), key(12))
            },
            Vector("A12", "a disabled rule is not a candidate: no row, no timer row, no notification") {
                val harness = F0.harness(at2230, Rules.R1.copy(enabled = false))
                harness.features.set(Leaves.SCREEN, int(50, at2230))

                harness.timer()

                assertThat(harness.store.rows()).isEmpty()
                assertThat(harness.store.timerRows()).isEmpty()
                assertThat(harness.delivery.posts).isEmpty()
            },
            Vector("A13", "a duplicate run in slot 10 at 22:31 writes nothing and posts nothing") {
                val harness = r1("2026-10-01T22:30", int(45, at2230))
                harness.timer()
                val rows = harness.store.rows()
                // A duplicate work run that still holds the slot's timer row, and one more regular run.
                harness.store.seedTimer(
                    TimerRow(
                        key = TimerKeys.slot(key(10)),
                        kind = TimerKind.SLOT,
                        dueAt = at2230,
                        jitaiId = "R1",
                        version = 1,
                        slot = TimerSlot.Interval(LocalDate.parse("2026-10-01"), 10),
                        decisionKey = key(10),
                    ),
                )

                val second = harness.timerAt(F0.local("2026-10-01T22:31"))

                assertThat(second.pass?.written.orEmpty()).isEmpty()
                assertThat(harness.store.rows()).isEqualTo(rows)
                assertThat(harness.delivery.posts).hasSize(1)
                assertThat(harness.delivery.alerts).isEqualTo(1)
                assertThat(second.recovery.results).isEmpty()
                assertThat(second.pass?.deliveries.orEmpty().filterIsInstance<DeliveryResult.Delivered>()).isEmpty()
                assertThat(harness.store.timer(TimerKeys.slot(key(10)))).isNull()
            },
        )
    }
}
