package dev.agentle.jitai.dsl.validation

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.LogRecord
import dev.agentle.core.common.LogSink
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Severity
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.model.ContentStrategy
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.nl.AppLabelResolver
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.RuleLiteral
import dev.agentle.jitai.dsl.testing.Fixtures
import dev.agentle.jitai.dsl.testing.errorCodes
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.coroutines.cancellation.CancellationException

/**
 * R10 §11 "never throws" and red team privacy-ai-11: an unexpected failure in a port or a stage becomes E099 with the
 * stage only. No exception text reaches an issue, the report or a log record, and CancellationException is rethrown.
 */
class RobustnessTest {
    private val records = ArrayList<LogRecord>()
    private val logger = Logger(listOf(LogSink { records += it }), nowEpochMs = { 0L })

    @Test
    fun `a failing feature access port is E099 in S6 and logs the stage and the failure class only`() {
        val report = Fixtures.validateText(Fixtures.example2, context(featureAccess = { error(SECRET) }))

        assertInternal(report, "S6")
        assertThat(records.single().severity).isEqualTo(Severity.ERROR)
        assertThat(records.single().component).isEqualTo("jitai.dsl.validator")
        assertThat(records.single().message).isEqualTo("validation failed")
        assertThat(records.single().fields).containsExactly("stage", "S6", "failure", "IllegalStateException")
    }

    @Test
    fun `a failing app list is E099 in S6`() {
        val report = Fixtures.validateText(Fixtures.example1, context(apps = { throw IllegalArgumentException(SECRET) }))

        assertInternal(report, "S6")
    }

    @Test
    fun `a failing id generator is E099 in S7`() {
        val report = Fixtures.validateText(Fixtures.example2, context(ids = { throw UnsupportedOperationException(SECRET) }))

        assertInternal(report, "S7")
    }

    @Test
    fun `a failing media library is E099 in S6`() {
        val report = Fixtures.validateDefinition(mediaRule, context(media = { error(SECRET) }))

        assertInternal(report, "S6")
    }

    @Test
    fun `a stack overflow in a port is E099`() {
        val report = Fixtures.validateText(Fixtures.example2, context(featureAccess = { throw StackOverflowError(SECRET) }))

        assertInternal(report, "S6")
        assertThat(records.single().fields["failure"]).isEqualTo("StackOverflowError")
    }

    @Test
    fun `cancellation is rethrown, never reported`() {
        assertThrows<CancellationException> {
            Fixtures.validateText(Fixtures.example2, context(featureAccess = { throw CancellationException(SECRET) }))
        }
        assertThrows<CancellationException> {
            RuleValidator.revalidate(mediaRule, MediaLibrary { throw CancellationException(SECRET) })
        }
    }

    @Test
    fun `re-validation reports failing ports as E099 too`() {
        val failing = RuleValidator.revalidate(mediaRule, MediaLibrary { error(SECRET) })
        val overflow = RuleValidator.revalidate(mediaRule, MediaLibrary { throw StackOverflowError(SECRET) })

        assertThat(failing.codes).containsExactly(IssueCode.E099)
        assertThat(overflow.codes).containsExactly(IssueCode.E099)
        assertThat(failing.errors.single().message).isEqualTo("Internal validation error in stage S6; the proposal was rejected.")
        assertThat(failing.toString()).doesNotContain(SECRET)
    }

    @Test
    fun `a rule that changes between S6 and S8 is E099 in S8 with the codes logged`() {
        var calls = 0
        val flipping = MediaLibrary { calls++ == 0 }

        val report = Fixtures.validateDefinition(mediaRule, context(media = flipping))

        assertInternal(report, "S8")
        assertThat(records.single().message).isEqualTo("normalized rule failed re-validation")
        assertThat(records.single().fields).containsExactly("stage", "S8", "codes", "E068")
    }

