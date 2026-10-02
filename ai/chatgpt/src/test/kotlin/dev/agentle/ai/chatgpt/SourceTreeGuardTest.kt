package dev.agentle.ai.chatgpt

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * Repository guards of the SIWC brief (docs/research/06 §7): Agentle uses only its own dynamic registration and the
 * documented public API, so no other product's `app_` client id and no private ChatGPT backend path may appear in any
 * code or configuration file. Markdown is not scanned, and docs/research/ and docs/ARCHITECTURE.md quote these strings on
 * purpose. The SIWC modules also use only the injected clock, never the JVM default zone, never a WebView and never
 * console output (red team testing-build-04/19).
 */
class SourceTreeGuardTest {
    private val root: Path = generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }

    private fun relative(path: Path): String = root.relativize(path).toString().replace('\\', '/')

    private fun filesUnder(start: Path, extensions: Set<String>): List<Path> {
        val found = mutableListOf<Path>()
        Files.walkFileTree(
            start,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir != start && (dir.name in SKIPPED_DIRECTORIES || relative(dir) in EXCLUDED_PATHS)) {
                        FileVisitResult.SKIP_SUBTREE
                    } else {
                        FileVisitResult.CONTINUE
                    }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    // Path is an Iterable<Path>, so `found += file` would add its name elements instead.
                    if (file.extension in extensions && relative(file) !in EXCLUDED_PATHS && file.name != GUARD_FILE) found.add(file)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return found
    }

    /** Locations (file and line, never the matched text) where [pattern] occurs. */
    private fun hits(files: List<Path>, pattern: Regex): List<String> = files.flatMap { file ->
        // ISO-8859-1 never fails to decode, and every pattern here is ASCII.
        String(Files.readAllBytes(file), Charsets.ISO_8859_1).lineSequence()
            .mapIndexedNotNull { index, line -> "${relative(file)}:${index + 1}".takeIf { pattern.containsMatchIn(line) } }
            .toList()
    }

    @Test
    fun `the guard patterns catch what they are meant to catch`() {
        assertThat(FOREIGN_CLIENT_ID.containsMatchIn("client_id=app_" + "Ab3".repeat(8))).isTrue()
        assertThat(FOREIGN_CLIENT_ID.containsMatchIn("R.string.app_name")).isFalse()
        assertThat(PRIVATE_BACKEND.containsMatchIn("https://chatgpt.com" + "/backend-api/conversation")).isTrue()
        assertThat(PRIVATE_BACKEND.containsMatchIn("https://chatgpt.com/settings/usage")).isFalse()
        assertThat(PLATFORM_APIS.containsMatchIn("val zone = ZoneId." + "systemDefault()")).isTrue()
    }

    @Test
    fun `no other product's client id and no private ChatGPT backend path in any code or configuration file`() {
        val scanned = filesUnder(root, CODE_AND_CONFIG)

        assertWithMessage("files scanned").that(scanned.size).isGreaterThan(MIN_FILES)
        assertWithMessage("an app_ client id of another product").that(hits(scanned, FOREIGN_CLIENT_ID)).isEmpty()
        assertWithMessage("a private ChatGPT backend path").that(hits(scanned, PRIVATE_BACKEND)).isEmpty()
    }

    @Test
    fun `the SIWC modules use the injected clock and never the default zone, a WebView or console output`() {
        val mine = SIWC_SOURCES.map(root::resolve).filter(Files::isDirectory).flatMap { filesUnder(it, setOf("kt")) }

        assertWithMessage("SIWC sources scanned").that(mine.size).isGreaterThan(MIN_SIWC_FILES)
        assertWithMessage("a platform time, zone, WebView or console API").that(hits(mine, PLATFORM_APIS)).isEmpty()
    }

    private companion object {
        const val GUARD_FILE = "SourceTreeGuardTest.kt"
        const val MIN_FILES = 100
        const val MIN_SIWC_FILES = 30

        val FOREIGN_CLIENT_ID = Regex("app_[A-Za-z0-9]{20,}")
        val PRIVATE_BACKEND = Regex(Regex.escape("chatgpt.com" + "/backend-api"))
        val PLATFORM_APIS = Regex(
            listOf(
                """ZoneId\.systemDefault""",
                """TimeZone\.currentSystemDefault""",
                """TimeZone\.getDefault""",
                """System\.currentTimeMillis""",
                """System\.nanoTime""",
                """Clock\.System""",
                """\b(Instant|LocalDate|LocalDateTime|ZonedDateTime|OffsetDateTime)\.now\(""",
                """android\.webkit""",
                """\bWebView\(""",
                """\bprintln\(""",
                """printStackTrace""",
                """Thread\.sleep""",
            ).joinToString("|"),
        )

        val CODE_AND_CONFIG = setOf(
            "kt", "kts", "java", "gradle", "xml", "json", "toml", "properties", "yml", "yaml", "pro", "cfg", "py", "sh", "js", "html",
        )
        val SKIPPED_DIRECTORIES = setOf("build", ".git", ".gradle", ".idea", ".kotlin", ".claude", ".cxx", "node_modules")
        val EXCLUDED_PATHS = setOf("docs/research", "docs/ARCHITECTURE.md")

        /** The modules and fake packages of Team SIWC, main and test sources. */
        val SIWC_SOURCES = listOf(
            "ai/chatgpt/src",
            "core/oauth/src",
            "fakes/src/main/kotlin/dev/agentle/fakes/chatgpt",
            "fakes/src/main/kotlin/dev/agentle/fakes/browser",
            "fakes/src/test/kotlin/dev/agentle/fakes/chatgpt",
            "fakes/src/test/kotlin/dev/agentle/fakes/browser",
        )
    }
}
