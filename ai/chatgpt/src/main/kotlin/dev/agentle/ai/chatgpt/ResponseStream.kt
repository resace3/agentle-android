package dev.agentle.ai.chatgpt

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException

/** One dispatched server-sent event: its `event:` name (if any) and its `data:` lines joined with LF. */
internal data class SseEvent(val name: String?, val data: String) {
    override fun toString(): String = "SseEvent(name=$name, bytes=${data.length})"
}

/** An event (or a line) is larger than the reader allows; the stream is refused. */
internal class EventTooLargeException : IOException("server-sent event too large")

/**
 * The event-stream interpretation of the WHATWG HTML standard (§9.2.6) as far as docs/research/06 §4.3 needs it:
 * lines end with CRLF, LF or a lone CR; the `data:` lines of one event are joined with LF; comments (`:`), `id`,
 * `retry` and unknown fields are ignored; an event is dispatched at an empty line, and an event the stream ends in
 * the middle of is discarded. One event, all its lines together, may hold at most [maxEventBytes]; the reader never
 * buffers much more than that, whatever the server sends.
 */
internal class SseEventReader(private val source: BufferedSource, private val maxEventBytes: Long) {
    private var skipLineFeed = false

    /** The next event, or null at the end of the stream. Throws [EventTooLargeException] and transport errors. */
    fun next(): SseEvent? {
        var name: String? = null
        val data = ArrayList<String>()
        var eventBytes = 0L
        while (true) {
            val line = readLine(maxEventBytes - eventBytes) ?: return null
            eventBytes += line.bytes + 1
            if (eventBytes > maxEventBytes) throw EventTooLargeException()
            val text = line.text
            when {
                text.isEmpty() && data.isNotEmpty() -> return SseEvent(name, data.joinToString("\n"))

                text.isEmpty() -> {
                    name = null
                    eventBytes = 0
                }

                text.startsWith(":") -> Unit

                else -> {
                    val field = text.substringBefore(':')
                    val value = if (':' in text) text.substringAfter(':').removePrefix(" ") else ""
                    when (field) {
                        "event" -> name = value
                        "data" -> data += value
                        else -> Unit
                    }
                }
            }
        }
    }

    private class Line(val text: String, val bytes: Long)

    /** One line without its terminator, or null at the end of the stream (an unterminated last line is discarded). */
    private fun readLine(limit: Long): Line? {
        if (skipLineFeed) {
            skipLineFeed = false
            if (!source.request(1)) return null
            if (source.buffer[0] == LF) source.skip(1)
        }
        var scanned = 0L
        var line: Line? = null
        while (line == null) {
            val buffer: Buffer = source.buffer
            val index = buffer.indexOfElement(TERMINATORS, scanned)
            if (index >= 0) {
                if (index > limit) throw EventTooLargeException()
                val text = buffer.readUtf8(index)
                skipLineFeed = buffer.readByte() == CR
                line = Line(text, index)
            } else {
                if (buffer.size > limit) throw EventTooLargeException()
                scanned = buffer.size
                if (!source.request(buffer.size + 1)) return null
            }
        }
        return line
    }

    private companion object {
        const val CR: Byte = 13
        const val LF: Byte = 10
        val TERMINATORS = "\r\n".encodeUtf8()
    }
}

/**
 * Turns a Responses API event stream into the completed text (docs/research/06 §4.3, §8.3): deltas of
 * `response.output_text.delta` are collected, success is only `response.completed` (its output text is the fallback
 * when no delta came), `response.failed` and `error` events map by code, `response.incomplete` is its own failure,
 * `[DONE]` and other event types are ignored, and a stream that ends or breaks before `response.completed` is
 * interrupted. Partial text is never returned. Nothing of an event's content is logged or put into an error.
 */
internal class ResponseStreamParser(private val maxEventBytes: Long, private val maxTextChars: Long) {
    fun parse(source: BufferedSource, requestId: String?): SiwcResult<ResponseText> {
        val reader = SseEventReader(source, maxEventBytes)
        val text = StringBuilder()
        return try {
            var result: SiwcResult<ResponseText>? = null
            while (result == null) {
                val event = reader.next() ?: return failed(SiwcErrorMapper.streamInterrupted(requestId))
                result = handle(event, text, requestId)
            }
            result
        } catch (_: EventTooLargeException) {
            failed(SiwcErrorMapper.invalidResponse(requestId, "event_too_large"))
        } catch (_: IOException) {
            // Cut connection, read timeout between events, or the call was cancelled.
            failed(SiwcErrorMapper.streamInterrupted(requestId))
        }
    }

    private fun handle(event: SseEvent, text: StringBuilder, requestId: String?): SiwcResult<ResponseText>? {
        if (event.data.isBlank() || event.data.trim() == DONE) return null
        val json = parseObject(event.data) ?: return failed(SiwcErrorMapper.invalidResponse(requestId, "event"))
        return when (json.string("type") ?: event.name) {
            "response.output_text.delta" -> {
                json.string("delta")?.let(text::append)
                if (text.length > maxTextChars) failed(SiwcErrorMapper.invalidResponse(requestId, "text_too_large")) else null
            }

            "response.completed" -> completed(json["response"] as? JsonObject, text, requestId)

            "response.failed" -> failed(SiwcErrorMapper.streamEvent(errorCode(json["response"]), requestId))

            "response.incomplete" -> failed(SiwcErrorMapper.incomplete(requestId))

            "error" -> failed(SiwcErrorMapper.streamEvent(json.string("code") ?: errorCode(json), requestId))

            else -> null
        }
    }

    private fun completed(response: JsonObject?, deltas: StringBuilder, requestId: String?): SiwcResult<ResponseText> {
        val text = if (deltas.isNotEmpty()) deltas.toString() else outputText(response)
        return SiwcResult.Ok(ResponseText(text, response?.string("model"), requestId))
    }

    /** The `output_text` parts of the message items of a completed response, in order. */
    private fun outputText(response: JsonObject?): String {
        val output = response?.get("output") as? JsonArray ?: return ""
        return output.filterIsInstance<JsonObject>()
            .filter { it.string("type") == "message" }
            .flatMap { (it["content"] as? JsonArray).orEmpty() }
            .filterIsInstance<JsonObject>()
            .filter { it.string("type") == "output_text" }
            .mapNotNull { it.string("text") }
            .joinToString("")
    }

    private fun errorCode(element: JsonElement?): String? = ((element as? JsonObject)?.get("error") as? JsonObject)?.string("code")

    private fun parseObject(data: String): JsonObject? = try {
        Json.parseToJsonElement(data) as? JsonObject
    } catch (_: SerializationException) {
        // The message embeds the input (model output); it is dropped (red team privacy-ai-11).
        null
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun failed(failure: SiwcFailure): SiwcResult<ResponseText> = SiwcResult.Failed(failure)

    private companion object {
        const val DONE = "[DONE]"
    }
}
