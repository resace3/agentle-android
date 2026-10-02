package dev.agentle.jitai.dsl.validation

import com.google.common.truth.Truth.assertThat
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.analysis.MinuteMask
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.model.ActiveWindow
import dev.agentle.jitai.dsl.model.CreatedBy
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiLifecycle
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.LifecycleEvent
import dev.agentle.jitai.dsl.model.QuietHoursPolicy
import dev.agentle.jitai.dsl.model.SuppressionTarget
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.Operator
import dev.agentle.jitai.dsl.rule.OperatorSemantics
import dev.agentle.jitai.dsl.rule.TypedLiterals
import dev.agentle.jitai.dsl.testing.Fixtures
import dev.agentle.jitai.dsl.testing.errorCodes
import dev.agentle.jitai.dsl.testing.issues
import dev.agentle.jitai.dsl.testing.warningCodes
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/**
 * R10 §12.M from the rule side (integrator correction jitai-correctness-01): the stored goldens of §13.6 and the rules R1
 * and S1 carry exactly what the safety gates read. The gates themselves run in the engine module.
 */
class SafetyGateRulesTest {
    @Test
    fun `M5 every category posts to its own notification channel`() {
        assertThat(golden("definition-12-r1.json").category.notificationChannelId).isEqualTo("jitai_digital_wellbeing")
        assertThat(golden("definition-13-6-1.json").category.notificationChannelId).isEqualTo("jitai_sleep_wind_down")
        assertThat(JitaiCategory.entries.map { it.notificationChannelId }).containsExactly(
            "jitai_physical_activity",
            "jitai_sleep_wind_down",
            "jitai_digital_wellbeing",
            "jitai_stress_break",
            "jitai_general",
        ).inOrder()
    }

    @Test
    fun `M7 13_6_1 may notify in quiet hours only while the phone is in use`() {
        val definition = golden("definition-13-6-1.json")
        val requirement = definition.contextRequirements as Condition.Eq
        val literals = checkNotNull(TypedLiterals.typedLiterals(requirement))
        val report = Fixtures.validateText(Fixtures.example1, Fixtures.context(settings = Fixtures.DEFAULT_SETTINGS))

        assertThat(definition.delivery.quietHoursPolicy).isEqualTo(QuietHoursPolicy.ALLOW_WHEN_INTERACTIVE)
        assertThat(requirement.feature).isEqualTo("device_interactive")
        assertThat(OperatorSemantics.holds(Operator.EQ, FeatureScalar.BoolValue(true), literals)).isTrue()
        assertThat(OperatorSemantics.holds(Operator.EQ, FeatureScalar.BoolValue(false), literals)).isFalse()
        assertThat(report.issues(IssueCode.C03).map { it.message }).containsExactly(
            "This reminder may appear during your quiet hours (22:00-07:00) while you are using your phone. Allow?",
        )
        assertThat(report.definition?.contextRequirements).isEqualTo(requirement)
    }

    @Test
    fun `M9 S1 blocks R1 from 22_00 up to but not including 23_00`() {
        val r1 = golden("definition-12-r1.json")
        val window = checkNotNull(S1.activeWindow).let { checkNotNull(MinuteMask.window(it.start, it.end)) }

        val s1Report = Fixtures.validateDefinition(S1)
        val blocked = Fixtures.validateDefinition(r1, Fixtures.context(existing = listOf(S1)))
        val pausedS1 = S1.copy(status = JitaiStatus.PAUSED, enabled = false)
        val paused = Fixtures.validateDefinition(r1, Fixtures.context(existing = listOf(pausedS1)))
        val expired = Fixtures.validateDefinition(r1, Fixtures.context(existing = listOf(S1.copy(expiresAt = Fixtures.F0_NOW))))
        val otherCategory = Fixtures.validateDefinition(
            r1,
            Fixtures.context(existing = listOf(S1.copy(suppression = SuppressionTarget(listOf(JitaiCategory.GENERAL))))),
        )

        assertThat(s1Report.errorCodes).isEmpty()
        assertThat(22 * 60 + 30 in window).isTrue()
        assertThat(23 * 60 in window).isFalse()
        assertThat(blocked.issues(IssueCode.W06).map { it.line })
            .containsExactly("W06 /category: \"Evening block\" may block this reminder.")
        assertThat(paused.warningCodes).doesNotContain(IssueCode.W06)
        assertThat(expired.warningCodes).doesNotContain(IssueCode.W06)
        assertThat(otherCategory.warningCodes).doesNotContain(IssueCode.W06)
    }

    @Test
    fun `M9 13_6_3 blocks from midnight up to but not including 9_00 once it is approved`() {
        val proposed = golden("definition-13-6-3.json")
        val active = JitaiLifecycle.apply(
            proposed,
            LifecycleEvent.APPROVE,
            Fixtures.clock(),
            verdict = RuleValidator.revalidate(proposed),
        ).getOrThrow()
        val window = checkNotNull(active.activeWindow).let { checkNotNull(MinuteMask.window(it.start, it.end)) }
        val morningWalk = walk.copy(trigger = Trigger.DailyAt(listOf("08:30")))
        val eveningWalk = walk.copy(trigger = Trigger.DailyAt(listOf("09:00")))

        assertThat(8 * 60 + 59 in window).isTrue()
        assertThat(9 * 60 in window).isFalse()
        assertThat(Fixtures.validateDefinition(morningWalk, Fixtures.context(existing = listOf(proposed))).warningCodes)
            .doesNotContain(IssueCode.W06)
        assertThat(Fixtures.validateDefinition(morningWalk, Fixtures.context(existing = listOf(active))).warningCodes)
            .contains(IssueCode.W06)
        assertThat(Fixtures.validateDefinition(eveningWalk, Fixtures.context(existing = listOf(active))).warningCodes)
            .doesNotContain(IssueCode.W06)
    }

    @Test
    fun `a blocking rule never blocks itself`() {
        val report = Fixtures.validateDefinition(S1, Fixtures.context(existing = listOf(S1)))

        assertThat(report.warningCodes).doesNotContain(IssueCode.W06)
    }

    private companion object {
        val walk: JitaiDefinition = golden("definition-3-1.json")

        /** R10 §12 rule S1: SUPPRESSION, window 22:00-23:00, no conditions, targets DIGITAL_WELLBEING. */
        val S1: JitaiDefinition = golden("definition-13-6-3.json").copy(
            id = "5b1e7c2a-9d3f-4e8b-a6c0-2f4d8e1b7a93",
            name = "Evening block",
            description = "",
            category = JitaiCategory.DIGITAL_WELLBEING,
            status = JitaiStatus.ACTIVE,
            enabled = true,
            activeWindow = ActiveWindow("22:00", "23:00"),
            conditions = null,
            suppression = SuppressionTarget(listOf(JitaiCategory.DIGITAL_WELLBEING)),
            createdBy = CreatedBy.USER_MANUAL,
            createdAt = Instant.parse("2026-09-01T08:00:00Z"),
            modifiedAt = Instant.parse("2026-09-01T08:00:00Z"),
            provenance = null,
        )

        fun golden(file: String): JitaiDefinition = RuleCodec.decodeDefinition(Fixtures.resource("r10/$file")).getOrThrow()
    }
}
