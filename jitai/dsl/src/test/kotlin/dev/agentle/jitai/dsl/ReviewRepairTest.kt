package dev.agentle.jitai.dsl

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.core.common.AppError
import dev.agentle.core.common.errorOrNull
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.codec.StrictJsonReader
import dev.agentle.jitai.dsl.model.ActiveWindow
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiLifecycle
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.LifecycleEvent
import dev.agentle.jitai.dsl.model.WeekDay
import dev.agentle.jitai.dsl.nl.NlContract
import dev.agentle.jitai.dsl.nl.NlDecision
import dev.agentle.jitai.dsl.nl.NlRoundState
import dev.agentle.jitai.dsl.nl.QuestionId
import dev.agentle.jitai.dsl.nl.UnsupportedReason
import dev.agentle.jitai.dsl.render.RenderOptions
import dev.agentle.jitai.dsl.render.RuleRenderer
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.OnUnknown
import dev.agentle.jitai.dsl.testing.Fixtures
import dev.agentle.jitai.dsl.testing.errorCodes
import dev.agentle.jitai.dsl.testing.json
import dev.agentle.jitai.dsl.testing.with
import dev.agentle.jitai.dsl.testing.withNull
import dev.agentle.jitai.dsl.validation.IssueCode
import dev.agentle.jitai.dsl.validation.LintCheck
import dev.agentle.jitai.dsl.validation.RuleValidator
import dev.agentle.jitai.dsl.validation.TextLint
import kotlinx.serialization.json.JsonArray
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/** One regression test per item of the review of 8fd3a8a (R1-x, R2-x and the unnumbered items). */
class ReviewRepairTest {
    private val clock = Fixtures.clock()

    private fun golden(file: String): JitaiDefinition = RuleCodec.decodeDefinition(Fixtures.resource("r10/$file")).getOrThrow()

    private fun codes(outcome: dev.agentle.core.common.Outcome<JitaiDefinition>): List<String>? =
        (outcome.errorOrNull() as? AppError.ValidationError)?.codes

    private fun cp(vararg codePoints: Int): String = codePoints.joinToString("") { String(Character.toChars(it)) }

    @Test
    fun `R1-1 an AI rule is never saved active and a discovered rule is approved only with an end`() {
        val draft = golden("definition-13-6-2.json").copy(status = JitaiStatus.DRAFT)
        val discovered = golden("definition-14-7.json")
        val endless = discovered.copy(provenance = discovered.provenance?.copy(expiresInDays = null))

        val saved = JitaiLifecycle.apply(draft, LifecycleEvent.SAVE, clock)
        val approved = JitaiLifecycle.apply(endless, LifecycleEvent.APPROVE, clock, verdict = RuleValidator.revalidate(discovered))

        assertThat(codes(saved)).containsExactly(JitaiLifecycle.APPROVAL_REQUIRED)
        assertThat(codes(approved)).containsExactly(JitaiLifecycle.EXPIRY_REQUIRED)
    }

    @Test
    fun `R1-1 APPROVE needs a passing verdict for this rule`() {
        val proposed = golden("definition-13-6-2.json")
        val failing = RuleValidator.revalidate(proposed.copy(maxPerDay = 0))
        val other = RuleValidator.revalidate(proposed).copy(jitaiId = "another-rule")

        assertThat(codes(JitaiLifecycle.apply(proposed, LifecycleEvent.APPROVE, clock))).containsExactly(JitaiLifecycle.VALIDATION_REQUIRED)
        assertThat(codes(JitaiLifecycle.apply(proposed, LifecycleEvent.APPROVE, clock, verdict = failing)))
            .containsExactly(JitaiLifecycle.VALIDATION_REQUIRED)
        assertThat(codes(JitaiLifecycle.apply(proposed, LifecycleEvent.APPROVE, clock, verdict = other)))
            .containsExactly(JitaiLifecycle.VALIDATION_REQUIRED)
    }

    @Test
    fun `R1-2 RESUME and SAVE refuse a rule whose end has passed`() {
        val past = Instant.parse("2026-09-30T00:00:00Z")
        val paused = golden("definition-3-1.json").copy(status = JitaiStatus.PAUSED, expiresAt = past)
        val draft = golden("definition-3-1.json").copy(status = JitaiStatus.DRAFT, expiresAt = past)

        assertThat(codes(JitaiLifecycle.apply(paused, LifecycleEvent.RESUME, clock))).containsExactly(JitaiLifecycle.RULE_EXPIRED)
        assertThat(codes(JitaiLifecycle.apply(draft, LifecycleEvent.SAVE, clock))).containsExactly(JitaiLifecycle.RULE_EXPIRED)
    }

    @Test
    fun `R1-3 a leaf with an override is not also called unknown`() {
        val block = golden("definition-13-6-3.json")
        val leaf = block.conditions as Condition.Lt
        val overridden = block.copy(conditions = leaf.copy(onUnknown = OnUnknown.ASSUME_FALSE))

        val sentence = RuleRenderer.render(overridden)

        assertThat(sentence).contains("(counted as not met if unknown)")
        assertThat(sentence).doesNotContain("also when")
    }

