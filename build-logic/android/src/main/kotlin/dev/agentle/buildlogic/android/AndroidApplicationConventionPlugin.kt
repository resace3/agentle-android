package dev.agentle.buildlogic.android

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.kotlin.dsl.configure

/**
 * The app: compile/target SDK from the catalog, `backend` flavors (`prod`, `fake`), `fakeRelease` disabled,
 * R8 for release, and `verifyNoFakesInProd` wired into `check` (docs/ARCHITECTURE.md §4).
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("agentle.quality")

        extensions.configure<ApplicationExtension> {
            namespace = agentleNamespace()
            configureAndroidCommon(this)
            defaultConfig.targetSdk = libs.intVersion("targetSdk")
            flavorDimensions += "backend"
            productFlavors.register("prod") {
                dimension = "backend"
            }
            productFlavors.register("fake") {
                dimension = "backend"
                applicationIdSuffix = ".fake"
                versionNameSuffix = "-fake"
            }
            buildTypes.named("release") {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }
        }

        extensions.configure<ApplicationAndroidComponentsExtension> {
            beforeVariants { variant ->
                val fake = variant.productFlavors.any { (dimension, flavor) -> dimension == "backend" && flavor == "fake" }
                if (fake && variant.buildType == "release") variant.enable = false
            }
        }

        val verify = tasks.register("verifyNoFakesInProd") {
            group = "verification"
            description = "Fails if test support (:fakes, :core:testing) or a test-only library is on a prod runtime classpath."
        }
        afterEvaluate {
            // Shipping variants only: test classpaths (prodDebugUnitTestRuntimeClasspath, ...AndroidTest...) may use fakes.
            val prodClasspaths = configurations.names.filter {
                it.startsWith("prod") && it.endsWith("RuntimeClasspath") && !it.contains("Test")
            }
            val roots = prodClasspaths.map { name ->
                name to configurations.getByName(name).incoming.resolutionResult.rootComponent
            }
            verify.configure {
                doLast {
                    val offenders = roots.flatMap { (name, root) -> testOnlyDependencies(root.get()).map { "$name: $it" } }
                    if (offenders.isNotEmpty()) {
                        throw GradleException("Test-only dependencies on prod runtime classpaths:\n" + offenders.joinToString("\n") { "  - $it" })
                    }
                }
            }
        }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(verify) }
    }
}

/** Test support projects and test-only libraries (group:module prefixes) that must never ship (red team testing-build-13). */
private val testOnlyProjects = setOf(":fakes", ":core:testing")
private val testOnlyCoordinates = listOf(
    "com.squareup.okhttp3:mockwebserver",
    "org.jetbrains.kotlinx:kotlinx-coroutines-test",
    "app.cash.turbine:",
    "org.robolectric:",
    "junit:",
    "org.junit",
    "androidx.test",
)

/** Every forbidden component reachable from [root], with the dependency chain that brings it in. */
private fun testOnlyDependencies(root: ResolvedComponentResult): List<String> {
    val parent = mutableMapOf<ResolvedComponentResult, ResolvedComponentResult?>(root to null)
    val queue = ArrayDeque(listOf(root))
    val hits = mutableListOf<String>()
    while (queue.isNotEmpty()) {
        val component = queue.removeFirst()
        val forbidden = when (val id = component.id) {
            is ProjectComponentIdentifier -> id.projectPath in testOnlyProjects
            is ModuleComponentIdentifier -> testOnlyCoordinates.any { "${id.group}:${id.module}".startsWith(it) }
            else -> false
        }
        if (forbidden) {
            hits += generateSequence(component) { parent[it] }.toList().reversed().joinToString(" -> ") { componentName(it) }
            continue
        }
        component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach {
            if (it.selected !in parent) {
                parent[it.selected] = component
                queue += it.selected
            }
        }
    }
    return hits
}

private fun componentName(component: ResolvedComponentResult): String = when (val id = component.id) {
    is ProjectComponentIdentifier -> id.projectPath
    is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
    else -> id.displayName
}
