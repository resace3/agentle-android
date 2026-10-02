package dev.agentle.jitai.engine.eval

import com.google.common.truth.Truth.assertThat
import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureSnapshot
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.Quality
import dev.agentle.jitai.dsl.model.CreatedBy
import dev.agentle.jitai.dsl.model.JitaiKind
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.OnUnknown
import dev.agentle.jitai.dsl.rule.Operator
import dev.agentle.jitai.dsl.rule.RuleLiteral
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.Leaves
import dev.agentle.jitai.engine.Rules
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Instant

/** R10 §6 and §12.C / §12.D (evaluator side) / §12.E1-E2, E9: three-valued evaluation of rule trees. */
class RuleEvaluatorTest {
    private val evaluator = RuleEvaluator()
    private val at = F0.local("2026-10-01T17:00")

    private fun snapshot(vararg values: Pair<String, FeatureValue>, at: Instant = this.at, zone: String = F0.BERLIN.id): FeatureSnapshot =
        FeatureSnapshot.of(at, zone, values.associate { (id, value) -> FeatureRef(id) to value })

    // -- §12.C truth tables ------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("truthTable")
    fun `R10 12_C truth table - all, any and not over P and Q`(id: String, p: Tri, q: Tri, tree: String, expected: Tri) {
        val condition = when (tree) {
            "all" -> Leaves.all(P, Q)
            "any" -> Leaves.any(P, Q)
            else -> Leaves.not(P)
        }

        val trace = evaluator.evaluate(condition, snapshot(STEPS to pValue(p), LOCATION to qValue(q)))

        assertThat(trace.result).isEqualTo(expected)
        assertThat(trace.nodes.first().result).isEqualTo(expected)
    }

