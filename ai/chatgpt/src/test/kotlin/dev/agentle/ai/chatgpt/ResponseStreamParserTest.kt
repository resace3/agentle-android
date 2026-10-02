package dev.agentle.ai.chatgpt

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.fakes.chatgpt.ChatGptFixtures
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException

/**
 * Golden tests: the fake's literal R06 §9.3/§9.4 stream bodies fed straight into the client's parser (red team
 * oauth-security-14), plus the event-stream framing rules and the R06 §4.3 caps.
 */
class ResponseStreamParserTest {
    private val parser = ResponseStreamParser(maxEventBytes = 4L * 1024 * 1024, maxTextChars = 16L * 1024 * 1024)

    private fun parse(body: String, using: ResponseStreamParser = parser): SiwcResult<ResponseText> =
        using.parse(Buffer().writeUtf8(body), "req_9")

    private fun failure(result: SiwcResult<*>): SiwcFailure = (result as SiwcResult.Failed).failure

    @ParameterizedTest(name = "line ending {0}")
    @ValueSource(strings = ["LF", "CRLF", "CR"])
    fun `the R06 9_3 success stream yields the completed text with any line ending`(ending: String) {
        val separator = mapOf("LF" to "\n", "CRLF" to "\r\n", "CR" to "\r").getValue(ending)

        val result = parse(ChatGptFixtures.STREAM_SUCCESS.replace("\n", separator))

        assertThat(result).isEqualTo(SiwcResult.Ok(ResponseText(ChatGptFixtures.STREAM_TEXT, ChatGptFixtures.MODEL, "req_9")))
    }

    @Test
    fun `R06 9_4 mid-stream usage limit maps to RATE_LIMITED PLAN_LIMIT and drops the partial text`() {
        val failure = failure(parse(ChatGptFixtures.STREAM_FAILED_USAGE_LIMIT))

        assertThat(failure.status).isEqualTo(SiwcStatus(SiwcState.RATE_LIMITED, SiwcReason.PLAN_LIMIT, "req_9"))
        assertThat(failure.error).isEqualTo(AppError.NotEligible("usage_limit_reached"))
    }

    @Test
    fun `R06 9_4 mid-stream usage unavailable maps to PLAN_USAGE_UNAVAILABLE`() {
        val failure = failure(parse(ChatGptFixtures.STREAM_FAILED_USAGE_UNAVAILABLE))

        assertThat(failure.status).isEqualTo(SiwcStatus(SiwcState.PLAN_USAGE_UNAVAILABLE, SiwcReason.USAGE_UNAVAILABLE, "req_9"))
    }

    @Test
    fun `R06 9_4 stream incomplete is its own failure`() {
        val failure = failure(parse(ChatGptFixtures.STREAM_INCOMPLETE))

        assertThat(failure.status).isEqualTo(SiwcStatus(SiwcState.SERVER_ERROR, SiwcReason.INCOMPLETE, "req_9"))
        assertThat(failure.error).isEqualTo(AppError.RemoteServerError(200, "response_incomplete"))
    }

    @Test
    fun `R06 9_4 error event maps by its code`() {
        val failure = failure(parse(ChatGptFixtures.STREAM_ERROR_EVENT))

        assertThat(failure.status).isEqualTo(SiwcStatus(SiwcState.SERVER_ERROR, SiwcReason.UPSTREAM, "req_9"))
    }

    @Test
    fun `an error event with a nested error object and an unknown code is an upstream failure`() {
        val failure = failure(parse("data: {\"type\":\"error\",\"error\":{\"code\":\"something_new\"}}\n\n"))

        assertThat(failure.status?.reason).isEqualTo(SiwcReason.UPSTREAM)
    }

    @Test
    fun `a stream that ends before response completed is interrupted, whatever text arrived`() {
        val truncated = ChatGptFixtures.STREAM_SUCCESS.substringBefore("event: response.completed")

        val failure = failure(parse(truncated))

        assertThat(failure.status).isEqualTo(SiwcStatus(SiwcState.SERVER_ERROR, SiwcReason.STREAM_INTERRUPTED, "req_9"))
        assertThat(failure.error).isEqualTo(AppError.NetworkUnavailable("stream_interrupted"))
    }

