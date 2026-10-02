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

    const val TASK_NAME: String = "verifyModuleGraph"

    fun register(project: Project) {
        val task = project.tasks.register(TASK_NAME) {
            group = "verification"
            description = "Applies the module-graph rules to the resolved compile and runtime classpaths."
        }
        project.tasks.matching { it.name == "check" }.configureEach { dependsOn(task) }
        project.gradle.projectsEvaluated {
            check(project)
            registerResolvedCheck(project, task)
        }
    }

    /**
     * The same rules on resolved classpaths (red team testing-build-13): an `api` re-export or any transitive path
     * cannot bypass them. Resolution results are providers, so the task stays configuration-cache safe.
     */
    private fun registerResolvedCheck(project: Project, task: org.gradle.api.tasks.TaskProvider<org.gradle.api.Task>) {
        val androidPaths = project.rootProject.allprojects.filter { isAndroid(it) }.map { it.path }.toSet()
        val jvmModule = !isAndroid(project)
        val path = project.path
        val roots = project.configurations.filter { conf ->
            conf.isCanBeResolved && !isTestConfiguration(conf.name) &&
                (conf.name.endsWith("CompileClasspath") || conf.name.endsWith("RuntimeClasspath") ||
                    conf.name == "compileClasspath" || conf.name == "runtimeClasspath")
        }.map { it.name to it.incoming.resolutionResult.rootComponent }
        val forbidden = forbiddenEdges[path].orEmpty()
        task.configure {
            doLast {
                val violations = roots.flatMap { (conf, root) ->
                    reachableProjects(root.get()).filter { it != path }.mapNotNull { target ->
                        when {
                            jvmModule && target in androidPaths -> "JVM modules never depend on Android modules"
                            target == ":fakes" && !(path == ":app" && conf.startsWith("fake")) -> "fakes only via app fake* classpaths or tests"
                            path.startsWith(":feature:") && target in featureForbidden -> "UI modules must go through :data ports"
                            target in forbidden -> "the engine decides; delivery and AI calls live elsewhere"
                            else -> null
                        }?.let { "$path reaches $target on $conf ($it)" }
                    }
                }.distinct()
                if (violations.isNotEmpty()) {
                    throw GradleException("Forbidden module dependencies on resolved classpaths:\n" + violations.joinToString("\n") { "  - $it" })
                }
            }
        }
    }

    private fun reachableProjects(root: org.gradle.api.artifacts.result.ResolvedComponentResult): Set<String> {
        val seen = mutableSetOf<org.gradle.api.artifacts.result.ResolvedComponentResult>()
        val paths = mutableSetOf<String>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            if (!seen.add(component)) continue
            (component.id as? org.gradle.api.artifacts.component.ProjectComponentIdentifier)?.let { paths += it.projectPath }
            component.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>().forEach { queue += it.selected }
        }
        return paths
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
