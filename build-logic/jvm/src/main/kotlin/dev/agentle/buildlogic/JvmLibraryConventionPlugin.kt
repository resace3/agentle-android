package dev.agentle.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Pure Kotlin/JVM module: JDK 21 toolchain, bytecode 17, JUnit 6 (Jupiter) tests, quality gates and coverage.
 * Domain modules use this; they must never depend on Android modules.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("agentle.quality")

        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
            explicitApiWarning()
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                freeCompilerArgs.addAll("-Xjsr305=strict", "-Xconsistent-data-class-copy-visibility")
                allWarningsAsErrors.set(providers.gradleProperty("agentle.warningsAsErrors").map(String::toBoolean).orElse(false))
            }
        }
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        dependencies {
            add("testImplementation", platform(libs.lib("junit-bom")))
            add("testImplementation", libs.lib("junit-jupiter"))
            add("testRuntimeOnly", libs.lib("junit-platform-launcher"))
            add("testImplementation", libs.lib("truth"))
            add("testImplementation", libs.lib("kotlinx-coroutines-test"))
            add("testImplementation", libs.lib("turbine"))
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // Unique names for parameterized tests in the JUnit XML (evidence counts depend on it).
            systemProperty("junit.jupiter.params.displayname.default", "{displayName}[{index}] {argumentsWithNames}")
            systemProperty("user.timezone", "UTC")
            maxHeapSize = "1g"
            testLogging {
                events("failed", "skipped")
                exceptionFormat = TestExceptionFormat.FULL
                showStandardStreams = false
            }
        }
    }
}
