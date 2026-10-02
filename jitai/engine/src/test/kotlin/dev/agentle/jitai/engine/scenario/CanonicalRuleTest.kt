package dev.agentle.jitai.engine.scenario

import com.google.common.truth.Truth.assertThat
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.Leaves
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.eval.Tri
import dev.agentle.jitai.engine.int
import dev.agentle.jitai.engine.missing
import dev.agentle.jitai.engine.pipeline.DeliveryResult
import dev.agentle.jitai.engine.testing.EngineHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration.Companion.milliseconds

/** R10 §12.A: the task's canonical rule R1 end to end through the engine. */
class CanonicalRuleTest {
    private fun r1Harness(at: String, screen: FeatureValue?): EngineHarness {
        val harness = F0.harness(F0.local(at), Rules.R1)
        if (screen != null) harness.features.set(Leaves.SCREEN, screen)
        return harness
    }

    private fun key(slot: Int) = "v1|R1|I|2026-10-01|$slot"

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    fun `R10 12_A rows A1 to A8 resolve the slot as specified`(
        id: String,
        at: String,
        screen: FeatureValue,
        slot: Int,
        expected: DecisionState,
    ) = runTest {
        val harness = r1Harness(at, screen)

        val report = harness.engine.runTick().getOrThrow()

        val row = harness.store.row(key(slot))
        assertThat(row).isNotNull()
        assertThat(row!!.state).isEqualTo(expected)
        assertThat(report.pass.stateOf(key(slot))).isEqualTo(expected)
        if (expected == DecisionState.DELIVERED) {
            assertThat(harness.delivery.activeTags()).containsExactly(key(slot))
            assertThat(harness.delivery.alerts).isEqualTo(1)
        } else {
            assertThat(harness.delivery.posts).isEmpty()
        }
    }

    @Test
    fun `A1 records leaf results in the trace - leaf 1 F, leaf 2 T, root F`() = runTest {
        val harness = r1Harness("2026-10-01T22:30", int(44, F0.local("2026-10-01T22:30")))

        harness.engine.runTick().getOrThrow()

        val row = harness.store.row(key(10))!!
        assertThat(row.conditionsResult).isEqualTo(Tri.FALSE)
        assertThat(row.contextResult).isNull()
        val trace = dev.agentle.jitai.engine.pipeline.TraceCodec.decode(row.content.traceJson!!)!!
        assertThat(trace.conditions!!.nodes.map { it.result }).containsExactly(Tri.FALSE, Tri.FALSE, Tri.TRUE).inOrder()
    }

    @Test
    fun `A7 usage access revoked - leaf U with NO_PERMISSION, row UNKNOWN`() = runTest {
        val harness = r1Harness("2026-10-01T22:30", missing(MissingReason.NO_PERMISSION))

        harness.engine.runTick().getOrThrow()

        val row = harness.store.row(key(10))!!
        assertThat(row.state).isEqualTo(DecisionState.UNKNOWN)
        val trace = dev.agentle.jitai.engine.pipeline.TraceCodec.decode(row.content.traceJson!!)!!
        assertThat(trace.conditions!!.nodes[1].value!!.reason).isEqualTo(MissingReason.NO_PERMISSION)
    }

    @Test
    fun `A9 to A11 cooldown counts from the delivery at 22_00 and passes at exactly 60 minutes`() = runTest {
        val harness = r1Harness("2026-10-01T22:00", int(50, F0.local("2026-10-01T22:00")))
        harness.engine.runTick().getOrThrow()
        assertThat(harness.store.row(key(8))!!.state).isEqualTo(DecisionState.DELIVERED)

        // A9: slot 11 at 22:45.
        harness.clock.advanceTo(F0.local("2026-10-01T22:45"))
        harness.engine.runTick().getOrThrow()
        assertThat(harness.store.row(key(11))!!.state).isEqualTo(DecisionState.SUPPRESSED)
        assertThat(harness.store.row(key(11))!!.reason).isEqualTo(ReasonCode.COOLDOWN)

        // A11: slot 12 at exactly 23:00:00.000.
        harness.clock.advanceTo(F0.local("2026-10-01T23:00"))
        harness.engine.runTick().getOrThrow()
        assertThat(harness.store.row(key(12))!!.state).isEqualTo(DecisionState.DELIVERED)
        assertThat(harness.delivery.activeTags()).containsExactly(key(8), key(12))
    }

