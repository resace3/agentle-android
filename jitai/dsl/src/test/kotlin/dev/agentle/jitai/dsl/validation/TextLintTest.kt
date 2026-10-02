package dev.agentle.jitai.dsl.validation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.testing.Fixtures
import dev.agentle.jitai.dsl.testing.errorCodes
import dev.agentle.jitai.dsl.testing.issues
import dev.agentle.jitai.dsl.testing.json
import dev.agentle.jitai.dsl.testing.with
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** R10 §11.6 text lint L1-L8 (ids kept exactly, red team privacy-ai-06). */
class TextLintTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("findings")
    fun `L1-L4, L6 and L7 run on the NFKC case-folded text`(row: String, text: String, check: LintCheck?, match: String?) {
        val findings = TextLint.check(text).map { it.check to it.match }

        if (check == null) {
            assertWithMessage(row).that(findings).isEmpty()
        } else {
            assertWithMessage(row).that(findings).contains(check to match)
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invisibles")
    fun `L5 finds controls and invisible characters in the raw text`(row: String, text: String, hex: String?) {
        val finding = TextLint.invisible(text)

        assertWithMessage(row).that(finding?.hex).isEqualTo(hex)
        if (hex != null) {
            assertWithMessage(row).that(TextLint.check(text).first().check).isEqualTo(LintCheck.L5)
            assertWithMessage(row).that(finding?.match?.codePointAt(0)).isEqualTo(hex.toInt(16))
        }
    }

    @Test
    fun `L8 drops generated text with placeholders`() {
        assertThat(TextLint.checkGenerated("You walked {{steps_today}} steps.").map { it.check }).containsExactly(LintCheck.L8)
        assertThat(TextLint.checkGenerated("A short walk would help.")).isEmpty()
        assertThat(TextLint.checkGenerated("See www.x {{").map { it.check }).containsExactly(LintCheck.L1, LintCheck.L8).inOrder()
        assertThat(TextLint.check("You walked {{steps_today}} steps.")).isEmpty()
    }

    @Test
    fun `each lint id maps to one validator code`() {
        val table = LintCheck.entries.associate { it.name to (it.code to it.category) }

        assertThat(table).containsExactly(
            "L1", IssueCode.E061 to null,
            "L2", IssueCode.E061 to null,
            "L3", IssueCode.E061 to null,
            "L4", IssueCode.E062 to "markup",
            "L5", IssueCode.E066 to null,
            "L6", IssueCode.E062 to "medical wording",
            "L7", IssueCode.E062 to "a causal claim",
            "L8", null to null,
        ).inOrder()
        assertThat(TextLint.VERSION).isEqualTo(1)
    }

    @Test
    fun `fold is NFKC plus lower case in the root locale`() {
        assertThat(TextLint.fold("ＡＢＣ Ünd")).isEqualTo("abc ünd")
        assertThat(TextLint.fold("ﬁ")).isEqualTo("fi")
    }

    @Test
    fun `AI text gets every check and the validator reports the first match`() {
        val report = Fixtures.validateText(json(Fixtures.example2).with("/jitai/description", "See www.agentle.app for walks").toString())
        val causal = Fixtures.validateText(json(Fixtures.example2).with("/jitai/content/title", "Sitting causes stress").toString())

        assertThat(report.issues(IssueCode.E061).map { it.line })
            .containsExactly("E061 /jitai/description: /jitai/description contains a link, email address or phone number (\"www.\").")
        assertThat(causal.issues(IssueCode.E062).map { it.message })
            .containsExactly("/jitai/content/title contains a causal claim: \"causes\".")
    }

    @Test
    fun `USER text gets L5 only`() {
        val walk = RuleCodec.decodeDefinition(Fixtures.definition31).getOrThrow()

        val link = Fixtures.validateDefinition(walk.copy(description = "See www.agentle.app, it causes no stress"))
        val invisible = Fixtures.validateDefinition(walk.copy(name = "Walk\u200Bnudge"))

        assertThat(link.errorCodes).isEmpty()
        assertThat(invisible.issues(IssueCode.E066).map { it.line })
            .containsExactly("E066 /name: /name contains a control or invisible character (U+200B).")
    }

    companion object {
        private fun row(id: String, text: String, check: LintCheck?, match: String?) = Arguments.of(id, text, check, match)

        @JvmStatic
        fun findings(): List<Arguments> = listOf(
            row("L1 https", "Read https://example.org now", LintCheck.L1, "https://"),
            row("L1 www", "Open www.agentle", LintCheck.L1, "www."),
            row("L1 bundled TLD", "Visit agentle.app today", LintCheck.L1, "agentle.app"),
            row("L1 subdomain", "See help.agentle.io", LintCheck.L1, "help.agentle.io"),
            row("L1 folded upper case", "Visit AGENTLE.COM", LintCheck.L1, "agentle.com"),
            row("L1 an abbreviation is no domain", "Walk, e.g. to the park.", null, null),
            row("L1 an unbundled TLD", "Try node.js later", null, null),
            row("L2 email", "Write to me@example.org", LintCheck.L2, "me@example.org"),
            row("L2 email with any TLD", "Write to a@b.zz", LintCheck.L2, "a@b.zz"),
            row("L3 phone", "Call 555 123 4567", LintCheck.L3, "555 123 4567"),
            row("L3 phone with parentheses", "Call (030) 1234-567", LintCheck.L3, "030) 1234-567"),
            row("L3 short numbers", "Walk 3,000 steps in 45 min", null, null),
            row("L3 six digits", "Code 123456", null, null),
            row("L4 tag", "<b>Walk</b>", LintCheck.L4, "<b"),
            row("L4 closing tag", "Walk</b>", LintCheck.L4, "</"),
            row("L4 link", "[walk](here)", LintCheck.L4, "]("),
            row("L4 backtick", "Run `walk`", LintCheck.L4, "`"),
            row("L4 bold", "**Walk**", LintCheck.L4, "**"),
            row("L4 a less-than sign", "if a < b", null, null),
            row("L6 stem", "Insomnia tips", LintCheck.L6, "insomnia"),
            row("L6 longer word", "You were diagnosed", LintCheck.L6, "diagnosed"),
            row("L6 word start only", "Still undiagnosed", null, null),
            row("L6 fullwidth letters", "Ｔｈｅｒａｐｙ time", LintCheck.L6, "therapy"),
            row("L7 phrase", "This is because of your phone", LintCheck.L7, "because of"),
            row("L7 because alone", "Walk because it helps", null, null),
            row("L7 inflected first word", "Late nights caused by screens", LintCheck.L7, "caused"),
            row("L7 leads to", "Scrolling leads to late nights", LintCheck.L7, "leads to"),
            row("L7 due to", "Due to the rain", LintCheck.L7, "due to"),
            row("L7 makes you", "Walking makes you happy", LintCheck.L7, "makes you"),
            row("L7 spaces between words", "This results   in sleep", LintCheck.L7, "results   in"),
            row("L7 inside a word", "The becauseof word", null, null),
            row("clean text", "Time for a short walk?", null, null),
        )

        @JvmStatic
        fun invisibles(): List<Arguments> = listOf(
            Arguments.of("zero width space", "a\u200Bb", "200B"),
            Arguments.of("zero width joiner", "a\u200Db", "200D"),
            Arguments.of("line feed", "a\nb", "000A"),
            Arguments.of("tab", "a\tb", "0009"),
            Arguments.of("C1 next line", "a\u0085b", "0085"),
            Arguments.of("bidi override", "a\u202Eb", "202E"),
            Arguments.of("bidi isolate", "a\u2066b", "2066"),
            Arguments.of("line separator", "a\u2028b", "2028"),
            Arguments.of("paragraph separator", "a\u2029b", "2029"),
            Arguments.of("byte order mark", "\uFEFFa", "FEFF"),
            Arguments.of("emoji", "a😀b", null),
            Arguments.of("plain", "Walk", null),
        )
    }
}
