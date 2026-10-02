package dev.agentle.jitai.dsl.validation

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.model.ContentStrategy
import dev.agentle.jitai.dsl.model.RuleOrigin
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.RuleLiteral
import dev.agentle.jitai.dsl.testing.Fixtures
import org.junit.jupiter.api.Test

/** R10 §11.1: precedence of specific codes, the order of issues, the 50-error list and the repair lines. */
class ValidationReportTest {
    @Test
    fun `more specific codes replace general ones at the same path`() {
        val sink = IssueSink()
        sink.add(IssueCode.E006, Stage.S4, "/jitai/id")
        sink.add(IssueCode.E005, Stage.S4, "/jitai/id")
        val literal = mapOf("expected" to "a time", "feature" to "local_time", "json" to "\"7:00\"")
        sink.add(IssueCode.E015, Stage.S6, "/conditions/value", literal)
        sink.add(IssueCode.E024, Stage.S6, "/conditions/value", mapOf("value" to "7:00"))
        sink.add(IssueCode.E013, Stage.S6, "/conditions/args/package", mapOf("feature" to "f", "arg" to "package", "reason" to "r"))
        sink.add(IssueCode.E081, Stage.S6, "/conditions/args/package", mapOf("value" to "x"))
        sink.add(IssueCode.E009, Stage.S4, "/trigger/events/0", mapOf("allowed" to "...", "value" to "BOOT_COMPLETED"))
        sink.add(IssueCode.E030, Stage.S4, "/trigger/events/0", mapOf("event" to "BOOT_COMPLETED"))
        sink.add(IssueCode.E006, Stage.S4, "/jitai/color")

        val codes = sink.sorted(IssueSeverity.ERROR).map { it.code to it.path }

        assertThat(codes).containsExactly(
            IssueCode.E006 to "/jitai/color",
            IssueCode.E005 to "/jitai/id",
            IssueCode.E030 to "/trigger/events/0",
            IssueCode.E081 to "/conditions/args/package",
            IssueCode.E024 to "/conditions/value",
        ).inOrder()
    }

    @Test
    fun `issues are sorted by stage, then path, then code, and duplicates are dropped`() {
        val sink = IssueSink()
        sink.add(IssueCode.E042, Stage.S6, "/b", mapOf("n" to "4"))
        sink.add(IssueCode.E041, Stage.S6, "/b", mapOf("n" to "4"))
        sink.add(IssueCode.E042, Stage.S6, "/a", mapOf("n" to "4"))
        sink.add(IssueCode.E004, Stage.S4, "/z", mapOf("value" to "2"))
        sink.add(IssueCode.E004, Stage.S4, "/z", mapOf("value" to "2"))
        sink.add(IssueCode.W05, Stage.S6, "/a", mapOf("n" to "6", "period" to "day"))

        assertThat(sink.sorted(IssueSeverity.ERROR).map { "${it.stage} ${it.path} ${it.code}" })
            .containsExactly("S4 /z E004", "S6 /a E042", "S6 /b E041", "S6 /b E042")
            .inOrder()
        assertThat(sink.sorted(IssueSeverity.WARNING).map { it.code }).containsExactly(IssueCode.W05)
        assertThat(sink.codes()).hasSize(6)
    }

    @Test
    fun `at most 50 errors are listed and errorCount counts all of them`() {
        val walk = RuleCodec.decodeDefinition(Fixtures.definition31).getOrThrow()
        val leaves = (0 until 60).map { Condition.Eq("unknown_feature_$it", value = RuleLiteral.of(1)) }

        val rule = walk.copy(conditions = Condition.AllOf(leaves), content = ContentStrategy.Static("Walk?", "Now?"))

        val report = Fixtures.validateDefinition(rule)

        assertThat(report.errorCount).isEqualTo(62)
        assertThat(report.errors).hasSize(ValidationReport.MAX_LISTED_ERRORS)
        assertThat(report.isValid).isFalse()
        assertThat(report.definition).isNull()
        assertThat(report.errors.take(2).map { it.code }).containsExactly(IssueCode.E021, IssueCode.E023).inOrder()
        assertThat(report.errors.map { it.path }).isInOrder()
    }

    @Test
    fun `codes list errors first, and repair lines leave out E099`() {
        val issue = ValidationIssue(IssueCode.E042, "/jitai/maxPerDay", "maxPerDay must be 1-3 for AI rules; got 4.")
        val internal = ValidationIssue(IssueCode.E099, "", "Internal validation error in stage S6; the proposal was rejected.")
        val confirm = ValidationIssue(IssueCode.C04, "/jitai/trigger/times/0", "Assumed: \"x\"", stage = Stage.S6)
        val warning = ValidationIssue(IssueCode.W07, "/jitai/conditions", "w")
        val report = ValidationReport(listOf(issue, internal), 2, listOf(confirm), listOf(warning), RuleOrigin.AI)

        assertThat(report.codes).containsExactly(IssueCode.E042, IssueCode.E099, IssueCode.C04, IssueCode.W07).inOrder()
        assertThat(report.repairLines).containsExactly("E042 /jitai/maxPerDay: maxPerDay must be 1-3 for AI rules; got 4.")
        assertThat(report.isValid).isFalse()
        assertThat(issue.severity).isEqualTo(IssueSeverity.ERROR)
        assertThat(confirm.severity).isEqualTo(IssueSeverity.CONFIRM)
        assertThat(issue.toString()).isEqualTo(issue.line)
        assertThat(ValidationReport(emptyList(), 0, emptyList(), listOf(warning), RuleOrigin.USER).isValid).isTrue()
    }

    @Test
    fun `templates keep unknown parameters and never re-scan inserted values`() {
        assertThat(IssueCode.E042.format(mapOf("n" to "{max}"))).isEqualTo("maxPerDay must be 1-{max} for {origin} rules; got {max}.")
        assertThat(IssueCode.C01.format(mapOf("appLabel" to "{appLabel}"))).isEqualTo("Which app did you mean by \"{appLabel}\"?")
        assertThat(IssueCode.C02.variantCount).isEqualTo(2)
        assertThat(IssueCode.C02.template(1)).isEqualTo("This reminder will open a short video. Allow?")
        assertThat(IssueCode.E099.format(emptyMap())).isEqualTo("Internal validation error in stage {stage}; the proposal was rejected.")
    }
}
