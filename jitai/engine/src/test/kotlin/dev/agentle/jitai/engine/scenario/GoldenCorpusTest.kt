package dev.agentle.jitai.engine.scenario

import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.FeatureRef
import dev.agentle.jitai.dsl.model.ActiveWindow
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.SuppressionTarget
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.Leaves
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.content.RenderedIntervention
import dev.agentle.jitai.engine.int
import dev.agentle.jitai.engine.pipeline.TraceCodec
import dev.agentle.jitai.engine.row
import dev.agentle.jitai.engine.testing.EngineHarness
import dev.agentle.jitai.engine.timer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/**
 * testing-build-03: a golden corpus of decision traces and rendered interventions in `src/test/resources/golden`. Each
 * stored file must decode with the current code and equal what the scenario produces now, so a format change is a
 * reviewed diff. Regenerate with `AGENTLE_GOLDEN_UPDATE=true`.
 */
class GoldenCorpusTest {
    class Case(val name: String, val key: String, val build: suspend () -> EngineHarness) {
        override fun toString(): String = name
    }

    @ParameterizedTest(quoteTextArguments = false, name = "golden {0}")
    @MethodSource("cases")
    fun `stored traces and renderings decode and match the current engine`(case: Case) = runTest {
        val harness = case.build()
        val trace = checkNotNull(harness.row(case.key).content.traceJson)
        val rendered = harness.delivery.posts.firstOrNull {
            it.decisionKey == case.key
        }?.let { JSON.encodeToString(RenderedIntervention.serializer(), it) }
        if (UPDATE) {
            File(DIR, "${case.name}.trace.json").apply { parentFile.mkdirs() }.writeText(trace + "\n")
            rendered?.let { File(DIR, "${case.name}.rendered.json").writeText(it + "\n") }
        }

        val storedTrace = resource("${case.name}.trace.json")
        assertWithMessage(case.name).that(TraceCodec.decode(storedTrace)).isNotNull()
        assertWithMessage(case.name).that(TraceCodec.decode(storedTrace)).isEqualTo(TraceCodec.decode(trace))
        if (rendered != null) {
            val stored = JSON.decodeFromString(RenderedIntervention.serializer(), resource("${case.name}.rendered.json"))
            assertWithMessage(case.name).that(stored).isEqualTo(JSON.decodeFromString(RenderedIntervention.serializer(), rendered))
        }
    }

    companion object {
        private val UPDATE = System.getenv("AGENTLE_GOLDEN_UPDATE") == "true"
        private const val DIR = "src/test/resources/golden"
        private val JSON = Json { prettyPrint = true }

        private fun resource(name: String): String =
            checkNotNull(GoldenCorpusTest::class.java.getResource("/golden/$name")) { "missing golden/$name" }.readText().trimEnd()

        private suspend fun r1(screen: Boolean, vararg extra: dev.agentle.jitai.dsl.model.JitaiDefinition): EngineHarness {
            val at = F0.local("2026-10-01T22:30")
            val harness = F0.harness(at, Rules.R1, *extra)
            if (screen) harness.features.set(Leaves.SCREEN, int(50, at))
            harness.timer()
            return harness
        }

        @JvmStatic
        fun cases(): List<Case> = listOf(
            Case("r1-delivered", "v1|R1|I|2026-10-01|10") { r1(screen = true) },
            Case("r1-unknown", "v1|R1|I|2026-10-01|10") { r1(screen = false) },
            Case("r1-suppressed-by-rule", "v1|R1|I|2026-10-01|10") {
                val s = Rules.suppression(
                    "S",
                    ActiveWindow("22:00", "23:00"),
                    Leaves.eq("charging", true),
                    SuppressionTarget(categories = listOf(JitaiCategory.DIGITAL_WELLBEING)),
                )
                val harness = F0.harness(F0.local("2026-10-01T22:30"), Rules.R1, s)
                harness.features.set(Leaves.SCREEN, int(50, F0.local("2026-10-01T22:30")))
                harness.features.clear(FeatureRef("charging"))
                harness.timer()
                harness
            },
            Case("r2-template", "v1|R2|D|2026-10-01|17:00") {
                val harness = F0.harness(F0.local("2026-10-01T17:00"), Rules.R2)
                harness.features.set(Leaves.STEPS, int(2_000, F0.local("2026-10-01T16:50")))
                harness.timer()
                harness
            },
        )
    }
}
