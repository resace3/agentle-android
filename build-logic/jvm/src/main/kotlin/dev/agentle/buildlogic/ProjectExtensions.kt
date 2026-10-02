package dev.agentle.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun VersionCatalog.lib(alias: String) = findLibrary(alias).orElseThrow {
    IllegalArgumentException("Unknown catalog library '$alias'")
}

fun VersionCatalog.version(alias: String): String = findVersion(alias).orElseThrow {
    IllegalArgumentException("Unknown catalog version '$alias'")
}.requiredVersion
