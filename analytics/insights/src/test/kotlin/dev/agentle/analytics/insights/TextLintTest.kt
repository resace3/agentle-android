package dev.agentle.analytics.insights

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** The §11.6 lint (L1-L8) that guards every fixed insight and proposal text (docs/research/10 §11.6, §14.7). */
class TextLintTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("failing")
    fun `texts that fail the lint`(row: String, text: String, check: LintCheck) {
        assertWithMessage(row).that(TextLint.check(text)).contains(check)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clean")
    fun `texts that pass the lint`(row: String, text: String) {
        assertWithMessage(row).that(TextLint.check(text)).isEmpty()
    }

    @Test
    fun `checks keep their R10 ids and order`() {
        assertThat(LintCheck.entries.map { it.id }).containsExactly("L1", "L2", "L3", "L4", "L5", "L6", "L7", "L8").inOrder()
        assertThat(TextLint.check("Email me@agentle.app because of {{x}}"))
            .containsExactly(LintCheck.URL, LintCheck.EMAIL, LintCheck.CAUSAL_CLAIM, LintCheck.PLACEHOLDER).inOrder()
    }

    @Test
    fun `a template that fails the lint is a defect`() {
        assertThrows<IllegalStateException> { PatternText.requireClean("Late screen time causes late bedtimes.") }
        assertThat(PatternText.requireClean("These happened together.")).isEqualTo("These happened together.")
    }

    companion object {
        @JvmStatic
        fun failing(): List<Arguments> = listOf(
            Arguments.of("F1 L1 scheme", "see https://example.org/x", LintCheck.URL),
            Arguments.of("F2 L1 www", "visit www.example", LintCheck.URL),
            Arguments.of("F3 L1 bundled TLD", "try agentle.app tonight", LintCheck.URL),
            Arguments.of("F4 L2 email", "write to someone@mail.example", LintCheck.EMAIL),
            Arguments.of("F5 L3 phone", "call 555 123 4567", LintCheck.PHONE_NUMBER),
            Arguments.of("F6 L3 phone with dashes", "call (555)-123-45-67", LintCheck.PHONE_NUMBER),
            Arguments.of("F7 L4 tag", "<b>late</b>", LintCheck.MARKUP),
            Arguments.of("F8 L4 link", "[here](x)", LintCheck.MARKUP),
            Arguments.of("F9 L4 backtick", "use `code`", LintCheck.MARKUP),
            Arguments.of("F10 L4 bold", "**late**", LintCheck.MARKUP),
            Arguments.of("F11 L5 line break", "two\nlines", LintCheck.CONTROL_OR_INVISIBLE),
            Arguments.of("F12 L5 zero width", "late​night", LintCheck.CONTROL_OR_INVISIBLE),
            Arguments.of("F13 L5 C1 control", "late\u0085night", LintCheck.CONTROL_OR_INVISIBLE),
            Arguments.of("F14 L5 bidi override", "late‮night", LintCheck.CONTROL_OR_INVISIBLE),
            Arguments.of("F15 L6 addicted", "you seem addicted to your phone", LintCheck.MEDICAL_WORDING),
            Arguments.of("F16 L6 medication", "Medication may help", LintCheck.MEDICAL_WORDING),
            Arguments.of("F17 L6 insomnia", "signs of insomnia", LintCheck.MEDICAL_WORDING),
            Arguments.of("F18 L7 causes", "late screen time causes late bedtimes", LintCheck.CAUSAL_CLAIM),
            Arguments.of("F19 L7 caused", "it caused a late night", LintCheck.CAUSAL_CLAIM),
            Arguments.of("F20 L7 caused by", "nights caused by screens", LintCheck.CAUSAL_CLAIM),
            Arguments.of("F21 L7 because of", "late because of screens", LintCheck.CAUSAL_CLAIM),
            Arguments.of("F22 L7 due to", "Due to screens", LintCheck.CAUSAL_CLAIM),
            Arguments.of("F23 L7 leads to", "screen time leads to late nights", LintCheck.CAUSAL_CLAIM),
            Arguments.of("F24 L7 results in", "it results in late nights", LintCheck.CAUSAL_CLAIM),
            Arguments.of("F25 L7 makes you", "it makes you sleep later", LintCheck.CAUSAL_CLAIM),
            Arguments.of("F26 L7 proves", "this proves it", LintCheck.CAUSAL_CLAIM),
            Arguments.of("F27 L7 effects of", "the effects of screens", LintCheck.CAUSAL_CLAIM),
            Arguments.of("F28 L7 upper case", "SCREENS CAUSE LATE NIGHTS", LintCheck.CAUSAL_CLAIM),
            Arguments.of("F29 L7 full-width letters (NFKC)", "screens ｃａｕｓｅ late nights", LintCheck.CAUSAL_CLAIM),
            Arguments.of("F30 L7 tab inside a phrase", "due\tto screens", LintCheck.CAUSAL_CLAIM),
            Arguments.of("F31 L8 placeholder", "{{screen_minutes_since}} minutes", LintCheck.PLACEHOLDER),
        )

        @JvmStatic
        fun clean(): List<Arguments> = listOf(
            Arguments.of("P1 finding", P1_TEXT),
            Arguments.of("C1 because alone", "Late, because the evening was busy."),
            Arguments.of("C2 causeway", "We walked the causeway."),
            Arguments.of("C3 address", "Your address book is not used."),
            Arguments.of(
                "C4 counts and percentages",
                "On 17 of 24 nights (71%) this happened together; on 9 of 36 nights (25%) it did not.",
            ),
            Arguments.of("C5 thousands", "Fewer than 5,000 steps on 12 days."),
            Arguments.of("C6 times", "Between 10 PM and midnight, 22:00-24:00."),
            Arguments.of("C7 range", "-9 to -1 percentage points"),
            Arguments.of("C8 decimal", "about 0.5 of the nights, e.g. weekends"),
            Arguments.of("C9 single brace", "a {note} in braces"),
            Arguments.of("C10 less than", "a < b is fine"),
        )
    }
}
