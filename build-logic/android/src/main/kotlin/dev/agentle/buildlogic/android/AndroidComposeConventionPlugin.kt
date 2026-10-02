package dev.agentle.buildlogic.android

import com.android.build.api.dsl.CommonExtension
import io.github.takahirom.roborazzi.RoborazziExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Compose compiler + BOM + UI test (v2 APIs, Robolectric) + Roborazzi screenshot tests.
 *
 * Screenshot goldens (docs/research/08-testing-strategy.md §4.2 and §9) live in each UI library module's
 * `src/screenshots`. They are recorded on purpose, by the CI `record_screenshots` dispatch (`recordRoborazziDebug`), and
 * CI runs `verifyRoborazziDebug` in every module that has goldens. Pin screenshot tests to one SDK
 * (`@Config(sdk = [37])`) so every SDK matrix records and verifies the same image. The Roborazzi plugin is applied to
 * library modules only: the app owns no screens, and its two flavor test tasks would share one output directory.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        val android = extensions.getByName("android") as CommonExtension
        android.buildFeatures.compose = true
        pluginManager.withPlugin("com.android.library") {
            pluginManager.apply("io.github.takahirom.roborazzi")
            extensions.configure<RoborazziExtension> {
                outputDir.set(layout.projectDirectory.dir("src/screenshots"))
            }
        }
        dependencies {
            val bom = platform(libs.lib("compose-bom"))
            add("implementation", bom)
            add("implementation", libs.lib("compose-ui"))
            add("implementation", libs.lib("compose-material3"))
            add("implementation", libs.lib("compose-ui-tooling-preview"))
            add("debugImplementation", libs.lib("compose-ui-tooling"))
            add("testImplementation", bom)
            add("testImplementation", libs.lib("compose-ui-test-junit4"))
            add("debugImplementation", libs.lib("compose-ui-test-manifest"))
            add("testImplementation", libs.lib("roborazzi"))
            add("testImplementation", libs.lib("roborazzi-compose"))
            add("testImplementation", libs.lib("roborazzi-junit-rule"))
            add("androidTestImplementation", bom)
            add("androidTestImplementation", libs.lib("compose-ui-test-junit4"))
        }
    }
}
