package dev.agentle.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Writes the test suites this build must produce, one `<suite> <minimum distinct tests>` line each, for
 * `tools/junit_summary.py --expect-file` (red team testing-build-03): a suite that never ran, or ran fewer tests than
 * its minimum, fails the evidence instead of silently shrinking it. The header records the build mode.
 *
 * A suite's minimum comes from tools/test-minimums.txt; otherwise it is 1 when the module has unit-test sources and
 * 0 (the suite may be absent) when it has none. The task reads the source tree when it runs, so it never goes stale.
 */
abstract class WriteExpectedSuitesTask : DefaultTask() {
    /** Suite key (`:module:testTask`, as junit_summary names it) to the module's `src` directory. */
    @get:Input
    abstract val suiteSourceRoots: MapProperty<String, String>

    /** Minimums from tools/test-minimums.txt, for the suites of this build only. */
    @get:Input
    abstract val minimums: MapProperty<String, Int>

    /** `full` or `jvm-only`. */
    @get:Input
    abstract val buildMode: Property<String>

    /** `all`, `jvm` or `android`: which modules' suites are listed. */
    @get:Input
    abstract val kind: Property<String>

    @get:OutputFile
    abstract val output: RegularFileProperty

    init {
        doNotTrackState("Reads the test source trees when it runs; cheap enough to always run")
    }

    @TaskAction
    fun write() {
        val explicit = minimums.get()
        val lines = buildList {
            add("# Expected test suites, written by :writeExpectedSuites. Do not edit; minimums live in tools/test-minimums.txt.")
            add("# mode: ${buildMode.get()}")
            add("# kind: ${kind.get()}")
            add("# <suite> <minimum distinct tests>; 0: the module has no unit-test sources yet, so the suite may be absent")
            suiteSourceRoots.get().toSortedMap().forEach { (suite, srcDir) ->
                val minimum = explicit[suite] ?: if (hasUnitTestSources(File(srcDir))) 1 else 0
                add("$suite $minimum")
            }
        }
        output.get().asFile.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun hasUnitTestSources(srcDir: File): Boolean =
        srcDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("test") && it.name != "testFixtures" }
            .any { dir -> dir.walkTopDown().any { it.isFile && it.extension in SOURCE_EXTENSIONS } }

    private companion object {
        val SOURCE_EXTENSIONS = setOf("kt", "java")
    }
}
