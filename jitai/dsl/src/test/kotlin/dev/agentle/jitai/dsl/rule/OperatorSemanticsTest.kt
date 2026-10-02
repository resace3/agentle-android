package dev.agentle.jitai.dsl.rule

import com.google.common.truth.Truth.assertThat
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureType
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.codec.RuleCodec
import kotlinx.datetime.DayOfWeek
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * R10 §12.B: each leaf decoded from its wire form, its literals converted by the catalog type (R10 §4.4) and compared
 * with [OperatorSemantics] on a known value. The UNKNOWN inputs of the table (BatteryManager unavailable, geofencing
 * inactive) never reach a comparison: the evaluator's three-valued step handles them (R10 §6, JITAI-ENGINE).
 */
class OperatorSemanticsTest {
    @ParameterizedTest(name = "{0} at {2} -> {3}")
    @MethodSource("rowsB")
    fun `R10 12_B operators and boundaries`(row: String, leafJson: String, input: String, expected: Boolean) {
        val leaf = RuleCodec.decodeCondition(leafJson).getOrThrow() as Condition.FeatureLeaf
        val literals = checkNotNull(TypedLiterals.typedLiterals(leaf)) { "$row: literals do not convert" }

        assertThat(OperatorSemantics.holds(leaf.operator, scalar(leaf.feature, input), literals)).isEqualTo(expected)
    }

    @Test
    fun `NEVER compares as plus infinity`() {
        val literal = listOf(TypedLiteral(FeatureScalar.IntValue(120)))
        val expected = mapOf(
            Operator.GT to true,
            Operator.GTE to true,
            Operator.NEQ to true,
            Operator.LT to false,
            Operator.LTE to false,
            Operator.EQ to false,
            Operator.BETWEEN to false,
            Operator.IN to false,
        )

        expected.forEach { (operator, result) ->
            assertThat(OperatorSemantics.holds(operator, FeatureScalar.Never, literal)).isEqualTo(result)
        }
    }

    @Test
    fun `NONE equals no package and is not ordered`() {
        val literal = listOf(TypedLiteral(FeatureScalar.PackageValue("com.instagram.android")))

        assertThat(OperatorSemantics.holds(Operator.EQ, FeatureScalar.NoPackage, literal)).isFalse()
        assertThat(OperatorSemantics.holds(Operator.IN, FeatureScalar.NoPackage, literal)).isFalse()
        assertThat(OperatorSemantics.holds(Operator.NEQ, FeatureScalar.NoPackage, literal)).isTrue()
        assertThat(OperatorSemantics.holds(Operator.GT, FeatureScalar.NoPackage, literal)).isNull()
    }

    @Test
    fun `incomparable inputs give null instead of a guess`() {
        val int = listOf(TypedLiteral(FeatureScalar.IntValue(45)))
        val bool = listOf(TypedLiteral(FeatureScalar.BoolValue(true)))

        assertThat(OperatorSemantics.holds(Operator.EQ, FeatureScalar.IntValue(45), emptyList())).isNull()
        assertThat(OperatorSemantics.holds(Operator.EQ, FeatureScalar.LocalTimeValue(45), int)).isNull()
        assertThat(OperatorSemantics.holds(Operator.GT, FeatureScalar.BoolValue(true), bool)).isNull()
        assertThat(OperatorSemantics.holds(Operator.BETWEEN, FeatureScalar.IntValue(45), int)).isNull()
        assertThat(OperatorSemantics.holds(Operator.BETWEEN, FeatureScalar.BoolValue(true), bool + bool)).isNull()
    }

    @Test
    fun `order keys follow R10 4_3`() {
        assertThat(FeatureScalar.IntValue(-5).orderKey()).isEqualTo(-5L)
        assertThat(FeatureScalar.LocalTimeValue(0).orderKey()).isEqualTo(0L)
        assertThat(FeatureScalar.NightTimeValue(12 * 60).orderKey()).isEqualTo(0L)
        assertThat(FeatureScalar.NightTimeValue(11 * 60 + 59).orderKey()).isEqualTo(1439L)
        assertThat(FeatureScalar.BoolValue(true).orderKey()).isNull()
        assertThat(TypedLiteral(FeatureScalar.IntValue(7)).orderKey).isEqualTo(7L)
        assertThat(TypedLiteral(FeatureScalar.IntValue(7)).toString()).isEqualTo(FeatureScalar.IntValue(7).toString())
    }

