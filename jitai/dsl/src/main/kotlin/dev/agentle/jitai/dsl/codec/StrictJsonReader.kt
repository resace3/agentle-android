package dev.agentle.jitai.dsl.codec

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/**
 * A strict RFC 8259 reader for R10 §11.1 stages S2 and S3, written so the rules do not depend on parser behavior the
 * kotlinx.serialization guide leaves undocumented: bracket depth is bounded before any recursion gets deep, a key
 * repeated inside one object is an error (not "last one wins"), unquoted tokens other than `true`, `false`, `null` and
 * JSON numbers are syntax errors, and number tokens are kept verbatim (`45.0` stays `45.0`) for literal fidelity.
 *
 * Never throws: every failure is a [Result] with an offset or a JSON-pointer path, never input text.
 */
internal class StrictJsonReader private constructor(private val text: String, private val maxDepth: Int) {
    sealed interface Result {
        data class Ok(val value: JsonElement) : Result

        /** Unexpected character or end of input at [offset] (0-based, UTF-16 index). */
        data class SyntaxError(val offset: Int) : Result

        /** An object or array opened at [offset] is deeper than the limit. */
        data class TooDeep(val offset: Int) : Result

        /** [key] appears twice in the object at [path]. */
        data class DuplicateKey(val key: String, val path: String) : Result

        /** A complete value is followed by more non-whitespace text. */
        data object TrailingText : Result
    }

    private class Stop(val result: Result) : RuntimeException(null, null, false, false)

    private var pos = 0
    private val segments = ArrayList<String>()

    private fun readRoot(): Result = try {
        skipWhitespace()
        val value = readValue(depth = 0)
        skipWhitespace()
        if (pos != text.length) Result.TrailingText else Result.Ok(value)
    } catch (stop: Stop) {
        stop.result
    }

    private fun readValue(depth: Int): JsonElement {
        if (pos >= text.length) fail()
        return when (text[pos]) {
            '{' -> readObject(depth + 1)
            '[' -> readArray(depth + 1)
            '"' -> JsonPrimitive(readString())
            't' -> readWord("true", JsonPrimitive(true))
            'f' -> readWord("false", JsonPrimitive(false))
            'n' -> readWord("null", JsonNull)
            '-', in '0'..'9' -> readNumber()
            else -> fail()
        }
    }

    private fun readObject(depth: Int): JsonObject {
        if (depth > maxDepth) throw Stop(Result.TooDeep(pos))
        pos++
        val members = LinkedHashMap<String, JsonElement>()
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return JsonObject(members)
        }
        while (true) {
            if (peek() != '"') fail()
            val key = readString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            if (members.containsKey(key)) throw Stop(Result.DuplicateKey(key, JsonPointer.of(segments)))
            segments.add(key)
            members[key] = readValue(depth)
            segments.removeAt(segments.lastIndex)
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    pos++
                    skipWhitespace()
                }

                '}' -> {
                    pos++
                    return JsonObject(members)
                }

                else -> fail()
            }
        }
    }

    private fun readArray(depth: Int): JsonArray {
        if (depth > maxDepth) throw Stop(Result.TooDeep(pos))
        pos++
        val items = ArrayList<JsonElement>()
        skipWhitespace()
        if (peek() == ']') {
            pos++
            return JsonArray(items)
        }
        while (true) {
            segments.add(items.size.toString())
            items.add(readValue(depth))
            segments.removeAt(segments.lastIndex)
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    pos++
                    skipWhitespace()
                }

                ']' -> {
                    pos++
                    return JsonArray(items)
                }

                else -> fail()
            }
        }
    }

    private fun readString(): String {
        pos++
        val out = StringBuilder()
        while (pos < text.length) {
            val c = text[pos]
            when {
                c == '"' -> {
                    pos++
                    return out.toString()
                }

                c == '\\' -> readEscape(out)

                c < ' ' -> fail()

                else -> {
                    out.append(c)
                    pos++
                }
            }
        }
        fail()
    }

    private fun readEscape(out: StringBuilder) {
        if (pos + 1 >= text.length) fail()
        val simple = when (text[pos + 1]) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> null
            else -> fail(pos + 1)
        }
        if (simple != null) {
            out.append(simple)
            pos += 2
            return
        }
        if (pos + 6 > text.length) fail(pos + 2)
        var code = 0
        for (i in pos + 2 until pos + 6) {
            val digit = Character.digit(text[i], HEX_RADIX)
            if (digit < 0) fail(i)
            code = code * HEX_RADIX + digit
        }
        out.append(code.toChar())
        pos += 6
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun readNumber(): JsonPrimitive {
        val start = pos
        if (peek() == '-') pos++
        when (peek()) {
            '0' -> pos++
            in '1'..'9' -> skipDigits()
            else -> fail()
        }
        if (peek() == '.') {
            pos++
            if (peek() !in '0'..'9') fail()
            skipDigits()
        }
        if (peek() == 'e' || peek() == 'E') {
            pos++
            if (peek() == '+' || peek() == '-') pos++
            if (peek() !in '0'..'9') fail()
            skipDigits()
        }
        return JsonUnquotedLiteral(text.substring(start, pos))
    }

    private fun skipDigits() {
        while (peek() in '0'..'9') pos++
    }

    private fun readWord(word: String, value: JsonElement): JsonElement {
        if (!text.startsWith(word, pos)) fail()
        pos += word.length
        return value
    }

    private fun expect(c: Char) {
        if (peek() != c) fail()
        pos++
    }

    /** The current character, or NUL at the end of input (NUL never matches an expected character). */
    private fun peek(): Char = if (pos < text.length) text[pos] else '\u0000'

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) pos++
    }

    private fun fail(offset: Int = pos): Nothing = throw Stop(Result.SyntaxError(offset))

    companion object {
        const val DEFAULT_MAX_DEPTH: Int = 20
        private const val HEX_RADIX = 16

        /** Reads exactly one JSON value spanning the whole [text]. */
        fun read(text: String, maxDepth: Int = DEFAULT_MAX_DEPTH): Result = StrictJsonReader(text, maxDepth).readRoot()
    }
}

/** RFC 6901 JSON pointers. */
internal object JsonPointer {
    fun escape(segment: String): String = segment.replace("~", "~0").replace("/", "~1")

    fun of(segments: List<String>): String = segments.joinToString("") { "/" + escape(it) }

    fun child(path: String, key: String): String = path + "/" + escape(key)

    fun child(path: String, index: Int): String = "$path/$index"
}
