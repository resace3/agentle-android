package dev.agentle.analytics.features.daily

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.MissingReason
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.Lineage
import dev.agentle.core.model.SourceFamily
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Instant

class RollingWindowsTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `rolling windows use final days and say UNKNOWN when too few are covered`(
        row: String,
        feature: String,
        window: Int,
        days: List<Pair<Double?, DailyRowStatus>>,
        status: DerivedStatus,
        value: Double?,
        covered: Int,
    ) {
        val def = requireNotNull(DailyFeatureCatalog.rollingFeature(feature))
        val daily = days.mapIndexed { i, (v, s) -> dailyRow(ANCHOR.plusDays(-i), def.dailyFeatureId, v, s) }.associateBy { it.date }

        val result = RollingWindows.compute(def, window, ANCHOR, daily, T)

        assertWithMessage(row).that(result.status).isEqualTo(status)
        if (value == null) assertThat(result.value).isNull() else assertThat(result.value!!).isWithin(1e-9).of(value)
        assertThat(result.coveredDays).isEqualTo(covered)
        assertThat(result.catalogVersion).isEqualTo(DailyFeatureCatalog.VERSION)
    }

    @ParameterizedTest(name = "M{index} a {0}-day window needs {1} final days")
    @CsvSource("1,1", "3,2", "7,5", "14,10", "30,20", "90,60")
    fun `minimum final days are two thirds of the window, rounded up`(window: Int, min: Int) {
        assertThat(RollingWindows.minDays(window)).isEqualTo(min)
    }

    @Test
    fun `lineage is the union of the window's rows`() {
        val def = requireNotNull(DailyFeatureCatalog.rollingFeature("steps"))
        val daily = mapOf(
            ANCHOR to
                dailyRow(ANCHOR, "steps", 10.0, DailyRowStatus.FINAL, Lineage(setOf(DataCategory.ACTIVITY), setOf(SourceFamily.GH_API))),
            ANCHOR.plusDays(-1) to dailyRow(
                ANCHOR.plusDays(-1),
                "steps",
                12.0,
                DailyRowStatus.FINAL,
                Lineage(setOf(DataCategory.ACTIVITY), setOf(SourceFamily.ON_DEVICE)),
            ),
        )

        val result = RollingWindows.compute(def, 3, ANCHOR, daily, T)

        assertThat(result.lineage.sourceFamilies).containsExactly(SourceFamily.GH_API, SourceFamily.ON_DEVICE)
        assertThat(result.value).isEqualTo(11.0)
    }

    @Test
    fun `every rolling feature gets every window and per-subject features get none`() {
        val rows = RollingWindows.computeAll(ANCHOR, emptyMap(), T)

        assertThat(rows.size).isEqualTo(DailyFeatureCatalog.rolling.sumOf { it.windows.size })
        assertThat(rows.all { it.status == DerivedStatus.UNKNOWN }).isTrue()
        assertThat(rows.map { it.featureId }.toSet()).doesNotContain("app_minutes")
        assertThat(rows.filter { it.featureId == "sleep_regularity" }.map { it.windowDays }).doesNotContain(1)
    }

    @Test
    fun `a feature without rolling aggregation cannot be rolled`() {
        val def = RollingFeatureDefinition("app_minutes", "app_minutes", RollingAggregator.NONE, "per subject")

        assertThrows<IllegalStateException> { RollingWindows.compute(def, 7, ANCHOR, emptyMap(), T) }
        assertThrows<IllegalArgumentException> { RollingWindows.compute(def, 0, ANCHOR, emptyMap(), T) }
    }

    companion object {
        private val ANCHOR = date("2026-09-14")
        private val T = Instant.parse("2026-09-20T00:00:00Z")
        private val SCREEN = Lineage(setOf(DataCategory.SCREEN), setOf(SourceFamily.ON_DEVICE))

        private fun dailyRow(day: LocalDate, feature: String, value: Double?, status: DailyRowStatus, lineage: Lineage = SCREEN) =
            DailySummaryRow(
                date = day, metric = feature, featureId = feature, value = value,
                coverage = if (value ==
                    null
                ) {
                    0.0
                } else {
                    1.0
                },
                status = status,
                missingReason = if (status == DailyRowStatus.MISSING) MissingReason.NO_DATA else null, lineage = lineage, computedAt = T,
            )

        private fun final(vararg values: Double?) = values.map { v ->
            v to (
                if (v ==
                    null
                ) {
                    DailyRowStatus.MISSING
                } else {
                    DailyRowStatus.FINAL
                }
                )
        }

        @JvmStatic
        fun cases(): List<Arguments> = listOf(
            Arguments.of(
                "G1 mean over 5 of 7 final days",
                "screen_minutes",
                7,
                final(10.0, null, 20.0, 30.0, null, 40.0, 50.0),
                DerivedStatus.OK,
                30.0,
                5,
            ),
            Arguments.of(
                "G2 4 of 7 final days is UNKNOWN",
                "screen_minutes",
                7,
                final(10.0, null, 20.0, null, null, 40.0, 50.0),
                DerivedStatus.UNKNOWN,
                null,
                4,
            ),
            Arguments.of(
                "G3 a provisional day makes the window STALE",
                "screen_minutes",
                7,
                listOf(5.0 to DailyRowStatus.PROVISIONAL) + final(10.0, 20.0, 30.0, 40.0, 50.0),
                DerivedStatus.STALE,
                30.0,
                5,
            ),
            Arguments.of("G4 a 1-day window of a final day", "screen_minutes", 1, final(42.0), DerivedStatus.OK, 42.0, 1),
            Arguments.of(
                "G5 a 1-day window of a provisional day is UNKNOWN",
                "screen_minutes",
                1,
                listOf(42.0 to DailyRowStatus.PROVISIONAL),
                DerivedStatus.UNKNOWN,
                null,
                0,
            ),
            Arguments.of(
                "G6 partial days are not observations of the whole day",
                "screen_minutes",
                3,
                listOf(5.0 to DailyRowStatus.PARTIAL) + final(10.0),
                DerivedStatus.UNKNOWN,
                null,
                1,
            ),
            Arguments.of("G7 a sum needs every day", "interventions_delivered", 3, final(1.0, 2.0, 3.0), DerivedStatus.OK, 6.0, 3),
            Arguments.of(
                "G8 a sum with a missing day is UNKNOWN",
                "interventions_delivered",
                3,
                final(1.0, null, 3.0),
                DerivedStatus.UNKNOWN,
                null,
                2,
            ),
            Arguments.of(
                "G9 a sum with a provisional day is STALE",
                "interventions_delivered",
                3,
                listOf(1.0 to DailyRowStatus.PROVISIONAL) + final(2.0, 3.0),
                DerivedStatus.STALE,
                6.0,
                3,
            ),
            Arguments.of(
                "G10 sleep regularity is the sample standard deviation",
                "sleep_regularity",
                3,
                final(900.0, 930.0, 960.0),
                DerivedStatus.OK,
                30.0,
                3,
            ),
            Arguments.of("G11 one night has no spread", "sleep_regularity", 3, final(900.0, null, null), DerivedStatus.UNKNOWN, null, 1),
            Arguments.of(
                "G12 a 90-day mean needs 60 final days",
                "steps",
                90,
                final(*Array(59) { 1_000.0 }) + final(*Array<Double?>(31) { null }),
                DerivedStatus.UNKNOWN,
                null,
                59,
            ),
            Arguments.of("G13 a 90-day mean with 60 final days", "steps", 90, final(*Array(60) { 1_000.0 }), DerivedStatus.OK, 1_000.0, 60),
        )
    }
}