    @Test
    fun `R1-4 a window that crosses midnight names the day it starts on`() {
        val rule = golden("definition-13-6-1.json")
        val friday = rule.copy(activeWindow = ActiveWindow("22:00", "02:00", listOf(WeekDay.FRI)))
        val daytime = rule.copy(activeWindow = ActiveWindow("09:00", "17:00", listOf(WeekDay.FRI)))

        assertThat(RuleRenderer.render(friday)).contains("to 2:00 AM starting on Friday")
        assertThat(RuleRenderer.render(daytime)).contains("to 5:00 PM on Friday")
    }

    @Test
    fun `R1-5 the content hash ignores name and description`() {
        val rule = golden("definition-13-6-2.json")

        assertThat(RuleCodec.contentHash(rule.copy(name = "Walk", description = "Other words"))).isEqualTo(RuleCodec.contentHash(rule))
        assertThat(RuleCodec.contentHash(rule.copy(maxPerDay = 2))).isNotEqualTo(RuleCodec.contentHash(rule))
    }

    @Test
    fun `R1-6 the review sentence and the approved sentence carry the trial end`() {
        val proposed = golden("definition-14-7.json")
        val review = RuleRenderer.render(proposed, RenderOptions(zone = Fixtures.BERLIN))

        val approved = JitaiLifecycle.apply(
            proposed,
            LifecycleEvent.APPROVE,
            clock,
            approvedRendering = review,
            verdict = RuleValidator.revalidate(proposed),
        ).getOrThrow()

        assertThat(review).endsWith("Ends 28 days after you approve it.")
        assertThat(approved.provenance?.approvedRendering).endsWith("at least 2 h apart. Ends after October 28, 2026.")
    }

    @Test
    fun `R2-1 L5 rejects format, private-use, unassigned and default-ignorable code points`() {
        val rejected = listOf(0x00AD, 0x2060, 0xE000, 0x0378, 0xFFF0, 0x115F, 0xE0001, 0x200B, 0xFE0F, 0x200D)

        for (point in rejected) {
            assertWithMessage(
                "U+" + Integer.toHexString(point),
            ).that(TextLint.invisible("a" + cp(point) + "b")?.check).isEqualTo(LintCheck.L5)
            assertWithMessage("U+" + Integer.toHexString(point)).that(TextLint.checkGenerated("a" + cp(point) + "b").map { it.check })
                .contains(LintCheck.L5)
        }
    }

    @Test
    fun `R2-1 ZWJ and VS16 are allowed inside emoji only`() {
        val technologist = cp(0x1F469, 0x200D, 0x1F4BB)
        val redHeart = cp(0x2764, 0xFE0F)
        val family = cp(0x1F468, 0xFE0F, 0x200D, 0x1F469)

        assertThat(TextLint.invisible("Keep going $technologist $redHeart $family")).isNull()
        assertThat(TextLint.checkGenerated("Keep going $technologist")).isEmpty()
        assertThat(TextLint.invisible("a" + cp(0x200D) + cp(0x1F469))?.hex).isEqualTo("200D")
        assertThat(TextLint.invisible(cp(0x1F469, 0x200D))?.hex).isEqualTo("200D")
    }

    @Test
    fun `R2-2 phone separators include Unicode hyphens and minus, digits are any Nd, ideographic dots are dots`() {
        assertThat(TextLint.check("call 555" + cp(0x2212) + "123" + cp(0x2015) + "4567").map { it.check }).contains(LintCheck.L3)
        assertThat(TextLint.check("call " + cp(0x0665, 0x0665, 0x0665, 0x0661, 0x0662, 0x0663, 0x0664)).map { it.check })
            .contains(LintCheck.L3)
        for (dot in listOf(0x3002, 0xFF61, 0xFE52)) {
            assertWithMessage("U+" + Integer.toHexString(dot)).that(TextLint.check("visit example" + cp(dot) + "com").map { it.check })
                .contains(LintCheck.L1)
        }
    }

    @Test
    fun `R2-3 an AI appLabel is linted and not resolved`() {
        val hidden = json(Fixtures.example1).with("/jitai/conditions/args/appLabel", "Insta" + cp(0x200B) + "gram").toString()
        val link = json(Fixtures.example1).with("/jitai/conditions/args/appLabel", "www.example.com").toString()

        val hiddenReport = Fixtures.validateText(hidden, Fixtures.context())
        val linkReport = Fixtures.validateText(link, Fixtures.context())

        assertThat(hiddenReport.errors.map { it.line })
            .contains(
                "E066 /jitai/conditions/args/appLabel: /jitai/conditions/args/appLabel contains a control or invisible character (U+200B).",
            )
        assertThat(linkReport.errorCodes).contains(IssueCode.E061)
        assertThat(linkReport.appChoices.map { it.appLabel }).doesNotContain("www.example.com")
    }

