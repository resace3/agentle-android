package dev.agentle.ai.context

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.AiEnvelopeJson
import dev.agentle.ai.api.AiPurpose
import dev.agentle.ai.api.AiRequestMode
import dev.agentle.ai.api.BlockKind
import dev.agentle.ai.api.DataItem
import dev.agentle.core.common.AppError
import dev.agentle.core.common.errorOrNull
import dev.agentle.core.common.getOrThrow
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.AiDataCategory.ACTIVITY
import dev.agentle.core.model.AiDataCategory.APP_IDENTITY
import dev.agentle.core.model.AiDataCategory.BODY
import dev.agentle.core.model.AiDataCategory.CALENDAR_BUSY
import dev.agentle.core.model.AiDataCategory.DEVICE_STATE
import dev.agentle.core.model.AiDataCategory.GOALS
import dev.agentle.core.model.AiDataCategory.HEART
import dev.agentle.core.model.AiDataCategory.INTERVENTION_HISTORY
import dev.agentle.core.model.AiDataCategory.LOCATION_CLASS
import dev.agentle.core.model.AiDataCategory.NOTIFICATION_COUNTS
import dev.agentle.core.model.AiDataCategory.SCREEN_TIME_TOTALS
import dev.agentle.core.model.AiDataCategory.SELF_REPORTS
import dev.agentle.core.model.AiDataCategory.SETTINGS
import dev.agentle.core.model.AiDataCategory.SLEEP
import dev.agentle.core.model.AiDataCategory.STEPS
import dev.agentle.core.model.AiDataCategory.USER_TEXT
import dev.agentle.core.model.ConnectorIds
import dev.agentle.core.model.DataLineage
import dev.agentle.core.model.EventType
import dev.agentle.core.model.SourceFamily
import dev.agentle.core.model.TextOrigin
import dev.agentle.core.model.UntrustedText
import dev.agentle.core.time.ClosedOpenRange
import dev.agentle.fakes.ai.FakeDailyMetric
import dev.agentle.fakes.ai.FakeDailyRow
import dev.agentle.fakes.ai.InMemoryAiContextDataSource
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** privacy-ai-13: the preview shows the frozen input, and the bytes sent are the bytes previewed. */
class PreviewBindingTest {
    @Test
    fun `the preview shows every value of the frozen input and is bound to the bytes that are sent (privacy-ai-13)`() = runTest {
        val world = World(
            data = ScriptedDataSource(
                aggregates = listOf(quantity("steps.daily_avg", 6250.0, "steps", lineage(STEPS))),
                apps = listOf(appUsage("Maps <b>", 42)),
                texts = listOf(note("Slept well")),
            ),
            hooked = true,
        )
        world.grant(AiPurpose.GENERAL_QUESTION, STEPS, APP_IDENTITY, SCREEN_TIME_TOTALS, USER_TEXT)
        val envelope = world.engine.build(AiPurpose.GENERAL_QUESTION, "How did I sleep?").getOrThrow()
        val preview = world.engine.preview(envelope)

        assertThat(preview.blocks.flatMap { it.items }).containsExactly(
            AiPreviewItem("steps.daily_avg", "6250 steps"),
            AiPreviewItem("apps.usage", "Maps b: 42 min"),
            AiPreviewItem("user.note", "Slept well"),
        )
        assertThat(preview.userRequestText).isEqualTo("How did I sleep")
        assertThat(preview.toString()).doesNotContain("Slept")
        assertThat(envelope.maxOutputTokens).isEqualTo(PurposePolicy.spec(AiPurpose.GENERAL_QUESTION).caps.maxOutputTokens)

        // Data changes between preview and send: the envelope still sends the previewed bytes; a rebuild is a new preview.
        world.scripted.texts = listOf(note("Opened a dating app"))
        world.send(envelope).getOrThrow()
        val sent = world.provider.journal.single { it.sent }
        assertThat(sent.inputSha256).isEqualTo(preview.inputSha256)
        assertThat(sent.sentDataInput).doesNotContain("dating")
        val rebuilt = world.engine.build(AiPurpose.GENERAL_QUESTION, "How did I sleep?").getOrThrow()
        assertThat(world.engine.preview(rebuilt).inputSha256).isNotEqualTo(preview.inputSha256)

        val record = world.audit[envelope.requestId]!!
        assertThat(record.payloadSha256).isEqualTo(preview.inputSha256)
        assertThat(record.initiator).isEqualTo(AiInitiator.USER)
        assertThat(record.promptVersion).isEqualTo(AiInstructionSet.VERSION)
        assertThat(record.accountSubHash).hasLength(64)
        assertThat(record.accountSubHash).doesNotContain(ACCOUNT)
    }

    @Test
    fun `a later round needs a new preview only when it adds personal content (privacy-ai-13)`() = runTest {
        val world = World(data = ScriptedDataSource(texts = listOf(note("Slept well"))))
        world.grant(AiPurpose.JITAI_FROM_NATURAL_LANGUAGE, SETTINGS)
        val first = world.engine.build(AiPurpose.JITAI_FROM_NATURAL_LANGUAGE, "Remind me to walk").getOrThrow()
        val sameContent = world.engine.build(AiPurpose.JITAI_FROM_NATURAL_LANGUAGE, "Remind me to walk").getOrThrow()
        val clarified = world.engine.build(AiPurpose.JITAI_FROM_NATURAL_LANGUAGE, "Remind me to walk after lunch").getOrThrow()

        assertThat(AiRequestPreview.needsNewPreview(first, sameContent)).isFalse()
        assertThat(AiRequestPreview.needsNewPreview(first, clarified)).isTrue()
    }
}
