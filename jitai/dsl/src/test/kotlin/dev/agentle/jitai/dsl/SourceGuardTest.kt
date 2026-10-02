package dev.agentle.jitai.dsl

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import java.io.File

/**
 * R10 §11.7 "model output is data, never code" and the module's code rules, checked on the main sources: no interpreter,
 * reflection, class loading, processes, files, URIs or SQL; regular expressions only from constants; time only from
 * [dev.agentle.core.time.AgentleClock] and zones only from the caller; no printing and no exception text.
 */
class SourceGuardTest {
    private val sources: Map<String, List<String>> = File("src/main/kotlin").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .associate { it.path to it.readLines() }

    @Test
    fun `the main sources are found`() {
        assertThat(sources.keys.map { File(it).name }).containsAtLeast("RuleValidator.kt", "RuleCodec.kt", "Condition.kt")
    }

    @Test
    fun `no interpreter, reflection, dynamic loading, processes, files, URIs or SQL`() {
        assertThat(hits(FORBIDDEN_CODE)).isEmpty()
    }

    @Test
    fun `time comes only from AgentleClock and zones only from the caller`() {
        assertThat(hits(FORBIDDEN_TIME)).isEmpty()
    }

    @Test
    fun `no printing, no stack traces and no exception text`() {
        assertThat(hits(FORBIDDEN_OUTPUT)).isEmpty()
    }

    @Test
    fun `regular expressions are constants built from bundled text`() {
        val built = sources.flatMap { (path, lines) ->
            lines.withIndex()
                .filter { (_, line) -> "Regex(" in line && !REGEX_CONSTANT.containsMatchIn(line) }
                .map { (index, line) -> "$path:${index + 1}: ${line.trim()}" }
        }

        assertWithMessage("Regex built outside a constant declaration").that(built).isEmpty()
        assertThat(hits(listOf(Regex("""\.toRegex\("""), Regex("""Pattern\.compile""")))).isEmpty()
    }

    private fun hits(patterns: List<Regex>): List<String> = sources.flatMap { (path, lines) ->
        lines.withIndex()
            .filter { (_, line) -> !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") }
            .filter { (_, line) -> patterns.any { it.containsMatchIn(line) } }
            .map { (index, line) -> "$path:${index + 1}: ${line.trim()}" }
    }

    private companion object {
        val FORBIDDEN_CODE = listOf(
            Regex("""\beval\("""),
            Regex("""ScriptEngine|javax\.script"""),
            Regex("""Class\.forName|ClassLoader|loadClass"""),
            Regex("""java\.lang\.reflect|kotlin\.reflect\.full|\.declaredMethods|getDeclared"""),
            Regex("""Runtime\.getRuntime|ProcessBuilder"""),
            Regex("""java\.io\.File\b|java\.nio\.file|android\.net\.Uri|android\.content\.Intent|java\.net\."""),
            Regex("""java\.sql|rawQuery|execSQL"""),
        )

        val FORBIDDEN_TIME = listOf(
            Regex("""System\.currentTimeMillis|System\.nanoTime"""),
            Regex("""Instant\.now|Clock\.System|LocalDate\.now|LocalDateTime\.now|LocalTime\.now"""),
            Regex("""Calendar\.getInstance|java\.util\.Date\b"""),
            Regex("""currentSystemDefault|ZoneId\.systemDefault|TimeZone\.getDefault|Locale\.getDefault"""),
        )

        val FORBIDDEN_OUTPUT = listOf(
            Regex("""\bprintln\(|\bprint\(|System\.out|System\.err"""),
            Regex("""printStackTrace|stackTraceToString"""),
            Regex("""\b(e|ex|error|ignored|t|throwable|cause|failure)\.(message|localizedMessage)\b"""),
        )

        /** `val NAME = Regex(` or `val NAME: Regex = Regex(` at declaration level. */
        val REGEX_CONSTANT = Regex("""^\s*((private|internal|public)\s+)?val\s+[A-Z][A-Z0-9_]*(\s*:\s*Regex)?\s*=\s*Regex\(""")
    }
}
