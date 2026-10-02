package dev.agentle.jitai.engine.scenario

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.jitai.dsl.model.ActiveWindow
import dev.agentle.jitai.dsl.model.ContentStrategy
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.OnUnknown
import dev.agentle.jitai.dsl.rule.RuleLiteral
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.Vector
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.JitaiResponse
import dev.agentle.jitai.engine.eval.RuleRefs
import dev.agentle.jitai.engine.eval.TraceNode
import dev.agentle.jitai.engine.response.EngagementBackoff
import dev.agentle.jitai.engine.row
import dev.agentle.jitai.engine.seedCounted
import dev.agentle.jitai.engine.testing.EngineHarness
import dev.agentle.jitai.engine.timer
import dev.agentle.jitai.engine.timerAt
import dev.agentle.jitai.engine.trace
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Instant

/**
 * `{jitai: self}` is bound to the rule that contains it before refs are collected and before every lookup
 * (REALTIME-FEATURES-R1-2), shown with the R10 §12.L history vectors through the engine. The fake resolver answers an
 * unbound `self` with `Missing(INVALID_VALUE)` like the realtime one, and records every reference it was asked for.
 */
class SelfBindingTest {
    @ParameterizedTest(quoteTextArguments = false, name = "{0}")
    @MethodSource("vectors")
    fun `R10 12_L intervention history with a self selector`(vector: Vector) = runTest { vector.body(this) }

    @Test
    fun `two rules with a self leaf in one pass get their own values`() = runTest {
        val ra = historyRule("RA", Leaves.sinceDelivery(gte = 120))
        val rb = historyRule("RB", Leaves.sinceDelivery(gte = 120))
        val harness = F0.harness(F0.local("2026-10-01T22:30"), ra, rb)
        harness.history("RA")
        harness.history("RB")
        harness.seedCounted("RA", F0.local("2026-10-01T21:30"), key = "v1|RA|I|2026-10-01|6")

        val report = harness.timer()

        assertThat(harness.row(key("RA", 10)).state).isEqualTo(DecisionState.NOT_TRIGGERED)
        assertThat(harness.row(key("RB", 10)).state).isEqualTo(DecisionState.DELIVERED)
        assertThat(report.pass!!.written.map { it.decisionKey }).containsExactly(key("RA", 10), key("RB", 10))
        // The first resolution is the pass snapshot; the claim re-reads only live state.
        val asked = harness.features.resolutions.first()
        assertThat(asked).containsAtLeast(bound(SINCE, "RA"), bound(SINCE, "RB"))
        assertThat(asked.filter { it.args[RuleRefs.JITAI_ARG] == RuleRefs.SELF }).isEmpty()
        // The trace keeps the args as written; the value is the rule's own.
        assertThat(leaf(harness, key("RA", 10)).args).containsExactly(RuleRefs.JITAI_ARG, RuleRefs.SELF)
        assertThat(leaf(harness, key("RA", 10)).value!!.scalar).isEqualTo("60")
        assertThat(leaf(harness, key("RB", 10)).value!!.scalar).isEqualTo("NEVER")
    }

    @Test
    fun `a placeholder on a self leaf renders the rule's own value`() = runTest {
        val rule = historyRule("RP", Leaves.sinceDelivery(gte = 120))
            .copy(content = ContentStrategy.Template("Break", "Last reminder {{minutes_since_last_delivery}} min ago."))
        val harness = F0.harness(F0.local("2026-10-01T22:30"), rule)
        harness.history("RP")
        harness.seedCounted("RP", F0.local("2026-10-01T20:00"), key = "v1|RP|I|2026-10-01|0")

        harness.timer()

        assertThat(harness.delivery.posts.single().body).isEqualTo("Last reminder 150 min ago.")
    }

