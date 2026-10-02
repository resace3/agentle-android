package dev.agentle.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/**
 * Forbidden module dependencies (docs/ARCHITECTURE.md section 3), checked once every project is evaluated, so the
 * type of each target module is known:
 * - JVM modules never depend on Android modules, in any configuration (JVM-only mode cannot even include them);
 * - only `:app` may depend on `:fakes`, and only through `fake*` configurations; any module may use it in tests;
 * - `:feature:*` UI modules never reach persistence, connector implementations, the ChatGPT client or workers;
 * - the JITAI engine decides but never delivers or calls an AI provider: no `:interventions`, no `:ai:chatgpt`.
 */
object ModuleGraphRules {
    private val featureForbidden = setOf(
        ":core:database",
        ":connectors:android",
        ":connectors:googlehealth",
        ":ai:chatgpt",
        ":background",
    )

    private val forbiddenEdges = mapOf(
        ":jitai:engine" to setOf(":interventions", ":ai:chatgpt"),
    )

    fun register(project: Project) {
        project.gradle.projectsEvaluated { check(project) }
    }

    /** Configurations that never reach a shipped artifact: tests and coverage aggregation. */
    private fun isTestConfiguration(name: String): Boolean =
        name.contains("test", ignoreCase = true) || name.startsWith("kover", ignoreCase = true)

    private fun isAndroid(project: Project): Boolean = project.extensions.findByName("android") != null

    private fun check(project: Project) {
        val violations = mutableListOf<String>()
        val jvmModule = !isAndroid(project)
        project.configurations.forEach { configuration ->
            configuration.dependencies.withType(ProjectDependency::class.java).forEach { dependency ->
                val target = dependency.path
                val conf = configuration.name
                if (target == project.path) return@forEach
                val targetProject = project.rootProject.findProject(target)
                if (jvmModule && targetProject != null && isAndroid(targetProject)) {
                    violations += "${project.path} -> $target via '$conf' (JVM modules never depend on Android modules)"
                }
                if (target == ":fakes" && !isTestConfiguration(conf)) {
                    val allowed = project.path == ":app" && conf.startsWith("fake")
                    if (!allowed) violations += "${project.path} -> $target via '$conf' (fakes only via app fake* configurations or tests)"
                }
                if (project.path.startsWith(":feature:") && target in featureForbidden && !isTestConfiguration(conf)) {
                    violations += "${project.path} -> $target via '$conf' (UI modules must go through :data ports)"
                }
                if (target in forbiddenEdges[project.path].orEmpty()) {
                    violations += "${project.path} -> $target via '$conf' (the engine decides; delivery and AI calls live elsewhere)"
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Forbidden module dependencies:\n" + violations.joinToString("\n") { "  - $it" })
        }
    }
}