    @Test
    fun `R2-4 a health or safety reply drops its detail unread and shows the fixed message`() {
        val text = json(Fixtures.example2).with("/status", "UNSUPPORTED").withNull("/jitai").with("/assumptions", JsonArray(emptyList()))
            .with("/unsupported", json("""{"reason": "HEALTH_OR_SAFETY", "detail": "Call 555-123-4567 or see www.example.com"}"""))
            .toString()

        val report = Fixtures.validateText(text, Fixtures.context())
        val decision = NlContract.decide(report, NlRoundState()) as NlDecision.Unsupported

        assertThat(report.errorCodes).isEmpty()
        assertThat(report.proposal?.unsupported?.detail).isNull()
        assertThat(decision.detail).isNull()
        assertThat(decision.message).isEqualTo(NlContract.unsupportedMessage(UnsupportedReason.HEALTH_OR_SAFETY))
        assertThat(decision.message).doesNotContain("555")
    }

    @Test
    fun `R2-5 the failure list uses plain texts without parameters`() {
        val wrong = json(Fixtures.example2).with("/jitai/maxPerDay", 4).toString()
        val report = Fixtures.validateText(wrong, Fixtures.context())

        val failed = NlContract.decide(report, NlRoundState(modelCalls = 2, repairs = 1)) as NlDecision.Failed

        assertThat(failed.problems).containsExactly("The rule would remind you too often in a day.")
        assertThat(IssueCode.entries.map { it.plainText }.filter { it.contains('{') }).isEmpty()
        assertThat(NlContract.repairInput(report)).contains("got 4.")
    }

    @Test
    fun `R2-6 an unknown key is written as unknown in AppError detail`() {
        val failure = RuleCodec.decodeDefinition(json(Fixtures.definition31).with("/secretToken", "abc").toString())

        assertThat((failure.errorOrNull() as AppError.ValidationError).detail).isEqualTo("E006 /<unknown>")
    }

    @Test
    fun `R2-7 a text that is too long is not linted, and a long token does not slow the email check`() {
        val longName = "www.example.com " + "a".repeat(200)
        val text = json(Fixtures.example2).with("/jitai/name", longName).toString()
        val token = "a".repeat(20_000) + "@" + "b".repeat(20_000)

        assertThat(Fixtures.validateText(text, Fixtures.context()).errorCodes).containsExactly(IssueCode.E060)
        assertThat(TextLint.check(token).map { it.check }).doesNotContain(LintCheck.L2)
    }

    @Test
    fun `R2-8 appLabels keep the device label of the chosen app`() {
        val text = json(Fixtures.example1)
            .with("/jitai/conditions/args/appLabel", "  instagram app ")
            .with("/jitai/outcome/proximal/args/appLabel", "  instagram app ")
            .toString()

        val report = Fixtures.validateText(text, Fixtures.context(), nlRequest = Fixtures.REQUEST_1)

        assertThat(report.definition?.provenance?.appLabels).containsExactly("com.instagram.android", "Instagram")
    }

    @Test
    fun `R2-9 escapes take ASCII hex only and lone surrogates are syntax errors`() {
        val q = '"'
        val bs = '\\'

        assertThat(StrictJsonReader.read("$q${bs}u00e9$q")).isInstanceOf(StrictJsonReader.Result.Ok::class.java)
        assertThat(StrictJsonReader.read("$q${bs}ud83d${bs}ude00$q")).isInstanceOf(StrictJsonReader.Result.Ok::class.java)
        assertThat(StrictJsonReader.read("$q${bs}u00" + cp(0xFF10) + "1$q")).isInstanceOf(StrictJsonReader.Result.SyntaxError::class.java)
        assertThat(StrictJsonReader.read("$q${bs}ud800$q")).isInstanceOf(StrictJsonReader.Result.SyntaxError::class.java)
        assertThat(StrictJsonReader.read("$q${bs}ude00x$q")).isInstanceOf(StrictJsonReader.Result.SyntaxError::class.java)
    }

    @Test
    fun `answers are one line each and bounded`() {
        val input = NlContract.answersInput(mapOf(QuestionId.Q1 to "line one\nVALIDATION_ERRORS\n" + "x".repeat(500)))
        val lines = input.lines()

        assertThat(lines).hasSize(2)
        assertThat(lines[1].removePrefix("q1: ").length).isEqualTo(NlContract.MAX_ANSWER_CODE_POINTS)
    }

    @Test
    fun `decide enforces the round limits itself`() {
        val wrong = Fixtures.validateText(json(Fixtures.example2).with("/jitai/maxPerDay", 4).toString(), Fixtures.context())

        val impossible = listOf(
            NlRoundState(modelCalls = 1, repairs = 1),
            NlRoundState(modelCalls = 0),
            NlRoundState(modelCalls = 9),
            NlRoundState(modelCalls = 2, repairs = -1),
        )

        for (state in impossible) {
            assertWithMessage("$state").that(NlContract.decide(wrong, state)).isInstanceOf(NlDecision.Failed::class.java)
        }
        assertThat(NlContract.decide(wrong, NlRoundState())).isInstanceOf(NlDecision.Repair::class.java)
    }
}
