package dev.agentle.core.common

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Android's regex engine (ICU) refuses a `{` or `}` that is not part of a quantifier such as `{2,}` or a property such as
 * `\p{L}`, where the desktop JVM reads it as a literal. Such a pattern passes every JVM and Robolectric test and then
 * crashes the app the first time its class loads on a phone (a chat reply did in October 2026). This checks the regex
 * literals in the main sources of every module.
 */
class AndroidRegexSyntaxTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "core").isDirectory }

    private val sources: List<File> = root.walkTopDown()
        .onEnter { it.name !in SKIPPED_DIRS }
        .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }
        .toList()

    @Test
    fun `the main sources of every module are found`() {
        assertThat(sources.map { it.name }).containsAtLeast("Redactor.kt", "AiTextPolicy.kt", "ContentParts.kt", "AppAi.kt")
    }

    @Test
    fun `no regex literal has a brace Android rejects`() {
        val rejected = sources.flatMap { file ->
            val text = file.readText()
            literals(text).filter { (_, pattern) -> hasLooseBrace(pattern) }.map { (offset, pattern) ->
                "${file.relativeTo(root).invariantSeparatorsPath}:${text.take(offset).count { it == '\n' } + 1}: $pattern"
            }
        }

        assertWithMessage("Escape these braces (\\\\{ and \\\\}); Android cannot compile the pattern").that(rejected).isEmpty()
    }

    @Test
    fun `the check tells loose braces from quantifiers, properties and templates`() {
        assertThat(hasLooseBrace("""\{\{|}}""")).isTrue()
        assertThat(hasLooseBrace("""\{\{[^{}]*\}\}""")).isTrue()
        assertThat(hasLooseBrace("""[^"&\s,}]+""")).isTrue()
        assertThat(hasLooseBrace("""\{\{([a-z0-9_]+)\}\}""")).isFalse()
        assertThat(hasLooseBrace("""\d(?:[ .]*\d){6,}\p{L}{2,4}""")).isFalse()
        assertThat(hasLooseBrace("""[A-Za-z]{1,${'$'}{MAX}}(?:${'$'}{STEMS.joinToString("|")})""")).isFalse()
        assertThat(literals("""val a = Regex("\\{\\{|}}") val b = Regex(""" + "\"\"\"x{2}\"\"\")").map { it.second })
            .containsExactly("""\{\{|}}""", "x{2}")
        assertThat(literals("Regex(\"(?:\${WORDS.joinToString(\"|\")})\\\\b\")").map { it.second })
            .containsExactly("(?:\${WORDS.joinToString(\"|\")})\\b")
    }

    private companion object {
        val SKIPPED_DIRS = setOf(".git", ".gradle", ".claude", ".idea", "build", "node_modules")

        private val PLAIN_LITERAL = Regex("""(?:\bRegex|Pattern\.compile)\(\s*"((?:\$\{[^{}\n]*\}|[^"\\\n]|\\.)*)"(?!")""")
        private val RAW_LITERAL = Regex("(?:\\bRegex|Pattern\\.compile)\\(\\s*\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)
        private val TO_REGEX = Regex(""""((?:\$\{[^{}\n]*\}|[^"\\\n]|\\.)*)"\.toRegex\(""")
        private val QUANTIFIER = Regex("""\{(?:\d+|\u0001)(?:,(?:\d+|\u0001)?)?\}""")
        private val TEMPLATE = Regex("""\$\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}|\$[A-Za-z_][A-Za-z0-9_]*""")

        /** Each regex literal of [text] (offset, pattern as the regex engine reads it, string templates kept as written). */
        fun literals(text: String): List<Pair<Int, String>> =
            (PLAIN_LITERAL.findAll(text) + TO_REGEX.findAll(text)).map { it.range.first to unescape(it.groupValues[1]) }
                .plus(RAW_LITERAL.findAll(text).map { it.range.first to it.groupValues[1] })
                .sortedBy { it.first }
                .toList()

        /** Kotlin string escapes to the text the regex engine sees; an escaped `$` stays a regex `$`, not a template. */
        fun unescape(literal: String): String = buildString {
            var index = 0
            while (index < literal.length) {
                val char = literal[index]
                if (char == '\\' && index + 1 < literal.length) {
                    when (val next = literal[index + 1]) {
                        '\\', '"', '\'' -> append(next)
                        '$' -> append('\u0002')
                        else -> append(char).append(next)
                    }
                    index += 2
                } else {
                    append(char)
                    index += 1
                }
            }
        }

        /** True when [pattern] has a `{` or `}` outside a quantifier, a `\\p{..}`-style escape or a string template. */
        fun hasLooseBrace(pattern: String): Boolean {
            val text = TEMPLATE.replace(pattern, "\u0001")
            var index = 0
            while (index in text.indices) {
                index = next(text, index) ?: return true
            }
            return false
        }

        /** Where the token at [index] ends, or null when it is a loose brace. */
        private fun next(text: String, index: Int): Int? {
            val char = text[index]
            val property = char == '\\' && text.getOrNull(index + 1)?.let { it in "pPxN" } == true && text.getOrNull(index + 2) == '{'
            return when {
                property -> text.indexOf('}', index + 2).takeIf { it >= 0 }?.plus(1)
                char == '\\' -> index + 2
                char == '{' -> QUANTIFIER.matchAt(text, index)?.let { index + it.value.length }
                char == '}' -> null
                else -> index + 1
            }
        }
    }
}
