package dev.agentle.interventions

import com.google.common.truth.Truth.assertThat
import dev.agentle.interventions.wiring.StopGate
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.engine.decision.DecisionContent
import dev.agentle.jitai.engine.decision.DecisionRecord
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.JitaiResponse
import dev.agentle.jitai.engine.decision.TriggerKind
import dev.agentle.jitai.engine.time.MonotonicStamp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.junit.Test
import kotlin.time.Instant

class StopGateTest {
    private fun record(state: DecisionState = DecisionState.DELIVERED, response: JitaiResponse = JitaiResponse.NONE) = DecisionRecord(
        decisionKey = "v1|jitai-1|I|2026-10-01|10",
        jitaiId = "jitai-1",
        jitaiVersion = 1,
        triggerKind = TriggerKind.INTERVAL,
        category = JitaiCategory.PHYSICAL_ACTIVITY,
        channel = DeliveryChannel.NOTIFICATION,
        state = state,
        decided = MonotonicStamp(Instant.parse("2026-10-01T08:00:00Z"), 1_000, 1),
        zoneId = "Europe/Berlin",
        localDateTime = LocalDateTime.parse("2026-10-01T10:00"),
        engineDay = LocalDate.parse("2026-10-01"),
        nonce = "nonce-1",
        content = DecisionContent(snapshotJson = "{}", response = response),
    )

    @Test
    fun `a matching nonce on a delivered decision may stop`() {
        assertThat(StopGate.accepts(record(), "nonce-1")).isTrue()
        assertThat(StopGate.accepts(record(DecisionState.CARD_PENDING), "nonce-1")).isTrue()
    }

    @Test
    fun `a wrong, missing or stale nonce never stops`() {
        assertThat(StopGate.accepts(record(), "nonce-2")).isFalse()
        assertThat(StopGate.accepts(record(), null)).isFalse()
        assertThat(StopGate.accepts(null, "nonce-1")).isFalse()
        assertThat(StopGate.accepts(record(DecisionState.EXPIRED), "nonce-1")).isFalse()
        assertThat(StopGate.accepts(record(DecisionState.CANCELLED), "nonce-1")).isFalse()
        assertThat(StopGate.accepts(record(response = JitaiResponse.OPENED), "nonce-1")).isFalse()
    }
}
