package dev.agentle.buildlogic

import com.diffplug.gradle.spotless.SpotlessExtension
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.language.base.plugins.LifecycleBasePlugin

/** detekt 2 (AGP 9 compatible) + Spotless/ktlint, Kover and the module-graph rules. Applied by every module convention. */
class QualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("dev.detekt")
        pluginManager.apply("com.diffplug.spotless")
        pluginManager.apply("org.jetbrains.kotlinx.kover")

        extensions.configure<DetektExtension> {
            buildUponDefaultConfig.set(true)
            parallel.set(true)
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            val baselineFile = file("detekt-baseline.xml")
            if (baselineFile.exists()) baseline.set(baselineFile)
            // Release variants compile the same sources as debug (no src/release), so type-resolved detekt skips them.
            ignoredBuildTypes.set(listOf("release"))
        }
        // The plain `detekt` task silently skips every rule that needs type resolution, among them ForbiddenMethodCall
        // (the one-clock rule), so `check` also runs the type-resolved tasks over main and test sources (red team
        // testing-build-04). Both exist in every module: JVM modules per compilation, Android modules across variants.
        plugins.withType(LifecycleBasePlugin::class.java) {
            tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME).configure {
                dependsOn(DETEKT_MAIN_TASK, DETEKT_TEST_TASK)
            }
        }
        extensions.configure<SpotlessExtension> {
            kotlin {
                target("src/**/*.kt")
                ktlint(libs.version("ktlint")).editorConfigOverride(
                    mapOf(
                        "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
                        "ktlint_standard_function-naming" to "enabled",
                        "max_line_length" to "140",
                    ),
                )
            }
            kotlinGradle {
                target("*.gradle.kts")
                ktlint(libs.version("ktlint"))
            }
        }
        ModuleGraphRules.register(this)
    }

    private companion object {
        const val DETEKT_MAIN_TASK = "detektMain"
        const val DETEKT_TEST_TASK = "detektTest"
    }
}
