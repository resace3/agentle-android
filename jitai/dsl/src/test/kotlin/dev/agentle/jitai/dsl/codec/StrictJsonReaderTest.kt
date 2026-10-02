package dev.agentle.jitai.dsl.codec

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.jitai.dsl.codec.StrictJsonReader.Result
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** R10 §11.1 S2/S3: the strict reader (depth, duplicate keys, syntax, verbatim number tokens). */
internal class StrictJsonReaderTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    fun `rejected documents report an offset or a path, never text`(row: String, text: String, expected: Result) {
        assertWithMessage(row).that(StrictJsonReader.read(text)).isEqualTo(expected)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("numbers")
    fun `number tokens are kept verbatim`(token: String) {
        val result = StrictJsonReader.read("[$token]") as Result.Ok

        assertThat((result.value as JsonArray)[0].toString()).isEqualTo(token)
    }

    @Test
    fun `escapes decode to their characters`() {
        val text = """{"s": "\"\\\/\b\f\n\r\té€", "e": "", "u": "😀"}"""
        val value = (StrictJsonReader.read(text) as Result.Ok).value.jsonObject

        assertThat(value.getValue("s").jsonPrimitive.content).isEqualTo("\"\\/\b\u000C\n\r\té€")
        assertThat(value.getValue("e").jsonPrimitive.content).isEmpty()
        assertThat(value.getValue("u").jsonPrimitive.content).isEqualTo("😀")
    }

    @Test
    fun `whitespace around the value and empty containers are accepted`() {
        val result = StrictJsonReader.read(" \t\r\n{ \"a\" : [ ] , \"b\" : { } , \"c\" : [ true , false , null ] }\n")

        assertThat(result).isInstanceOf(Result.Ok::class.java)
        assertThat((result as Result.Ok).value.toString()).isEqualTo("""{"a":[],"b":{},"c":[true,false,null]}""")
        assertThat(StrictJsonReader.read("\"x\"")).isEqualTo(Result.Ok(JsonPrimitive("x")))
    }

    @Test
    fun `depth is bounded before recursion gets deep`() {
        val deep = "[".repeat(20) + "]".repeat(20)
        val tooDeep = "[".repeat(21) + "]".repeat(21)
        val objects = "{\"a\":".repeat(21) + "1" + "}".repeat(21)
        val huge = "[".repeat(100_000)

        assertThat(StrictJsonReader.read(deep)).isInstanceOf(Result.Ok::class.java)
        assertThat(StrictJsonReader.read(tooDeep)).isEqualTo(Result.TooDeep(20))
        assertThat(StrictJsonReader.read(objects)).isEqualTo(Result.TooDeep(100))
        assertThat(StrictJsonReader.read(huge)).isEqualTo(Result.TooDeep(20))
        assertThat(StrictJsonReader.read("[[1]]", maxDepth = 1)).isEqualTo(Result.TooDeep(1))
    }

    @Test
    fun `json pointers escape tilde and slash`() {
        assertThat(JsonPointer.escape("a~b/c")).isEqualTo("a~0b~1c")
        assertThat(JsonPointer.of(listOf("jitai", "a/b", "0"))).isEqualTo("/jitai/a~1b/0")
        assertThat(JsonPointer.of(emptyList())).isEmpty()
        assertThat(JsonPointer.child("/jitai", "x~")).isEqualTo("/jitai/x~0")
        assertThat(JsonPointer.child("/of", 2)).isEqualTo("/of/2")
    }

    companion object {
        private fun row(id: String, text: String, expected: Result) = Arguments.of(id, text, expected)

        private fun syntax(offset: Int) = Result.SyntaxError(offset)

        @JvmStatic
        fun failures(): List<Arguments> = listOf(
            row("empty input", "", syntax(0)),
            row("only whitespace", "   ", syntax(3)),
            row("duplicate key at the root", """{"a":1,"a":2}""", Result.DuplicateKey("a", "")),
            row("duplicate key nested", """{"x":{"a":1,"a":2}}""", Result.DuplicateKey("a", "/x")),
            row("duplicate key in an array item", """{"x":[{"a/b":1,"a/b":2}]}""", Result.DuplicateKey("a/b", "/x/0")),
            row("text after the object", """{"a":1} x""", Result.TrailingText),
            row("two objects", """{}{}""", Result.TrailingText),
            row("leading zero", "01", Result.TrailingText),
            row("trailing comma in object", """{"a":1,}""", syntax(7)),
            row("trailing comma in array", "[1,]", syntax(3)),
            row("missing comma in array", "[1 2]", syntax(3)),
            row("missing colon", """{"a" 1}""", syntax(5)),
            row("missing comma in object", """{"a":1 "b":2}""", syntax(7)),
            row("unquoted key", """{a:1}""", syntax(1)),
            row("unterminated object", """{"a":1""", syntax(6)),
            row("unterminated array", "[1", syntax(2)),
            row("unterminated string", "\"abc", syntax(4)),
            row("raw control character", "\"a\u0001b\"", syntax(2)),
            row("unknown escape", """"\x"""", syntax(2)),
            row("escape at the end", "\"\\", syntax(1)),
            row("short unicode escape", """"\u12"""", syntax(3)),
            row("bad unicode digit", """"\u12G4"""", syntax(5)),
            row("single quotes", "'a'", syntax(0)),
            row("NaN", "NaN", syntax(0)),
            row("Infinity", "[Infinity]", syntax(1)),
            row("plus sign", "+1", syntax(0)),
            row("lone minus", "-", syntax(1)),
            row("minus then letter", "-a", syntax(1)),
            row("fraction without digits", "1.", syntax(2)),
            row("exponent without digits", "1e", syntax(2)),
            row("signed exponent without digits", "1e+", syntax(3)),
            row("truncated true", "tru", syntax(0)),
            row("truncated null", "[nul]", syntax(1)),
            row("comment", "{} // note", Result.TrailingText),
            row("byte order mark", "\uFEFF{}", syntax(0)),
        )

        @JvmStatic
        fun numbers(): List<String> = listOf("0", "-0", "45", "45.0", "4.5e1", "1E+5", "1e-5", "-12.50e3", "0.250", "99999999999999999999")
    }
}
