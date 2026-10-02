package dev.agentle.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/** Root project: merged Kover report over every real module, and the committed-secrets guard. */
class RootConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jetbrains.kotlinx.kover")
        subprojects.filter { it.buildFile.exists() }.forEach { module ->
            dependencies.add("kover", module)
        }
        tasks.register("verifyNoSecrets", VerifyNoSecretsTask::class.java) {
            group = "verification"
            description = "Fails if an OAuth client secret, API key or private key is committed anywhere in the tree."
            rootDirectory.set(layout.projectDirectory)
            sources.from(
                fileTree(rootDir) {
                    exclude("**/build/**", "**/.gradle/**", ".git/**", ".claude/**", ".idea/**", ".kotlin/**", "**/*.jar", "**/*.png", "**/*.webp")
                },
            )
            report.set(layout.buildDirectory.file("reports/verifyNoSecrets.txt"))
        }
        Unit
    }
}
