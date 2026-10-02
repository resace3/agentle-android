@file:OptIn(AiEnvelopeConstruction::class)

package dev.agentle.ai.api

import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.DataLineage
import dev.agentle.core.model.SourceFamily
import dev.agentle.core.model.UntrustedText
import kotlin.time.Instant

/** Envelopes for tests of this module (tests may opt in to [AiEnvelopeConstruction]; production code outside :ai:context may not). */
internal object Fixtures {
    val CREATED: Instant = Instant.parse("2026-10-01T12:00:00Z")
    val RANGE_START: Instant = Instant.parse("2026-09-24T04:00:00Z")
    val RANGE_END: Instant = Instant.parse("2026-10-01T04:00:00Z")
    const val INSTRUCTIONS: String = "sleep-insight-v1. Explain the sleep summary in plain words. Reply with one JSON object."

    fun item(
        item: DataItem,
        category: AiDataCategory = AiDataCategory.SLEEP,
        source: SourceFamily = SourceFamily.HEALTH_CONNECT,
    ): ContextItem = ContextItem(item, DataLineage.of(category, source))

    fun sleepBlock(): ContextBlock = ContextBlock(
        "sleep_summary_7d",
        AiDataCategory.SLEEP,
        BlockKind.AGGREGATES,
        listOf(
            item(DataItem.Quantity("sleep.minutes_avg_7d", 432.0, "min")),
            item(DataItem.TimeOfDay("sleep.bedtime_median_7d", "23:40")),
            item(DataItem.Code("sleep.trend_7d", "STABLE")),
        ),
    )

    fun stepsBlock(): ContextBlock = ContextBlock(
        "steps_7d",
        AiDataCategory.STEPS,
        BlockKind.AGGREGATES,
        listOf(item(DataItem.Quantity("steps.avg_7d", 6250.0, "steps"), AiDataCategory.STEPS, SourceFamily.ON_DEVICE)),
    )

    fun envelope(
        blocks: List<ContextBlock> = listOf(sleepBlock()),
        userText: UntrustedText? = null,
        instructions: String = INSTRUCTIONS,
        purpose: AiPurpose = AiPurpose.SLEEP_INSIGHT,
        mode: AiRequestMode = AiRequestMode.USER_INITIATED,
        requestId: String = "req-1",
        rangeStart: Instant? = RANGE_START,
        rangeEnd: Instant? = RANGE_END,
        consentVersion: Int = 1,
    ): AiRequestEnvelope = AiRequestEnvelope(
        requestId = requestId,
        purpose = purpose,
        mode = mode,
        instructions = instructions,
        userText = userText,
        blocks = blocks,
        rangeStart = rangeStart,
        rangeEnd = rangeEnd,
        createdAt = CREATED,
        consentVersion = consentVersion,
    )
}
