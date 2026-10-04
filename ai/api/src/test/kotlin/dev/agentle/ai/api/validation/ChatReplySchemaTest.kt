package dev.agentle.ai.api.validation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ChatReplySchemaTest {
    private val context = OutputValidationContext(provenance = null)

    private fun validate(text: String) = AiOutputValidator.validateWith(text, ChatReplySchema.validator, context)

    private fun codes(text: String) = (validate(text) as OutputValidation.Invalid).codes

    @Test
    fun `a plain answer has a null dashboard`() {
        val result = validate("""{"schemaVersion":1,"reply":"You unlocked your phone 48 times a day.","dashboard":null}""")

        val value = (result as OutputValidation.Valid).value
        assertThat(value.reply).isEqualTo("You unlocked your phone 48 times a day.")
        assertThat(value.dashboard).isNull()
    }

    @Test
    fun `a dashboard request decodes into a proposal`() {
        val result = validate(
            """{"schemaVersion":1,"reply":"Your sleep dashboard is in the sidebar.",""" +
                """"dashboard":{"title":"Sleep","metrics":["SLEEP_MINUTES","RESTING_HEART_RATE"],"days":14}}""",
        )

        val dashboard = (result as OutputValidation.Valid).value.dashboard
        assertThat(dashboard).isEqualTo(DashboardProposal("Sleep", listOf("SLEEP_MINUTES", "RESTING_HEART_RATE"), 14))
    }

    @Test
    fun `unknown metrics, empty or repeated metric lists and day counts out of range are refused`() {
        fun dashboard(metrics: String, days: Int) =
            """{"schemaVersion":1,"reply":"Done.","dashboard":{"title":"Mine","metrics":$metrics,"days":$days}}"""

        assertThat(codes(dashboard("""["STEPS","CAFFEINE"]""", 7))).isNotEmpty()
        assertThat(codes(dashboard("[]", 7))).isNotEmpty()
        assertThat(codes(dashboard("""["STEPS","STEPS"]""", 7))).isNotEmpty()
        assertThat(codes(dashboard("""["STEPS"]""", 0))).isNotEmpty()
        assertThat(codes(dashboard("""["STEPS"]""", ChatReplySchema.MAX_DAYS + 1))).isNotEmpty()
    }

    @Test
    fun `a missing dashboard, extra fields and links in the text are refused`() {
        assertThat(codes("""{"schemaVersion":1,"reply":"Hi."}""")).isNotEmpty()
        assertThat(codes("""{"schemaVersion":1,"reply":"Hi.","dashboard":null,"script":"run()"}""")).isNotEmpty()
        assertThat(codes("""{"schemaVersion":1,"reply":"See https://attacker.example/claim now.","dashboard":null}""")).isNotEmpty()
    }

    @Test
    fun `the model reads every metric code in the rendered schema and the guide`() {
        ChatReplySchema.METRICS.keys.forEach { code ->
            assertThat(ChatReplySchema.SCHEMA.jsonSchema).contains("\"$code\"")
            assertThat(ChatReplySchema.METRIC_GUIDE).contains("$code: ")
        }
    }
}
