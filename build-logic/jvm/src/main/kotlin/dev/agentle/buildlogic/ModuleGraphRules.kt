package dev.agentle.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/**
 * Forbidden module dependencies (docs/ARCHITECTURE.md section 3), checked when each project is evaluated:
 * - only `:app` may depend on `:fakes`, and only through `fake*` configurations; any module may use it in tests;
 * - `:feature:*` UI modules never reach persistence, connector implementations, the ChatGPT client or workers.
 */
object ModuleGraphRules {
    private val featureForbidden = setOf(
        ":core:database",
        ":connectors:android",
        ":connectors:googlehealth",
        ":ai:chatgpt",
        ":background",
    )

    fun register(project: Project) {
        project.afterEvaluate { check(this) }
    }

    /** Configurations that never reach a shipped artifact: tests and coverage aggregation. */
    private fun isTestConfiguration(name: String): Boolean =
        name.contains("test", ignoreCase = true) || name.startsWith("kover", ignoreCase = true)

    private fun check(project: Project) {
        val violations = mutableListOf<String>()
        project.configurations.forEach { configuration ->
            configuration.dependencies.withType(ProjectDependency::class.java).forEach { dependency ->
                val target = dependency.path
                val conf = configuration.name
                if (target == project.path) return@forEach
                if (target == ":fakes" && !isTestConfiguration(conf)) {
                    val allowed = project.path == ":app" && conf.startsWith("fake")
                    if (!allowed) violations += "${project.path} -> $target via '$conf' (fakes only via app fake* configurations or tests)"
                }
                if (project.path.startsWith(":feature:") && target in featureForbidden && !isTestConfiguration(conf)) {
                    violations += "${project.path} -> $target via '$conf' (UI modules must go through :data ports)"
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Forbidden module dependencies:\n" + violations.joinToString("\n") { "  - $it" })
        }
    }
}
