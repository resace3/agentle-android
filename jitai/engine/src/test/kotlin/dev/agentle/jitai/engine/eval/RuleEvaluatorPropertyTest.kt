package dev.agentle.jitai.engine.eval

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureSnapshot
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.OnUnknown
import dev.agentle.jitai.dsl.rule.Operator
import dev.agentle.jitai.dsl.rule.RuleLiteral
import dev.agentle.jitai.engine.F0
import kotlinx.datetime.DayOfWeek
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Seeded property tests of the evaluator (R10 §6): on random rule trees and random snapshots it never throws, its
 * trace is internally consistent (every group node is the Kleene fold of its children) and deterministic, and without
 * overrides it is regular: a known result never changes when missing values become known (strong Kleene K3).
 */
class RuleEvaluatorPropertyTest {
    private val evaluator = RuleEvaluator()

    @Test
    fun `random valid and invalid rules on random snapshots never throw and give a consistent deterministic trace`() {
        val random = Random(SEED)
        repeat(ITERATIONS) { iteration ->
            val condition = Gen(random, overrides = true).condition(depth = 1)
            val snapshot = Gen(random, overrides = true).snapshot(condition)
            val kind = if (random.nextBoolean()) RootKind.INTERVENTION else RootKind.SUPPRESSION
            val policy = OverridePolicy(allowDeliveryIncreasing = random.nextBoolean())

            val trace = evaluator.evaluate(condition, snapshot, kind, policy)

            assertWithMessage("iteration $iteration").that(trace.nodes.first().path).isEmpty()
            assertWithMessage("iteration $iteration").that(trace.nodes.first().result).isEqualTo(trace.result)
            assertWithMessage("iteration $iteration").that(evaluator.evaluate(condition, snapshot, kind, policy)).isEqualTo(trace)
            assertGroupsFold(trace, iteration)
        }
    }

    @Test
    fun `without overrides a known root never changes when missing values become known (K3 regularity)`() {
        val random = Random(SEED + 1)
        var checked = 0
        repeat(ITERATIONS) { iteration ->
            val gen = Gen(random, overrides = false)
            val condition = gen.condition(depth = 1)
            val partial = gen.snapshot(condition)
            val result = evaluator.evaluate(condition, partial).result
            if (result == Tri.UNKNOWN) return@repeat
            checked++
            val refined = partial.copy(
                values = partial.values.mapValues { (key, value) ->
                    if (value is FeatureValue.Missing) gen.known(key.substringBefore('{')) ?: value else value
                },
            )
            assertWithMessage("iteration $iteration").that(evaluator.evaluate(condition, refined).result).isEqualTo(result)
        }
        assertThat(checked).isGreaterThan(ITERATIONS / 10)
    }

    @Test
    fun `an applied override only ever replaces an UNKNOWN leaf`() {
        val random = Random(SEED + 2)
        repeat(ITERATIONS) { _ ->
            val gen = Gen(random, overrides = true)
            val condition = gen.condition(depth = 1)
            val trace = evaluator.evaluate(condition, gen.snapshot(condition), RootKind.INTERVENTION, OverridePolicy(true))
            trace.nodes.filter { it.appliedOverride != null }.forEach { node ->
                val forced = if (node.appliedOverride == OnUnknown.ASSUME_TRUE) Tri.TRUE else Tri.FALSE
                assertThat(node.result).isEqualTo(forced)
                assertThat(node.overrideIgnored).isNull()
            }
        }
    }

    private fun assertGroupsFold(trace: TreeTrace, iteration: Int) {
        val byPath = trace.nodes.associateBy { it.path }
        trace.nodes.forEach { node ->
            if (node.note == TraceNote.TOO_DEEP || node.note == TraceNote.EMPTY_GROUP) return@forEach
            when (node.type) {
                "all", "any" -> {
                    val children = generateSequence(0) { it + 1 }.map { byPath["${node.path}/of/$it"] }.takeWhile { it != null }
                        .filterNotNull().map { it.result }.toList()
                    val folded = if (node.type == "all") Tri.all(children) else Tri.any(children)
                    assertWithMessage("iteration $iteration node ${node.path}").that(node.result).isEqualTo(folded)
                }

                "not" -> {
                    val child = byPath["${node.path}/of"] ?: return@forEach
                    assertWithMessage("iteration $iteration node ${node.path}").that(node.result).isEqualTo(!child.result)
                }
            }
        }
    }

    /** Random trees over a fixed set of catalog features plus deliberately invalid leaves, and matching snapshots. */
    private class Gen(private val random: Random, private val overrides: Boolean) {
        private val features = listOf(
            "steps_today",
            "screen_minutes_last_60m",
            "location_class",
            "charging",
            "local_time",
            "day_of_week",
            "foreground_app",
            "bedtime_last_night",
            "minutes_since_last_delivery",
        )

        fun condition(depth: Int): Condition = when {
            depth >= MAX_TREE_DEPTH || random.nextInt(10) < 4 -> leaf()

            else -> when (random.nextInt(4)) {
                0 -> Condition.AllOf(List(random.nextInt(0, 4)) { condition(depth + 1) })
                1 -> Condition.AnyOf(List(random.nextInt(0, 4)) { condition(depth + 1) })
                2 -> Condition.Not(condition(depth + 1))
                else -> leaf()
            }
        }

