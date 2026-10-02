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

        val testZone = providers.gradleProperty(TEST_ZONE_PROPERTY).orElse(DEFAULT_TEST_ZONE)
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // Unique names for parameterized tests in the JUnit XML (evidence counts depend on it).
            systemProperty("junit.jupiter.params.displayname.default", "{displayName}[{index}] {argumentsWithNames}")
            // A non-UTC, half-hour, DST default zone (red team testing-build-04): code that uses the JVM or system
            // default zone instead of AgentleClock.zone() gives wrong local days and fails tests. TZ is set too, so
            // native code (SQLite 'localtime') sees the same zone.
            systemProperty("user.timezone", testZone.get())
            environment("TZ", testZone.get())
            maxHeapSize = "1g"
            testLogging {
                events("failed", "skipped")
                exceptionFormat = TestExceptionFormat.FULL
                showStandardStreams = false
            }
        }
    }

    companion object {
        /** `-Pagentle.testZone=UTC` overrides the test JVMs' default zone for local experiments. */
        const val TEST_ZONE_PROPERTY: String = "agentle.testZone"

        /**
         * Default zone of every test JVM. Keep in sync with build-logic/android KotlinAndroid.kt. It must differ from
         * TestAgentleClock's default zone (Australia/Adelaide) so mixing the two zones fails a test.
         */
        const val DEFAULT_TEST_ZONE: String = "America/St_Johns"
    }
}
