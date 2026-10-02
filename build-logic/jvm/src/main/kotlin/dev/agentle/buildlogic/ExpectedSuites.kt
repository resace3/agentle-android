package dev.agentle.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * Registers `:writeExpectedSuites` on the root project: every module's required unit-test tasks (JVM `test`,
 * Android library `testDebugUnitTest`, the app's `testFakeDebugUnitTest` and `testProdDebugUnitTest`).
 * `-Pagentle.expectedSuites=jvm|android` limits the list to the suites one CI job runs; the default is `all`.
 */
internal object ExpectedSuites {
    const val TASK_NAME: String = "writeExpectedSuites"
    const val KIND_PROPERTY: String = "agentle.expectedSuites"
    private const val MINIMUMS_PATH = "tools/test-minimums.txt"
    private val kinds = setOf("all", "jvm", "android")
    private val appTestTasks = listOf("testFakeDebugUnitTest", "testProdDebugUnitTest")

    fun register(root: Project) {
        val kind = root.providers.gradleProperty(KIND_PROPERTY).orElse("all")
        val minimumsText = root.providers.fileContents(root.layout.projectDirectory.file(MINIMUMS_PATH)).asText
        val jvmOnly = root.providers.environmentVariable("AGENTLE_JVM_ONLY").orNull == "true" ||
            root.providers.gradleProperty("agentle.jvmOnly").orNull == "true"
        // Evaluated when the task graph is stored, after every module applied its plugins.
        val suiteSourceRoots = root.provider {
            val selected = kind.get()
            if (selected !in kinds) throw GradleException("-P$KIND_PROPERTY=$selected: use one of $kinds")
            root.subprojects.flatMap { module ->
                requiredTestTasks(module, selected).map { task -> "${module.path}:$task" to module.file("src").absolutePath }
            }.toMap()
        }
        root.tasks.register(TASK_NAME, WriteExpectedSuitesTask::class.java) {
            group = "verification"
            description = "Writes build/expected-suites.txt for tools/junit_summary.py --expect-file."
            this.kind.set(kind)
            buildMode.set(if (jvmOnly) "jvm-only" else "full")
            this.suiteSourceRoots.set(suiteSourceRoots)
            minimums.set(
                suiteSourceRoots.map { suites -> parseMinimums(minimumsText.orNull.orEmpty()).filterKeys { it in suites } },
            )
            output.set(root.layout.buildDirectory.file("expected-suites.txt"))
        }
    }

    private fun requiredTestTasks(module: Project, kind: String): List<String> {
        val plugins = module.pluginManager
        return when {
            plugins.hasPlugin("com.android.application") -> if (kind == "jvm") emptyList() else appTestTasks
            plugins.hasPlugin("com.android.library") -> if (kind == "jvm") emptyList() else listOf("testDebugUnitTest")
            plugins.hasPlugin("org.jetbrains.kotlin.jvm") -> if (kind == "android") emptyList() else listOf("test")
            else -> emptyList()
        }
    }

    /** `<suite> <minimum>` per line; `#` starts a comment. */
    internal fun parseMinimums(text: String): Map<String, Int> =
        text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .associate { line ->
                val parts = line.split(Regex("\\s+"))
                val minimum = parts.getOrNull(1)?.toIntOrNull()
                if (parts.size != 2 || minimum == null || minimum < 0) {
                    throw GradleException("$MINIMUMS_PATH: expected '<suite> <minimum>', got '$line'")
                }
                parts[0] to minimum
            }
}
