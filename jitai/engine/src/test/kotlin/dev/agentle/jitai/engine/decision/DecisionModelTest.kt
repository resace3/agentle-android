package dev.agentle.jitai.engine.decision

import com.google.common.truth.Truth.assertThat
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.eval.TraceNode
import dev.agentle.jitai.engine.eval.TreeTrace
import dev.agentle.jitai.engine.eval.Tri
import dev.agentle.jitai.engine.gates.GateCheck
import dev.agentle.jitai.engine.pipeline.DecisionTrace
import dev.agentle.jitai.engine.pipeline.DecisionTraceSummary
import dev.agentle.jitai.engine.pipeline.TraceCodec
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource

/** R10 §8.2 decision keys, §8.3 state machine and reason codes, §6.7 trace encoding. */
class DecisionModelTest {
    @ParameterizedTest(quoteTextArguments = false, name = "{0} -> {1}: {2}")
    @MethodSource("transitions")
    fun `R10 8_3 only the listed transitions are legal`(from: DecisionState, to: DecisionState, legal: Boolean) {
        assertThat(DecisionStateMachine.isLegal(from, to)).isEqualTo(legal)
    }

    @ParameterizedTest(quoteTextArguments = false, name = "{0}")
    @EnumSource(DecisionState::class)
    fun `rows are inserted only in an evaluation outcome or DECIDED`(state: DecisionState) {
        val initial = state in setOf(
            DecisionState.NOT_TRIGGERED,
            DecisionState.NOT_AVAILABLE,
            DecisionState.UNKNOWN,
            DecisionState.MISSED,
            DecisionState.SUPPRESSED,
            DecisionState.NOT_RANDOMIZED,
            DecisionState.DECIDED,
        )
        assertThat(DecisionStateMachine.canInsert(state)).isEqualTo(initial)
    }

    @Test
    fun `counted states are DECIDED, DELIVERING, DELIVERED, DELIVERY_UNCERTAIN and CARD_PENDING (own caps only)`() {
        assertThat(DecisionState.entries.filter { it.counted }).containsExactly(
            DecisionState.DECIDED,
            DecisionState.DELIVERING,
            DecisionState.DELIVERED,
            DecisionState.DELIVERY_UNCERTAIN,
            DecisionState.CARD_PENDING,
        )
        // Correction 6: the in-app card fallback is excluded from the global caps until displayed.
        assertThat(DecisionState.entries.filter { it.countsGlobally }).containsExactly(
            DecisionState.DECIDED,
            DecisionState.DELIVERING,
            DecisionState.DELIVERED,
            DecisionState.DELIVERY_UNCERTAIN,
        )
        assertThat(DecisionState.entries.filterNot { it.isFinal })
            .containsExactly(DecisionState.DECIDED, DecisionState.DELIVERING, DecisionState.CARD_PENDING)
        assertThat(DecisionState.entries).hasSize(14)
    }

    @Test
    fun `the gates are G01-G16 in evaluation order`() {
        assertThat(ReasonCode.GATES.map { it.gateId }).isEqualTo((1..16).map { "G%02d".format(it) })
        assertThat(ReasonCode.STATE_CHANGED.isGate).isFalse()
    }

    @Test
    fun `R10 8_2 key formats`() {
        val date = LocalDate.parse("2026-10-01")

        assertThat(DecisionKeys.event("R1", F0.local("2026-10-01T22:07:30"))).isEqualTo("v1|R1|E|1989872")
        assertThat(DecisionKeys.interval("R1", date, 3)).isEqualTo("v1|R1|I|2026-10-01|3")
        assertThat(DecisionKeys.dailyAt("R2", date, "17:00")).isEqualTo("v1|R2|D|2026-10-01|17:00")
        assertThat(DecisionKeys.snoozeFollowUp("R1", "v1|R1|I|2026-10-01|8")).matches("v1\\|R1\\|R\\|[0-9a-f]{16}")
        assertThat(DecisionKeys.snoozeFollowUp("R1", "v1|R1|I|2026-10-01|8"))
            .isEqualTo("v1|R1|R|" + DecisionKeys.sha256Hex("v1|R1|I|2026-10-01|8").take(16))
    }

