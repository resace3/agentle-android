package dev.agentle.buildlogic.android

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/** A UI feature module: Android library + Compose + Hilt + the shared UI/model/lifecycle dependencies. */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("agentle.android.library")
        pluginManager.apply("agentle.android.compose")
        pluginManager.apply("agentle.hilt")
        dependencies {
            add("implementation", project(":core:ui"))
            add("implementation", project(":core:model"))
            add("implementation", project(":core:common"))
            add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.lib("androidx-lifecycle-viewmodel-compose"))
            add("implementation", libs.lib("androidx-hilt-lifecycle-viewmodel-compose"))
            add("implementation", libs.lib("kotlinx-collections-immutable"))
            add("testImplementation", project(":core:testing"))
        }
    }
}
