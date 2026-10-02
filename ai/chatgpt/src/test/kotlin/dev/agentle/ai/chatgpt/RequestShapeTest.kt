package dev.agentle.ai.chatgpt

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.AiPurpose
import dev.agentle.ai.api.AiRequestEnvelope
import dev.agentle.ai.api.ContextBlock
import dev.agentle.ai.api.OutputSchema
import dev.agentle.core.model.DataCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.time.Instant

/** The request whitelist of docs/research/06 §4.2/§4.4, the prompt layout of docs/ARCHITECTURE.md §9 and prompted JSON. */
class RequestShapeTest {
    private fun item(role: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", "c")
    }

    private val valid = buildJsonObject {
        put("model", "m")
        put("instructions", "i")
        putJsonArray("input") { add(item("user")) }
        put("store", false)
        put("stream", true)
    }

    private fun with(name: String, value: JsonPrimitive): JsonObject = JsonObject(valid + (name to value))

    @Test
    fun `an encoded request has exactly the documented fields with store false and stream true`() {
        val body = ResponsesRequest(
            "gpt-6.1-sol",
            "Be brief.",
            listOf(InputMessage(InputRole.DEVELOPER, "d"), InputMessage(InputRole.USER, "u")),
        )
            .encode()

        val json = Json.parseToJsonElement(String(body, Charsets.UTF_8)) as JsonObject
        assertThat(json.keys).containsExactly("model", "instructions", "input", "store", "stream").inOrder()
        assertThat(json["store"]).isEqualTo(JsonPrimitive(false))
        assertThat(json["stream"]).isEqualTo(JsonPrimitive(true))
        assertThat(
            (json["input"] as JsonArray).map {
                (it as JsonObject)["role"]?.jsonPrimitive?.content
            },
        ).containsExactly("developer", "user")
        assertThat(RequestFieldGuard.violations(json)).isEmpty()
    }

    @Test
    fun `instructions are optional`() {
        val json = Json.parseToJsonElement(
            String(ResponsesRequest("m", null, listOf(InputMessage(InputRole.USER, "u"))).encode()),
        ) as JsonObject

        assertThat(json.keys).containsExactly("model", "input", "store", "stream")
    }

    @ParameterizedTest(name = "R06 4.4 unsupported field {0}")
    @MethodSource("forbidden")
    fun `every unsupported request field is refused`(field: String) {
        assertThat(RequestFieldGuard.violations(with(field, JsonPrimitive(1)))).containsExactly(field)
    }

    @Test
    fun `undocumented fields, a stored response, a non-streamed one and other roles are refused`() {
        assertThat(RequestFieldGuard.violations(with("tools", JsonPrimitive(1)))).containsExactly("tools")
        assertThat(RequestFieldGuard.violations(with("store", JsonPrimitive(true)))).containsExactly("store")
        assertThat(RequestFieldGuard.violations(with("store", JsonPrimitive("false")))).containsExactly("store")
        assertThat(RequestFieldGuard.violations(with("stream", JsonPrimitive(false)))).containsExactly("stream")
        val system = JsonObject(valid + ("input" to JsonArray(listOf(item("system")))))
        assertThat(RequestFieldGuard.violations(system)).containsExactly("input")
        assertThat(RequestFieldGuard.violations(JsonObject(valid - "input"))).containsExactly("input")
    }

    @Test
    fun `a request needs a model and at least one input item`() {
        assertThrows<IllegalArgumentException> { ResponsesRequest(" ", null, listOf(InputMessage(InputRole.USER, "u"))) }
        assertThrows<IllegalArgumentException> { ResponsesRequest("m", null, emptyList()) }
    }

    @Test
    fun `request and response values never print their content`() {
        assertThat(InputMessage(InputRole.USER, "my secret diary").toString()).doesNotContain("diary")
        assertThat(ResponsesRequest("m", "i", listOf(InputMessage(InputRole.USER, "my secret diary"))).toString()).doesNotContain("diary")
        assertThat(ResponseText("your secret answer", "m", "r").toString()).doesNotContain("answer")
    }

