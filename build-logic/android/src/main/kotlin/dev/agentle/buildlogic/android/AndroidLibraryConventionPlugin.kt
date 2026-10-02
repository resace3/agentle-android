package dev.agentle.buildlogic.android

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("agentle.quality")
        extensions.configure<LibraryExtension> {
            namespace = agentleNamespace()
            configureAndroidCommon(this)
            defaultConfig.consumerProguardFiles("consumer-rules.pro")
        }
    }
}
