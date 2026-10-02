package dev.agentle.ai.context

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Source-tree guard for privacy-ai-03: only `:ai:context` production code (and the declaration in `:ai:api`) may opt
 * in to [dev.agentle.ai.api.AiEnvelopeConstruction], so nothing else in the app can create AI request content. Test
 * source sets may opt in to build fixtures. Build scripts may never pass the opt-in as a compiler flag.
 */
class OptInGuardTest {
    /** Finds every production source file and build script under [root] that names the opt-in outside the allowed set. */
    private class Scanner(private val root: File) {
        val offenders = mutableListOf<String>()
        val allowed = mutableListOf<String>()

        fun scan(): Scanner = apply { walk(root) }

        private fun walk(dir: File) {
            dir.listFiles().orEmpty().sortedBy { it.name }.forEach { file ->
                when {
                    file.isDirectory && (file.name.startsWith(".") || file.name == "build") -> Unit
                    file.isDirectory -> walk(file)
                    else -> inspect(file)
                }
            }
        }

        private fun inspect(file: File) {
            val path = file.relativeTo(root).invariantSeparatorsPath
            val buildScript = file.name.endsWith(".gradle.kts") || file.name.endsWith(".gradle")
            val production = file.extension in SOURCE_EXTENSIONS && productionSourceSet(path)
            if ((buildScript || production) && MARKER in file.readText()) {
                if (!buildScript && ALLOWED.any { it.matches(path) }) allowed += path else offenders += path
            }
        }

        /** True for files under `src/<set>/` whose set name does not mention tests (main, debug, release, fake, ...). */
        private fun productionSourceSet(path: String): Boolean {
            val parts = path.split('/')
            val src = parts.indexOf("src")
            return src >= 0 && src + 1 < parts.lastIndex && !parts[src + 1].lowercase().contains("test")
        }
    }

    @Test
    fun `no production source outside ai-context creates AI request content`() {
        val scan = Scanner(AiLineageTablesTest.repoRoot()).scan()

        assertThat(scan.offenders).isEmpty()
        assertThat(scan.allowed).containsAtLeast(
            "ai/api/src/main/kotlin/dev/agentle/ai/api/AiRequest.kt",
            "ai/context/src/main/kotlin/dev/agentle/ai/context/ContextSelectionEngine.kt",
            "ai/context/src/main/kotlin/dev/agentle/ai/context/EgressGuard.kt",
            "ai/context/src/main/kotlin/dev/agentle/ai/context/BlockAssembler.kt",
        )
    }

    @Test
    fun `the scanner finds planted offenders and ignores tests, build output and hidden directories`(@TempDir root: File) {
        fun plant(path: String, text: String = "@file:OptIn($MARKER::class)") {
            File(root, path).apply { parentFile.mkdirs() }.writeText(text)
        }
        plant("settings.gradle.kts", "rootProject.name = \"x\"")
        plant("ai/context/src/main/kotlin/dev/agentle/ai/context/Engine.kt")
        plant("ai/api/src/main/kotlin/dev/agentle/ai/api/AiRequest.kt")
        plant("ai/api/src/main/kotlin/dev/agentle/ai/api/Sneaky.kt")
        plant("ai/chatgpt/src/main/kotlin/dev/agentle/ai/chatgpt/Provider.kt")
        plant("app/src/fake/kotlin/dev/agentle/app/FakeWiring.kt")
        plant("app/src/main/java/dev/agentle/app/Legacy.java", "class Legacy { /* $MARKER */ }")
        plant("jitai/engine/build.gradle.kts", "kotlin { compilerOptions { optIn.add(\"dev.agentle.ai.api.$MARKER\") } }")
        plant("app/src/test/kotlin/dev/agentle/app/AppTest.kt")
        plant("app/src/androidTest/kotlin/dev/agentle/app/UiTest.kt")
        plant("core/testing/src/testFixtures/kotlin/Fixture.kt")
        plant("app/build/generated/src/main/kotlin/Generated.kt")
        plant(".claude/worktrees/other/app/src/main/kotlin/Copy.kt")
        plant("ai/chatgpt/src/main/kotlin/dev/agentle/ai/chatgpt/Clean.kt", "class Clean")

        val scan = Scanner(root).scan()

        assertThat(scan.offenders).containsExactly(
            "ai/api/src/main/kotlin/dev/agentle/ai/api/Sneaky.kt",
            "ai/chatgpt/src/main/kotlin/dev/agentle/ai/chatgpt/Provider.kt",
            "app/src/fake/kotlin/dev/agentle/app/FakeWiring.kt",
            "app/src/main/java/dev/agentle/app/Legacy.java",
            "jitai/engine/build.gradle.kts",
        )
        assertThat(scan.allowed).containsExactly(
            "ai/api/src/main/kotlin/dev/agentle/ai/api/AiRequest.kt",
            "ai/context/src/main/kotlin/dev/agentle/ai/context/Engine.kt",
        )
    }

    private companion object {
        const val MARKER = "AiEnvelopeConstruction"
        val SOURCE_EXTENSIONS = setOf("kt", "kts", "java")
        val ALLOWED = listOf(
            Regex("ai/api/src/main/kotlin/dev/agentle/ai/api/AiRequest\\.kt"),
            Regex("ai/context/src/main/.+"),
        )
    }
}
