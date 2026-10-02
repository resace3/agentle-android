// Agentle: one Gradle build, two build-logic included builds.
//
// JVM-only mode (AGENTLE_JVM_ONLY=true or -Pagentle.jvmOnly=true) includes only the pure-Kotlin modules and the
// JVM half of build-logic, so the domain core builds and tests where Google Maven (AGP, AndroidX) is unreachable.
pluginManagement {
    val jvmOnlyMode = System.getenv("AGENTLE_JVM_ONLY") == "true" ||
        providers.gradleProperty("agentle.jvmOnly").orNull == "true"
    includeBuild("build-logic/jvm")
    if (!jvmOnlyMode) includeBuild("build-logic/android")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("com\\.google\\.firebase.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "agentle"

val jvmOnly = System.getenv("AGENTLE_JVM_ONLY") == "true" ||
    providers.gradleProperty("agentle.jvmOnly").orNull == "true"

// Pure Kotlin/JVM modules: all domain logic. Build and test anywhere.
include(
    ":core:model",
    ":core:common",
    ":core:time",
    ":core:network",
    ":core:oauth",
    ":core:testing",
    ":connectors:api",
    ":connectors:googlehealth",
    ":ai:api",
    ":ai:chatgpt",
    ":ai:context",
    ":analytics:features",
    ":analytics:insights",
    ":jitai:dsl",
    ":jitai:engine",
    ":fakes",
)

if (jvmOnly) {
    rootProject.buildFileName = "build-jvm.gradle.kts"
} else {
    // Android modules: persistence, collectors, delivery, scheduling, UI and app wiring.
    include(
        ":core:database",
        ":core:security",
        ":core:datastore",
        ":core:ui",
        ":data",
        ":connectors:android",
        ":interventions",
        ":background",
        ":feature:onboarding",
        ":feature:hub",
        ":feature:insights",
        ":feature:connections",
        ":feature:settings",
        ":app",
    )
}
