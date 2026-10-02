// Root build for JVM-only mode (see settings.gradle.kts). Same as build.gradle.kts minus the Google Maven plugins.
// The Android modules are not part of this build, but their Kotlin sources are still formatted (spotless) and
// statically checked (detekt, no type resolution) from the root project so they can be fixed without Google Maven.
// Rules that need type resolution (ForbiddenMethodCall: the one-clock rule) are skipped there; the Android CI job runs
// them through detektMain/detektTest.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
    alias(libs.plugins.kover)
    id("agentle.root")
}

val androidModuleDirs: List<File> = rootDir.walkTopDown()
    .onEnter { it.name != "build" && it.name != "build-logic" && !it.name.startsWith(".") }
    .filter { it.name == "build.gradle.kts" && it.parentFile != rootDir }
    .map { it.parentFile }
    .filter { dir -> allprojects.none { it.projectDir == dir } }
    .sorted()
    .toList()

detekt {
    buildUponDefaultConfig.set(true)
    parallel.set(true)
    // detekt-android-tests.yml: no JUnit Jupiter imports in Android sources (their tests run on JUnit 4).
    config.setFrom(file("config/detekt/detekt.yml"), file("config/detekt/detekt-android-tests.yml"))
    source.setFrom(
        androidModuleDirs.flatMap { dir -> listOf("main", "test", "androidTest").map { File(dir, "src/$it/kotlin") } }
            .filter { it.exists() },
    )
}

spotless {
    kotlin {
        target(androidModuleDirs.map { "${it.relativeTo(rootDir)}/src/**/*.kt" })
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(
            mapOf(
                "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
                "ktlint_standard_function-naming" to "enabled",
                "max_line_length" to "140",
            ),
        )
    }
    kotlinGradle {
        target(androidModuleDirs.map { "${it.relativeTo(rootDir)}/*.gradle.kts" })
        ktlint(libs.versions.ktlint.get())
    }
}
