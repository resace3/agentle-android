package dev.agentle.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Fails the build if the source tree holds a credential that must never ship (docs/ARCHITECTURE.md §1: no committed
 * OAuth client secrets, no developer-owned API keys). Google OAuth client secrets start with `GOCSPX-`; a Google
 * Health client secret would also mean a token broker, which the architecture forbids.
 */
@CacheableTask
abstract class VerifyNoSecretsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val root = rootDirectory.get().asFile
        val hits = sources.files.sorted().flatMap { file -> scan(file).map { "${file.relativeTo(root)}:$it" } }
        report.get().asFile.writeText(hits.joinToString("\n"))
        if (hits.isNotEmpty()) {
            val list = hits.joinToString("\n") { "  - $it" }
            throw GradleException("Secrets found in the source tree (remove them; never commit credentials):\n$list")
        }
    }

    private fun scan(file: File): List<String> {
        if (file.length() > MAX_BYTES) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        if (text.contains('\u0000')) return emptyList()
        return text.lineSequence().withIndex()
            .mapNotNull { (index, line) ->
                PATTERNS.firstOrNull { (regex, _) -> regex.containsMatchIn(line) }?.let { "${index + 1}: ${it.second}" }
            }
            .toList()
    }

    companion object {
        private const val MAX_BYTES = 2_000_000L

        /** Pattern and a label; the matched value itself is never printed. */
        val PATTERNS: List<Pair<Regex, String>> = listOf(
            Regex("""GOCSPX-[A-Za-z0-9_-]{20,}""") to "Google OAuth client secret",
            Regex("""\bsk-(?:proj-|svcacct-|admin-)?[A-Za-z0-9_-]{40,}""") to "OpenAI API key",
            Regex("""\bAIza[0-9A-Za-z_-]{35}\b""") to "Google API key",
            Regex("""-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----""") to "private key",
        )
    }
}
