package dev.agentle.buildlogic.android

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { IllegalArgumentException("Unknown catalog library '$alias'") }

internal fun VersionCatalog.intVersion(alias: String): Int =
    findVersion(alias).orElseThrow { IllegalArgumentException("Unknown catalog version '$alias'") }.requiredVersion.toInt()

/** `:feature:hub` -> `dev.agentle.feature.hub`; `:app` -> `dev.agentle.app`. */
internal fun Project.agentleNamespace(): String =
    "dev.agentle." + path.removePrefix(":").replace(':', '.').replace("-", "")