    @Test
    fun `bindSelf replaces only the self selector of the jitai arg`() {
        assertThat(RuleRefs.bindSelf(self(SINCE), "R1")).isEqualTo(bound(SINCE, "R1"))
        assertThat(RuleRefs.bindSelf(self(SINCE), null)).isEqualTo(self(SINCE))
        listOf("any", "category:DIGITAL_WELLBEING", "R7").forEach { selector ->
            val ref = FeatureRef(SINCE, mapOf(RuleRefs.JITAI_ARG to selector))
            assertWithMessage(selector).that(RuleRefs.bindSelf(ref, "R1")).isEqualTo(ref)
        }
        val other = FeatureRef("app_minutes_today", mapOf("package" to "self"))
        assertThat(RuleRefs.bindSelf(other, "R1")).isEqualTo(other)
        assertThat(RuleRefs.of(historyRule("RA", Leaves.sinceDelivery(gte = 1)))).containsExactly(bound(SINCE, "RA"))
        assertThat(bound(SINCE, "RA").key).isNotEqualTo(bound(SINCE, "RB").key)
    }

    companion object {
        private const val SINCE = "minutes_since_last_delivery"
        private const val LAST_RESPONSE = "last_response"
        private const val IGNORED_RUN = "consecutive_ignored"
        private val SELF_ARGS = mapOf(RuleRefs.JITAI_ARG to RuleRefs.SELF)
        private val NOW: Instant = F0.local("2026-10-01T22:30")

        private fun key(id: String, slot: Int) = "v1|$id|I|2026-10-01|$slot"

        private fun self(feature: String) = FeatureRef(feature, SELF_ARGS)

        private fun bound(feature: String, id: String) = FeatureRef(feature, mapOf(RuleRefs.JITAI_ARG to id))

        private object Leaves {
            fun sinceDelivery(gte: Long, onUnknown: OnUnknown? = null): Condition =
                Condition.Gte(SINCE, SELF_ARGS, RuleLiteral.of(gte), onUnknown)

            fun lastResponse(eq: String): Condition = Condition.Eq(LAST_RESPONSE, SELF_ARGS, RuleLiteral.of(eq))

            fun ignoredRun(lt: Long, onUnknown: OnUnknown? = null): Condition =
                Condition.Lt(IGNORED_RUN, SELF_ARGS, RuleLiteral.of(lt), onUnknown)
        }

        /**
         * An interval-15 rule in 20:00-02:00 whose conditions read its own history, without a cooldown; created just
         * before the first run, so no earlier slot is backfilled as MISSED.
         */
        private fun historyRule(id: String, conditions: Condition, createdAt: Instant = F0.local("2026-10-01T22:29")): JitaiDefinition =
            Rules.rule(
                id = id,
                trigger = Trigger.Interval(15),
                window = ActiveWindow("20:00", "02:00"),
                conditions = conditions,
                cooldown = null,
                createdAt = createdAt,
            )

        /**
         * Answers rule [id]'s history features from the harness store, with the realtime engine's semantics (R10 §5.4 I):
         * minutes since the latest delivery (NEVER without one), the latest delivery's response (NONE without one) and
         * the run of ignored deliveries ([EngagementBackoff.consecutiveIgnored]). Only the bound references are answered.
         */
        private fun EngineHarness.history(id: String) {
            fun delivered() = store.rows().filter { it.jitaiId == id && it.delivered != null }.sortedByDescending { it.delivered!!.wall }
            features.provide(bound(SINCE, id)) { at ->
                val last = delivered().firstOrNull()?.delivered?.wall
                FeatureValue.Known(last?.let { FeatureScalar.IntValue((at - it).inWholeMinutes) } ?: FeatureScalar.Never, at)
            }
            features.provide(bound(LAST_RESPONSE, id)) { at ->
                FeatureValue.Known(FeatureScalar.EnumValue((delivered().firstOrNull()?.content?.response ?: JitaiResponse.NONE).name), at)
            }
            features.provide(bound(IGNORED_RUN, id)) { at ->
                FeatureValue.Known(FeatureScalar.IntValue(EngagementBackoff.consecutiveIgnored(delivered()).toLong()), at)
            }
        }

        private fun leaf(harness: EngineHarness, key: String, feature: String = SINCE): TraceNode =
            harness.trace(key).conditions!!.nodes.single { it.feature == feature }

        /** Asserts the engine asked for [id]'s bound references and never for an unbound `self`. */
        private fun EngineHarness.assertAskedBound(id: String, vararg features: String) {
            val asked = this.features.resolutions.flatten()
            assertThat(asked).containsAtLeastElementsIn(features.map { bound(it, id) })
            assertThat(asked.filter { it.args[RuleRefs.JITAI_ARG] == RuleRefs.SELF }).isEmpty()
        }

        @JvmStatic
        fun vectors(): List<Vector> = listOf(
            Vector("L1", "never delivered: minutes_since_last_delivery{self} NEVER, last_response{self} NONE; the rule fires") {
                val rule = historyRule("L1", Condition.AllOf(listOf(Leaves.sinceDelivery(gte = 120), Leaves.lastResponse(eq = "NONE"))))
                val harness = F0.harness(NOW, rule, historyRule("L9", Leaves.sinceDelivery(gte = 1)))
                harness.history("L1")
                // Another rule's recent delivery is not this rule's history.
                harness.seedCounted("L9", F0.local("2026-10-01T22:00"), key = "v1|L9|I|2026-10-01|8")

                harness.timer()

                assertThat(harness.row(key("L1", 10)).state).isEqualTo(DecisionState.DELIVERED)
                assertThat(leaf(harness, key("L1", 10)).value!!.scalar).isEqualTo("NEVER")
                assertThat(leaf(harness, key("L1", 10), LAST_RESPONSE).value!!.scalar).isEqualTo("NONE")
                harness.assertAskedBound("L1", SINCE, LAST_RESPONSE)
            },
            Vector("L2", "delivered 20:00:00; evaluated 21:59:59 / 22:00:00: 119 (gte 120 F) / 120 (T, DELIVERED)") {
                val rule = historyRule("L2", Leaves.sinceDelivery(gte = 120), createdAt = F0.local("2026-10-01T19:59"))
                val harness = F0.harness(F0.local("2026-10-01T20:00"), rule)
                harness.history("L2")
                harness.timer()
                assertThat(harness.row(key("L2", 0)).state).isEqualTo(DecisionState.DELIVERED)

                // The 21:45 slot runs late at 21:59:59, inside its lateness (the slot's end).
                harness.timerAt(Instant.parse("2026-10-01T19:59:59Z"))
                assertThat(harness.row(key("L2", 7)).state).isEqualTo(DecisionState.NOT_TRIGGERED)
                assertThat(leaf(harness, key("L2", 7)).value!!.scalar).isEqualTo("119")
                harness.timerAt(F0.local("2026-10-01T22:00"))

                assertThat(harness.row(key("L2", 8)).state).isEqualTo(DecisionState.DELIVERED)
                assertThat(leaf(harness, key("L2", 8)).value!!.scalar).isEqualTo("120")
                harness.assertAskedBound("L2", SINCE)
            },
            Vector("L4", "responses OPENED, IGNORED, DISMISSED, IGNORED: consecutive_ignored{self} 3; the guard lt 3 holds the rule") {
                val guard = historyRule("L4", Leaves.ignoredRun(lt = 3))
                // The same guard with a confirmed ASSUME_TRUE: an unbound self would read U and silently drop the guard.
                val assumed = historyRule("L5", Leaves.ignoredRun(lt = 3, onUnknown = OnUnknown.ASSUME_TRUE))
                    .copy(userConfirmedUnknownOverrides = true)
                val harness = F0.harness(NOW, guard, assumed)
                listOf("L4", "L5").forEach { id ->
                    harness.history(id)
                    listOf(JitaiResponse.OPENED, JitaiResponse.IGNORED, JitaiResponse.DISMISSED, JitaiResponse.IGNORED)
                        .forEachIndexed { day, response ->
                            harness.seedCounted(
                                id,
                                F0.local("2026-09-2${6 + day}T21:00"),
                                response = response,
                                key = "v1|$id|I|2026-09-2${6 + day}|4",
                            )
                        }
                }

                harness.timer()

                listOf("L4", "L5").forEach { id ->
                    assertWithMessage(id).that(harness.row(key(id, 10)).state).isEqualTo(DecisionState.NOT_TRIGGERED)
                    assertWithMessage(id).that(leaf(harness, key(id, 10), IGNORED_RUN).value!!.scalar).isEqualTo("3")
                    harness.assertAskedBound(id, IGNORED_RUN)
                }
                assertThat(harness.delivery.posts).isEmpty()
            },
        )
    }
}
