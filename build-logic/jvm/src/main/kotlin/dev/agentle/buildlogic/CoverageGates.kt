package dev.agentle.buildlogic

import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import kotlinx.kover.gradle.plugin.dsl.KoverVerificationRulesConfig
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Kover coverage gates, the one place for their thresholds (docs/ARCHITECTURE.md §17, docs/research/08 §10, red team
 * testing-build-17): line and branch minimums for a whole module or for a set of classes inside it.
 *
 * Never lower a minimum to get green; add tests. `-Pagentle.coverage.enforce=true` makes a miss fail the build. Until
 * the integrator turns it on (default `false`), Kover reports a miss as a warning. `verifyCoverageGates` (every
 * module) writes the module's Kover XML reports and runs its gates; `writeCoverageGates` (root) lists the gates for
 * `tools/junit_summary.py --coverage-gates`, which prints every module's line and branch numbers in the CI summary.
 * `:fakes` and `:core:testing` are test support: never instrumented, never in a report, never counted.
 */
object CoverageGates {
    const val ENFORCE_PROPERTY: String = "agentle.coverage.enforce"

    /** `all` (default), `jvm` or `android`: whose reports `verifyCoverageGates` writes (the Android CI job: `android`). */
    const val MODULES_PROPERTY: String = "agentle.coverage.modules"
    const val TASK_NAME: String = "verifyCoverageGates"
    const val WRITE_TASK_NAME: String = "writeCoverageGates"
    private val moduleKinds = setOf("all", "jvm", "android")

    /** Test support: never measured, never counted. */
    val neverCounted: Set<String> = setOf(":fakes", ":core:testing")

    /**
     * A gate. Without [variant] it covers the whole module (JVM modules: Kover's total report, `koverVerify`). With
     * one it is a Kover variant over [classes] (all classes when empty), checked by `koverVerify<Variant>`; Android
     * modules always use a variant, copied from their `debug` build variant, because their total variant also runs
     * the release unit tests.
     */
    data class Gate(
        val area: String,
        val lineMin: Int,
        val branchMin: Int? = null,
        val variant: String? = null,
        val classes: List<String> = emptyList(),
    )

    /**
     * Module path to its gates. Normalization (offsets, local days, dedupe, source fusion) and OAuth state are class
     * sets: the packages R08 §10 names (`*.normalization`, `*.auth`) plus the classes that do that work today. A
     * pattern that matches no class adds nothing. A gate whose patterns match nothing measures nothing: Kover passes
     * it, and the CI summary shows it as EMPTY (a failure once enforced), so a renamed class cannot escape its gate.
     */
    val gates: Map<String, List<Gate>> = mapOf(
        ":jitai:dsl" to listOf(Gate("jitai", lineMin = 95, branchMin = 90)),
        ":jitai:engine" to listOf(Gate("jitai", lineMin = 95, branchMin = 90)),
        ":analytics:features" to listOf(
            Gate("features", lineMin = 90),
            Gate(
                "normalization",
                lineMin = 90,
                branchMin = 85,
                variant = "normalization",
                classes = listOf(
                    "dev.agentle.analytics.features.normalization.*",
                    "dev.agentle.analytics.features.daily.CanonicalSource*",
                    "dev.agentle.analytics.features.daily.MinuteFusion*",
                    "dev.agentle.analytics.features.realtime.StepFusion*",
                ),
            ),
        ),
        ":connectors:googlehealth" to listOf(
            Gate(
                "normalization",
                lineMin = 90,
                branchMin = 85,
                variant = "normalization",
                classes = listOf(
                    "dev.agentle.connectors.googlehealth.normalization.*",
                    "dev.agentle.connectors.googlehealth.GhMapper*",
                    "dev.agentle.connectors.googlehealth.GhSessionMapper*",
                ),
            ),
            Gate(
                "oauth",
                lineMin = 90,
                variant = "oauth",
                classes = listOf("dev.agentle.connectors.googlehealth.auth.*", "dev.agentle.connectors.googlehealth.GoogleHealthAuthorizer*"),
            ),
        ),
        ":core:oauth" to listOf(Gate("oauth", lineMin = 90)),
        ":core:network" to listOf(
            Gate(
                "oauth",
                lineMin = 90,
                variant = "oauth",
                classes = listOf("dev.agentle.core.network.auth.*", "dev.agentle.core.network.AccessTokenSource*"),
            ),
        ),
        ":ai:chatgpt" to listOf(
            Gate(
                "oauth",
                lineMin = 90,
                variant = "oauth",
                classes = listOf(
                    "dev.agentle.ai.chatgpt.auth.*",
                    "dev.agentle.ai.chatgpt.SiwcAuthorizer*",
                    "dev.agentle.ai.chatgpt.SiwcSessionManager*",
                    "dev.agentle.ai.chatgpt.SiwcDiscovery*",
                    "dev.agentle.ai.chatgpt.SiwcStatus*",
                    "dev.agentle.ai.chatgpt.SiwcVault*",
                    "dev.agentle.ai.chatgpt.SignInCoordinator*",
                    "dev.agentle.ai.chatgpt.ChatGptAuthClient*",
                    "dev.agentle.ai.chatgpt.IdTokenVerifier*",
                ),
            ),
        ),
        ":data" to listOf(Gate("repository", lineMin = 85, variant = "repository")),
    )