    private fun scalar(feature: String, input: String): FeatureScalar = when {
        input == "NEVER" -> FeatureScalar.Never

        input == "NONE" -> FeatureScalar.NoPackage

        else -> when (checkNotNull(RealtimeFeatureCatalog[feature]).type) {
            FeatureType.INT -> FeatureScalar.IntValue(input.toLong())
            FeatureType.BOOL -> FeatureScalar.BoolValue(input.toBooleanStrict())
            FeatureType.ENUM -> FeatureScalar.EnumValue(input)
            FeatureType.DAY_OF_WEEK -> FeatureScalar.DayOfWeekValue(DayOfWeek.valueOf(input))
            FeatureType.LOCAL_TIME -> FeatureScalar.LocalTimeValue(checkNotNull(ClockTime.minuteOfDay(input)))
            FeatureType.NIGHT_TIME -> FeatureScalar.NightTimeValue(checkNotNull(ClockTime.minuteOfDay(input)))
            FeatureType.PACKAGE -> FeatureScalar.PackageValue(input)
        }
    }

    companion object {
        private fun leaf(type: String, feature: String, literal: String, args: String = "{}"): String =
            """{"type": "$type", "feature": "$feature", "args": $args, $literal, "onUnknown": null}"""

        private fun row(id: String, leaf: String, vararg cases: Pair<String, Boolean>): List<Arguments> =
            cases.map { (input, expected) -> Arguments.of(id, leaf, input, expected) }

        private const val SCREEN = "screen_minutes_last_60m"
        private const val SELF = """{"jitai": "self"}"""

        @JvmStatic
        fun rowsB(): List<Arguments> = listOf(
            row("B1 gt 45", leaf("gt", SCREEN, "\"value\": 45"), "44" to false, "45" to false, "46" to true),
            row("B2 gte 45", leaf("gte", SCREEN, "\"value\": 45"), "44" to false, "45" to true, "46" to true),
            row("B3 lt 45", leaf("lt", SCREEN, "\"value\": 45"), "44" to true, "45" to false, "46" to false),
            row("B4 lte 45", leaf("lte", SCREEN, "\"value\": 45"), "44" to true, "45" to true, "46" to false),
            row("B5 eq 45", leaf("eq", SCREEN, "\"value\": 45"), "44" to false, "45" to true, "46" to false),
            row("B6 neq 45", leaf("neq", SCREEN, "\"value\": 45"), "44" to true, "45" to false, "46" to true),
            row(
                "B7 between 45..50",
                leaf("between", SCREEN, "\"min\": 45, \"max\": 50"),
                "44" to false,
                "45" to true,
                "50" to true,
                "51" to false,
            ),
            row("B8 in [45, 50]", leaf("in", SCREEN, "\"values\": [45, 50]"), "44" to false, "45" to true, "46" to false, "50" to true),
            row(
                "B9 local_time gte 22:00",
                leaf("gte", "local_time", "\"value\": \"22:00\""),
                "21:59" to false,
                "22:00" to true,
                "23:59" to true,
                "00:00" to false,
            ),
            row(
                "B10 local_time between 09:00..17:00",
                leaf("between", "local_time", "\"min\": \"09:00\", \"max\": \"17:00\""),
                "08:59" to false,
                "09:00" to true,
                "17:00" to true,
                "17:01" to false,
            ),
            row(
                "B11 bedtime gte 23:30 (night clock)",
                leaf("gte", "bedtime_last_night", "\"value\": \"23:30\""),
                "21:00" to false,
                "23:29" to false,
                "23:30" to true,
                "00:15" to true,
                "11:59" to true,
                "12:00" to false,
            ),
            row(
                "B12 bedtime between 23:00..01:00",
                leaf("between", "bedtime_last_night", "\"min\": \"23:00\", \"max\": \"01:00\""),
                "22:59" to false,
                "23:00" to true,
                "00:30" to true,
                "01:00" to true,
                "01:01" to false,
            ),
            row("B13 charging eq true", leaf("eq", "charging", "\"value\": true"), "true" to true, "false" to false),
            row(
                "B14 location_class in HOME, WORK",
                leaf("in", "location_class", "\"values\": [\"HOME\", \"WORK\"]"),
                "HOME" to true,
                "WORK" to true,
                "OTHER" to false,
            ),
            row(
                "B15 foreground_app eq instagram",
                leaf("eq", "foreground_app", "\"value\": \"com.instagram.android\""),
                "com.instagram.android" to true,
                "com.google.android.youtube" to false,
                "NONE" to false,
            ),
            row(
                "B15 foreground_app neq instagram",
                leaf("neq", "foreground_app", "\"value\": \"com.instagram.android\""),
                "NONE" to true,
            ),
            // 119 min 59 s is floored to 119 by the feature (R10 §5.4), so the comparison sees 119.
            row(
                "B16 minutes_since_last_delivery self lt 120",
                leaf("lt", "minutes_since_last_delivery", "\"value\": 120", SELF),
                "NEVER" to false,
                "119" to true,
                "120" to false,
            ),
            row(
                "B17 minutes_since_last_delivery self gte 120",
                leaf("gte", "minutes_since_last_delivery", "\"value\": 120", SELF),
                "NEVER" to true,
            ),
        ).flatten()
    }
}