        private fun override(): OnUnknown? = if (overrides && random.nextInt(4) == 0) OnUnknown.entries.random(random) else null

        private fun leaf(): Condition {
            if (random.nextInt(20) == 0) return Condition.LocalTimeIn(time(), time())
            if (random.nextInt(25) == 0) return Condition.Gte("not_in_catalog", value = RuleLiteral.of(1), onUnknown = override())
            val feature = features.random(random)
            val operator = Operator.entries.random(random)
            val literals = List(
                if (operator == Operator.BETWEEN) {
                    2
                } else if (operator == Operator.IN) {
                    random.nextInt(1, 4)
                } else {
                    1
                },
            ) {
                literal(feature)
            }
            return when (operator) {
                Operator.BETWEEN -> Condition.Between(feature, min = literals[0], max = literals[1], onUnknown = override())
                Operator.IN -> Condition.In(feature, values = literals, onUnknown = override())
                else -> Condition.compare(operator, feature, literals.single(), onUnknown = override())
            }
        }

        /** A literal of the feature's type most of the time, sometimes of another type or out of range. */
        private fun literal(feature: String): RuleLiteral = when {
            random.nextInt(15) == 0 -> RuleLiteral.of(random.nextBoolean())

            random.nextInt(15) == 0 -> RuleLiteral.of(1_000_000)

            else -> when (feature) {
                "steps_today" -> RuleLiteral.of(random.nextLong(0, 10_000))
                "screen_minutes_last_60m" -> RuleLiteral.of(random.nextLong(0, 61))
                "minutes_since_last_delivery" -> RuleLiteral.of(random.nextLong(0, 600))
                "location_class" -> RuleLiteral.of(listOf("HOME", "WORK", "OTHER").random(random))
                "charging" -> RuleLiteral.of(random.nextBoolean())
                "local_time", "bedtime_last_night" -> RuleLiteral.of(time())
                "day_of_week" -> RuleLiteral.of(listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN").random(random))
                else -> RuleLiteral.of(listOf("com.example.app", "org.sample.video", "bad package").random(random))
            }
        }

        private fun time(): String = if (random.nextInt(20) ==
            0
        ) {
            "24:61"
        } else {
            "%02d:%02d".format(Locale.ROOT, random.nextInt(24), random.nextInt(60))
        }

        fun snapshot(condition: Condition): FeatureSnapshot {
            val at = BASE + random.nextLong(0, 48 * 60).minutes
            val zone = if (random.nextInt(30) == 0) "Mars/Olympus" else listOf(F0.BERLIN.id, F0.NEW_YORK.id, "Asia/Kolkata").random(random)
            val values = RuleRefs.of(condition, null).mapNotNull { ref -> value(ref, at)?.let { ref to it } }.toMap()
            return FeatureSnapshot.of(at, zone, values)
        }

        private fun value(ref: FeatureRef, at: Instant): FeatureValue? = when (random.nextInt(10)) {
            0 -> null

            1, 2 -> FeatureValue.Missing(MissingReason.entries.random(random))

            3 -> known(ref.featureId)?.let { known ->
                FeatureValue.Stale((known as FeatureValue.Known).value, at - random.nextLong(0, 30).hours, MissingReason.NOT_SYNCED)
            }

            4 -> FeatureValue.Known(FeatureScalar.BoolValue(true), at)

            else -> known(ref.featureId)
        }

        /** A known value of [featureId]'s catalog type, or null for features this generator does not model. */
        fun known(featureId: String): FeatureValue? {
            val scalar: FeatureScalar = when (featureId) {
                "steps_today" -> FeatureScalar.IntValue(random.nextLong(0, 10_000))

                "screen_minutes_last_60m" -> FeatureScalar.IntValue(random.nextLong(0, 61))

                "minutes_since_last_delivery" -> if (random.nextInt(5) ==
                    0
                ) {
                    FeatureScalar.Never
                } else {
                    FeatureScalar.IntValue(random.nextLong(0, 600))
                }

                "location_class" -> FeatureScalar.EnumValue(listOf("HOME", "WORK", "OTHER").random(random))

                "charging" -> FeatureScalar.BoolValue(random.nextBoolean())

                "local_time" -> FeatureScalar.LocalTimeValue(random.nextInt(1_440))

                "bedtime_last_night" -> FeatureScalar.NightTimeValue(random.nextInt(1_440))

                "day_of_week" -> FeatureScalar.DayOfWeekValue(DayOfWeek.entries.random(random))

                "foreground_app" -> if (random.nextInt(4) == 0) FeatureScalar.NoPackage else FeatureScalar.PackageValue("com.example.app")

                else -> return null
            }
            return FeatureValue.Known(scalar, BASE)
        }
    }

    private companion object {
        const val SEED = 20_261_001L
        const val ITERATIONS = 3_000
        const val MAX_TREE_DEPTH = 5
        val BASE: Instant = Instant.parse("2026-10-01T00:00:00Z")
    }
}