    @Test
    fun `keys name their kind and JITAI and reject malformed parts`() {
        assertThat(DecisionKeys.kindOf("v1|R2|D|2026-10-01|17:00")).isEqualTo(TriggerKind.DAILY_AT)
        assertThat(DecisionKeys.jitaiIdOf("v1|R2|D|2026-10-01|17:00")).isEqualTo("R2")
        assertThat(DecisionKeys.kindOf("v2|R2|D|x")).isNull()
        assertThat(DecisionKeys.kindOf("v1|R2|Z|x")).isNull()
        assertThat(DecisionKeys.kindOf("v1|R2")).isNull()
        assertThat(DecisionKeys.jitaiIdOf("garbage")).isNull()
        assertThrows<IllegalArgumentException> { DecisionKeys.interval("R1", LocalDate.parse("2026-10-01"), -1) }
        assertThrows<IllegalArgumentException> { DecisionKeys.dailyAt("R1", LocalDate.parse("2026-10-01"), "7:00") }
        assertThrows<IllegalArgumentException> { DecisionKeys.event("a|b", F0.CREATED) }
        assertThrows<IllegalArgumentException> { DecisionKeys.event("", F0.CREATED) }
    }

    @Test
    fun `scheduled kinds write every resolution, events only eligible outcomes`() {
        assertThat(TriggerKind.entries.filter { it.writesEveryResolution })
            .containsExactly(TriggerKind.INTERVAL, TriggerKind.DAILY_AT, TriggerKind.SNOOZE_FOLLOW_UP)
    }

    @Test
    fun `responses - IGNORED and DISMISSED extend the run, OPENED and HELPFUL reset it`() {
        assertThat(JitaiResponse.entries.filter { it.extendsIgnoredRun }).containsExactly(JitaiResponse.IGNORED, JitaiResponse.DISMISSED)
        assertThat(JitaiResponse.entries.filter { it.resetsIgnoredRun }).containsExactly(JitaiResponse.OPENED, JitaiResponse.HELPFUL)
    }

    // -- §6.7 trace encoding ----------------------------------------------------------------------------------------------

    private fun bigTrace(leaves: Int): DecisionTrace {
        val nodes = listOf(TraceNode("", "all", Tri.FALSE)) + (0 until leaves).map { i ->
            TraceNode("/of/$i", "gte", if (i == 3) Tri.FALSE else Tri.TRUE, feature = "screen_minutes_last_60m", literals = listOf("$i"))
        }
        val tree = TreeTrace(Tri.FALSE, nodes)
        return DecisionTrace(
            conditions = tree,
            context = tree,
            gates = ReasonCode.GATES.map { GateCheck(it, passed = it != ReasonCode.COOLDOWN, detail = "count=1 limit=3") },
            suppressedBy = listOf("S1", "S2"),
            reason = ReasonCode.COOLDOWN,
        )
    }

    @Test
    fun `a small trace round-trips unchanged`() {
        val trace = bigTrace(3)

        val encoded = TraceCodec.encode(trace)

        assertThat(TraceCodec.decode(encoded)).isEqualTo(trace)
    }

    @Test
    fun `truncation is deterministic - first failing branch, then roots, then failing gates, then the summary`() {
        val trace = bigTrace(30)
        val outputs = (20_000 downTo 10 step 10).map { max -> max to TraceCodec.encode(trace, maxBytes = max) }

        val stages = outputs.map { (_, text) -> stageOf(text) }.distinct()

        assertThat(stages).containsExactly("full", "branch", "roots", "gates", "summary").inOrder()
        outputs.forEach { (max, text) ->
            if (stageOf(text) != "summary") assertThat(text.toByteArray().size).isAtMost(max)
        }
        val branch = TraceCodec.decode(outputs.first { stageOf(it.second) == "branch" }.second)!!
        assertThat(branch.conditions!!.nodes.map { it.path }).containsExactly("", "/of/3").inOrder()
        assertThat(branch.conditions!!.truncated).isTrue()
        val gates = TraceCodec.decode(outputs.first { stageOf(it.second) == "gates" }.second)!!
        assertThat(gates.suppressedBy).containsExactly("S1")
        val summary = outputs.last().second
        assertThat(Json.decodeFromString(DecisionTraceSummary.serializer(), summary))
            .isEqualTo(DecisionTraceSummary(Tri.FALSE, Tri.FALSE, ReasonCode.COOLDOWN))
        assertThat(TraceCodec.encode(trace, maxBytes = 2_000)).isEqualTo(TraceCodec.encode(trace, maxBytes = 2_000))
        assertThat(TraceCodec.encode(bigTrace(500)).toByteArray().size).isAtMost(TraceCodec.MAX_BYTES)
    }