    @Test
    fun `a completed event without its terminating blank line is discarded as the stream ends`() {
        val unterminated = ChatGptFixtures.STREAM_SUCCESS.trimEnd('\n')

        assertThat(failure(parse(unterminated)).status?.reason).isEqualTo(SiwcReason.STREAM_INTERRUPTED)
    }

    @Test
    fun `a broken connection mid-stream is an interruption`() {
        val source = object : Source {
            private var sent = false

            override fun read(sink: Buffer, byteCount: Long): Long {
                if (sent) throw IOException("connection reset")
                sent = true
                val chunk = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hi\"}\n\n"
                sink.writeUtf8(chunk)
                return chunk.length.toLong()
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() = Unit
        }

        val result = parser.parse(source.buffer(), null)

        assertThat(failure(result).status?.reason).isEqualTo(SiwcReason.STREAM_INTERRUPTED)
    }

    @Test
    fun `comments, DONE, unknown fields and unknown event types are ignored`() {
        val body = ": keep-alive\n\n" +
            "id: 7\nretry: 1000\ndata: {\"type\":\"response.in_progress\"}\n\n" +
            "data: [DONE]\n\n" +
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}\n\n" +
            "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"model\":\"m1\"}}\n\n"

        assertThat(parse(body)).isEqualTo(SiwcResult.Ok(ResponseText("Hello", "m1", "req_9")))
    }

    @Test
    fun `without deltas the output text of the completed response is used`() {
        val body = "data: {\"type\":\"response.completed\",\"response\":{\"model\":\"m1\",\"output\":[" +
            "{\"type\":\"reasoning\"}," +
            "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Part one. \"},{\"type\":\"refusal\"}," +
            "{\"type\":\"output_text\",\"text\":\"Part two.\"}]}]}}\n\n"

        assertThat(parse(body)).isEqualTo(SiwcResult.Ok(ResponseText("Part one. Part two.", "m1", "req_9")))
    }

    @Test
    fun `the event type falls back to the event name and data lines are joined with LF`() {
        val body = "event: response.output_text.delta\ndata: {\"delta\":\ndata: \"a\"}\n\n" +
            "event: response.completed\ndata: {}\n\n"

        assertThat(parse(body)).isEqualTo(SiwcResult.Ok(ResponseText("a", null, "req_9")))
    }

    @Test
    fun `data that is not a JSON object is an invalid response`() {
        val failure = failure(parse("data: not json\n\n"))

        assertThat(failure.error).isEqualTo(AppError.ParsingError("invalid_event"))
    }

    @Test
    fun `an event larger than the cap is refused`() {
        val small = ResponseStreamParser(maxEventBytes = 64, maxTextChars = 1024)

        val failure = failure(parse("data: {\"type\":\"response.output_text.delta\",\"delta\":\"${"x".repeat(100)}\"}\n\n", small))

        assertThat(failure.error).isEqualTo(AppError.ParsingError("invalid_event_too_large"))
    }

    @Test
    fun `a line without an end larger than the cap is refused without buffering the rest`() {
        val small = ResponseStreamParser(maxEventBytes = 64, maxTextChars = 1024)

        val failure = failure(parse("data: " + "x".repeat(10_000), small))

        assertThat(failure.error).isEqualTo(AppError.ParsingError("invalid_event_too_large"))
    }

    @Test
    fun `many small lines of one event are capped together`() {
        val small = ResponseStreamParser(maxEventBytes = 64, maxTextChars = 1024)

        val failure = failure(parse("data: 1234567890\n".repeat(10) + "\n", small))

        assertThat(failure.error).isEqualTo(AppError.ParsingError("invalid_event_too_large"))
    }

    @Test
    fun `text beyond the cap is refused`() {
        val small = ResponseStreamParser(maxEventBytes = 1024, maxTextChars = 8)
        val delta = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"12345\"}\n\n"

        val failure = failure(parse(delta + delta, small))

        assertThat(failure.error).isEqualTo(AppError.ParsingError("invalid_text_too_large"))
    }

    @Test
    fun `an event reader returns null at the end and discards an unfinished event`() {
        val reader = SseEventReader(Buffer().writeUtf8("event: a\ndata: 1\n\ndata: 2\n"), 1024)

        assertThat(reader.next()).isEqualTo(SseEvent("a", "1"))
        assertThat(reader.next()).isNull()
        assertThat(SseEvent("a", "secret").toString()).doesNotContain("secret")
    }
}