    /** Applies the module's gates; called by the quality convention after the Kotlin or Android plugin. */
    fun configure(project: Project) {
        val enforce = project.providers.gradleProperty(ENFORCE_PROPERTY).map(String::toBoolean).orElse(false).get()
        val modules = moduleKind(project)
        val aggregate = project.tasks.register(TASK_NAME) {
            group = "verification"
            description = "Writes this module's Kover XML reports and checks its coverage gates (CoverageGates.kt)."
        }
        if (project.path in neverCounted) {
            project.extensions.configure<KoverProjectExtension> { disable() }
            return
        }
        val moduleGates = gates[project.path].orEmpty()
        val android = isAndroid(project)
        project.extensions.configure<KoverProjectExtension> {
            moduleGates.mapNotNull { it.variant }.forEach { variant ->
                currentProject { copyVariant(variant, if (android) ANDROID_GATE_SOURCE else JVM_VARIANT) }
            }
            reports {
                moduleGates.forEach { gate ->
                    val rules: KoverVerificationRulesConfig.() -> Unit = {
                        warningInsteadOfFailure.set(!enforce)
                        rule("${gate.area} line >= ${gate.lineMin}%") { minBound(gate.lineMin, CoverageUnit.LINE) }
                        gate.branchMin?.let { min -> rule("${gate.area} branch >= $min%") { minBound(min, CoverageUnit.BRANCH) } }
                    }
                    val variant = gate.variant
                    if (variant == null) {
                        total { verify { rules() } }
                    } else {
                        variant(variant) {
                            if (gate.classes.isNotEmpty()) filters { includes { classes(gate.classes) } }
                            verify {
                                onCheck.set(true)
                                rules()
                            }
                        }
                    }
                }
            }
        }
        if (modules != "all" && modules != (if (android) "android" else "jvm")) return
        aggregate.configure { dependsOn(reportTasks(project, android, moduleGates)) }
        // `koverVerify` also checks the module's class-set gates, so either task name runs every gate of the module.
        if (!android && moduleGates.any { it.variant != null }) {
            project.tasks.named("koverVerify").configure {
                dependsOn(moduleGates.mapNotNull { it.variant }.map { "koverVerify${it.capitalized()}" })
            }
        }
    }

    /**
     * Registers `writeCoverageGates` on the root project: `build/coverage-gates.txt`, one
     * `<module> <report> <area> <line minimum> <branch minimum or ->` line per gate of the modules this build measures
     * (`report` is `total` or the Kover variant, so junit_summary finds `build/reports/kover/report<Variant>.xml`).
     */
    fun registerRoot(root: Project) {
        val enforce = root.providers.gradleProperty(ENFORCE_PROPERTY).map(String::toBoolean).orElse(false)
        // Evaluated when the task graph is stored, after every module applied its plugins.
        val lines = root.provider {
            val modules = moduleKind(root)
            root.subprojects.filter { module ->
                module.path !in neverCounted &&
                    when (modules) {
                        "jvm" -> module.pluginManager.hasPlugin(KOTLIN_JVM_PLUGIN)
                        "android" -> isAndroid(module)
                        else -> module.pluginManager.hasPlugin(KOTLIN_JVM_PLUGIN) || isAndroid(module)
                    }
            }.map { it.path }.sorted().flatMap { path ->
                gates[path].orEmpty().map { gate -> "$path ${gate.variant ?: "total"} ${gate.area} ${gate.lineMin} ${gate.branchMin ?: "-"}" }
            }
        }
        root.tasks.register(WRITE_TASK_NAME, WriteCoverageGatesTask::class.java) {
            group = "verification"
            description = "Writes build/coverage-gates.txt for tools/junit_summary.py --coverage-gates."
            this.lines.set(lines)
            this.enforce.set(enforce)
            this.modules.set(root.provider { moduleKind(root) })
            output.set(root.layout.buildDirectory.file("coverage-gates.txt"))
        }
    }

    private fun moduleKind(project: Project): String {
        val kind = project.providers.gradleProperty(MODULES_PROPERTY).orElse("all").get()
        if (kind !in moduleKinds) throw GradleException("-P$MODULES_PROPERTY=$kind: use one of $moduleKinds")
        return kind
    }

    /** The Kover tasks `verifyCoverageGates` runs: XML report and verification of the total or of each gate variant. */
    private fun reportTasks(project: Project, android: Boolean, moduleGates: List<Gate>): List<String> = buildList {
        if (!android) {
            add("koverXmlReport")
            add("koverVerify")
        } else if (moduleGates.isEmpty()) {
            // No gate: only the numbers, from the variant CI tests (the app's prod flavor, the libraries' debug).
            add("koverXmlReport${(if (project.pluginManager.hasPlugin(ANDROID_APP_PLUGIN)) "prodDebug" else ANDROID_GATE_SOURCE).capitalized()}")
        }
        moduleGates.mapNotNull { it.variant }.forEach { variant ->
            add("koverXmlReport${variant.capitalized()}")
            add("koverVerify${variant.capitalized()}")
        }
    }

    private fun isAndroid(project: Project): Boolean =
        project.pluginManager.hasPlugin(ANDROID_APP_PLUGIN) || project.pluginManager.hasPlugin(ANDROID_LIBRARY_PLUGIN)

    private fun String.capitalized(): String = replaceFirstChar { it.uppercaseChar() }

    private const val ANDROID_APP_PLUGIN = "com.android.application"
    private const val ANDROID_LIBRARY_PLUGIN = "com.android.library"
    private const val KOTLIN_JVM_PLUGIN = "org.jetbrains.kotlin.jvm"

    /** Kover's provided variant in JVM modules, and the Android build variant gate variants copy. */
    private const val JVM_VARIANT = "jvm"
    private const val ANDROID_GATE_SOURCE = "debug"
}