    private fun stageOf(text: String): String {
        val trace = TraceCodec.decode(text) ?: return "summary"
        val nodes = trace.conditions!!.nodes.size
        return when {
            nodes > 2 -> "full"
            nodes == 2 -> "branch"
            trace.gates!!.size == ReasonCode.GATES.size -> "roots"
            else -> "gates"
        }
    }

    @Test
    fun `decode returns null for anything that is not a full trace`() {
        assertThat(TraceCodec.decode("not json")).isNull()
        assertThat(TraceCodec.decode("{\"gates\":[{\"gate\":\"NOPE\",\"passed\":true}]}")).isNull()
        assertThat(TraceCodec.summary(bigTrace(1))).contains("COOLDOWN")
    }

    @Test
    fun `a record's cooldown anchor is deliveredAt when DELIVERED, else the decision point`() {
        val decided = dev.agentle.jitai.engine.time.MonotonicStamp(F0.local("2026-10-01T22:30"), 1_000, 41)
        val delivered = decided.copy(elapsedMillis = 61_000)
        val record = DecisionRecord(
            decisionKey = "v1|R1|I|2026-10-01|10",
            jitaiId = "R1",
            jitaiVersion = 1,
            triggerKind = TriggerKind.INTERVAL,
            category = dev.agentle.jitai.dsl.model.JitaiCategory.GENERAL,
            channel = dev.agentle.jitai.dsl.model.DeliveryChannel.NOTIFICATION,
            state = DecisionState.DELIVERED,
            decided = decided,
            zoneId = F0.BERLIN.id,
            localDateTime = kotlinx.datetime.LocalDateTime.parse("2026-10-01T22:30"),
            engineDay = LocalDate.parse("2026-10-01"),
            delivered = delivered,
            content = DecisionContent(snapshotJson = "{}", response = JitaiResponse.OPENED),
        )

        assertThat(record.cooldownAnchor).isEqualTo(delivered)
        assertThat(record.copy(state = DecisionState.DELIVERY_UNCERTAIN).cooldownAnchor).isEqualTo(decided)
        assertThat(record.notificationTag).isEqualTo(record.decisionKey)
        assertThat(record.withoutContent().content).isEqualTo(DecisionContent())
        assertThat(record.decisionPointAt).isEqualTo(decided.wall)
    }

    companion object {
        private val LEGAL = setOf(
            DecisionState.DECIDED to DecisionState.DELIVERING,
            DecisionState.DECIDED to DecisionState.EXPIRED,
            DecisionState.DECIDED to DecisionState.CANCELLED,
            DecisionState.DECIDED to DecisionState.SUPPRESSED,
            DecisionState.DECIDED to DecisionState.CARD_PENDING,
            DecisionState.DELIVERING to DecisionState.DELIVERED,
            DecisionState.DELIVERING to DecisionState.DELIVERY_UNCERTAIN,
            DecisionState.DELIVERING to DecisionState.FAILED,
            DecisionState.DELIVERING to DecisionState.SUPPRESSED,
            DecisionState.DELIVERING to DecisionState.CARD_PENDING,
            // Correction 5: a worker cancelled before the post reverts its claim.
            DecisionState.DELIVERING to DecisionState.DECIDED,
            DecisionState.CARD_PENDING to DecisionState.DELIVERED,
            DecisionState.CARD_PENDING to DecisionState.EXPIRED,
            DecisionState.CARD_PENDING to DecisionState.CANCELLED,
        )

        @JvmStatic
        fun transitions(): List<Arguments> = DecisionState.entries.flatMap { from ->
            DecisionState.entries.map { to -> Arguments.of(from, to, (from to to) in LEGAL) }
        }
    }
}
