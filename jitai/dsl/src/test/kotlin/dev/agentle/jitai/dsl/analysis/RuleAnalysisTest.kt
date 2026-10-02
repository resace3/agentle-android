package dev.agentle.jitai.dsl.analysis

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.FeatureRef
import dev.agentle.jitai.dsl.model.ActiveWindow
import dev.agentle.jitai.dsl.model.CreatedBy
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiKind
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.OnUnknown
import dev.agentle.jitai.dsl.rule.RuleLiteral
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Instant

/** R10 §4.8: leaf polarity, the dependency list and the sound but incomplete unsatisfiability analysis (E027). */
class RuleAnalysisTest {
    @Test
    fun `leaves carry wire paths, polarity, depth and the determining flag`() {
        val leaves = RuleAnalysis.leaves(MIXED)

        assertThat(leaves.map { it.path })
            .containsExactly("/of/0", "/of/1/of", "/of/2/of/0", "/of/2/of/1", "/of/3", "/of/4/of/of")
            .inOrder()
        assertThat(leaves.map { it.polarity }).containsExactly(POS, NEG, POS, POS, POS, POS).inOrder()
        assertThat(leaves.map { it.determining }).containsExactly(true, false, false, false, false, false).inOrder()
        assertThat(leaves.map { it.depth }).containsExactly(2, 3, 3, 3, 2, 4).inOrder()
        assertThat(leaves[3].featureId).isEqualTo("local_time")
        assertThat(leaves[3].ref).isEqualTo(FeatureRef("local_time"))
        assertThat(leaves[0].ref).isEqualTo(FeatureRef("steps_today"))
        assertThat(RuleAnalysis.depth(MIXED)).isEqualTo(4)
        assertThat(RuleAnalysis.nodeCount(MIXED)).isEqualTo(11)
    }

    @Test
    fun `a leaf or a time window at the root is determining`() {
        val leaf = RuleAnalysis.leaves(STEPS).single()
        val window = RuleAnalysis.leaves(Condition.LocalTimeIn("08:00", "09:00")).single()

        assertThat(listOf(leaf.path, leaf.depth, leaf.determining, leaf.polarity)).containsExactly("", 1, true, POS).inOrder()
        assertThat(listOf(window.path, window.determining)).containsExactly("", true).inOrder()
    }

    @Test
    fun `the dependency list holds every feature and args pair once, in wire order`() {
        val since = Condition.Gte("app_minutes_since", mapOf("package" to "com.instagram.android", "since" to "22:00"), lit(30))
        val definition = JitaiDefinition(
            id = "00000000-0000-4000-8000-000000000001",
            name = "n",
            createdBy = CreatedBy.USER_MANUAL,
            createdAt = Instant.fromEpochSeconds(0),
            modifiedAt = Instant.fromEpochSeconds(0),
            conditions = STEPS,
            contextRequirements = Condition.Eq("device_interactive", value = RuleLiteral.of(true)),
        )

        assertThat(RuleAnalysis.dependencies(MIXED, Condition.AllOf(listOf(since, STEPS)), null).map { it.key })
            .containsExactly(
                "steps_today",
                "charging",
                "battery_pct",
                "local_time",
                "screen_minutes_last_60m",
                "app_minutes_since{package=com.instagram.android,since=22:00}",
            ).inOrder()
        assertThat(RuleAnalysis.dependencies(definition).map { it.key }).containsExactly("steps_today", "device_interactive").inOrder()
        assertThat(RuleAnalysis.dependencies()).isEmpty()
    }