    @Test
    fun `decoded inputs deeper than 64 levels are E020 before encoding`() {
        val leaf = Condition.Lt("steps_today", value = RuleLiteral.of(3000))
        val deep = (1..70).fold<Int, Condition>(leaf) { tree, _ -> Condition.Not(tree) }
        val walk = golden("definition-3-1.json")
        val proposal = RuleCodec.decodeProposal(Fixtures.example2).getOrThrow()
        val discovered = RuleCodec.decodeDiscovered(Fixtures.discovered).getOrThrow()

        val definition = Fixtures.validateDefinition(walk.copy(conditions = deep))
        val draft = RuleValidator.validate(
            ValidationRequest(ValidationInput.Proposal(proposal.copy(jitai = proposal.jitai?.copy(contextRequirements = deep)))),
            Fixtures.context(),
        )
        val mined = RuleValidator.validate(
            ValidationRequest(ValidationInput.Discovered(discovered.copy(jitai = discovered.jitai.copy(conditions = deep)))),
            Fixtures.context(),
        )

        assertThat(definition.errors.map { it.line }).containsExactly("E020 /conditions: conditions is 71 levels deep; the limit is 6.")
        assertThat(draft.errors.map { it.line })
            .containsExactly("E020 /jitai/contextRequirements: contextRequirements is 71 levels deep; the limit is 4.")
        assertThat(mined.errorCodes).containsExactly(IssueCode.E020)
        assertThat(RuleValidator.revalidate(walk.copy(conditions = deep)).codes).containsExactly(IssueCode.E020)
    }

    @Test
    fun `every input form of one rule gives the same report`() {
        val proposal = RuleCodec.decodeProposal(Fixtures.example2).getOrThrow()
        val discovered = RuleCodec.decodeDiscovered(Fixtures.discovered).getOrThrow()
        val walk = golden("definition-3-1.json")

        val fromText = RuleValidator.validateProposalText(Fixtures.example2, Fixtures.context(), nlRequest = Fixtures.REQUEST_2)
        val fromProposal = RuleValidator.validate(
            ValidationRequest(ValidationInput.Proposal(proposal), nlRequest = Fixtures.REQUEST_2),
            Fixtures.context(),
        )
        val minedText = RuleValidator.validate(ValidationRequest(ValidationInput.DiscoveredText(Fixtures.discovered)), Fixtures.context())
        val mined = RuleValidator.validate(ValidationRequest(ValidationInput.Discovered(discovered)), Fixtures.context())
        val storedText = RuleValidator.validate(
            ValidationRequest(ValidationInput.DefinitionText(Fixtures.definition31)),
            Fixtures.context(),
        )
        val stored = RuleValidator.validateDefinition(walk, Fixtures.context())

        assertThat(fromText.errorCodes).isEmpty()
        assertThat(fromProposal).isEqualTo(fromText)
        assertThat(minedText.errorCodes).isEmpty()
        assertThat(mined).isEqualTo(minedText)
        assertThat(storedText.errorCodes).isEmpty()
        assertThat(stored).isEqualTo(storedText)
    }

    private fun assertInternal(report: ValidationReport, stage: String) {
        assertThat(report.errors.map { it.line })
            .containsExactly("E099 : Internal validation error in stage $stage; the proposal was rejected.")
        assertThat(report.definition).isNull()
        assertThat(report.rendering).isNull()
        assertThat(report.contentHash).isNull()
        assertThat(report.repairLines).isEmpty()
        assertThat(report.toString()).doesNotContain(SECRET)
        assertThat(records.toString()).doesNotContain(SECRET)
        assertThat(records).hasSize(1)
    }

    private fun context(
        featureAccess: FeatureAccess = FeatureAccess.ALL_READY,
        apps: AppLabelResolver = Fixtures.apps,
        ids: IdGenerator = IdGenerator { "00000000-0000-4000-8000-000000000001" },
        media: MediaLibrary = Fixtures.MEDIA,
    ): ValidationContext = Fixtures.context(apps = apps, featureAccess = featureAccess, ids = ids, media = media, logger = logger)

    private companion object {
        /** Stands in for anything private an exception message could carry. */
        const val SECRET = "sk-secret-token-4821"

        fun golden(file: String): JitaiDefinition = RuleCodec.decodeDefinition(Fixtures.resource("r10/$file")).getOrThrow()

        val mediaRule: JitaiDefinition = golden("definition-3-1.json").let { walk ->
            walk.copy(
                delivery = walk.delivery.copy(channel = DeliveryChannel.IMAGE),
                content = ContentStrategy.LocalMedia("sunset_walk_01", ContentStrategy.Template("Walk?", "A short walk now?")),
            )
        }
    }
}
