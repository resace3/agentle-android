package dev.agentle.jitai.dsl.property

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.analysis.RuleAnalysis
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.model.ContentStrategy
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.render.RuleRenderer
import dev.agentle.jitai.dsl.testing.Fixtures
import dev.agentle.jitai.dsl.testing.errorCodes
import dev.agentle.jitai.dsl.validation.IssueCode
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Seeded property test of the rule DSL (brief item 7): 2,000 random valid condition trees round trip through the codec
 * (decode(encode(t)) == t and the encoding is a fixed point) and through the validator as USER rules, where the only
 * error a valid tree may get is a provable unsatisfiability (E027), exactly when [RuleAnalysis.unsatisfiable] finds one.
 */
class RuleTreePropertyTest {
    @Test
    fun `2,000 random valid trees round trip through the codec and the validator`() {
        val generator = RuleTreeGenerator(Random(SEED))
        val context = Fixtures.context()
        var accepted = 0

        repeat(TREES) { index ->
            val tree = generator.tree()
            val text = RuleCodec.encodeCondition(tree)
            val label = "tree $index: $text"

            val decoded = RuleCodec.decodeCondition(text).getOrThrow()
            assertWithMessage(label).that(decoded).isEqualTo(tree)
            assertWithMessage(label).that(RuleCodec.encodeCondition(decoded)).isEqualTo(text)

            val rule = base.copy(conditions = tree)
            assertWithMessage(label).that(RuleCodec.decodeDefinition(RuleCodec.encodeDefinition(rule)).getOrThrow()).isEqualTo(rule)
            val report = Fixtures.validateDefinition(rule, context)
            assertWithMessage(label).that(report.errorCodes.filter { it != IssueCode.E027 }).isEmpty()
            assertWithMessage(label).that(IssueCode.E027 in report.errorCodes).isEqualTo(RuleAnalysis.unsatisfiable(tree) != null)
            assertWithMessage(label).that(RuleRenderer.condition(tree)).isNotEmpty()
            if (report.isValid) {
                accepted++
                assertWithMessage(label).that(report.definition?.conditions).isEqualTo(tree)
                assertWithMessage(label).that(report.dependencies).isEqualTo(RuleAnalysis.dependencies(tree))
                assertWithMessage(label).that(report.rendering).isNotEmpty()
            }
        }

        assertThat(accepted).isAtLeast(TREES / 2)
    }

    @Test
    fun `trees stay within the USER limits`() {
        val generator = RuleTreeGenerator(Random(SEED + 1))

        val trees = List(500) { generator.tree() }

        assertThat(trees.maxOf { RuleAnalysis.depth(it) }).isAtMost(RuleTreeGenerator.MAX_DEPTH)
        assertThat(trees.maxOf { RuleAnalysis.nodeCount(it) }).isAtMost(RuleTreeGenerator.MAX_NODES)
        assertThat(trees.maxOf { RuleAnalysis.depth(it) }).isAtLeast(4)
        assertThat(trees.maxOf { RuleAnalysis.nodeCount(it) }).isAtLeast(20)
    }

    private companion object {
        const val SEED = 20_261_001L
        const val TREES = 2_000

        /** The walk nudge of R10 §3.1 (USER, daily 17:00) with static text, so any tree can stand in its conditions. */
        val base: JitaiDefinition = RuleCodec.decodeDefinition(Fixtures.definition31).getOrThrow().copy(
            content = ContentStrategy.Static("Time for a short walk?", "A few minutes on foot would help."),
            userConfirmedUnknownOverrides = true,
        )
    }
}
