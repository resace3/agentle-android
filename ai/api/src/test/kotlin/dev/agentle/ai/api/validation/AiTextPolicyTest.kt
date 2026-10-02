package dev.agentle.ai.api.validation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class AiTextPolicyTest {
    private val rules = TextRules(maxChars = 240, maxSentences = 3)

    private fun failed(text: String, rules: TextRules = this.rules, provenance: NumberProvenance? = null): List<String> =
        AiTextPolicy.check(text, rules, provenance).map { it.id }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "Read more at https://example.org today|L1",
            "Open www.something later|L1",
            "Try agentle.app for tips|L1",
            "Visit SHOP.EXAMPLE.CO now|L1",
            "Write to me@site.org|L2",
            "Call 555 123 4567 tonight|L3",
            "Dial (030) 123-4567|L3",
            "Use <b>bold</b>|L4",
            "See [this](that)|L4",
            "A `code` span|L4",
            "Some **strong** words|L4",
            "This looks like insomnia|L6",
            "Your medication schedule|L6",
            "You seem addicted to the phone|L6",
            "Late screens cause poor sleep|L7",
            "Screens caused the late night|L7",
            "You slept less because of the late call|L7",
            "Poor sleep due to caffeine|L7",
            "Scrolling makes you tired|L7",
            "Walking leads to better moods|L7",
            "This proves it|L7",
            "The effects of light|L7",
            "Take melatonin before bed|L11",
            "Maybe stop taking your sleeping pills|L11",
            "Please see a doctor about this|L11",
            "Talk to your therapist|L11",
            "Call 911 now|L11",
        ],
    )
    fun `each check catches its vector`(text: String, check: String) {
        assertThat(failed(text)).contains(check)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "You slept about 7 hours on most nights.",
            "Your bedtime moved later this week, e.g. on Friday.",
            "From 10 p.m. to 2 a.m. the phone was busy.",
            "Steps rose to 3,000 on Saturday.",
            "Because the week was busy, take it easy.",
            "Less than 3 < 4 is fine.",
            "Your undiagnosed curiosity is great.",
            "A walk after dinner may help you unwind.",
            "Taking a short walk can feel good.",
        ],
    )
    fun `ordinary sentences pass`(text: String) {
        assertThat(failed(text)).isEmpty()
    }

    @Test
    fun `control and invisible characters fail L5`() {
        listOf("a\nb", "a\tb", "a\u0000b", "a\u007Fb", "a\u0085b", "a\u200Bb", "a\u200Db", "a\u2028b", "a\u202Eb", "a\u2066b", "\uFEFFa")
            .forEach { text -> assertThat(failed(text)).contains("L5") }
        assertThat(AiTextPolicy.containsControlOrInvisible("plain text")).isFalse()
    }

    @Test
    fun `placeholders fail L8 unless the field is template text`() {
        assertThat(failed("Hello {{steps_today}}")).contains("L8")
        assertThat(failed("Stray }} here")).contains("L8")
        val template = rules.copy(placeholdersAllowed = true)
        assertThat(failed("You are at {{steps_today}} steps today.", template)).isEmpty()
        // A placeholder neither hides a phone number nor a number from L9.
        assertThat(failed("{{a}}5551234567", template)).contains("L3")
        assertThat(failed("{{steps_today}} steps", template, NumberProvenance.EMPTY)).isEmpty()
    }

    @Test
    fun `numbers must come from the request or template`() {
        val provenance = NumberProvenance.of(listOf(7, 3000))
        assertThat(failed("You slept 7 hours and walked 3,000 steps.", provenance = provenance)).isEmpty()
        assertThat(failed("You slept 8 hours.", provenance = provenance)).containsExactly("L9")
        assertThat(failed("You slept 8 hours.", provenance = null)).isEmpty()
    }

    @Test
    fun `sentence and length limits`() {
        val one = TextRules(maxChars = 20, maxSentences = 1)
        assertThat(failed("Hi. Two more.", one)).containsExactly("L10")
        assertThat(failed("Is this 3.5 hours?", one)).isEmpty()
        assertThat(failed("x".repeat(21), one)).containsExactly("L12")
        assertThat(failed("", one)).containsExactly("L12")
        assertThat(failed("", one.copy(minChars = 0))).isEmpty()
        assertThat(AiTextPolicy.sentenceCount("Hi! How are you? Fine.")).isEqualTo(3)
        assertThat(AiTextPolicy.sentenceCount("At 5 p.m. you walk")).isEqualTo(1)
        assertThat(AiTextPolicy.sentenceCount("")).isEqualTo(0)
    }

    @Test
    fun `the check ids and codes are stable`() {
        assertThat(AiTextCheck.entries.map { it.id }).containsExactly(
            "L1", "L2", "L3", "L4", "L5", "L6", "L7", "L8", "L9", "L10", "L11", "L12", "L13",
        ).inOrder()
        assertThat(AiTextCheck.URL.code).isEqualTo("E061")
        assertThat(AiTextCheck.MARKUP.code).isEqualTo("E062")
        assertThat(AiTextCheck.CONTROL_OR_INVISIBLE.code).isEqualTo("E066")
        assertThat(AiTextCheck.LENGTH.code).isEqualTo("E060")
        assertThat(AiTextCheck.NUMBER_IN_POOLED_TEXT.code).isEqualTo("E113")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Walk for 10 minutes.",
            "You have 2 reminders left.",
            "Room for \u0663 more steps.",
            "Only \u00B2 left.",
            "About \u00BD of your goal.",
        ],
    )
    fun `pooled text fails on any digit`(text: String) {
        assertThat(failed(text, TextRules.POOLED_BODY)).contains("L13")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Take a ten minute walk.",
            "Three quick stretches?",
            "You are half way there.",
            "A dozen deep breaths.",
            "Twenty-five squats!",
            "Give it a few seconds.",
            "Try it twice.",
            "Your first walk today.",
            "Hundreds of steps await.",
            "Six or seven sips of water.",
            "Ninety breaths.",
            "Sixes and sevens.",
        ],
    )
    fun `pooled text fails on any number word`(text: String) {
        assertThat(failed(text, TextRules.POOLED_BODY)).contains("L13")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Time to stretch your legs.",
            "You tend to feel better after a walk.",
            "Someone could use a break: you.",
            "Put the phone down and rest your eyes.",
            "Often a glass of water helps.",
            "Pay attention to your breathing.",
        ],
    )
    fun `pooled text without numbers passes`(text: String) {
        assertThat(failed(text, TextRules.POOLED_BODY)).isEmpty()
        assertThat(AiTextPolicy.containsNumber(text)).isFalse()
    }

    @Test
    fun `pooled text replaces provenance and has no placeholders`() {
        assertThat(failed("Walk 10 minutes.", TextRules.POOLED_BODY, NumberProvenance.of(listOf(10)))).containsExactly("L13")
        assertThrows<IllegalArgumentException> { TextRules(60, 1, placeholdersAllowed = true, numbersForbidden = true) }
        assertThat(TextRules.POOLED_TITLE.numbersForbidden).isTrue()
        assertThat(AiTextPolicy.NUMBER_WORDS).containsAtLeast("three", "ten", "half", "dozen", "twenties", "sixes", "thirds")
    }

    @Test
    fun `select falls back to the template and keeps only check ids`() {
        val template = "Time for a short walk?"
        val pass = AiTextPolicy.select("A walk could feel nice.", template, rules, null)
        assertThat(pass).isEqualTo(TextDecision("A walk could feel nice.", usedTemplate = false, failedChecks = emptyList()))
        val fail = AiTextPolicy.select("Visit evil.com now", template, rules, null)
        assertThat(fail.text).isEqualTo(template)
        assertThat(fail.usedTemplate).isTrue()
        assertThat(fail.failedChecks).containsExactly(AiTextCheck.URL)
        val missing = AiTextPolicy.select(null, template, rules, null)
        assertThat(missing).isEqualTo(TextDecision(template, usedTemplate = true, failedChecks = emptyList()))
    }

    @Test
    fun `fullwidth and case tricks are folded before matching`() {
        assertThat(failed("ＷＷＷ.example.com")).contains("L1")
        assertThat(failed("This is INSOMNIA")).contains("L6")
        assertThat(failed("ＴＡＫＥ MELATONIN")).contains("L11")
    }
}
