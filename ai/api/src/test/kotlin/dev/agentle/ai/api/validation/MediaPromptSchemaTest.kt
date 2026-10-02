package dev.agentle.ai.api.validation

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.AiPurpose
import dev.agentle.ai.api.AiRequestMode
import dev.agentle.ai.api.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MediaPromptSchemaTest {
    private val context = OutputValidationContext.forEnvelope(Fixtures.envelope())

    private fun prompt(
        kind: String,
        title: String = "Wind down",
        body: String? = null,
        narration: List<String> = emptyList(),
        card: String = "null",
    ): String = JsonObject(
        mapOf(
            "schemaVersion" to JsonPrimitive(1),
            "kind" to JsonPrimitive(kind),
            "title" to JsonPrimitive(title),
            "body" to (body?.let(::JsonPrimitive) ?: Json.parseToJsonElement("null")),
            "narration" to JsonArray(narration.map(::JsonPrimitive)),
            "card" to Json.parseToJsonElement(card),
        ),
    ).toString()

    private val card = """{"templateId":"STAT","caption":"Your usual bedtime","altText":"A card showing your usual bedtime of 11:40 PM."}"""

    private val recap = listOf(
        "This week you slept about 7 hours a night.",
        "Your usual bedtime was 11:40 PM.",
        "Keep the evenings calm and steady.",
    )

    private fun validate(text: String, context: OutputValidationContext = this.context) =
        AiOutputValidator.validateWith(text, MediaPromptSchema.validator, context)

    private fun issues(text: String, context: OutputValidationContext = this.context): List<Triple<String, String, String?>> =
        (validate(text, context) as OutputValidation.Invalid).issues.map { Triple(it.code, it.path, it.check) }

    @Test
    fun `every kind has a valid form`() {
        listOf(
            prompt("NOTIFICATION_TEXT", body = "A calm evening helps you rest. Maybe dim the screen?"),
            prompt("VOICE_SCRIPT", body = "Good evening. ".repeat(5).trim()),
            prompt("VIDEO_RECAP", title = "Your week", narration = recap),
            prompt("IMAGE_CARD", card = card),
        ).forEach { text -> assertThat(validate(text)).isInstanceOf(OutputValidation.Valid::class.java) }
        val decoded = (validate(prompt("IMAGE_CARD", card = card)) as OutputValidation.Valid).value
        assertThat(
            decoded.card,
        ).isEqualTo(MediaCard(CardTemplate.STAT, "Your usual bedtime", "A card showing your usual bedtime of 11:40 PM."))
    }

    @Test
    fun `fields that do not fit the kind are inconsistent`() {
        assertThat(issues(prompt("NOTIFICATION_TEXT"))).containsExactly(Triple("E111", "/body", null))
        assertThat(issues(prompt("NOTIFICATION_TEXT", body = "Rest well.", narration = recap)))
            .containsExactly(Triple("E111", "/narration", null))
        assertThat(issues(prompt("VOICE_SCRIPT", body = "Rest well.", card = card))).containsExactly(Triple("E111", "/card", null))
        assertThat(issues(prompt("VIDEO_RECAP", narration = recap.take(2)))).containsExactly(Triple("E111", "/narration", null))
        assertThat(issues(prompt("VIDEO_RECAP", body = "Rest well.", narration = recap))).containsExactly(Triple("E111", "/body", null))
        assertThat(issues(prompt("IMAGE_CARD"))).containsExactly(Triple("E111", "/card", null))
    }

    @Test
    fun `a narration longer than a short video allows is refused`() {
        val line = List(34) { "calm" }.joinToString(" ")
        val long = List(6) { line }
        assertThat(MediaPromptSchema.narrationWords(long)).isEqualTo(204)
        assertThat(issues(prompt("VIDEO_RECAP", narration = long))).containsExactly(Triple("E112", "/narration", null))
    }

    @Test
    fun `body limits depend on the kind`() {
        val longBody = "Rest " + "well ".repeat(60).trim() + "."
        assertThat(validate(prompt("VOICE_SCRIPT", body = longBody))).isInstanceOf(OutputValidation.Valid::class.java)
        assertThat(issues(prompt("NOTIFICATION_TEXT", body = longBody))).containsExactly(Triple("E060", "/body", "L12"))
    }

    @Test
    fun `app-owned keys are forbidden`() {
        val withAsset = Json.parseToJsonElement(prompt("NOTIFICATION_TEXT", body = "Rest.")).jsonObject + ("assetId" to JsonPrimitive("x"))
        assertThat(issues(JsonObject(withAsset).toString())).containsExactly(Triple("E005", "/assetId", null))
        val cardWithFields = """{"templateId":"STAT","caption":"c","altText":"a","fields":{"value":"1"}}"""
        assertThat(issues(prompt("IMAGE_CARD", card = cardWithFields))).containsExactly(Triple("E005", "/card/fields", null))
        assertThat(issues(prompt("IMAGE_CARD", card = """{"templateId":"PHOTO","caption":"c","altText":"a"}""")))
            .containsExactly(Triple("E009", "/card/templateId", null))
    }

    @Test
    fun `every text field is linted`() {
        val badCard = """{"templateId":"QUOTE","caption":"Visit evil.com","altText":"<img src=x>"}"""
        assertThat(issues(prompt("IMAGE_CARD", title = "Take melatonin", card = badCard))).containsExactly(
            Triple("E105", "/title", "L11"),
            Triple("E061", "/card/caption", "L1"),
            Triple("E062", "/card/altText", "L4"),
        )
        assertThat(issues(prompt("VIDEO_RECAP", narration = recap.take(2) + "You walked 12345 steps."))).containsExactly(
            Triple("E102", "/narration/2", "L9"),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["Walk for 10 minutes now.", "Time for a ten minute walk?", "You are half way to your goal.", "Only \u00BD left."],
    )
    fun `pooled intervention text with any number is refused`(body: String) {
        val envelope = Fixtures.envelope(purpose = AiPurpose.INTERVENTION_TEXT, mode = AiRequestMode.BACKGROUND)
        val pooled = OutputValidationContext.forPooledText(envelope)
        assertThat(pooled.numbersForbidden).isTrue()
        assertThat(issues(prompt("NOTIFICATION_TEXT", body = body), pooled)).containsExactly(Triple("E113", "/body", "L13"))
    }

    @Test
    fun `pooled intervention text without numbers passes, in every field`() {
        val pooled = OutputValidationContext.forPooledText(Fixtures.envelope(purpose = AiPurpose.INTERVENTION_TEXT))
        assertThat(validate(prompt("NOTIFICATION_TEXT", body = "A short walk could feel good right now."), pooled))
            .isInstanceOf(OutputValidation.Valid::class.java)
        assertThat(issues(prompt("NOTIFICATION_TEXT", title = "Three steps", body = "Walk now."), pooled))
            .containsExactly(Triple("E113", "/title", "L13"))
    }
}
