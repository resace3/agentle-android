package dev.agentle.buildlogic.android

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Room 3 (KSP only) with exported schemas in `<module>/schemas` (required for migration tests and auto-migrations).
 * The Room 3 Gradle extension is configured by name because its class name is not part of a stable API.
 */
class RoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("androidx.room3")
        val room = extensions.getByName("room3")
        room.javaClass.getMethod("schemaDirectory", String::class.java).invoke(room, "$projectDir/schemas")
        dependencies {
            add("api", libs.lib("androidx-room3-runtime"))
            add("ksp", libs.lib("androidx-room3-compiler"))
            add("implementation", libs.lib("androidx-sqlite-bundled"))
            add("implementation", libs.lib("androidx-room3-paging"))
            add("testImplementation", libs.lib("androidx-room3-testing"))
            add("testImplementation", libs.lib("androidx-sqlite-framework"))
            add("androidTestImplementation", libs.lib("androidx-room3-testing"))
        }
    }
}
