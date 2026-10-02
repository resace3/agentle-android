package dev.agentle.ai.api.validation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Stages S0-S3 shared by every schema (docs/research/10-jitai-engine-design.md section 11.1): extract one object, bound
 * its size, pre-scan nesting depth and duplicate keys in one linear pass, then parse. No stage evaluates anything.
 */
public object JsonText {
    public const val MAX_DEPTH: Int = 20
    private const val FENCE = "```"
    private val NUMBER = Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?$")
    private val parser = Json

    /**
     * S0. Trims whitespace; if the whole text is one fenced block (three backticks, optional `json`, newline, content,
     * newline, three backticks) takes its inside, the only repair ever made. Returns null unless the result starts with
     * `{` and ends with `}`.
     */
    public fun extract(text: String): String? {
        var candidate = text.trim()
        if (candidate.startsWith(FENCE)) {
            candidate = unfence(candidate) ?: return null
        }
        return candidate.takeIf { it.startsWith("{") && it.endsWith("}") }
    }

    private fun unfence(text: String): String? {
        val firstNewline = text.indexOf('\n')
        if (firstNewline < 0 || !text.endsWith(FENCE) || text.length < firstNewline + 1 + FENCE.length) return null
        val opener = text.substring(FENCE.length, firstNewline).trim()
        if (opener.isNotEmpty() && opener != "json") return null
        val inner = text.substring(firstNewline + 1, text.length - FENCE.length)
        if (!inner.endsWith("\n") || inner.contains(FENCE)) return null
        return inner.trim()
    }

    /**
     * S2. One pass over the characters, tracking strings and escapes. Returns [OutputCodes.TOO_DEEP] when brackets nest
     * deeper than [maxDepth], [OutputCodes.MALFORMED_JSON] when an object repeats a key (the parser's handling of
     * duplicates is undocumented), otherwise null. Malformed input is left to S3.
     */
    public fun preScan(text: String, maxDepth: Int = MAX_DEPTH): String? {
        val frames = ArrayDeque<Frame>()
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '"' -> {
                    val (value, end) = readString(text, index)
                    val top = frames.lastOrNull()
                    if (top is Frame.Obj && top.expectingKey) {
                        if (!top.keys.add(value)) return OutputCodes.MALFORMED_JSON
                        top.expectingKey = false
                    }
                    index = end
                }

                '{', '[' -> {
                    if (frames.size + 1 > maxDepth) return OutputCodes.TOO_DEEP
                    frames.addLast(if (text[index] == '{') Frame.Obj() else Frame.Arr)
                }

                '}', ']' -> frames.removeLastOrNull()

                ',' -> (frames.lastOrNull() as? Frame.Obj)?.expectingKey = true
            }
            index++
        }
        return null
    }

    /** S3. Parses [text] to an object whose every literal is a valid JSON literal; null on any syntax error. */
    public fun parseObject(text: String): JsonObject? {
        val element = try {
            parser.parseToJsonElement(text)
        } catch (expected: IllegalArgumentException) {
            // kotlinx.serialization's SerializationException extends IllegalArgumentException; its message can quote
            // the input, so it is dropped and only the code is reported (privacy-ai-11).
            return null
        }
        return (element as? JsonObject)?.takeIf { literalsValid(it) }
    }

    private fun literalsValid(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.values.all(::literalsValid)
        is JsonArray -> element.all(::literalsValid)
        is JsonNull -> true
        is JsonPrimitive -> element.isString || element.content == "true" || element.content == "false" || NUMBER.matches(element.content)
    }

    /** Reads the string starting at the quote at [start]; returns its decoded value and the index of the closing quote. */
    private fun readString(text: String, start: Int): Pair<String, Int> {
        val out = StringBuilder()
        var index = start + 1
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' -> return out.toString() to index

                char == '\\' && index + 1 < text.length -> {
                    index++
                    index = appendEscape(text, index, out)
                }

                else -> out.append(char)
            }
            index++
        }
        return out.toString() to text.length
    }

    private fun appendEscape(text: String, index: Int, out: StringBuilder): Int {
        when (val escaped = text[index]) {
            'n' -> out.append('\n')

            't' -> out.append('\t')

            'r' -> out.append('\r')

            'b' -> out.append('\b')

            'f' -> out.append('\u000C')

            'u' -> {
                val hex = text.substring(index + 1, minOf(index + 5, text.length))
                val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    return index + 4
                }
                out.append(escaped)
            }

            else -> out.append(escaped)
        }
        return index
    }

    private sealed interface Frame {
        class Obj(val keys: MutableSet<String> = HashSet(), var expectingKey: Boolean = true) : Frame

        data object Arr : Frame
    }
}
