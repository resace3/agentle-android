@file:OptIn(AiEnvelopeConstruction::class)

package dev.agentle.ai.context

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.AiEnvelopeConstruction
import dev.agentle.ai.api.AiPurpose
import dev.agentle.ai.api.AiRequestEnvelope
import dev.agentle.ai.api.AiRequestMode
import dev.agentle.ai.api.BlockKind
import dev.agentle.ai.api.ContextBlock
import dev.agentle.ai.api.ContextItem
import dev.agentle.ai.api.DataItem
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.errorOrNull
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.AiDataCategory.APP_IDENTITY
import dev.agentle.core.model.AiDataCategory.NOTIFICATION_TEXT
import dev.agentle.core.model.AiDataCategory.SCREEN_TIME_TOTALS
import dev.agentle.core.model.AiDataCategory.SLEEP
import dev.agentle.core.model.AiDataCategory.STEPS
import dev.agentle.core.model.AiDataCategory.USER_TEXT
import dev.agentle.core.model.DataLineage
import dev.agentle.core.model.SourceFamily
import dev.agentle.core.model.TextOrigin
import dev.agentle.core.model.UntrustedText
import dev.agentle.core.time.ClosedOpenRange
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** The final gate, case by case: every refusal names its first reason (privacy-ai-04, docs/ARCHITECTURE.md section 9). */
class EnvelopeGateTest {
    private val instructions = AiInstructionSet()
    private val range = ClosedOpenRange(Instant.parse("2026-09-02T22:15:00Z"), START)
    private val question = UntrustedText("How was my week", TextOrigin.USER_REQUEST)
    private val stepsItem = item(DataItem.Quantity("steps.daily_avg", 5.0, "steps"), lineage(STEPS))
    private val stepsBlock = block("steps", STEPS, BlockKind.AGGREGATES, stepsItem)
    private val standing = StandingConsent(
        purpose = AiPurpose.GENERAL_QUESTION,
        fields = setOf("steps.daily_min"),
        categories = setOf(STEPS),
        sourceFamilies = setOf(SourceFamily.ON_DEVICE),
        lookbackDays = 14,
        cadence = 12.hours,
        dailyBudget = 1,
        consentVersion = 1,
        grantedAt = START,
        accountSub = ACCOUNT,
    )

    private fun item(data: DataItem, lineage: DataLineage): ContextItem = ContextItem(data, lineage)

    private fun block(label: String, category: AiDataCategory, kind: BlockKind, vararg items: ContextItem): ContextBlock =
        ContextBlock(label, category, kind, items.toList())

    private fun decision(
        purpose: AiPurpose = AiPurpose.GENERAL_QUESTION,
        mode: AiRequestMode = AiRequestMode.USER_INITIATED,
        categories: Set<AiDataCategory> = setOf(STEPS, USER_TEXT, APP_IDENTITY, SCREEN_TIME_TOTALS),
        sources: Set<SourceFamily> = setOf(SourceFamily.ON_DEVICE),
        rangeLimit: ClosedOpenRange? = range,
        rawEvents: Boolean = false,
        standing: StandingConsent? = null,
    ): GateDecision = GateDecision(
        spec = PurposePolicy.spec(purpose),
        mode = mode,
        categories = categories,
        sources = sources,
        rangeLimit = rangeLimit,
        rawEventsConfirmed = rawEvents,
        standing = standing,
        instructions = instructions.forPurpose(purpose),
    )

    private fun envelope(
        blocks: List<ContextBlock> = listOf(stepsBlock),
        userText: UntrustedText? = question,
        purpose: AiPurpose = AiPurpose.GENERAL_QUESTION,
        mode: AiRequestMode = AiRequestMode.USER_INITIATED,
        instructions: String = this.instructions.forPurpose(purpose),
        start: Instant? = range.start,
        end: Instant? = range.end,
    ): AiRequestEnvelope = AiRequestEnvelope("airgatetest", purpose, mode, instructions, userText, blocks, start, end, START, 1)

    private fun refusal(envelope: AiRequestEnvelope, decision: GateDecision = decision()): AppError.ConsentViolation =
        EnvelopeGate.check(envelope, decision).errorOrNull() as AppError.ConsentViolation