    @ParameterizedTest(name = "{0} {1} {2} -> {3}")
    @MethodSource("overrideEffects")
    fun `R10 6_4 override effect table`(kind: JitaiKind, polarity: Polarity, onUnknown: OnUnknown?, expected: OverrideEffect?) {
        assertThat(RuleAnalysis.overrideEffect(kind, polarity, onUnknown)).isEqualTo(expected)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("timeMasks")
    fun `minute masks of local_time_in nodes and local_time leaves`(row: String, node: Condition, minutes: Int?, first: Int?) {
        val mask = RuleAnalysis.timeMask(node)

        assertWithMessage(row).that(mask?.cardinality).isEqualTo(minutes)
        assertWithMessage(row).that(mask?.first()).isEqualTo(first)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("findings")
    fun `E027 findings are proofs that a tree is never true`(
        row: String,
        tree: Condition,
        window: ActiveWindow?,
        expected: Unsatisfiability?,
    ) {
        assertWithMessage(row).that(RuleAnalysis.unsatisfiable(tree, window)).isEqualTo(expected)
    }

    @Test
    fun `trees deeper than 64 levels are not analyzed`() {
        fun nested(levels: Int): Condition = (1..levels).fold<Int, Condition>(NEVER_TIME) { tree, _ -> Condition.AllOf(listOf(tree)) }

        assertThat(RuleAnalysis.unsatisfiable(nested(63))).isNotNull()
        assertThat(RuleAnalysis.unsatisfiable(nested(64))).isNull()
        assertThat(RuleAnalysis.depth(nested(64))).isEqualTo(65)
    }

    @Test
    fun `leaf texts of E027 explanations`() {
        assertThat(RuleAnalysis.leafText(Condition.Between("battery_pct", min = lit(10), max = lit(20))))
            .isEqualTo("battery_pct between 10 and 20")
        assertThat(RuleAnalysis.leafText(Condition.In("day_type", values = listOf(lit("WEEKDAY"), lit("WEEKEND")))))
            .isEqualTo("day_type in [WEEKDAY, WEEKEND]")
        assertThat(RuleAnalysis.leafText(Condition.Neq("charging", value = RuleLiteral.of(true)))).isEqualTo("charging != true")
        assertThat(RuleAnalysis.leafText(Condition.Lt("app_opens_last_60m", mapOf("package" to "com.a.b"), lit(5))))
            .isEqualTo("app_opens_last_60m{package=com.a.b} < 5")
    }

    companion object {
        private val POS = Polarity.POSITIVE
        private val NEG = Polarity.NEGATIVE

        private fun lit(value: Int) = RuleLiteral.of(value)

        private fun lit(value: String) = RuleLiteral.of(value)

        private val STEPS = Condition.Lt("steps_today", value = lit(3000))
        private val NEVER_TIME = Condition.Gt("local_time", value = lit("23:59"))

        private val MIXED: Condition = Condition.AllOf(
            listOf(
                STEPS,
                Condition.Not(Condition.Eq("charging", value = RuleLiteral.of(true))),
                Condition.AnyOf(listOf(Condition.Gte("battery_pct", value = lit(20)), Condition.LocalTimeIn("08:00", "09:00"))),
                Condition.Gte("screen_minutes_last_60m", value = lit(45), onUnknown = OnUnknown.ASSUME_FALSE),
                Condition.Not(Condition.Not(Condition.Lt("battery_pct", value = lit(90)))),
            ),
        )

        private fun all(vararg children: Condition): Condition = Condition.AllOf(children.toList())

        private fun any(vararg children: Condition): Condition = Condition.AnyOf(children.toList())

        private fun battery(type: String, value: Int): Condition = when (type) {
            "gt" -> Condition.Gt("battery_pct", value = lit(value))
            "gte" -> Condition.Gte("battery_pct", value = lit(value))
            "lt" -> Condition.Lt("battery_pct", value = lit(value))
            "lte" -> Condition.Lte("battery_pct", value = lit(value))
            "eq" -> Condition.Eq("battery_pct", value = lit(value))
            else -> Condition.Neq("battery_pct", value = lit(value))
        }

        private fun batteryIn(vararg values: Int): Condition = Condition.In("battery_pct", values = values.map { lit(it) })

        private fun batteryBetween(min: Int, max: Int): Condition = Condition.Between("battery_pct", min = lit(min), max = lit(max))

        private fun time(type: String, value: String, feature: String = "local_time", onUnknown: OnUnknown? = null): Condition =
            when (type) {
                "gt" -> Condition.Gt(feature, value = lit(value), onUnknown = onUnknown)
                "gte" -> Condition.Gte(feature, value = lit(value), onUnknown = onUnknown)
                "lt" -> Condition.Lt(feature, value = lit(value), onUnknown = onUnknown)
                "lte" -> Condition.Lte(feature, value = lit(value), onUnknown = onUnknown)
                "eq" -> Condition.Eq(feature, value = lit(value), onUnknown = onUnknown)
                else -> Condition.Neq(feature, value = lit(value), onUnknown = onUnknown)
            }

        private fun days(vararg excluded: String): Condition =
            Condition.AllOf(excluded.map { Condition.Neq("day_of_week", value = lit(it)) })

        private fun finding(explanation: String, path: String = "") = Unsatisfiability(path, explanation)

        private fun row(id: String, tree: Condition, expected: Unsatisfiability?, window: ActiveWindow? = null) =
            Arguments.of(id, tree, window, expected)

        @JvmStatic
        fun overrideEffects(): List<Arguments> = listOf(
            Arguments.of(JitaiKind.INTERVENTION, POS, OnUnknown.ASSUME_TRUE, OverrideEffect.DELIVERY_INCREASING),
            Arguments.of(JitaiKind.INTERVENTION, POS, OnUnknown.ASSUME_FALSE, OverrideEffect.DELIVERY_DECREASING),
            Arguments.of(JitaiKind.INTERVENTION, NEG, OnUnknown.ASSUME_TRUE, OverrideEffect.DELIVERY_DECREASING),
            Arguments.of(JitaiKind.INTERVENTION, NEG, OnUnknown.ASSUME_FALSE, OverrideEffect.DELIVERY_INCREASING),
            Arguments.of(JitaiKind.SUPPRESSION, POS, OnUnknown.ASSUME_TRUE, OverrideEffect.DELIVERY_DECREASING),
            Arguments.of(JitaiKind.SUPPRESSION, POS, OnUnknown.ASSUME_FALSE, OverrideEffect.DELIVERY_INCREASING),
            Arguments.of(JitaiKind.SUPPRESSION, NEG, OnUnknown.ASSUME_TRUE, OverrideEffect.DELIVERY_INCREASING),
            Arguments.of(JitaiKind.SUPPRESSION, NEG, OnUnknown.ASSUME_FALSE, OverrideEffect.DELIVERY_DECREASING),
            Arguments.of(JitaiKind.INTERVENTION, POS, null, null),
            Arguments.of(JitaiKind.SUPPRESSION, NEG, null, null),
        )

        @JvmStatic
        fun timeMasks(): List<Arguments> = listOf(
            Arguments.of("local_time_in crossing midnight", Condition.LocalTimeIn("22:00", "02:00"), 240, 0),
            Arguments.of("local_time_in same day", Condition.LocalTimeIn("08:00", "09:30"), 90, 480),
            Arguments.of("local_time_in empty", Condition.LocalTimeIn("08:00", "08:00"), null, null),
            Arguments.of("local_time_in invalid", Condition.LocalTimeIn("8:00", "09:00"), null, null),
            Arguments.of("gt", time("gt", "23:58"), 1, 1439),
            Arguments.of("gt the last minute", time("gt", "23:59"), 0, null),
            Arguments.of("gte", time("gte", "22:00"), 120, 1320),
            Arguments.of("lt", time("lt", "01:00"), 60, 0),
            Arguments.of("lt midnight", time("lt", "00:00"), 0, null),
            Arguments.of("lte", time("lte", "01:00"), 61, 0),
            Arguments.of("eq", time("eq", "12:00"), 1, 720),
            Arguments.of("neq", time("neq", "00:00"), 1439, 1),
            Arguments.of("between", Condition.Between("local_time", min = lit("10:00"), max = lit("11:00")), 61, 600),
            Arguments.of("between inverted", Condition.Between("local_time", min = lit("11:00"), max = lit("10:00")), null, null),
            Arguments.of("in", Condition.In("local_time", values = listOf(lit("10:00"))), null, null),
            Arguments.of("number literal", Condition.Gte("local_time", value = lit(5)), null, null),
            Arguments.of("invalid time", time("gte", "7:00"), null, null),
            Arguments.of("other time feature", time("gte", "07:00", feature = "wake_time_today"), null, null),
            Arguments.of("group", Condition.AllOf(listOf(Condition.LocalTimeIn("08:00", "09:00"))), null, null),
            Arguments.of("not", Condition.Not(Condition.LocalTimeIn("08:00", "09:00")), null, null),
        )

        @JvmStatic
        fun findings(): List<Arguments> = intervalRows() + setRows() + timeRows() + structureRows()

        private fun intervalRows(): List<Arguments> = listOf(
            row(
                "lt and gte",
                all(STEPS, Condition.Gte("steps_today", value = lit(3000))),
                finding("steps_today < 3000 and steps_today >= 3000"),
            ),
            row("gt and lte", all(battery("gt", 50), battery("lte", 50)), finding("battery_pct > 50 and battery_pct <= 50")),
            row("gt and lte overlapping", all(battery("gt", 50), battery("lte", 51)), null),
            row(
                "between and gt",
                all(batteryBetween(10, 20), battery("gt", 30)),
                finding("battery_pct between 10 and 20 and battery_pct > 30"),
            ),
            row("two eq", all(battery("eq", 5), battery("eq", 6)), finding("battery_pct = 5 and battery_pct = 6")),
            row("eq inside a range", all(battery("eq", 5), battery("lt", 6)), null),
            row("disjoint in lists", all(batteryIn(1, 2), batteryIn(3)), finding("battery_pct in [1, 2] and battery_pct in [3]")),
            row(
                "in minus neq",
                all(batteryIn(1, 2), battery("neq", 1), battery("neq", 2)),
                finding("battery_pct in [1, 2] and battery_pct != 1 and battery_pct != 2"),
            ),
            row(
                "bounded range all excluded",
                all(batteryBetween(1, 2), battery("neq", 1), battery("neq", 2)),
                finding("battery_pct between 1 and 2 and battery_pct != 1 and battery_pct != 2"),
            ),
            row("bounded range with a value left", all(batteryBetween(1, 3), battery("neq", 1), battery("neq", 2)), null),
            row("open range with neq", all(battery("gte", 1), battery("neq", 1)), null),
            row(
                "different args are different features",
                all(
                    Condition.Lt("app_minutes_last_60m", mapOf("package" to "com.a.b"), lit(5)),
                    Condition.Gte("app_minutes_last_60m", mapOf("package" to "com.c.d"), lit(5)),
                ),
                null,
            ),
            row(
                "ASSUME_TRUE leaves are left out",
                all(STEPS, Condition.Gte("steps_today", value = lit(3000), onUnknown = OnUnknown.ASSUME_TRUE)),
                null,
            ),
            row(
                "ASSUME_FALSE leaves still count",
                all(STEPS, Condition.Gte("steps_today", value = lit(3000), onUnknown = OnUnknown.ASSUME_FALSE)),
                finding("steps_today < 3000 and steps_today >= 3000"),
            ),
            row("an inverted between is left to E017", all(batteryBetween(50, 10), battery("lt", 5)), null),
            row(
                "an operator the type does not allow is left out",
                all(Condition.Gt("day_type", value = lit("WEEKEND")), Condition.Eq("day_type", value = lit("WEEKDAY"))),
                null,
            ),
            row(
                "night order runs from noon to noon",
                all(time("gt", "23:00", "bedtime_last_night"), time("lt", "01:00", "bedtime_last_night")),
                null,
            ),
            row(
                "local times run from midnight",
                all(time("gt", "23:00", "wake_time_today"), time("lt", "01:00", "wake_time_today")),
                finding("wake_time_today > 23:00 and wake_time_today < 01:00"),
            ),
        )

        private fun setRows(): List<Arguments> = listOf(
            row(
                "bool eq both",
                all(Condition.Eq("charging", value = RuleLiteral.of(true)), Condition.Eq("charging", value = RuleLiteral.of(false))),
                finding("charging = true and charging = false"),
            ),
            row(
                "bool neq both",
                all(Condition.Neq("charging", value = RuleLiteral.of(true)), Condition.Neq("charging", value = RuleLiteral.of(false))),
                finding("charging != true and charging != false"),
            ),
            row(
                "bool eq and neq of the other value",
                all(Condition.Eq("charging", value = RuleLiteral.of(true)), Condition.Neq("charging", value = RuleLiteral.of(false))),
                null,
            ),
            row(
                "enum",
                all(Condition.In("day_type", values = listOf(lit("WEEKDAY"))), Condition.Neq("day_type", value = lit("WEEKDAY"))),
                finding("day_type in [WEEKDAY] and day_type != WEEKDAY"),
            ),
            row(
                "every day excluded",
                days("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"),
                finding(
                    "day_of_week != MON and day_of_week != TUE and day_of_week != WED and day_of_week != THU and " +
                        "day_of_week != FRI and day_of_week != SAT and day_of_week != SUN",
                ),
            ),
            row("six days excluded", days("MON", "TUE", "WED", "THU", "FRI", "SAT"), null),
            row(
                "packages have no finite domain",
                all(Condition.Neq("foreground_app", value = lit("com.a.b")), Condition.Neq("foreground_app", value = lit("com.c.d"))),
                null,
            ),
            row(
                "two packages on screen",
                all(Condition.Eq("foreground_app", value = lit("com.a.b")), Condition.Eq("foreground_app", value = lit("com.c.d"))),
                finding("foreground_app = com.a.b and foreground_app = com.c.d"),
            ),
            row(
                "an unknown feature is left out",
                all(Condition.Eq("heart_rate_variability", value = lit(1)), Condition.Eq("heart_rate_variability", value = lit(2))),
                null,
            ),
            row(
                "a literal that does not convert is left out",
                all(Condition.Eq("charging", value = lit(1)), Condition.Eq("charging", value = RuleLiteral.of(false))),
                null,
            ),
        )

        private fun timeRows(): List<Arguments> = listOf(
            row("local_time never true", NEVER_TIME, finding("local_time > 23:59 is never true")),
            row(
                "local_time leaves",
                all(time("gte", "22:00"), time("lt", "21:00")),
                finding("local_time >= 22:00 and local_time < 21:00"),
            ),
            row(
                "local_time_in nodes",
                all(Condition.LocalTimeIn("08:00", "09:00"), Condition.LocalTimeIn("10:00", "11:00")),
                finding("local_time 08:00-09:00 and local_time 10:00-11:00"),
            ),
            row("not of a time leaf", all(Condition.Not(time("gte", "00:00"))), finding("not (local_time >= 00:00)")),
            row("not of a time leaf with an override", all(Condition.Not(time("gte", "00:00", onUnknown = OnUnknown.ASSUME_FALSE))), null),
            row("a time leaf with ASSUME_TRUE", time("gt", "23:59", onUnknown = OnUnknown.ASSUME_TRUE), null),
            row(
                "outside the active window",
                Condition.LocalTimeIn("08:00", "09:00"),
                finding("local_time 08:00-09:00 is outside the active window 20:00-23:00"),
                ActiveWindow("20:00", "23:00"),
            ),
            row(
                "any of two windows outside the active window",
                any(Condition.LocalTimeIn("08:00", "09:00"), Condition.LocalTimeIn("10:00", "11:00")),
                finding("(local_time 08:00-09:00 or local_time 10:00-11:00) is outside the active window 20:00-23:00"),
                ActiveWindow("20:00", "23:00"),
            ),
            row(
                "an any with a non-time child has no time text",
                any(Condition.LocalTimeIn("08:00", "09:00"), STEPS),
                null,
                ActiveWindow("20:00", "23:00"),
            ),
            row("overlapping the active window", time("gte", "22:00"), null, ActiveWindow("20:00", "02:00")),
            row("an empty active window is not analyzed", Condition.LocalTimeIn("08:00", "09:00"), null, ActiveWindow("20:00", "20:00")),
            row("an invalid active window is not analyzed", Condition.LocalTimeIn("08:00", "09:00"), null, ActiveWindow("25:00", "23:00")),
            row("no time condition", STEPS, null, ActiveWindow("20:00", "23:00")),
        )

        private fun structureRows(): List<Arguments> = listOf(
            row(
                "an any needs every child to be never true",
                any(all(Condition.Lt("steps_today", value = lit(1)), Condition.Gt("steps_today", value = lit(5))), NEVER_TIME),
                finding("steps_today < 1 and steps_today > 5", "/of/0"),
            ),
            row(
                "an any with one satisfiable child",
                any(all(Condition.Lt("steps_today", value = lit(1)), Condition.Gt("steps_today", value = lit(5))), STEPS),
                null,
            ),
            row(
                "a nested all",
                all(Condition.Eq("charging", value = RuleLiteral.of(true)), all(battery("gt", 50), battery("lt", 40))),
                finding("battery_pct > 50 and battery_pct < 40", "/of/1"),
            ),
            row(
                "a not is never treated as never true",
                Condition.Not(all(Condition.Lt("steps_today", value = lit(1)), Condition.Gt("steps_today", value = lit(5)))),
                null,
            ),
            row("an empty all", Condition.AllOf(emptyList()), null),
            row("an empty any", Condition.AnyOf(emptyList()), null),
            row("a single leaf", STEPS, null),
        )
    }
}
