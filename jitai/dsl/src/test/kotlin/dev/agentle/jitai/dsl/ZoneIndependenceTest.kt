package dev.agentle.jitai.dsl

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiLifecycle
import dev.agentle.jitai.dsl.model.LifecycleEvent
import dev.agentle.jitai.dsl.render.RenderOptions
import dev.agentle.jitai.dsl.render.RuleRenderer
import dev.agentle.jitai.dsl.testing.Fixtures
import dev.agentle.jitai.dsl.validation.RuleValidator
import dev.agentle.jitai.dsl.validation.TextLint
import dev.agentle.jitai.dsl.validation.ValidationInput
import dev.agentle.jitai.dsl.validation.ValidationRequest
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.Locale
import kotlin.time.Instant

/**
 * Integrator correction testing-build-04: every test JVM runs in America/St_Johns (`user.timezone` and `TZ`, set by
 * build-logic), a half-hour zone with DST, while the fixtures use the zone of their [dev.agentle.core.time.AgentleClock]
 * (Europe/Berlin) or of [RenderOptions]. Code that read the JVM default zone would compute other local dates than the
 * ones pinned here and in the rest of the suite. These tests never read or change the JVM default zone (detekt
 * ForbiddenMethodCall); they pin dates for several given zones and check that the JVM default locale changes nothing.
 */
class ZoneIndependenceTest {
    @Test
    fun `reports, sentences and expiry do not change with the JVM default locale`() {
        val baseline = snapshot()

        val turkish = withDefaultLocale(Locale.forLanguageTag("tr-TR")) { snapshot() }
        val arabic = withDefaultLocale(Locale.forLanguageTag("ar-EG")) { snapshot() }

        assertThat(turkish).isEqualTo(baseline)
        assertThat(arabic).isEqualTo(baseline)
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

    /** 2026-10-01T16:00Z plus the 28-day trial of the R10 §14.7 proposal, at local midnight of the clock's zone. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "Europe/Berlin, 2026-10-28T23:00:00Z",
        "America/St_Johns, 2026-10-29T02:30:00Z",
        "Pacific/Kiritimati, 2026-10-29T10:00:00Z",
        "UTC, 2026-10-29T00:00:00Z",
    )
    fun `APPROVE sets expiresAt at local midnight in the zone of the clock`(zone: String, expected: String) {
        val clock = Fixtures.clock(now = Instant.parse("2026-10-01T16:00:00Z"), zone = TimeZone.of(zone))

        val proposed = golden("definition-14-7.json")
        val verdict = RuleValidator.revalidate(proposed)

        val approved = JitaiLifecycle.apply(proposed, LifecycleEvent.APPROVE, clock, verdict = verdict).getOrThrow()

        assertWithMessage(zone).that(approved.expiresAt).isEqualTo(Instant.parse(expected))
    }

    @Test
    fun `text folding ignores a Turkish default locale`() {
        val folded = withDefaultLocale(Locale.forLanguageTag("tr-TR")) { TextLint.fold("INSOMNIA") }

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

    /** Runs [block] with [locale] as the JVM default locale (the default zone is left as the build set it). */
    private fun <T> withDefaultLocale(locale: Locale, block: () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            return block()
        } finally {
            Locale.setDefault(previous)
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
