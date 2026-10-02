package dev.agentle.jitai.engine.scenario

import com.google.common.truth.Truth.assertThat
import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureResolver
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureSnapshot
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.realtime.DeliveryRecord
import dev.agentle.analytics.features.realtime.DeliveryState
import dev.agentle.analytics.features.realtime.RealtimeFeatureEngine
import dev.agentle.analytics.features.realtime.testing.InMemoryRealtimeInputs
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.engine.EnginePorts
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.JitaiEngine
import dev.agentle.jitai.engine.Leaves
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.row
import dev.agentle.jitai.engine.time.EngineDays
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * REALTIME-FEATURES-R1-2 across modules: the engine binds `{jitai: self}` per rule before the real
 * [RealtimeFeatureEngine] resolves it, so two rules with the same self leaf see their own delivery history (R10 L1/L2).
 */
class RealtimeSelfBindingTest {
    private class Recording(private val delegate: FeatureResolver) : FeatureResolver {
        val values = mutableMapOf<FeatureRef, FeatureValue>()

        override suspend fun resolve(refs: Set<FeatureRef>, at: Instant): FeatureSnapshot =
            delegate.resolve(refs, at).also { snapshot -> refs.forEach { ref -> snapshot[ref]?.let { values[ref] = it } } }
    }

    @Test
    fun `R10 L1 two rules with deliveries_today{jitai=self} each resolve their own count through RealtimeFeatureEngine`() = runTest {
        val start = F0.local("2026-10-01T17:00")
        val self = Leaves.lt("deliveries_today", 1).let {
            it as dev.agentle.jitai.dsl.rule.Condition.Lt
        }.copy(args = mapOf("jitai" to "self"))
        fun rule(id: String) = Rules.rule(
            id,
            Trigger.DailyAt(listOf("17:00")),
            conditions = self,
            maxPerDay = 3,
            createdAt =
            start - 1.hours,
        )
        val a = rule(A)
        val b = rule(B)
        val harness = F0.harness(start, a, b)
        val inputs = InMemoryRealtimeInputs()
        inputs.history.rows += DeliveryRecord(
            decisionKey = "v1|$A|D|2026-10-01|09:00",
            jitaiId = A,
            jitaiCategory = "DIGITAL_WELLBEING",
            state = DeliveryState.DELIVERED,
            at = F0.local("2026-10-01T09:00"),
            engineDay = EngineDays.of(F0.local("2026-10-01T09:00"), F0.BERLIN),
        )
        val resolver = Recording(RealtimeFeatureEngine(inputs.toInputs(), harness.clock))
        val ports = harness.ports
        val engine = JitaiEngine(
            EnginePorts(
                repository = ports.repository,
                store = ports.store,
                delivery = ports.delivery,
                settings = ports.settings,
                features = resolver,
                events = ports.events,
                aiTexts = ports.aiTexts,
                outcomeData = ports.outcomeData,
                dailyRefresh = null,
            ),
            harness.clock,
            harness.clock,
            harness.nonces,
        )

        engine.runTimer().getOrThrow()

        assertThat(resolver.values[FeatureRef("deliveries_today", mapOf("jitai" to A))]).isEqualTo(count(1))
        assertThat(resolver.values[FeatureRef("deliveries_today", mapOf("jitai" to B))]).isEqualTo(count(0))
        assertThat(resolver.values.keys.none { it.args["jitai"] == "self" }).isTrue()
        assertThat(harness.row("v1|$B|D|2026-10-01|17:00").state).isEqualTo(DecisionState.DELIVERED)
        assertThat(harness.store.row("v1|$A|D|2026-10-01|17:00")?.state).isNotEqualTo(DecisionState.DELIVERED)
    }

    private fun count(value: Long) = FeatureValue.Known(FeatureScalar.IntValue(value), F0.local("2026-10-01T17:00"))

    private companion object {
        const val A = "0d6c1f3e-1111-4a5b-9c2d-000000000001"
        const val B = "0d6c1f3e-2222-4a5b-9c2d-000000000002"
    }
}
