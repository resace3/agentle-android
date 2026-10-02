package dev.agentle.jitai.engine.scenario

import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.FeatureRef
import dev.agentle.jitai.dsl.model.ActiveWindow
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.SuppressionTarget
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.Leaves
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.Vector
import dev.agentle.jitai.engine.bool
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.int
import dev.agentle.jitai.engine.row
import dev.agentle.jitai.engine.testing.EngineHarness
import dev.agentle.jitai.engine.timer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** R10 §12.C through the engine: an unknown condition never delivers, an unknown suppression blocks (monotone bound). */
class LogicScenarioTest {
    @ParameterizedTest(quoteTextArguments = false, name = "{0}")
    @MethodSource("vectors")
    fun `R10 12_C three-valued logic end to end`(vector: Vector) = runTest { vector.body(this) }

    companion object {
        private const val SLOT_10 = "v1|R1|I|2026-10-01|10"
        private const val PROBE = "charging"

        /** S: SUPPRESSION 22:00-23:00 targeting DIGITAL_WELLBEING when `[PROBE] == true`. */
        private val suppression = Rules.suppression(
            "S",
            ActiveWindow("22:00", "23:00"),
            Leaves.eq(PROBE, true),
            SuppressionTarget(categories = listOf(JitaiCategory.DIGITAL_WELLBEING)),
        )

        private fun harness(vararg extra: dev.agentle.jitai.dsl.model.JitaiDefinition, screen: Boolean = true): EngineHarness {
            val at = F0.local("2026-10-01T22:30")
            val harness = F0.harness(at, Rules.R1, *extra)
            if (screen) harness.features.set(Leaves.SCREEN, int(50, at))
            return harness
        }

        @JvmStatic
        fun vectors(): List<Vector> = listOf(
            Vector("C1", "INTERVENTION all[P,Q] = U (screen missing): no delivery, the scheduled point writes UNKNOWN") {
                val harness = harness(screen = false)
                harness.timer()
                assertWithMessage("C1").that(harness.row(SLOT_10).state).isEqualTo(DecisionState.UNKNOWN)
                assertWithMessage("C1").that(harness.delivery.posts).isEmpty()
            },
            Vector("C2", "SUPPRESSION with P = U inside its window blocks its targets (SUPPRESSED_BY_RULE)") {
                val harness = harness(suppression)
                harness.features.clear(FeatureRef(PROBE))
                harness.timer()
                assertWithMessage("C2").that(harness.row(SLOT_10).reason).isEqualTo(ReasonCode.SUPPRESSED_BY_RULE)
                assertWithMessage("C2").that(harness.delivery.posts).isEmpty()
            },
            Vector("C3", "the same SUPPRESSION with P = F does not block") {
                val harness = harness(suppression)
                harness.features.set(PROBE, bool(false, F0.local("2026-10-01T22:30")))
                harness.timer()
                assertWithMessage("C3").that(harness.row(SLOT_10).state).isEqualTo(DecisionState.DELIVERED)
            },
        )
    }
}
