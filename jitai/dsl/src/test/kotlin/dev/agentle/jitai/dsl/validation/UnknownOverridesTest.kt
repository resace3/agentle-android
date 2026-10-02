package dev.agentle.jitai.dsl.validation

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.model.ContentStrategy
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.OnUnknown
import dev.agentle.jitai.dsl.rule.RuleLiteral
import dev.agentle.jitai.dsl.testing.Fixtures
import dev.agentle.jitai.dsl.testing.confirmCodes
import dev.agentle.jitai.dsl.testing.errorCodes
import dev.agentle.jitai.dsl.testing.issues
import dev.agentle.jitai.dsl.testing.json
import dev.agentle.jitai.dsl.testing.leaf
import dev.agentle.jitai.dsl.testing.negate
import dev.agentle.jitai.dsl.testing.with
import org.junit.jupiter.api.Test

/**
 * R10 §12.C rows C5-C10: delivery-increasing `onUnknown` overrides (R10 §6.4). P is `steps_today lt 3000`. Rows C1-C4
 * are the three-valued evaluation itself, which the engine module runs.
 */
class UnknownOverridesTest {
    @Test
    fun `C5 a USER override the user confirmed is accepted`() {
        val report = Fixtures.validateDefinition(walk.copy(conditions = P_ASSUME_TRUE, userConfirmedUnknownOverrides = true))

        assertThat(report.errorCodes).isEmpty()
        assertThat(report.confirmCodes).doesNotContain(IssueCode.C05)
        assertThat(report.definition?.conditions).isEqualTo(P_ASSUME_TRUE)
        assertThat(report.rendering).contains("(counted as met if unknown)")
    }

    @Test
    fun `C6 the same rule without the confirmation is rejected with E026 and offers C05`() {
        val rule = walk.copy(conditions = P_ASSUME_TRUE)
        val report = Fixtures.validateDefinition(rule)

        assertThat(report.issues(IssueCode.E026).map { it.line }).containsExactly(
            "E026 /conditions/onUnknown: onUnknown ASSUME_TRUE at /conditions/onUnknown would let this rule notify you " +
                "when steps_today is unknown.",
        )
        assertThat(report.issues(IssueCode.C05).map { it.message })
            .containsExactly("This reminder can fire even when step data is missing or out of date.")
        assertThat(RuleValidator.revalidate(rule).codes).containsExactly(IssueCode.E026)
    }

    @Test
    fun `C7 the same override in an AI proposal is E026 without C05`() {
        val report = Fixtures.validateText(proposal.with("/jitai/conditions/onUnknown", "ASSUME_TRUE").toString())

        assertThat(report.errorCodes).containsExactly(IssueCode.E026)
        assertThat(report.issues(IssueCode.E026).single().path).isEqualTo("/jitai/conditions/onUnknown")
        assertThat(report.confirmCodes).doesNotContain(IssueCode.C05)
    }

    @Test
    fun `C8 ASSUME_FALSE at polarity plus decreases delivery and is accepted`() {
        val report = Fixtures.validateText(proposal.with("/jitai/conditions/onUnknown", "ASSUME_FALSE").toString())

        assertThat(report.errorCodes).isEmpty()
        assertThat((report.definition?.conditions as Condition.Lt).onUnknown).isEqualTo(OnUnknown.ASSUME_FALSE)
        assertThat(report.rendering).contains("your step count today is less than 3,000 steps (counted as not met if unknown)")
    }

    @Test
    fun `C9 ASSUME_FALSE under a not increases delivery`() {
        val negated = negate(leaf("lt", "steps_today", 3000, onUnknown = "ASSUME_FALSE"))
        val report = Fixtures.validateText(proposal.with("/jitai/conditions", negated).toString())

        assertThat(report.errorCodes).containsExactly(IssueCode.E026)
        assertThat(report.issues(IssueCode.E026).single().message).isEqualTo(
            "onUnknown ASSUME_FALSE at /jitai/conditions/of/onUnknown would let this rule notify you when steps_today is unknown.",
        )
    }

    @Test
    fun `C10 ASSUME_FALSE in an AI SUPPRESSION blocks less`() {
        val report = Fixtures.validateText(json(Fixtures.example3).with("/jitai/conditions/onUnknown", "ASSUME_FALSE").toString())
        val blocksMore = Fixtures.validateText(json(Fixtures.example3).with("/jitai/conditions/onUnknown", "ASSUME_TRUE").toString())

        assertThat(report.issues(IssueCode.E026).map { it.message }).containsExactly(
            "onUnknown ASSUME_FALSE at /jitai/conditions/onUnknown would let this rule stop blocking when " +
                "sleep_minutes_last_night is unknown.",
        )
        assertThat(blocksMore.errorCodes).isEmpty()
    }

    @Test
    fun `a placeholder needs a leaf without an override (E065)`() {
        val report = Fixtures.validateText(json(Fixtures.example2).with("/jitai/conditions/onUnknown", "ASSUME_FALSE").toString())

        assertThat(report.errorCodes).containsExactly(IssueCode.E065)
        assertThat(report.issues(IssueCode.E065).single().path).isEqualTo("/jitai/content/body")
    }

    @Test
    fun `contextRequirements of a SUPPRESSION are not classified, they are E053`() {
        val requirement = Condition.Eq("device_interactive", value = RuleLiteral.of(true), onUnknown = OnUnknown.ASSUME_TRUE)
        val suppression = RuleCodec.decodeDefinition(Fixtures.resource("r10/definition-13-6-3.json")).getOrThrow()

        val report = Fixtures.validateDefinition(suppression.copy(contextRequirements = requirement))

        assertThat(report.errorCodes).containsExactly(IssueCode.E053)
    }

    private companion object {
        /**
         * The walk nudge with static text: a `{{steps_today}}` placeholder needs a determining leaf, and a leaf with an
         * override is not one (E065), so the rows test the override alone.
         */
        val walk: JitaiDefinition = RuleCodec.decodeDefinition(Fixtures.definition31).getOrThrow()
            .copy(content = ContentStrategy.Static("Time for a short walk?", "A few minutes on foot would get you moving."))
        val proposal = json(Fixtures.example2)
            .with("/jitai/content", json("""{"type": "static", "title": "Time for a short walk?", "body": "A walk now?"}"""))
        val P_ASSUME_TRUE = Condition.Lt("steps_today", value = RuleLiteral.of(3000), onUnknown = OnUnknown.ASSUME_TRUE)
    }
}
