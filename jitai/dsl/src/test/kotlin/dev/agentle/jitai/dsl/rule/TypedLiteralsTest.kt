package dev.agentle.jitai.dsl.rule

import com.google.common.truth.Truth.assertThat
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureType
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.jitai.dsl.testing.json
import kotlinx.datetime.DayOfWeek
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** R10 §4.3 (operators per type) and §4.4 (literal conversion and literal fidelity). */
class TypedLiteralsTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("conversions")
    fun `R10 4_4 literal conversion`(row: String, feature: String, literal: String, expected: Any) {
        val definition = checkNotNull(RealtimeFeatureCatalog[feature])
        val parsed = checkNotNull(RuleLiteral.fromJson(json(literal))) { row }

        val result = TypedLiterals.convert(definition, parsed)

        val wanted = when (expected) {
            is LiteralRejection -> LiteralConversion.Rejected(expected)
            else -> LiteralConversion.Converted(TypedLiteral(expected as FeatureScalar))
        }
        assertThat(result).isEqualTo(wanted)
    }

    @Test
    fun `R10 4_3 operators allowed per value type`() {
        val all = Operator.entries
        val expected = mapOf(
            FeatureType.INT to all,
            FeatureType.BOOL to listOf(Operator.EQ, Operator.NEQ),
            FeatureType.ENUM to listOf(Operator.EQ, Operator.NEQ, Operator.IN),
            FeatureType.DAY_OF_WEEK to listOf(Operator.EQ, Operator.NEQ, Operator.IN),
            FeatureType.LOCAL_TIME to
                listOf(Operator.GT, Operator.GTE, Operator.LT, Operator.LTE, Operator.EQ, Operator.NEQ, Operator.BETWEEN),
            FeatureType.NIGHT_TIME to listOf(Operator.GT, Operator.GTE, Operator.LT, Operator.LTE, Operator.BETWEEN),
            FeatureType.PACKAGE to listOf(Operator.EQ, Operator.NEQ, Operator.IN),
        )

        assertThat(expected.keys).containsExactlyElementsIn(FeatureType.entries)
        expected.forEach { (type, operators) ->
            assertThat(TypedLiterals.allowedOperators(type)).containsExactlyElementsIn(operators).inOrder()
            all.forEach { assertThat(TypedLiterals.isAllowed(type, it)).isEqualTo(it in operators) }
        }
    }

    @Test
    fun `package names follow the R10 4_4 regex and length`() {
        assertThat(TypedLiterals.isPackageName("com.instagram.android")).isTrue()
        assertThat(TypedLiterals.isPackageName("a.b")).isTrue()
        assertThat(TypedLiterals.isPackageName("instagram")).isFalse()
        assertThat(TypedLiterals.isPackageName("1com.app")).isFalse()
        assertThat(TypedLiterals.isPackageName("com..app")).isFalse()
        assertThat(TypedLiterals.isPackageName("a." + "b".repeat(253))).isTrue()
        assertThat(TypedLiterals.isPackageName("a." + "b".repeat(254))).isFalse()
    }

    @Test
    fun `day names are the three-letter upper-case forms`() {
        assertThat(TypedLiterals.dayOfWeek("MON")).isEqualTo(DayOfWeek.MONDAY)
        assertThat(TypedLiterals.dayOfWeek("SUN")).isEqualTo(DayOfWeek.SUNDAY)
        assertThat(TypedLiterals.dayOfWeek("Monday")).isNull()
    }

    @Test
    fun `typed literals of a leaf need a known feature and convertible literals`() {
        val good = Condition.Between("steps_today", min = RuleLiteral.of(1000), max = RuleLiteral.of(3000))
        val unknown = Condition.Eq("heart_rate_variability", value = RuleLiteral.of(30))
        val bad = Condition.In("day_type", values = listOf(RuleLiteral.of("WEEKEND"), RuleLiteral.of("weekend")))

        assertThat(TypedLiterals.typedLiterals(good))
            .containsExactly(TypedLiteral(FeatureScalar.IntValue(1000)), TypedLiteral(FeatureScalar.IntValue(3000))).inOrder()
        assertThat(TypedLiterals.typedLiterals(unknown)).isNull()
        assertThat(TypedLiterals.typedLiterals(bad)).isNull()
    }

    @Test
    fun `literal fidelity keeps 45, 45_0, 4_5e1 and the string 45 apart`() {
        val literals = listOf("45", "45.0", "4.5e1", "\"45\"").map { checkNotNull(RuleLiteral.fromJson(json(it))) }

        assertThat(literals).containsExactly(
            RuleLiteral.NumberToken("45"),
            RuleLiteral.NumberToken("45.0"),
            RuleLiteral.NumberToken("4.5e1"),
            RuleLiteral.Text("45"),
        ).inOrder()
        assertThat(literals.map { it.json }).containsExactly("45", "45.0", "4.5e1", "\"45\"").inOrder()
        assertThat(literals.map { it.toString() }).containsExactly("45", "45.0", "4.5e1", "\"45\"").inOrder()
        assertThat(literals.map { it.toJsonElement().toString() }).containsExactly("45", "45.0", "4.5e1", "\"45\"").inOrder()
        assertThat((literals[0] as RuleLiteral.NumberToken).isInteger).isTrue()
        assertThat((literals[1] as RuleLiteral.NumberToken).isInteger).isFalse()
    }

    @Test
    fun `rule literals are numbers, strings or booleans only`() {
        assertThat(RuleLiteral.fromJson(JsonNull)).isNull()
        assertThat(RuleLiteral.fromJson(JsonObject(emptyMap()))).isNull()
        assertThat(RuleLiteral.fromJson(JsonArray(emptyList()))).isNull()
        assertThat(RuleLiteral.fromJson(json("true"))).isEqualTo(RuleLiteral.Bool(true))
        assertThat(RuleLiteral.fromJson(json("false"))).isEqualTo(RuleLiteral.of(false))
        assertThat(RuleLiteral.fromJson(JsonPrimitive("true"))).isEqualTo(RuleLiteral.Text("true"))
        assertThat(RuleLiteral.of(true).json).isEqualTo("true")
        assertThat(RuleLiteral.of(true).toString()).isEqualTo("true")
        assertThat(RuleLiteral.of(true).toJsonElement()).isEqualTo(JsonPrimitive(true))
        assertThat(RuleLiteral.of("22:00").toJsonElement()).isEqualTo(JsonPrimitive("22:00"))
        assertThat(RuleLiteral.of(3000L)).isEqualTo(RuleLiteral.NumberToken("3000"))
        assertThat(RuleLiteral.of("a\"b").json).isEqualTo("\"a\\\"b\"")
        listOf("01", "1.", ".5", "+1", "NaN", "1e", "abc").forEach { token ->
            assertThrows<IllegalArgumentException> { RuleLiteral.NumberToken(token) }
        }
    }

    @Test
    fun `condition helpers name every node`() {
        val leaves = Operator.entries.filter { it != Operator.BETWEEN && it != Operator.IN }.map {
            Condition.compare(it, "steps_today", RuleLiteral.of(1))
        } + Condition.Between("steps_today", min = RuleLiteral.of(1), max = RuleLiteral.of(2)) +
            Condition.In("steps_today", values = listOf(RuleLiteral.of(1)))
        val time = Condition.LocalTimeIn("22:00", "02:00")
        val tree = Condition.AllOf(listOf(Condition.AnyOf(leaves), Condition.Not(time)))

        assertThat(leaves.map { it.wireType }).containsExactlyElementsIn(Operator.entries.map { it.wire }).inOrder()
        assertThat(leaves.map { it.operator }).containsExactlyElementsIn(Operator.entries).inOrder()
        assertThat(listOf(tree, tree.of[0], tree.of[1], time).map { it.wireType })
            .containsExactly("all", "any", "not", "local_time_in").inOrder()
        assertThat(tree.children).hasSize(2)
        assertThat(tree.of[1].children).containsExactly(time)
        assertThat(time.children).isEmpty()
        assertThat(leaves.first().children).isEmpty()
        assertThat(leaves.first().ref.featureId).isEqualTo("steps_today")
        assertThat(Operator.fromWire("gte")).isEqualTo(Operator.GTE)
        assertThat(Operator.fromWire("regex")).isNull()
        assertThrows<IllegalArgumentException> { Condition.compare(Operator.IN, "steps_today", RuleLiteral.of(1)) }
        assertThrows<IllegalArgumentException> { Condition.compare(Operator.BETWEEN, "steps_today", RuleLiteral.of(1)) }
    }

    companion object {
        private fun row(id: String, feature: String, literal: String, expected: Any) = Arguments.of(id, feature, literal, expected)

        @JvmStatic
        fun conversions(): List<Arguments> = listOf(
            row("INT 45", "steps_today", "45", FeatureScalar.IntValue(45)),
            row("INT -5", "resting_hr_delta_vs_28d", "-5", FeatureScalar.IntValue(-5)),
            row("INT 45.0 E015", "steps_today", "45.0", LiteralRejection.TYPE_MISMATCH),
            row("INT 4.5e1 E015", "steps_today", "4.5e1", LiteralRejection.TYPE_MISMATCH),
            row("INT string 45 E015", "steps_today", "\"45\"", LiteralRejection.TYPE_MISMATCH),
            row("INT true E015", "steps_today", "true", LiteralRejection.TYPE_MISMATCH),
            row("INT 61 above range E016", "screen_minutes_last_60m", "61", LiteralRejection.OUT_OF_RANGE),
            row("INT -1 below range E016", "steps_today", "-1", LiteralRejection.OUT_OF_RANGE),
            row("INT beyond Long E016", "steps_today", "99999999999999999999", LiteralRejection.OUT_OF_RANGE),
            row("BOOL true", "charging", "true", FeatureScalar.BoolValue(true)),
            row("BOOL string true E015", "charging", "\"true\"", LiteralRejection.TYPE_MISMATCH),
            row("BOOL 1 E015", "charging", "1", LiteralRejection.TYPE_MISMATCH),
            row("ENUM WEEKEND", "day_type", "\"WEEKEND\"", FeatureScalar.EnumValue("WEEKEND")),
            row("ENUM other case E015", "day_type", "\"weekend\"", LiteralRejection.TYPE_MISMATCH),
            row("ENUM unknown member E015", "location_class", "\"PARK\"", LiteralRejection.TYPE_MISMATCH),
            row("ENUM number E015", "day_type", "1", LiteralRejection.TYPE_MISMATCH),
            row("DAY MON", "day_of_week", "\"MON\"", FeatureScalar.DayOfWeekValue(DayOfWeek.MONDAY)),
            row("DAY Monday E015", "day_of_week", "\"Monday\"", LiteralRejection.TYPE_MISMATCH),
            row("DAY 1 E015", "day_of_week", "1", LiteralRejection.TYPE_MISMATCH),
            row("LOCAL_TIME 22:00", "local_time", "\"22:00\"", FeatureScalar.LocalTimeValue(22 * 60)),
            row("LOCAL_TIME 9:00 E024", "local_time", "\"9:00\"", LiteralRejection.INVALID_TIME),
            row("LOCAL_TIME 24:00 E024", "local_time", "\"24:00\"", LiteralRejection.INVALID_TIME),
            row("LOCAL_TIME 21:59:30 E024", "local_time", "\"21:59:30\"", LiteralRejection.INVALID_TIME),
            row("LOCAL_TIME number E015", "local_time", "2200", LiteralRejection.TYPE_MISMATCH),
            row("NIGHT_TIME 23:30", "bedtime_last_night", "\"23:30\"", FeatureScalar.NightTimeValue(23 * 60 + 30)),
            row("NIGHT_TIME 25:00 E024", "bedtime_last_night", "\"25:00\"", LiteralRejection.INVALID_TIME),
            row("PACKAGE instagram", "foreground_app", "\"com.instagram.android\"", FeatureScalar.PackageValue("com.instagram.android")),
            row("PACKAGE bare word E081", "foreground_app", "\"instagram\"", LiteralRejection.INVALID_PACKAGE),
            row("PACKAGE number E015", "foreground_app", "5", LiteralRejection.TYPE_MISMATCH),
        )
    }
}
