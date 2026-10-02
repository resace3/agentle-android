package dev.agentle.jitai.dsl.property

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.errorOrNull
import dev.agentle.core.common.getOrNull
import dev.agentle.jitai.dsl.analysis.RuleAnalysis
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.nl.ProposalStatus
import dev.agentle.jitai.dsl.render.RuleRenderer
import dev.agentle.jitai.dsl.testing.Fixtures
import dev.agentle.jitai.dsl.validation.IssueCode
import dev.agentle.jitai.dsl.validation.IssueSeverity
import dev.agentle.jitai.dsl.validation.RuleValidator
import dev.agentle.jitai.dsl.validation.ValidationInput
import dev.agentle.jitai.dsl.validation.ValidationReport
import dev.agentle.jitai.dsl.validation.ValidationRequest
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Seeded fuzzing of the validator and the codec (brief item 7, R10 §11 "never throws"): 10,000 documents, made from
 * structural and textual mutations of every R10 example and from random JSON. Nothing throws, every rejection carries a
 * code, no document ends in E099 (which would mean a check that cannot handle some input), and every accepted document
 * has a stored definition that decodes, re-validates and renders.
 */
class ValidatorFuzzTest {
    private enum class Kind { PROPOSAL, DISCOVERED, DEFINITION, CONDITION }

    private class Seed(val kind: Kind, val text: String)

    @Test
    fun `10,000 mutated and random documents never throw and every rejection has a code`() {
        val random = Random(SEED)
        val fuzzer = JsonFuzzer(random)
        val outcomes = IntArray(2)

        repeat(DOCUMENTS) { index ->
            val seed = SEEDS[index % SEEDS.size]
            val text = when (random.nextInt(10)) {
                in 0..5 -> fuzzer.mutate(seed.text, edits = 1 + random.nextInt(3))
                in 6..7 -> fuzzer.mutateText(seed.text)
                else -> fuzzer.randomDocument()
            }
            val accepted = run(seed.kind, text, "document $index (${seed.kind})")
            outcomes[if (accepted) 0 else 1]++
        }

        assertThat(outcomes.sum()).isEqualTo(DOCUMENTS)
        assertThat(outcomes[0]).isAtLeast(DOCUMENTS / 50)
        assertThat(outcomes[1]).isAtLeast(DOCUMENTS / 2)
    }

    /** Runs one document through the codec and the validator; true when it was accepted. */
    private fun run(kind: Kind, text: String, label: String): Boolean = when (kind) {
        Kind.PROPOSAL -> {
            assertDecoded(RuleCodec.decodeProposal(text), label)
            assertReport(Fixtures.validateText(text, Fixtures.context()), label)
        }

        Kind.DISCOVERED -> {
            assertDecoded(RuleCodec.decodeDiscovered(text), label)
            assertReport(RuleValidator.validate(ValidationRequest(ValidationInput.DiscoveredText(text)), Fixtures.context()), label)
        }

        Kind.DEFINITION -> {
            RuleCodec.decodeDefinition(text).also { assertDecoded(it, label) }.getOrNull()?.let { definition ->
                assertWithMessage(label).that(RuleValidator.revalidate(definition).codes).doesNotContain(IssueCode.E099)
                RuleRenderer.render(definition)
            }
            assertReport(RuleValidator.validate(ValidationRequest(ValidationInput.DefinitionText(text)), Fixtures.context()), label)
        }

        Kind.CONDITION -> {
            val condition = RuleCodec.decodeCondition(text).also { assertDecoded(it, label) }.getOrNull()
            if (condition != null) {
                RuleAnalysis.unsatisfiable(condition)
                RuleAnalysis.dependencies(condition)
                RuleRenderer.condition(condition)
            }
            condition != null
        }
    }

    private fun assertDecoded(outcome: Outcome<*>, label: String) {
        val error = outcome.errorOrNull() ?: return
        assertWithMessage(label).that(error).isInstanceOf(AppError.ValidationError::class.java)
        val codes = (error as AppError.ValidationError).codes
        assertWithMessage(label).that(codes).isNotEmpty()
        assertWithMessage(label).that(codes).doesNotContain(IssueCode.E099.name)
    }

    private fun assertReport(report: ValidationReport, label: String): Boolean {
        assertWithMessage(label).that(report.errors.map { it.code }).doesNotContain(IssueCode.E099)
        assertWithMessage(label).that(report.errors.size).isEqualTo(minOf(report.errorCount, ValidationReport.MAX_LISTED_ERRORS))
        report.errors.forEach { issue ->
            assertWithMessage(label).that(issue.severity).isEqualTo(IssueSeverity.ERROR)
            assertWithMessage(label).that(issue.message).isNotEmpty()
        }
        if (!report.isValid) {
            assertWithMessage(label).that(report.errors).isNotEmpty()
            assertWithMessage(label).that(report.definition).isNull()
            return false
        }
        val waiting = report.appChoices.isNotEmpty() || report.proposal?.let { it.status != ProposalStatus.OK } == true
        val definition = report.definition
        if (definition == null) {
            assertWithMessage(label).that(waiting).isTrue()
            return false
        }
        assertWithMessage(label).that(RuleCodec.decodeDefinition(RuleCodec.encodeDefinition(definition)).getOrNull()).isEqualTo(definition)
        assertWithMessage(label).that(RuleValidator.revalidate(definition, Fixtures.MEDIA).errors).isEmpty()
        assertWithMessage(label).that(report.rendering).isNotEmpty()
        assertWithMessage(label).that(report.contentHash).isEqualTo(RuleCodec.contentHash(definition))
        return true
    }

    private companion object {
        const val SEED = 11_706L
        const val DOCUMENTS = 10_000

        val SEEDS: List<Seed> = listOf(
            Seed(Kind.PROPOSAL, Fixtures.example1),
            Seed(Kind.PROPOSAL, Fixtures.example2),
            Seed(Kind.PROPOSAL, Fixtures.example3),
            Seed(Kind.DISCOVERED, Fixtures.discovered),
            Seed(Kind.DEFINITION, Fixtures.definition31),
            Seed(Kind.DEFINITION, Fixtures.resource("r10/definition-12-r1.json")),
            Seed(Kind.DEFINITION, Fixtures.resource("r10/definition-13-6-1.json")),
            Seed(Kind.DEFINITION, Fixtures.resource("r10/definition-14-7.json")),
            Seed(Kind.CONDITION, Fixtures.resource("r10/condition-4-7-1.json")),
            Seed(Kind.CONDITION, Fixtures.resource("r10/condition-4-7-2.json")),
        )
    }
}
