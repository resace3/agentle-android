package dev.agentle.buildlogic.android

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
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
            description = "Fails if :fakes (fake servers, canned tokens) is on any prod runtime classpath."
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
                    val offenders = roots.flatMap { (name, root) ->
                        projectDependencies(root.get()).filter { it == ":fakes" }.map { "$name -> $it" }
                    }
                    if (offenders.isNotEmpty()) throw GradleException("Fakes on prod classpaths: $offenders")
                }
            }
        }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(verify) }
    }
}

private fun projectDependencies(root: ResolvedComponentResult): Set<String> {
    val seen = mutableSetOf<ResolvedComponentResult>()
    val paths = mutableSetOf<String>()
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
        val component = queue.removeFirst()
        if (!seen.add(component)) continue
        (component.id as? ProjectComponentIdentifier)?.let { paths += it.projectPath }
        component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue += it.selected }
    }
    return paths
}
