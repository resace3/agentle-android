package dev.agentle.app

import org.junit.jupiter.api.Test

/**
 * PROBE (BUILD-INFRA item D): a JUnit Jupiter test in an Android module compiles and never runs on JUnit 4. detekt
 * must report the import (ForbiddenImport); the suite count must stay 2. The next commit removes this file.
 */
class JupiterImportProbe {
    @Test
    fun neverRuns() = Unit
}
