package dev.agentle.buildlogic.android

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Compose compiler + BOM + UI test (v2 APIs, Robolectric) + Roborazzi screenshot dependencies. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        val android = extensions.getByName("android") as CommonExtension
        android.buildFeatures.compose = true
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