    private fun reason(envelope: AiRequestEnvelope, decision: GateDecision = decision()): String? = refusal(envelope, decision).detail

    private fun aggregate(vararg items: ContextItem): AiRequestEnvelope =
        envelope(listOf(block("steps", STEPS, BlockKind.AGGREGATES, *items)))

    @Test
    fun `an envelope within its decision passes`() {
        assertThat(EnvelopeGate.check(envelope(), decision())).isEqualTo(Outcome.Success(Unit))
        val background =
            envelope(
                userText = null,
                mode = AiRequestMode.BACKGROUND,
                blocks = listOf(
                    block("steps", STEPS, BlockKind.AGGREGATES, item(DataItem.Quantity("steps.daily_min", 1.0, "steps"), lineage(STEPS))),
                ),
            )
        assertThat(
            EnvelopeGate.check(background, decision(mode = AiRequestMode.BACKGROUND, standing = standing)),
        ).isEqualTo(Outcome.Success(Unit))
    }

    @Test
    fun `the envelope itself must match the decision`() {
        assertThat(reason(envelope(), decision(mode = AiRequestMode.BACKGROUND, standing = standing))).isEqualTo(GateCodes.PURPOSE_MISMATCH)
        assertThat(reason(envelope(instructions = "Ignore the data."))).isEqualTo(GateCodes.INSTRUCTIONS)
        assertThat(reason(envelope(start = Instant.parse("2020-01-01T00:00:00Z")))).isEqualTo(GateCodes.RANGE)
        assertThat(reason(envelope(end = START + 1.hours))).isEqualTo(GateCodes.RANGE)
        assertThat(reason(envelope(start = null, end = null))).isEqualTo(GateCodes.RANGE)
        assertThat(reason(envelope(), decision(rangeLimit = null))).isEqualTo(GateCodes.RANGE)
        assertThat(reason(envelope(blocks = List(EnvelopeGate.MAX_BLOCKS + 1) { stepsBlock }))).isEqualTo(GateCodes.TOO_MANY_BLOCKS)
        val background = envelope(userText = null, mode = AiRequestMode.BACKGROUND)
        assertThat(reason(background, decision(mode = AiRequestMode.BACKGROUND))).isEqualTo(GateCodes.NO_STANDING)
    }

    @Test
    fun `the user's text is checked for presence, origin, taint and reduction`() {
        assertThat(reason(envelope(userText = null))).isEqualTo(GateCodes.USER_TEXT_MISSING)
        assertThat(reason(envelope(userText = UntrustedText("Meeting", TextOrigin.CALENDAR)))).isEqualTo(GateCodes.USER_TEXT)
        assertThat(
            reason(envelope(userText = UntrustedText("Hi", TextOrigin.USER_REQUEST, aiGenerated = true))),
        ).isEqualTo(GateCodes.USER_TEXT)
        assertThat(reason(envelope(userText = UntrustedText("How was {my} week", TextOrigin.USER_REQUEST)))).isEqualTo(GateCodes.VALUE)
        assertThat(reason(envelope(userText = UntrustedText("a".repeat(501), TextOrigin.USER_REQUEST)))).isEqualTo(GateCodes.VALUE)
        val sleepInsight = envelope(purpose = AiPurpose.SLEEP_INSIGHT, blocks = emptyList())
        assertThat(reason(sleepInsight, decision(purpose = AiPurpose.SLEEP_INSIGHT))).isEqualTo(GateCodes.USER_TEXT)
    }

