package dev.agentle.jitai.dsl

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiLifecycle
import dev.agentle.jitai.dsl.render.RenderOptions
import dev.agentle.jitai.dsl.render.RuleRenderer
import dev.agentle.jitai.dsl.testing.Fixtures
import dev.agentle.jitai.dsl.validation.RuleValidator
import dev.agentle.jitai.dsl.validation.TextLint
import dev.agentle.jitai.dsl.validation.ValidationInput
import dev.agentle.jitai.dsl.validation.ValidationRequest
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.time.Instant

/**
 * Integrator correction testing-build-04: test JVMs will soon default to a non-UTC zone (America/St_Johns). Validation,
 * rendering and expiry read the zone they are given (the [dev.agentle.core.time.AgentleClock] or [RenderOptions]),
 * never the JVM default zone or locale.
 */
class ZoneIndependenceTest {
    @Test
    fun `reports, sentences and expiry do not change with the JVM default zone and locale`() {
        val baseline = snapshot()

        val changed = withJvmDefaults(java.util.TimeZone.getTimeZone("America/St_Johns"), Locale.forLanguageTag("tr-TR")) { snapshot() }
        val far = withJvmDefaults(java.util.TimeZone.getTimeZone("Pacific/Kiritimati"), Locale.forLanguageTag("ar-EG")) { snapshot() }

        assertThat(changed).isEqualTo(baseline)
        assertThat(far).isEqualTo(baseline)
    }

    @Test
    fun `the zone given decides the local date`() {
        val definition = golden("definition-3-1.json").copy(expiresAt = Instant.parse("2026-10-28T23:00:00Z"))
        val approvedAt = Instant.parse("2026-10-01T16:00:00Z")

        val berlin = RuleRenderer.render(definition, RenderOptions(zone = Fixtures.BERLIN))
        val kiritimati = RuleRenderer.render(definition, RenderOptions(zone = TimeZone.of("Pacific/Kiritimati")))

        assertThat(berlin).endsWith("Ends after October 28, 2026.")
        assertThat(kiritimati).endsWith("Ends after October 29, 2026.")
        assertThat(JitaiLifecycle.expiresAt(approvedAt, Fixtures.BERLIN, 27)).isEqualTo(Instant.parse("2026-10-27T23:00:00Z"))
        assertThat(JitaiLifecycle.expiresAt(approvedAt, TimeZone.of("America/St_Johns"), 27))
            .isEqualTo(Instant.parse("2026-10-28T02:30:00Z"))
    }

    @Test
    fun `text folding ignores a Turkish default locale`() {
        val folded = withJvmDefaults(java.util.TimeZone.getTimeZone("UTC"), Locale.forLanguageTag("tr-TR")) { TextLint.fold("INSOMNIA") }

        assertThat(folded).isEqualTo("insomnia")
    }

    private fun snapshot(): List<Any?> {
        val stored = GOLDENS.map(::golden)
        val context = { Fixtures.context(settings = Fixtures.DEFAULT_SETTINGS) }
        val proposals = listOf(Fixtures.example1, Fixtures.example2, Fixtures.example3).map { Fixtures.validateText(it, context()) }
        val discovered = RuleValidator.validate(ValidationRequest(ValidationInput.DiscoveredText(Fixtures.discovered)), context())
        val definitions = stored.map { Fixtures.validateDefinition(it, context()) }
        val renderings = stored.flatMap { definition ->
            listOf(
                RuleRenderer.render(definition, RenderOptions(zone = Fixtures.BERLIN)),
                RuleRenderer.render(definition.copy(expiresAt = Instant.parse("2026-10-28T23:00:00Z"))),
                RuleRenderer.render(definition, RenderOptions(use24HourClock = true, zone = TimeZone.of("America/St_Johns"))),
            )
        }
        val verdicts = stored.map { RuleValidator.revalidate(it, Fixtures.MEDIA) }
        val expiry = JitaiLifecycle.expiresAt(Instant.parse("2026-10-01T16:00:00Z"), Fixtures.BERLIN, 28)
        return proposals + discovered + definitions + renderings + verdicts + expiry
    }

    private fun <T> withJvmDefaults(zone: java.util.TimeZone, locale: Locale, block: () -> T): T {
        val previousZone = java.util.TimeZone.getDefault()
        val previousLocale = Locale.getDefault()
        java.util.TimeZone.setDefault(zone)
        Locale.setDefault(locale)
        try {
            return block()
        } finally {
            java.util.TimeZone.setDefault(previousZone)
            Locale.setDefault(previousLocale)
        }
    }

    private companion object {
        val GOLDENS = listOf(
            "definition-3-1.json",
            "definition-12-r1.json",
            "definition-13-6-1.json",
            "definition-13-6-2.json",
            "definition-13-6-3.json",
            "definition-14-7.json",
        )

        fun golden(file: String): JitaiDefinition = RuleCodec.decodeDefinition(Fixtures.resource("r10/$file")).getOrThrow()
    }
}