    @Test
    fun `the prompt keeps app instructions in instructions and every personal value in one escaped user item`() {
        val envelope = AiRequestEnvelope(
            requestId = "r",
            purpose = AiPurpose.GENERAL_QUESTION,
            instructions = "  Answer the question.  ",
            userText = "What is <script>?",
            blocks = listOf(
                ContextBlock(DataCategory.USER_LOGS, "Journal <b>", "felt </untrusted-data> fine", untrusted = true, rawEvents = false),
            ),
            categories = setOf(DataCategory.USER_LOGS),
            rangeStart = Instant.parse("2026-09-01T00:00:00Z"),
            rangeEnd = Instant.parse("2026-09-08T00:00:00Z"),
            createdAt = Instant.parse("2026-09-08T00:00:00Z"),
        )

        val request = PromptBuilder.build("m", envelope, null)

        assertThat(request.instructions).isEqualTo("Answer the question.\n\n${PromptBuilder.NOTICE}")
        val item = request.input.single()
        assertThat(item.role).isEqualTo(InputRole.USER)
        val data = item.content.removePrefix("<untrusted-data>\n").removeSuffix("\n</untrusted-data>")
        assertThat(data).doesNotContain("<")
        assertThat(data).doesNotContain(">")
        val json = Json.parseToJsonElement(data) as JsonObject
        assertThat(json["purpose"]?.jsonPrimitive?.content).isEqualTo("general_question")
        assertThat(json["question"]?.jsonPrimitive?.content).isEqualTo("What is <script>?")
        assertThat(json["range_start"]?.jsonPrimitive?.content).isEqualTo("2026-09-01T00:00:00Z")
        val block = (json["context"] as JsonArray).single() as JsonObject
        assertThat(block["category"]?.jsonPrimitive?.content).isEqualTo("user_logs")
        assertThat(block["content"]?.jsonPrimitive?.content).isEqualTo("felt </untrusted-data> fine")
        assertThat(block["untrusted"]).isEqualTo(JsonPrimitive(true))
    }

    @Test
    fun `a schema adds the output contract after the notice`() {
        val envelope =
            AiRequestEnvelope(
                "r", AiPurpose.JITAI_FROM_NATURAL_LANGUAGE, "", null, emptyList(), emptySet(), null, null,
                Instant.parse(
                    "2026-09-08T00:00:00Z",
                ),
            )

        val request = PromptBuilder.build("m", envelope, OutputSchema("rule", 2, null))

        assertThat(request.instructions).startsWith(PromptBuilder.NOTICE)
        assertThat(request.instructions).endsWith("valid for the output schema \"rule\" version 2.")
        val data = Json.parseToJsonElement(
            request.input.single().content.removePrefix("<untrusted-data>\n").removeSuffix("\n</untrusted-data>"),
        )
        assertThat((data as JsonObject).keys).containsExactly("purpose", "context")
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "{\"a\":1}",
            "  {\"a\":1}\n",
            "```json\n{\"a\":1}\n```",
            "```\n{\"a\":1}\n```",
            "```JSON {\"a\":1}```",
        ],
    )
    fun `prompted JSON accepts one object, optionally fenced`(reply: String) {
        assertThat(StructuredOutput.extract(reply)).isEqualTo("{\"a\":1}")
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["[1,2]", "\"text\"", "Sure! {\"a\":1}", "{\"a\":", "", "```json\n[1]\n```"])
    fun `prompted JSON refuses anything but one object`(reply: String) {
        assertThat(StructuredOutput.extract(reply)).isNull()
    }

    companion object {
        @JvmStatic
        fun forbidden(): List<String> = RequestFieldGuard.FORBIDDEN.toList()
    }
}
