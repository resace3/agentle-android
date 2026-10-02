package dev.agentle.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/** Root project: merged Kover report over every real module. */
class RootConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlinx.kover")
        subprojects.filter { it.buildFile.exists() }.forEach { module ->
            dependencies.add("kover", module)
        }
    }
}
