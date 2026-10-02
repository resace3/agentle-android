package dev.agentle.interventions

import dev.agentle.core.testing.TestAgentleClock
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.SnoozeOption
import dev.agentle.jitai.engine.content.ContentRef
import dev.agentle.jitai.engine.content.RenderedIntervention
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

object Fixtures {
    const val IN_APP_TITLE = "You walked 4,210 steps"
    const val IN_APP_BODY = "Resting HR 84: take a short walk"
    const val GENERIC_TITLE = "Agentle"
    const val GENERIC_BODY = "You have a new check-in. Open Agentle to see it."

    fun clock(): TestAgentleClock = TestAgentleClock(Instant.parse("2026-10-01T10:00:00Z"), TimeZone.of("Europe/Berlin"))

    fun intervention(
        key: String = "S|jitai-1|2026-10-01T10:00",
        channel: DeliveryChannel = DeliveryChannel.NOTIFICATION,
        detailed: Boolean = false,
        localOnly: Boolean = true,
        assetId: String? = null,
        nonce: String = "nonce-1",
    ): RenderedIntervention = RenderedIntervention(
        decisionKey = key,
        jitaiId = "jitai-1",
        jitaiName = "Afternoon walk",
        category = JitaiCategory.PHYSICAL_ACTIVITY,
        channel = channel,
        decidedChannel = channel,
        title = IN_APP_TITLE,
        body = IN_APP_BODY,
        postedTitle = if (detailed) IN_APP_TITLE else GENERIC_TITLE,
        postedBody = if (detailed) IN_APP_BODY else GENERIC_BODY,
        detailed = detailed,
        localOnly = localOnly,
        assetId = assetId,
        contentRef = ContentRef.StaticText,
        nonce = nonce,
        timeoutMinutes = 60,
        snoozeOptions = listOf(SnoozeOption.MINUTES_30, SnoozeOption.UNTIL_WINDOW_END),
    )
}