    @Test
    fun `A10 without a tick at 22_45 the slot is evaluated at 22_59_59_999 and still in cooldown`() = runTest {
        val harness = r1Harness("2026-10-01T22:00", int(50, F0.local("2026-10-01T22:00")))
        harness.engine.runTick().getOrThrow()

        harness.clock.advanceTo(F0.local("2026-10-01T23:00") - 1.milliseconds)
        harness.engine.runTick().getOrThrow()

        val row = harness.store.row(key(11))!!
        assertThat(row.state).isEqualTo(DecisionState.SUPPRESSED)
        assertThat(row.reason).isEqualTo(ReasonCode.COOLDOWN)
        // Slots 9 and 10 were never reached: MISSED, so every slot has exactly one row (R10 §7.4).
        assertThat(harness.store.row(key(9))!!.state).isEqualTo(DecisionState.MISSED)
        assertThat(harness.store.row(key(10))!!.reason).isEqualTo(ReasonCode.SLOT_NOT_REACHED)
    }

    @Test
    fun `A12 a disabled rule is not a candidate - no row, no notification`() = runTest {
        val harness = F0.harness(F0.local("2026-10-01T22:30"), Rules.R1.copy(enabled = false))
        harness.features.set(Leaves.SCREEN, int(50, F0.local("2026-10-01T22:30")))

        harness.engine.runTick().getOrThrow()

        assertThat(harness.store.rows()).isEmpty()
        assertThat(harness.delivery.posts).isEmpty()
    }

    @Test
    fun `A13 a duplicate tick in the same slot writes nothing and posts nothing`() = runTest {
        val harness = r1Harness("2026-10-01T22:30", int(45, F0.local("2026-10-01T22:30")))
        harness.engine.runTick().getOrThrow()
        val rows = harness.store.rows()

        harness.clock.advanceTo(F0.local("2026-10-01T22:31"))
        val second = harness.engine.runTick().getOrThrow()

        assertThat(second.pass.written).isEmpty()
        assertThat(harness.store.rows()).isEqualTo(rows)
        assertThat(harness.delivery.posts).hasSize(1)
        assertThat(harness.delivery.alerts).isEqualTo(1)
        assertThat(second.recovery.results).isEmpty()
        assertThat(second.pass.deliveries.filterIsInstance<DeliveryResult.Delivered>()).isEmpty()
    }

    companion object {
        @JvmStatic
        fun rows(): List<Arguments> {
            val at2230 = F0.local("2026-10-01T22:30")
            return listOf(
                Arguments.of("A1 screen 44 at 22:30", "2026-10-01T22:30", int(44, at2230), 10, DecisionState.NOT_TRIGGERED),
                Arguments.of("A2 screen 45 at 22:30", "2026-10-01T22:30", int(45, at2230), 10, DecisionState.DELIVERED),
                Arguments.of("A3 screen 46 at 22:30", "2026-10-01T22:30", int(46, at2230), 10, DecisionState.DELIVERED),
                // A4: the resolver floors 44 min 59.999 s to 44 (R10 §5.5); the engine sees 44.
                Arguments.of("A4 floored 44 at 22:30", "2026-10-01T22:30", int(44, at2230), 10, DecisionState.NOT_TRIGGERED),
                Arguments.of("A5 screen 50 at 21:59", "2026-10-01T21:59", int(50, at2230), 7, DecisionState.NOT_TRIGGERED),
                Arguments.of("A6 screen 50 at 22:00", "2026-10-01T22:00", int(50, at2230), 8, DecisionState.DELIVERED),
                Arguments.of(
                    "A7 usage access revoked",
                    "2026-10-01T22:30",
                    missing(MissingReason.NO_PERMISSION),
                    10,
                    DecisionState.UNKNOWN,
                ),
                Arguments.of(
                    "A8 collector gap at 21:59",
                    "2026-10-01T21:59",
                    missing(MissingReason.COVERAGE_GAP),
                    7,
                    DecisionState.NOT_TRIGGERED,
                ),
            )
        }
    }
}
