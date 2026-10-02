package dev.agentle.jitai.engine.scenario

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.OutcomeMetric
import dev.agentle.jitai.dsl.model.OutcomeMetricRef
import dev.agentle.jitai.dsl.model.OutcomeSpec
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.Leaves
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.schedule.ReplanReason
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** The entry points the background team calls: re-validation after an upgrade and the planned UTC offset. */
class BackgroundEntryPointsTest {
    @Test
    fun `revalidateStoredDefinitions pauses only failing definitions and removes their timers`() = runTest {
        val valid = Rules.R1.copy(outcome = OutcomeSpec(OutcomeMetricRef(OutcomeMetric.SCREEN_MINUTES_AFTER, windowMinutes = 60)))
        val broken = valid.copy(id = "RX", conditions = Leaves.gte("no_such_feature_xyz", 1))
        val harness = F0.harness(F0.local("2026-10-01T21:00"), valid, broken)
        harness.engine.replan(ReplanReason.DEFINITION_CHANGED).getOrThrow()
        assertThat(harness.store.timers().getOrThrow().map { it.jitaiId }).contains("RX")

        val paused = harness.engine.revalidateStoredDefinitions().getOrThrow()

        assertThat(harness.repository.invalidPaused).containsExactly("RX", listOf("E010"))
        assertThat(paused).isEqualTo(1)
        assertThat(harness.repository["RX"]!!.status).isEqualTo(JitaiStatus.PAUSED)
        assertThat(harness.repository["R1"]!!.status).isEqualTo(JitaiStatus.ACTIVE)
        assertThat(harness.store.timers().getOrThrow().map { it.jitaiId }).doesNotContain("RX")
        assertThat(harness.engine.revalidateStoredDefinitions().getOrThrow()).isEqualTo(0)
    }

    @Test
    fun `plannedOffsetSeconds is the offset of the last plan, null before any plan`() = runTest {
        val harness = F0.harness(F0.local("2026-10-24T21:00"), Rules.R1)
        assertThat(harness.engine.plannedOffsetSeconds().getOrThrow()).isNull()

        harness.engine.replan(ReplanReason.DEFINITION_CHANGED).getOrThrow()
        assertThat(harness.engine.plannedOffsetSeconds().getOrThrow()).isEqualTo(7_200)

        harness.clock.advanceTo(F0.local("2026-10-25T21:00"))
        harness.engine.replan(ReplanReason.OFFSET).getOrThrow()
        assertThat(harness.engine.plannedOffsetSeconds().getOrThrow()).isEqualTo(3_600)
    }
}
