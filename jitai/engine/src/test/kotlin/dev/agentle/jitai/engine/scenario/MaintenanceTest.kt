package dev.agentle.jitai.engine.scenario

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.getOrThrow
import dev.agentle.core.model.DataCategory
import dev.agentle.jitai.dsl.model.JitaiEventType
import dev.agentle.jitai.dsl.model.OutcomeMetric
import dev.agentle.jitai.dsl.model.OutcomeMetricRef
import dev.agentle.jitai.dsl.model.OutcomeRole
import dev.agentle.jitai.dsl.model.OutcomeSpec
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.Leaves
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.content.CategoryScrubber
import dev.agentle.jitai.engine.content.StoredSnapshots
import dev.agentle.jitai.engine.int
import dev.agentle.jitai.engine.outcome.OutcomeState
import dev.agentle.jitai.engine.pipeline.TraceCodec
import dev.agentle.jitai.engine.row
import dev.agentle.jitai.engine.timer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Retention, history deletion, on-demand outcomes and category scrubbing (R10 §8.7-§8.8, jitai-correctness-19). */
class MaintenanceTest {
    private val slot10 = "v1|R1|I|2026-10-01|10"
    private val r1 = Rules.R1.copy(outcome = OutcomeSpec(OutcomeMetricRef(OutcomeMetric.NOTIFICATION_OPENED, windowMinutes = 30)))

    private suspend fun delivered() = F0.harness(F0.local("2026-10-01T22:30"), r1).also {
        it.features.set(Leaves.SCREEN, int(50, F0.local("2026-10-01T22:30")))
        it.timer()
    }

    @Test
    fun `retention, history deletion and computeOutcome run through the ports`() = runTest {
        val harness = delivered()

        assertThat(harness.engine.applyRetention().getOrThrow().expiredTexts).isEqualTo(0)
        val opened = harness.engine.computeOutcome(slot10, OutcomeRole.PROXIMAL).getOrThrow()
        assertThat(opened!!.state).isEqualTo(OutcomeState.AVAILABLE)
        assertThat(harness.engine.computeOutcome(slot10, OutcomeRole.DISTAL).getOrThrow()).isNull()
        assertThat(harness.engine.computeOutcome("v1|R1|I|2026-10-01|99", OutcomeRole.PROXIMAL).getOrThrow()).isNull()
        assertThat(harness.engine.deleteInterventionHistory().getOrThrow()).isAtLeast(1)
    }

    @Test
    fun `scrubbing a deleted category replaces snapshot and trace values and drops the hash`() = runTest {
        val content = delivered().row(slot10).content
        val all = DataCategory.entries.toSet()

        val scrubbed = CategoryScrubber.scrubContent(content, all)

        assertThat(scrubbed.snapshotHash).isNull()
        assertThat(StoredSnapshots.decode(scrubbed.snapshotJson!!)!!.values.values.any { it.deleted }).isTrue()
        assertThat(TraceCodec.decode(scrubbed.traceJson!!)).isNotEqualTo(TraceCodec.decode(content.traceJson!!))
        assertThat(CategoryScrubber.scrubContent(scrubbed, all)).isEqualTo(scrubbed)
        assertThat(CategoryScrubber.scrubContent(content, emptySet())).isEqualTo(content)
        assertThat(CategoryScrubber.scrubSnapshot("not json", all)).isEqualTo("not json")
        assertThat(CategoryScrubber.scrubTrace("not json", all)).isEqualTo("not json")
    }

    @Test
    fun `scrubbing evaluation-log traces`() = runTest {
        val e = Rules.rule("E", Trigger.Event(listOf(JitaiEventType.POWER_CONNECTED)), conditions = Leaves.gte(Leaves.SCREEN, 45))
        val harness = F0.harness(F0.local("2026-10-01T22:30"), e)
        harness.features.set(Leaves.SCREEN, int(10, F0.local("2026-10-01T22:30")))
        harness.events.emit(JitaiEventType.POWER_CONNECTED, F0.local("2026-10-01T22:29"))
        harness.engine.runEvents().getOrThrow()
        val entry = harness.store.evalLog().first { it.traceJson != null }

        val scrubbed = CategoryScrubber.scrubEvalLog(entry, DataCategory.entries.toSet())

        assertThat(scrubbed.jitaiId).isEqualTo(entry.jitaiId)
        assertThat(CategoryScrubber.scrubEvalLog(scrubbed.copy(traceJson = null), DataCategory.entries.toSet()).traceJson).isNull()
    }
}
