// Root build for JVM-only mode (see settings.gradle.kts). Same as build.gradle.kts minus the Google Maven plugins.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.kover)
    id("agentle.root")
}