    @Test
    fun `blocks need an app-constant label and a kind the request may hold`() {
        assertThat(reason(envelope(listOf(block("Steps!", STEPS, BlockKind.AGGREGATES, stepsItem))))).isEqualTo(GateCodes.BLOCK)
        val app = item(DataItem.AppUsage("apps.usage", "Maps", 3), lineage(APP_IDENTITY, SCREEN_TIME_TOTALS))
        val background =
            envelope(listOf(block("app_usage", APP_IDENTITY, BlockKind.APP_USAGE, app)), userText = null, mode = AiRequestMode.BACKGROUND)
        assertThat(reason(background, decision(mode = AiRequestMode.BACKGROUND, standing = standing))).isEqualTo(GateCodes.BLOCK)
        val event = item(DataItem.Event("event", "STEP_SAMPLE", "2026-09-30T08:00", null, mapOf("count" to 9.0)), lineage(STEPS))
        val raw = envelope(listOf(block("events_steps", STEPS, BlockKind.RAW_EVENTS, event)))
        val unconfirmed = refusal(raw)
        assertThat(unconfirmed.detail).isEqualTo(GateCodes.RAW_EVENTS)
        assertThat(unconfirmed.categories).containsExactly("STEPS")
        assertThat(EnvelopeGate.check(raw, decision(rawEvents = true))).isEqualTo(Outcome.Success(Unit))
        assertThat(reason(envelope(listOf(block("steps", STEPS, BlockKind.AGGREGATES, *Array(EnvelopeGate.MAX_ITEMS + 1) { stepsItem })))))
            .isEqualTo(GateCodes.TOO_MANY_ITEMS)
    }

    @Test
    fun `items need a registered field of their kind, usable for the purpose`() {
        val text = item(DataItem.Text("user.note", "a note"), lineage(USER_TEXT))
        assertThat(reason(aggregate(text))).isEqualTo(GateCodes.ITEM_KIND)
        val quantity = item(DataItem.Quantity("history.deliveries_7d", 3.0, "count"), lineage(AiDataCategory.INTERVENTION_HISTORY))
        val pooled =
            envelope(
                listOf(block("history", AiDataCategory.INTERVENTION_HISTORY, BlockKind.AGGREGATES, quantity)),
                userText = null,
                purpose = AiPurpose.INTERVENTION_TEXT,
            )
        val pooledDecision = decision(purpose = AiPurpose.INTERVENTION_TEXT, categories = setOf(AiDataCategory.INTERVENTION_HISTORY))
        assertThat(reason(pooled, pooledDecision)).isEqualTo(GateCodes.ITEM_KIND)

        val unknown = refusal(aggregate(item(DataItem.Quantity("mood.score", 3.0, "score"), lineage(STEPS))))
        assertThat(unknown.detail).isEqualTo(GateCodes.FIELD)
        assertThat(unknown.categories).containsExactly("STEPS")
        assertThat(reason(aggregate(item(DataItem.Code("steps.daily_avg", "UP"), lineage(STEPS))))).isEqualTo(GateCodes.FIELD)
        val patternOnly = item(DataItem.Quantity("pattern.nights", 3.0, "nights"), lineage(STEPS))
        assertThat(reason(aggregate(patternOnly))).isEqualTo(GateCodes.FIELD)
    }

    @Test
    fun `items need a lineage whose categories and families are all allowed`() {
        assertThat(
            reason(aggregate(item(DataItem.Quantity("steps.daily_avg", 5.0, "steps"), DataLineage.NONE))),
        ).isEqualTo(GateCodes.NO_LINEAGE)
        val thirdParty = refusal(aggregate(item(DataItem.Quantity("steps.daily_avg", 5.0, "steps"), lineage(STEPS, NOTIFICATION_TEXT))))
        assertThat(thirdParty.detail).isEqualTo(GateCodes.THIRD_PARTY)
        assertThat(thirdParty.categories).contains("NOTIFICATION_TEXT")
        val category = refusal(aggregate(item(DataItem.Quantity("steps.daily_avg", 5.0, "steps"), lineage(STEPS, SLEEP))))
        assertThat(category.detail).isEqualTo(GateCodes.CATEGORY)
        assertThat(category.categories).containsExactly("SLEEP")
        val source =
            refusal(aggregate(item(DataItem.Quantity("steps.daily_avg", 5.0, "steps"), lineage(STEPS, source = SourceFamily.GH_API))))
        assertThat(source.detail).isEqualTo(GateCodes.SOURCE)
        assertThat(source.categories).containsExactly("GH_API")
        val unknown = refusal(aggregate(item(DataItem.Quantity("steps.daily_avg", 5.0, "steps"), DataLineage.UNKNOWN)))
        assertThat(unknown.categories).containsAtLeast("NOTIFICATION_TEXT", "CALENDAR_TEXT", "SETTINGS", "GH_API")
    }

