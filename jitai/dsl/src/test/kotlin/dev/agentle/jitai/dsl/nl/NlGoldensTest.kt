package dev.agentle.jitai.dsl.nl

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.testing.Fixtures
import dev.agentle.jitai.dsl.testing.confirmCodes
import dev.agentle.jitai.dsl.testing.errorCodes
import dev.agentle.jitai.dsl.testing.json
import dev.agentle.jitai.dsl.testing.warningCodes
import dev.agentle.jitai.dsl.testing.with
import dev.agentle.jitai.dsl.testing.withNull
import dev.agentle.jitai.dsl.validation.IssueCode
import dev.agentle.jitai.dsl.validation.ValidationReport
import kotlinx.serialization.json.JsonArray
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * R10 §12.R rows R1-R5: the expected model output of §13.6 stands in for the model (no network in tests), and the
 * validator, renderer and [NlContract.decide] must produce the documented review. R6-R10 are discovery statistics
 * (R10 §14.6) owned by the analytics module, not this one.
 */
class NlGoldensTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("goldens")
    fun `R1-R3 the 13_6 replies validate, render and go to review`(
        row: String,
        file: String,
        request: String,
        extra: List<IssueCode>,
        sentence: String,
    ) {
        val text = Fixtures.resource("r10/$file")
        val assumptions = checkNotNull(RuleCodec.decodeProposal(text).getOrThrow()).assumptions.size

        val report = reply(text, request)

        assertWithMessage(row).that(report.errorCodes).isEmpty()
        assertThat(report.confirmCodes.filter { it == IssueCode.C04 }).hasSize(assumptions)
        assertThat(report.codes).containsAtLeastElementsIn(extra)
        assertThat(report.rendering).isEqualTo(sentence)
        assertThat(NlContract.decide(report, NlRoundState())).isEqualTo(NlDecision.Review(report))
    }

    @Test
    fun `R1 confirm items are C03 and one C04 per assumption`() {
        val report = reply(Fixtures.example1, Fixtures.REQUEST_1)

        assertThat(report.confirmCodes).containsExactly(IssueCode.C03, IssueCode.C04, IssueCode.C04, IssueCode.C04)
        assertThat(report.warningCodes).doesNotContain(IssueCode.W07)
    }

    @Test
    fun `R4 maxPerDay 4 is repaired once with the exact error line`() {
        val wrong = json(Fixtures.example2).with("/jitai/maxPerDay", 4).toString()

        val first = reply(wrong, Fixtures.REQUEST_2)
        val decision = NlContract.decide(first, NlRoundState())
        val corrected = reply(Fixtures.example2, Fixtures.REQUEST_2)

        assertThat(first.errorCodes).containsExactly(IssueCode.E042)
        assertThat(decision).isInstanceOf(NlDecision.Repair::class.java)
        assertThat((decision as NlDecision.Repair).validationErrors).isEqualTo(
            "VALIDATION_ERRORS\nE042 /jitai/maxPerDay: maxPerDay must be 1-3 for AI rules; got 4.\nReturn the corrected proposal only.",
        )
        val round = NlContract.repairRound(NlContract.firstRound(NlSettings(quietHours = null), Fixtures.REQUEST_2), wrong, first)
        assertThat(round.map { it.role }).containsExactly(NlRole.DEVELOPER, NlRole.USER, NlRole.ASSISTANT, NlRole.DEVELOPER).inOrder()
        assertThat(round[2].text).isEqualTo(wrong)
        assertThat(round[3].text).isEqualTo(decision.validationErrors)
        assertThat(corrected.errorCodes).isEmpty()
        assertThat(NlContract.decide(corrected, NlRoundState(modelCalls = 2, repairs = 1))).isInstanceOf(NlDecision.Review::class.java)
    }

    @Test
    fun `R5 a second reply with errors ends in could not turn this into a safe rule`() {
        val wrong = json(Fixtures.example2).with("/jitai/maxPerDay", 4).with("/jitai/priority", 80).toString()
        val second = reply(wrong, Fixtures.REQUEST_2)

        val decision = NlContract.decide(second, NlRoundState(modelCalls = 2, repairs = 1))

        assertThat(decision).isEqualTo(
            NlDecision.Failed(
                "Agentle could not turn this into a safe rule",
                listOf("The rule would remind you too often in a day.", "The rule asked for a higher priority than allowed."),
            ),
        )
    }

    @Test
    fun `a failed first reply without calls left is not repaired`() {
        val report = reply("not json", Fixtures.REQUEST_2)

        assertThat(report.errorCodes).containsExactly(IssueCode.E001)
        assertThat(NlContract.decide(report, NlRoundState(modelCalls = 4))).isInstanceOf(NlDecision.Failed::class.java)
        assertThat(NlContract.decide(report, NlRoundState())).isInstanceOf(NlDecision.Repair::class.java)
    }

    @Test
    fun `clarification questions are shown at most twice`() {
        val questions = json("""[{"id": "q1", "text": "Which time?", "options": ["5 PM", "6 PM"]}]""")
        val text = json(
            Fixtures.example2,
        ).with("/status", "NEEDS_CLARIFICATION").withNull("/jitai").with("/assumptions", JsonArray(emptyList()))
            .with("/questions", questions).toString()
        val report = reply(text, Fixtures.REQUEST_2)

        val first = NlContract.decide(report, NlRoundState())
        val third = NlContract.decide(report, NlRoundState(modelCalls = 3, clarifications = 2))

        assertThat(report.errorCodes).isEmpty()
        assertThat(first).isEqualTo(NlDecision.Clarify(listOf(Question(QuestionId.Q1, "Which time?", listOf("5 PM", "6 PM")))))
        assertThat(third).isEqualTo(NlDecision.Failed(NlContract.FAILURE_MESSAGE, emptyList()))
    }

    @Test
    fun `unsupported replies keep the linted detail except for health and safety`() {
        fun unsupported(reason: String, detail: String?) = json(Fixtures.example2).with("/status", "UNSUPPORTED").withNull("/jitai")
            .with("/assumptions", JsonArray(emptyList()))
            .with("/unsupported", json("""{"reason": "$reason", "detail": ${detail?.let { "\"$it\"" } ?: "null"}}""")).toString()

        val data = reply(unsupported("NEEDS_UNAVAILABLE_DATA", "Agentle cannot read your calendar."), Fixtures.REQUEST_2)
        val safety = reply(unsupported("HEALTH_OR_SAFETY", null), "Remind me to take my pills")

        assertThat(NlContract.decide(data, NlRoundState()))
            .isEqualTo(NlDecision.Unsupported(UnsupportedReason.NEEDS_UNAVAILABLE_DATA, "Agentle cannot read your calendar."))
        assertThat(NlContract.decide(safety, NlRoundState())).isEqualTo(NlDecision.Unsupported(UnsupportedReason.HEALTH_OR_SAFETY, null))
    }

    private fun reply(text: String, request: String): ValidationReport =
        Fixtures.validateText(text, Fixtures.context(settings = Fixtures.DEFAULT_SETTINGS), nlRequest = request)

    companion object {
        @JvmStatic
        fun goldens(): List<Arguments> = listOf(
            Arguments.of(
                "R1 Instagram",
                "example-13-6-1.json",
                Fixtures.REQUEST_1,
                listOf(IssueCode.C03),
                "Every 30 minutes from 10:00 PM to 2:00 AM: if time in Instagram since 10:00 PM is at least 30 min, and only while " +
                    "you are using the phone, send a notification (allowed during quiet hours while you are using the phone). " +
                    "At most 1 per day and 7 per week, at least 2 h apart.",
            ),
            Arguments.of(
                "R2 walk",
                "example-13-6-2.json",
                Fixtures.REQUEST_2,
                listOf(IssueCode.W07),
                "Every day at 5:00 PM: if your step count today is less than 3,000 steps, send a notification. " +
                    "At most 1 per day and 7 per week, at least 1 h apart.",
            ),
            Arguments.of(
                "R3 short night",
                "example-13-6-3.json",
                Fixtures.REQUEST_3,
                listOf(IssueCode.W07),
                "From 12:00 AM to 9:00 AM: block Physical activity reminders if your sleep last night is less than 6 h " +
                    "(also when sleep data is unknown).",
            ),
        )
    }
}