    @Test
    fun `the truth table rows cover all 27 cases`() {
        assertThat(truthTable().map { it.get()[0] }.toSet()).hasSize(27)
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource("TRUE,TRUE,TRUE", "TRUE,FALSE,FALSE", "TRUE,UNKNOWN,UNKNOWN", "FALSE,UNKNOWN,FALSE", "UNKNOWN,UNKNOWN,UNKNOWN")
    fun `Kleene AND is FALSE-dominant and commutative`(a: Tri, b: Tri, expected: Tri) {
        assertThat(a and b).isEqualTo(expected)
        assertThat(b and a).isEqualTo(expected)
    }

    @Test
    fun `n-ary all and any of an empty list are UNKNOWN, never TRUE`() {
        assertThat(Tri.all(emptyList())).isEqualTo(Tri.UNKNOWN)
        assertThat(Tri.any(emptyList())).isEqualTo(Tri.UNKNOWN)
        assertThat(evaluator.evaluate(Condition.AllOf(emptyList()), snapshot()).nodes.single().note).isEqualTo(TraceNote.EMPTY_GROUP)
        assertThat(evaluator.evaluate(Condition.AnyOf(emptyList()), snapshot()).result).isEqualTo(Tri.UNKNOWN)
    }

    @Test
    fun `C4 all(T, U, F) is F, any(F, U, T) is T, all(T, U) is U`() {
        val values = snapshot(STEPS to int(2_500), LOCATION to missing(MissingReason.COLLECTOR_INACTIVE), SCREEN to int(10))
        val t = P
        val u = Q
        val f = Leaves.gte(SCREEN, 45)

        assertThat(evaluator.evaluate(Leaves.all(t, u, f), values).result).isEqualTo(Tri.FALSE)
        assertThat(evaluator.evaluate(Leaves.any(f, u, t), values).result).isEqualTo(Tri.TRUE)
        assertThat(evaluator.evaluate(Leaves.all(t, u), values).result).isEqualTo(Tri.UNKNOWN)
    }

    // -- §12.C overrides (the validator's E026 is the DSL's; the evaluator ignores what it must never honour) -------------

    @Test
    fun `C5 a confirmed USER override ASSUME_TRUE at polarity + makes the missing leaf T`() {
        val rule = Rules.rule("C5", null, Leaves.lt(STEPS, 3_000, OnUnknown.ASSUME_TRUE)).copy(userConfirmedUnknownOverrides = true)

        val trace = evaluator.evaluate(rule.conditions, snapshot(STEPS to missing()), RootKind.INTERVENTION, OverridePolicy.of(rule))

        assertThat(trace.result).isEqualTo(Tri.TRUE)
        assertThat(trace.nodes.single().appliedOverride).isEqualTo(OnUnknown.ASSUME_TRUE)
        assertThat(trace.nodes.single().value!!.state).isEqualTo(ValueState.MISSING)
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "C6 USER rule without confirmation, USER_MANUAL, false",
        "C7 the same override in an AI rule, AI_NATURAL_LANGUAGE, true",
        "C7 the same override in a discovered rule, AI_DISCOVERED, true",
    )
    fun `C6 C7 an unconfirmed or AI delivery-increasing override is ignored at run time`(
        id: String,
        createdBy: CreatedBy,
        confirmed: Boolean,
    ) {
        val rule = Rules.rule("C6", null, Leaves.lt(STEPS, 3_000, OnUnknown.ASSUME_TRUE), createdBy = createdBy)
            .copy(userConfirmedUnknownOverrides = confirmed)

        val trace = evaluator.evaluate(rule.conditions, snapshot(STEPS to missing()), RootKind.INTERVENTION, OverridePolicy.of(rule))

        assertThat(trace.result).isEqualTo(Tri.UNKNOWN)
        assertThat(trace.nodes.single().overrideIgnored).isTrue()
        assertThat(trace.nodes.single().appliedOverride).isNull()
    }

    @Test
    fun `C8 an AI ASSUME_FALSE at polarity + is delivery-decreasing and turns U into F`() {
        val rule = Rules.rule("C8", null, Leaves.lt(STEPS, 3_000, OnUnknown.ASSUME_FALSE), createdBy = CreatedBy.AI_NATURAL_LANGUAGE)

        val trace = evaluator.evaluate(rule.conditions, snapshot(STEPS to missing()), RootKind.INTERVENTION, OverridePolicy.of(rule))

        assertThat(trace.result).isEqualTo(Tri.FALSE)
        assertThat(trace.nodes.single().appliedOverride).isEqualTo(OnUnknown.ASSUME_FALSE)
    }

    @Test
    fun `C9 an AI ASSUME_FALSE under not (polarity -) is delivery-increasing and ignored`() {
        val rule = Rules.rule("C9", null, Leaves.not(Leaves.lt(STEPS, 3_000, OnUnknown.ASSUME_FALSE)), createdBy = CreatedBy.AI_DISCOVERED)

        val trace = evaluator.evaluate(rule.conditions, snapshot(STEPS to missing()), RootKind.INTERVENTION, OverridePolicy.of(rule))

        assertThat(trace.result).isEqualTo(Tri.UNKNOWN)
        assertThat(trace.nodes[1].overrideIgnored).isTrue()
    }

    @Test
    fun `C10 an AI SUPPRESSION with ASSUME_FALSE at polarity + would block less and is ignored - U still blocks`() {
        val rule = Rules.suppression(
            "C10",
            null,
            Leaves.lt(STEPS, 3_000, OnUnknown.ASSUME_FALSE),
            dev.agentle.jitai.dsl.model.SuppressionTarget(jitaiIds = listOf("R1")),
            createdBy = CreatedBy.AI_NATURAL_LANGUAGE,
        )

        val trace = evaluator.evaluate(
            rule.conditions,
            snapshot(STEPS to missing()),
            RootKind.of(JitaiKind.SUPPRESSION),
            OverridePolicy.of(rule),
        )

        assertThat(trace.result).isEqualTo(Tri.UNKNOWN)
        assertThat(trace.nodes.single().overrideIgnored).isTrue()
    }

    @ParameterizedTest(name = "{0} polarity {1} {2} -> increasing {3}")
    @CsvSource(
        "INTERVENTION, true, ASSUME_TRUE, true",
        "INTERVENTION, true, ASSUME_FALSE, false",
        "INTERVENTION, false, ASSUME_TRUE, false",
        "INTERVENTION, false, ASSUME_FALSE, true",
        "SUPPRESSION, true, ASSUME_TRUE, false",
        "SUPPRESSION, true, ASSUME_FALSE, true",
        "SUPPRESSION, false, ASSUME_TRUE, true",
        "SUPPRESSION, false, ASSUME_FALSE, false",
    )
    fun `R10 6_4 polarity table classifies every override`(kind: RootKind, positive: Boolean, override: OnUnknown, increasing: Boolean) {
        assertThat(OverridePolicy.isDeliveryIncreasing(override, positive, kind)).isEqualTo(increasing)
    }

    @Test
    fun `an override never applies to a known result`() {
        val trace = evaluator.evaluate(Leaves.lt(STEPS, 3_000, OnUnknown.ASSUME_FALSE), snapshot(STEPS to int(2_000)))

        assertThat(trace.result).isEqualTo(Tri.TRUE)
        assertThat(trace.nodes.single().appliedOverride).isNull()
    }

    // -- §12.D evaluator side (R2: steps_today lt 3000 at 17:00) ---------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("freshness")
    fun `R10 12_D leaf results for steps_today`(
        id: String,
        leaf: Condition,
        value: FeatureValue,
        at: Instant,
        expected: Tri,
        bound: Boolean,
    ) {
        val trace = evaluator.evaluate(leaf, snapshot(STEPS to value, at = at))

        assertThat(trace.result).isEqualTo(expected)
        assertThat(trace.nodes.single().lowerBound == true).isEqualTo(bound)
    }

    @Test
    fun `D8 a stale value from the previous local day is U with STALE_OTHER_DAY`() {
        val value = FeatureValue.Stale(FeatureScalar.IntValue(500), F0.local("2026-10-01T23:40"), MissingReason.NOT_SYNCED)

        val trace = evaluator.evaluate(P, snapshot(STEPS to value, at = F0.local("2026-10-02T00:20")))

        assertThat(trace.result).isEqualTo(Tri.UNKNOWN)
        assertThat(trace.nodes.single().note).isEqualTo(TraceNote.STALE_OTHER_DAY)
    }

    @Test
    fun `a stale value of a feature without a monotone bound is U`() {
        val value = FeatureValue.Stale(FeatureScalar.IntValue(5_000), at, MissingReason.NOT_SYNCED)

        val trace = evaluator.evaluate(Leaves.gte("steps_last_60m", 1_000), snapshot("steps_last_60m" to value))

        assertThat(trace.result).isEqualTo(Tri.UNKNOWN)
        assertThat(trace.nodes.single().lowerBound).isNull()
        assertThat(trace.nodes.single().value!!.state).isEqualTo(ValueState.STALE)
    }

    @ParameterizedTest(name = "{0} vs={1} k={2} -> {3}")
    @CsvSource(
        "GTE, 3000, 3000, TRUE", "GTE, 2999, 3000, UNKNOWN",
        "GT, 3001, 3000, TRUE", "GT, 3000, 3000, UNKNOWN",
        "LT, 3000, 3000, FALSE", "LT, 2999, 3000, UNKNOWN",
        "LTE, 3001, 3000, FALSE", "LTE, 3000, 3000, UNKNOWN",
        "EQ, 3001, 3000, FALSE", "EQ, 3000, 3000, UNKNOWN",
        "NEQ, 3001, 3000, TRUE", "NEQ, 3000, 3000, UNKNOWN",
    )
    fun `R10 6_3 monotone lower-bound table for single-literal operators`(operator: Operator, vs: Long, k: Long, expected: Tri) {
        assertThat(RuleEvaluator.lowerBound(operator, vs, listOf(k))).isEqualTo(expected)
    }

    @Test
    fun `R10 6_3 lower bound for between and in uses the upper end`() {
        assertThat(RuleEvaluator.lowerBound(Operator.BETWEEN, 5_001, listOf(1_000, 5_000))).isEqualTo(Tri.FALSE)
        assertThat(RuleEvaluator.lowerBound(Operator.BETWEEN, 5_000, listOf(1_000, 5_000))).isEqualTo(Tri.UNKNOWN)
        assertThat(RuleEvaluator.lowerBound(Operator.IN, 7_001, listOf(5_000, 7_000))).isEqualTo(Tri.FALSE)
        assertThat(RuleEvaluator.lowerBound(Operator.IN, 7_000, listOf(5_000, 7_000))).isEqualTo(Tri.UNKNOWN)

        val between = Condition.Between(STEPS, min = RuleLiteral.of(1_000), max = RuleLiteral.of(5_000))
        val stale = FeatureValue.Stale(FeatureScalar.IntValue(6_000), at, MissingReason.NOT_SYNCED)
        assertThat(evaluator.evaluate(between, snapshot(STEPS to stale)).result).isEqualTo(Tri.FALSE)
    }

    // -- §12.E1, E2, E9 local_time_in ------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} {1}-{2} at {3}")
    @CsvSource(
        "E1, 22:00, 02:00, 2026-10-01T21:59, FALSE",
        "E1, 22:00, 02:00, 2026-10-01T22:00, TRUE",
        "E1, 22:00, 02:00, 2026-10-01T23:59, TRUE",
        "E1, 22:00, 02:00, 2026-10-02T00:00, TRUE",
        "E1, 22:00, 02:00, 2026-10-02T01:59, TRUE",
        "E1, 22:00, 02:00, 2026-10-02T02:00, FALSE",
        "E2, 09:00, 17:00, 2026-10-01T08:59, FALSE",
        "E2, 09:00, 17:00, 2026-10-01T09:00, TRUE",
        "E2, 09:00, 17:00, 2026-10-01T16:59, TRUE",
        "E2, 09:00, 17:00, 2026-10-01T17:00, FALSE",
    )
    fun `E1 E2 local_time_in is half-open and crosses midnight`(id: String, start: String, end: String, local: String, expected: Tri) {
        val trace = evaluator.evaluate(Condition.LocalTimeIn(start, end), snapshot(at = F0.local(local)))

        assertThat(trace.result).isEqualTo(expected)
        assertThat(trace.nodes.single().value!!.scalar).isEqualTo(local.substringAfter('T'))
    }

    @Test
    fun `E1 seconds are truncated - 01_59_59 is still inside`() {
        val trace = evaluator.evaluate(Condition.LocalTimeIn("22:00", "02:00"), snapshot(at = F0.local("2026-10-02T01:59:59")))

        assertThat(trace.result).isEqualTo(Tri.TRUE)
    }

    @ParameterizedTest(name = "{0}-{1}")
    @CsvSource("22:00, 22:00", "25:00, 02:00", "22:00, 2:00")
    fun `E9 an empty or malformed local_time_in fails closed as UNKNOWN`(start: String, end: String) {
        val trace = evaluator.evaluate(Condition.LocalTimeIn(start, end), snapshot())

        assertThat(trace.result).isEqualTo(Tri.UNKNOWN)
        assertThat(trace.nodes.single().note).isEqualTo(TraceNote.INVALID_TIME)
    }

    // -- totality: what a stored rule should never contain evaluates to UNKNOWN with a note ------------------------------

    @Test
    fun `an unknown feature, an invalid literal and an operator the type does not allow are UNKNOWN with notes`() {
        val values = snapshot(STEPS to int(10), "charging" to bool(true))

        val unknown = evaluator.evaluate(Condition.Gte("no_such_feature", value = RuleLiteral.of(1)), values)
        val literal = evaluator.evaluate(Condition.Gte(STEPS, value = RuleLiteral.of("many")), values)
        val range = evaluator.evaluate(Condition.Gte(STEPS, value = RuleLiteral.of(999_999)), values)
        val operator = evaluator.evaluate(Condition.Gt("charging", value = RuleLiteral.of(true)), values)

        assertThat(unknown.nodes.single().note).isEqualTo(TraceNote.UNKNOWN_FEATURE)
        assertThat(literal.nodes.single().note).isEqualTo(TraceNote.INVALID_LITERAL)
        assertThat(range.nodes.single().note).isEqualTo(TraceNote.INVALID_LITERAL)
        assertThat(operator.nodes.single().note).isEqualTo(TraceNote.OPERATOR_NOT_ALLOWED)
        listOf(unknown, literal, range, operator).forEach { assertThat(it.result).isEqualTo(Tri.UNKNOWN) }
    }

    @Test
    fun `a value of the wrong type is a TYPE_MISMATCH and an unresolved reference is NOT_RESOLVED`() {
        val mismatch = evaluator.evaluate(P, snapshot(STEPS to bool(true)))
        val unresolved = evaluator.evaluate(P, snapshot())
        val staleWrongType = evaluator.evaluate(
            P,
            snapshot(STEPS to FeatureValue.Stale(FeatureScalar.BoolValue(true), at, MissingReason.NOT_SYNCED)),
        )

        assertThat(mismatch.result).isEqualTo(Tri.UNKNOWN)
        assertThat(mismatch.nodes.single().note).isEqualTo(TraceNote.TYPE_MISMATCH)
        assertThat(unresolved.nodes.single().value).isEqualTo(TraceValue.NOT_RESOLVED)
        assertThat(staleWrongType.nodes.single().note).isEqualTo(TraceNote.TYPE_MISMATCH)
    }

    @Test
    fun `an invalid zone id is UNKNOWN for local time and for same-day bounds, never an exception`() {
        val values =
            snapshot(STEPS to FeatureValue.Stale(FeatureScalar.IntValue(5_000), at, MissingReason.NOT_SYNCED), zone = "Mars/Olympus")

        assertThat(
            evaluator.evaluate(Condition.LocalTimeIn("22:00", "02:00"), values).nodes.single().note,
        ).isEqualTo(TraceNote.INVALID_ZONE)
        assertThat(evaluator.evaluate(P, values).nodes.single().note).isEqualTo(TraceNote.INVALID_ZONE)
    }

    @Test
    fun `a tree deeper than MAX_DEPTH is UNKNOWN instead of recursing`() {
        var condition: Condition = P
        repeat(RuleEvaluator.MAX_DEPTH + 5) { condition = Condition.Not(condition) }

        val trace = evaluator.evaluate(condition, snapshot(STEPS to int(1)))

        assertThat(trace.result).isEqualTo(Tri.UNKNOWN)
        assertThat(trace.nodes.last().note).isEqualTo(TraceNote.TOO_DEEP)
        assertThat(RuleRefs.leaves(condition)).isEmpty()
    }

    @Test
    fun `null conditions are TRUE with an empty trace`() {
        assertThat(evaluator.evaluate(null, snapshot())).isEqualTo(TreeTrace(Tri.TRUE, emptyList()))
    }

    // -- §6.7 trace -----------------------------------------------------------------------------------------------------

    @Test
    fun `the trace lists every node in pre-order with JSON-pointer paths, args sorted and literals as JSON`() {
        val condition = Leaves.all(
            Leaves.gte(SCREEN, 45),
            Leaves.any(Condition.Gte("app_minutes_last_60m", mapOf("package" to "com.example.app"), RuleLiteral.of(10)), Leaves.not(P)),
        )
        val values = FeatureSnapshot.of(
            at,
            F0.BERLIN.id,
            mapOf(
                FeatureRef(SCREEN) to FeatureValue.Known(FeatureScalar.IntValue(50), at, Quality.PROVISIONAL),
                FeatureRef("app_minutes_last_60m", mapOf("package" to "com.example.app")) to int(12),
                FeatureRef(STEPS) to int(2_000),
            ),
        )

        val trace = evaluator.evaluate(condition, values)

        assertThat(trace.nodes.map { it.path }).containsExactly("", "/of/0", "/of/1", "/of/1/of/0", "/of/1/of/1", "/of/1/of/1/of").inOrder()
        assertThat(trace.nodes.map { it.type }).containsExactly("all", "gte", "any", "gte", "not", "lt").inOrder()
        assertThat(trace.nodes[1].value!!.quality).isEqualTo(Quality.PROVISIONAL)
        assertThat(trace.nodes[3].args).containsExactly("package", "com.example.app")
        assertThat(trace.nodes[1].literals).containsExactly("45")
        assertThat(trace.result).isEqualTo(Tri.TRUE)
    }

    @Test
    fun `the first failing branch keeps the root and the first non-TRUE child at each level`() {
        val condition = Leaves.all(Leaves.gte(SCREEN, 45), Leaves.any(Leaves.gte(SCREEN, 59), Leaves.lt(STEPS, 3_000)))
        val trace = evaluator.evaluate(condition, snapshot(SCREEN to int(50), STEPS to int(5_000)))

        val branch = trace.firstFailingBranch()

        assertThat(branch.truncated).isTrue()
        assertThat(branch.nodes.map { it.path }).containsExactly("", "/of/1", "/of/1/of/0").inOrder()
        assertThat(TreeTrace(Tri.TRUE, emptyList()).firstFailingBranch().truncated).isTrue()
    }

    @Test
    fun `display renders every scalar content-free`() {
        assertThat(TraceValue.display(FeatureScalar.LocalTimeValue(22 * 60 + 5))).isEqualTo("22:05")
        assertThat(TraceValue.display(FeatureScalar.NightTimeValue(30))).isEqualTo("00:30")
        assertThat(TraceValue.display(FeatureScalar.DayOfWeekValue(kotlinx.datetime.DayOfWeek.MONDAY))).isEqualTo("MON")
        assertThat(TraceValue.display(FeatureScalar.PackageValue("com.example"))).isEqualTo("com.example")
        assertThat(TraceValue.display(FeatureScalar.NoPackage)).isEqualTo("NONE")
        assertThat(TraceValue.display(FeatureScalar.Never)).isEqualTo("NEVER")
        assertThat(TraceValue.display(FeatureScalar.BoolValue(false))).isEqualTo("false")
        assertThat(TraceValue.display(FeatureScalar.EnumValue("HOME"))).isEqualTo("HOME")
    }

    @Test
    fun `rule references cover conditions and context requirements, and placeholders resolve to the first leaf`() {
        val rule = Rules.rule("R", null, Leaves.all(P, Leaves.gte(SCREEN, 45)), context = Q)

        assertThat(RuleRefs.of(rule)).containsExactly(FeatureRef(STEPS), FeatureRef(SCREEN), FeatureRef(LOCATION))
        assertThat(RuleRefs.placeholderLeaf(rule, LOCATION)).isEqualTo(Q)
        assertThat(RuleRefs.placeholderLeaf(rule, "battery_pct")).isNull()
        assertThat(RuleRefs.of(null as Condition?)).isEmpty()
    }

    @Test
    fun `NEVER compares as infinity and NONE equals no package`() {
        val never = snapshot("minutes_since_last_delivery" to FeatureValue.Known(FeatureScalar.Never, at))
        val none = snapshot("foreground_app" to FeatureValue.Known(FeatureScalar.NoPackage, at))

        assertThat(
            evaluator.evaluate(Condition.Gte("minutes_since_last_delivery", value = RuleLiteral.of(120)), never).result,
        ).isEqualTo(Tri.TRUE)
        assertThat(
            evaluator.evaluate(Condition.Eq("foreground_app", value = RuleLiteral.of("com.example.app")), none).result,
        ).isEqualTo(Tri.FALSE)
    }

    @Test
    fun `RootKind maps the JITAI kind`() {
        assertThat(RootKind.of(JitaiKind.INTERVENTION)).isEqualTo(RootKind.INTERVENTION)
        assertThat(RootKind.of(JitaiKind.SUPPRESSION)).isEqualTo(RootKind.SUPPRESSION)
        assertThat(TimeZone.of(F0.BERLIN.id)).isEqualTo(F0.BERLIN)
    }

    companion object {
        const val STEPS = "steps_today"
        const val LOCATION = "location_class"
        const val SCREEN = "screen_minutes_last_60m"

        /** P = steps_today lt 3000; Q = location_class eq HOME (R10 §12.C). */
        val P: Condition = Leaves.lt(STEPS, 3_000)
        val Q: Condition = Leaves.eq(LOCATION, "HOME")

        private val AT: Instant = F0.local("2026-10-01T17:00")

        fun int(value: Long): FeatureValue = FeatureValue.Known(FeatureScalar.IntValue(value), AT)

        fun bool(value: Boolean): FeatureValue = FeatureValue.Known(FeatureScalar.BoolValue(value), AT)

        fun missing(reason: MissingReason = MissingReason.NO_DATA): FeatureValue = FeatureValue.Missing(reason)

        fun pValue(p: Tri): FeatureValue = when (p) {
            Tri.TRUE -> int(2_500)
            Tri.FALSE -> int(3_500)
            Tri.UNKNOWN -> missing()
        }

        fun qValue(q: Tri): FeatureValue = when (q) {
            Tri.TRUE -> FeatureValue.Known(FeatureScalar.EnumValue("HOME"), AT)
            Tri.FALSE -> FeatureValue.Known(FeatureScalar.EnumValue("OTHER"), AT)
            Tri.UNKNOWN -> missing(MissingReason.COLLECTOR_INACTIVE)
        }

        @JvmStatic
        fun truthTable(): List<Arguments> {
            val t = Tri.TRUE
            val f = Tri.FALSE
            val u = Tri.UNKNOWN
            // (P, Q) -> all[P,Q], any[P,Q], not P, exactly as printed in R10 §12.C.
            val rows = listOf(
                listOf(t, t, t, t, f),
                listOf(t, f, f, t, f),
                listOf(t, u, u, t, f),
                listOf(f, t, f, t, t),
                listOf(f, f, f, f, t),
                listOf(f, u, f, u, t),
                listOf(u, t, u, t, u),
                listOf(u, f, f, u, u),
                listOf(u, u, u, u, u),
            )
            return rows.flatMap { (p, q, all, any, not) ->
                listOf(
                    Arguments.of("P=${p.name[0]} Q=${q.name[0]} all", p, q, "all", all),
                    Arguments.of("P=${p.name[0]} Q=${q.name[0]} any", p, q, "any", any),
                    Arguments.of("P=${p.name[0]} Q=${q.name[0]} not P", p, q, "not", not),
                )
            }
        }

        @JvmStatic
        fun freshness(): List<Arguments> {
            val at = F0.local("2026-10-01T17:00")
            val stale = { value: Long ->
                FeatureValue.Stale(FeatureScalar.IntValue(value), F0.local("2026-10-01T16:15"), MissingReason.NOT_SYNCED)
            }
            return listOf(
                Arguments.of("D1 known 2,999", P, int(2_999), at, Tri.TRUE, false),
                Arguments.of("D2 known 3,000", P, int(3_000), at, Tri.FALSE, false),
                Arguments.of("D3 stale 3,200 bound", P, stale(3_200), at, Tri.FALSE, true),
                Arguments.of("D4 stale 2,800", P, stale(2_800), at, Tri.UNKNOWN, false),
                Arguments.of("D4 after the sync 2,950", P, int(2_950), at + kotlin.time.Duration.parse("10m"), Tri.TRUE, false),
                Arguments.of("D6 no data", P, missing(), at, Tri.UNKNOWN, false),
                Arguments.of("D7 true zero", P, int(0), at, Tri.TRUE, false),
                Arguments.of("D9 gte with stale 3,200", Leaves.gte(STEPS, 3_000), stale(3_200), at, Tri.TRUE, true),
            )
        }
    }
}