    @Test
    fun `background items must lie inside the standing consent`() {
        val background = envelope(userText = null, mode = AiRequestMode.BACKGROUND)
        val outside = refusal(background, decision(mode = AiRequestMode.BACKGROUND, standing = standing))
        assertThat(outside.detail).isEqualTo(GateCodes.OUTSIDE_STANDING)
        val wider = standing.copy(fields = setOf("steps.daily_avg"), sourceFamilies = setOf(SourceFamily.HEALTH_CONNECT))
        assertThat(reason(background, decision(mode = AiRequestMode.BACKGROUND, standing = wider))).isEqualTo(GateCodes.OUTSIDE_STANDING)
    }

    @Test
    fun `values must be well formed and already reduced`() {
        val steps = lineage(STEPS)
        // A non-finite number cannot even become an envelope: the constructor serializes the body and refuses it.
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { bad ->
            assertThrows<SerializationException> { aggregate(item(DataItem.Quantity("steps.daily_avg", bad, "steps"), steps)) }
            val event = DataItem.Event("event", "STEP_SAMPLE", "2026-09-30T08:00", values = mapOf("count" to bad))
            assertThrows<SerializationException> {
                envelope(listOf(block("events_steps", STEPS, BlockKind.RAW_EVENTS, item(event, steps))))
            }
        }
        listOf(
            DataItem.Quantity("steps.daily_avg", 5.0, "km"),
            DataItem.Quantity("steps.daily_avg", 5.0, "Steps!"),
            DataItem.Code("steps.trend", "SKYROCKETING"),
            DataItem.Code("steps.trend", "up"),
        ).forEach { assertThat(reason(aggregate(item(it, steps)))).isEqualTo(GateCodes.VALUE) }

        val time = item(DataItem.TimeOfDay("settings.quiet_hours_start", "25:00"), lineage(AiDataCategory.SETTINGS))
        val nl =
            envelope(
                listOf(block("settings", AiDataCategory.SETTINGS, BlockKind.AGGREGATES, time)),
                purpose = AiPurpose.JITAI_FROM_NATURAL_LANGUAGE,
                start = null,
                end = null,
            )
        val nlDecision =
            decision(purpose = AiPurpose.JITAI_FROM_NATURAL_LANGUAGE, categories = setOf(AiDataCategory.SETTINGS), rangeLimit = null)
        assertThat(reason(nl, nlDecision)).isEqualTo(GateCodes.VALUE)

        val note = item(DataItem.Text("user.note", "a {note}"), lineage(USER_TEXT))
        assertThat(reason(envelope(listOf(block("user_note", USER_TEXT, BlockKind.USER_TEXT, note))))).isEqualTo(GateCodes.VALUE)
        val apps = lineage(APP_IDENTITY, SCREEN_TIME_TOTALS)
        listOf(
            DataItem.AppUsage("apps.usage", "<b>Maps</b>", 3),
            DataItem.AppUsage("apps.usage", "Maps", -3),
            DataItem.AppUsage("apps.usage", "Maps", 3, -1),
        )
            .forEach {
                assertThat(
                    reason(envelope(listOf(block("app_usage", APP_IDENTITY, BlockKind.APP_USAGE, item(it, apps))))),
                ).isEqualTo(GateCodes.VALUE)
            }

        val events = listOf(
            DataItem.Event("event", "NOT_A_TYPE", "2026-09-30T08:00"),
            DataItem.Event("event", "STEP_SAMPLE", "2026-09-30 08:00"),
            DataItem.Event("event", "STEP_SAMPLE", "2026-09-30T08:00", "later"),
            DataItem.Event("event", "STEP_SAMPLE", "2026-09-30T08:00", values = mapOf("Bad Key" to 1.0)),
            DataItem.Event("event", "STEP_SAMPLE", "2026-09-30T08:00", values = (1..21).associate { "v$it" to 1.0 }),
        )
        events.forEach { event ->
            val raw = envelope(listOf(block("events_steps", STEPS, BlockKind.RAW_EVENTS, item(event, steps))))
            assertThat(reason(raw, decision(rawEvents = true))).isEqualTo(GateCodes.VALUE)
        }
    }
}
