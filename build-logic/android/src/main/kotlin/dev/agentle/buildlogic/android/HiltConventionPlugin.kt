package dev.agentle.buildlogic.android

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Hilt via KSP (no kapt with AGP 9 built-in Kotlin); kotlin-metadata-jvm aligned with Kotlin (doc 07 §5.3). */
class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("com.google.dagger.hilt.android")
        dependencies {
            add("implementation", libs.lib("hilt-android"))
            add("ksp", libs.lib("hilt-compiler"))
            add("ksp", libs.lib("kotlin-metadata-jvm"))
            add("testImplementation", libs.lib("hilt-android-testing"))
            add("kspTest", libs.lib("hilt-compiler"))
            add("androidTestImplementation", libs.lib("hilt-android-testing"))
            add("kspAndroidTest", libs.lib("hilt-compiler"))
        }
    }
}
