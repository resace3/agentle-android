package dev.agentle.buildlogic

import com.diffplug.gradle.spotless.SpotlessExtension
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

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
}
