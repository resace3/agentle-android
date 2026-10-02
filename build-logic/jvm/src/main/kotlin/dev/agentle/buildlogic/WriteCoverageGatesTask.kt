package dev.agentle.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Writes the coverage gates of the modules this build measures (CoverageGates.kt), one
 * `<module> <report> <area> <line minimum> <branch minimum or ->` line each, for
 * `tools/junit_summary.py --coverage-gates`. The header says whether a miss fails the build.
 */
abstract class WriteCoverageGatesTask : DefaultTask() {
    @get:Input
    abstract val lines: ListProperty<String>

    /** `-Pagentle.coverage.enforce`. */
    @get:Input
    abstract val enforce: Property<Boolean>

    /** `-Pagentle.coverage.modules`: `all`, `jvm` or `android`. */
    @get:Input
    abstract val modules: Property<String>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun write() {
        val text = buildList {
            add("# Coverage gates, written by :writeCoverageGates from build-logic CoverageGates.kt. Do not edit.")
            add("# enforce: ${enforce.get()}")
            add("# modules: ${modules.get()}")
            add("# <module> <report: total or Kover variant> <area> <line minimum %> <branch minimum % or ->")
            addAll(lines.get())
        }
        output.get().asFile.writeText(text.joinToString(separator = "\n", postfix = "\n"))
    }
}
