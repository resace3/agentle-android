package dev.agentle.analytics.insights

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.daily.DailyFeatureCatalog
import dev.agentle.analytics.features.daily.DailyRowStatus
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.Lineage
import dev.agentle.core.model.SourceFamily
import kotlinx.datetime.DayOfWeek
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Night table of docs/research/10 §14.2-§14.3: night types, exposure thresholds, outcome baselines and unknown values. */
class NightTableTest {
    private val config = InsightConfig(windowNights = 28)
    private val first = date("2026-10-01")
    private val last = date("2026-10-28")

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "N1, 2026-10-01, SATURDAY;SUNDAY, WORK_NIGHT",
        "N2, 2026-10-02, SATURDAY;SUNDAY, WEEKEND_NIGHT",
        "N3, 2026-10-03, SATURDAY;SUNDAY, WEEKEND_NIGHT",
        "N4, 2026-10-04, SATURDAY;SUNDAY, WORK_NIGHT",
        "N5, 2026-10-01, FRIDAY;SATURDAY, WEEKEND_NIGHT",
        "N6, 2026-10-03, FRIDAY;SATURDAY, WORK_NIGHT",
    )
    fun `a weekend night is one whose next date is a weekend day`(row: String, night: String, weekend: String, expected: NightType) {
        val days = weekend.split(';').map { DayOfWeek.valueOf(it) }.toSet()

        assertWithMessage(row).that(NightType.of(date(night), days)).isEqualTo(expected)
    }

    @ParameterizedTest(name = "{0} {1} at {2}")
    @CsvSource(
        "T1, SCREEN_30, 29.9, false",
        "T2, SCREEN_30, 30, true",
        "T3, SCREEN_45, 44.9, false",
        "T4, SCREEN_45, 45, true",
        "T5, SCREEN_60, 59.9, false",
        "T6, SCREEN_60, 60, true",
        "T7, SOCIAL_20, 19.9, false",
        "T8, SOCIAL_20, 20, true",
        "T9, NOTIFICATIONS_20, 19, false",
        "T10, NOTIFICATIONS_20, 20, true",
        "T11, STEPS_UNDER_5K, 4999, true",
        "T12, STEPS_UNDER_5K, 5000, false",
    )
    fun `exposure thresholds of the hypothesis family`(row: String, exposure: Exposure, value: Double, exposed: Boolean) {
        assertWithMessage(row).that(exposure.isExposed(value)).isEqualTo(exposed)
    }

    @Test
    fun `every exposure of a night is read from its daily feature`() {
        val night = date("2026-10-10")
        val rows = listOf(
            dailyRow(night, "screen_minutes_22_24", 45.0),
            dailyRow(night, "social_minutes_22_24", 20.0),
            dailyRow(night, "notifications_21_24", 19.0),
            dailyRow(night, "steps", 5_000.0),
        )

        val n = NightTableBuilder.build(last, rows, config).nights.single { it.date == night }

        assertThat(n.exposures).containsExactly(
            Exposure.SCREEN_30,
            true,
            Exposure.SCREEN_45,
            true,
            Exposure.SCREEN_60,
            false,
            Exposure.SOCIAL_20,
            true,
            Exposure.NOTIFICATIONS_20,
            false,
            Exposure.STEPS_UNDER_5K,
            false,
        )
        assertThat(n.outcomes).isEmpty()
    }

    @Test
    fun `the window holds every night oldest first, with unknown values left out`() {
        val table = NightTableBuilder.build(last, emptyList(), config)

        assertThat(table.firstNight).isEqualTo(first)
        assertThat(table.lastNight).isEqualTo(last)
        assertThat(table.nights.map { it.date }).isEqualTo((0 until 28).map { first.plusDays(it) })
        assertThat(table.nights.all { it.exposures.isEmpty() && it.outcomes.isEmpty() }).isTrue()
        assertThat(table.featureLineage).isEmpty()
    }

    @Test
    fun `O_late compares the bedtime with the window median plus 30 minutes`() {
        // 26 nights at 23:00 (660 on the night clock), one at 23:30 (690) and one at 23:29 (689): the median is 660.
        val bedtimes = (0 until 28).associate { first.plusDays(it) to 660.0 } +
            mapOf(first.plusDays(5) to 690.0, first.plusDays(6) to 689.0)
        val table = NightTableBuilder.build(last, bedtimes.map { (d, v) -> dailyRow(d, "bedtime", v, lineage = SLEEP_LINEAGE) }, config)
        val late = table.nights.associate { it.date to it.outcomes[NightOutcome.LATE_BEDTIME] }

        assertThat(late[first.plusDays(5)]).isTrue()
        assertThat(late[first.plusDays(6)]).isFalse()
        assertThat(late[first]).isFalse()
        assertThat(late.values.count { it == true }).isEqualTo(1)
    }

    @Test
    fun `O_short compares the asleep minutes with the window median minus 45 minutes`() {
        val asleep = (0 until 28).associate { first.plusDays(it) to 450.0 } +
            mapOf(first.plusDays(2) to 405.0, first.plusDays(3) to 406.0) - first.plusDays(4)
        val table = NightTableBuilder.build(last, asleep.map { (d, v) -> dailyRow(d, "sleep_minutes", v, lineage = SLEEP_LINEAGE) }, config)
        val short = table.nights.associate { it.date to it.outcomes[NightOutcome.SHORT_SLEEP] }

        assertThat(short[first.plusDays(2)]).isTrue()
        assertThat(short[first.plusDays(3)]).isFalse()
        assertThat(short[first.plusDays(4)]).isNull()
        assertThat(short.values.count { it == true }).isEqualTo(1)
    }

    @ParameterizedTest(name = "{0} next-day resting HR {1}")
    @CsvSource(
        // 14 baseline values 50..63 on the 14 dates ending with night N: the lower median is 56, the threshold 59.
        "R1, 59.0, 14, true",
        "R2, 58.9, 14, false",
        "R3, 70.0, 13, ",
        "R4, , 14, ",
    )
    fun `O_rhr compares the next date with the lower median of the 28 dates before it`(
        row: String,
        next: Double?,
        baselineValues: Int,
        expected: Boolean?,
    ) {
        val night = date("2026-10-20")
        val baseline = (0 until baselineValues).map { i -> dailyRow(night.plusDays(-i), "resting_hr", 63.0 - i, lineage = SLEEP_LINEAGE) }
        val rows = baseline + listOfNotNull(next?.let { dailyRow(night.plusDays(1), "resting_hr", it, lineage = SLEEP_LINEAGE) })

        val n = NightTableBuilder.build(last, rows, config).nights.single { it.date == night }

        assertWithMessage(row).that(n.outcomes[NightOutcome.HIGH_RESTING_HR]).isEqualTo(expected)
    }

    @Test
    fun `the O_rhr baseline ignores dates more than 27 days before the night`() {
        val night = date("2026-10-28")
        // 14 values, but the oldest one is 28 days before the night: only 13 count.
        val rows = (0 until 13).map { i -> dailyRow(night.plusDays(-i), "resting_hr", 60.0, lineage = SLEEP_LINEAGE) } +
            dailyRow(night.plusDays(-28), "resting_hr", 60.0, lineage = SLEEP_LINEAGE) +
            dailyRow(night.plusDays(1), "resting_hr", 90.0, lineage = SLEEP_LINEAGE)

        val n = NightTableBuilder.build(last, rows, config).nights.single { it.date == night }

        assertThat(n.outcomes).doesNotContainKey(NightOutcome.HIGH_RESTING_HR)
    }

    @Test
    fun `only FINAL rows of the current catalog version are observations`() {
        val night = date("2026-10-12")
        val rows = listOf(
            dailyRow(night, "screen_minutes_22_24", 50.0, status = DailyRowStatus.PROVISIONAL),
            dailyRow(night, "social_minutes_22_24", 50.0, status = DailyRowStatus.PARTIAL),
            dailyRow(night, "notifications_21_24", null, status = DailyRowStatus.MISSING),
            dailyRow(night, "steps", 100.0).copy(catalogVersion = DailyFeatureCatalog.VERSION - 1),
            dailyRow(night, "steps", 100.0).copy(metric = "steps{hour=22}"),
            dailyRow(night, "screen_minutes", 300.0),
        )

        val table = NightTableBuilder.build(last, rows, config)

        assertThat(table.nights.single { it.date == night }.exposures).isEmpty()
        assertThat(table.featureLineage).isEmpty()
    }

    @Test
    fun `lineage is the union of the rows read, per feature and per hypothesis`() {
        val screen = Lineage(setOf(DataCategory.SCREEN), setOf(SourceFamily.ON_DEVICE))
        val other = Lineage(setOf(DataCategory.APP_USAGE), setOf(SourceFamily.ON_DEVICE))
        val rows = listOf(
            dailyRow(first, "screen_minutes_22_24", 50.0, lineage = screen),
            dailyRow(first.plusDays(1), "screen_minutes_22_24", 10.0, lineage = other),
            dailyRow(first, "bedtime", 660.0, lineage = SLEEP_LINEAGE),
        )

        val table = NightTableBuilder.build(last, rows, config)

        assertThat(table.featureLineage["screen_minutes_22_24"]).isEqualTo(screen + other)
        assertThat(table.lineageOf(Hypothesis(Exposure.SCREEN_45, NightOutcome.LATE_BEDTIME))).isEqualTo(screen + other + SLEEP_LINEAGE)
        assertThat(table.lineageOf(Hypothesis(Exposure.STEPS_UNDER_5K, NightOutcome.LATE_BEDTIME))).isEqualTo(SLEEP_LINEAGE)
        assertThat(table.lineageOf(Hypothesis(Exposure.STEPS_UNDER_5K, NightOutcome.HIGH_RESTING_HR))).isEqualTo(Lineage.NONE)
    }

    @Test
    fun `the row range covers the window, the O_rhr baseline and the next date`() {
        assertThat(NightTableBuilder.rowRange(date("2026-10-04"))).isEqualTo(date("2026-06-10") to date("2026-10-05"))
        assertThat(NightTableBuilder.rowRange(last, config)).isEqualTo(date("2026-09-04") to date("2026-10-29"))
    }

    @Test
    fun `medians`() {
        assertThat(NightTableBuilder.median(emptyList())).isNull()
        assertThat(NightTableBuilder.median(listOf(3.0, 1.0, 2.0))).isEqualTo(2.0)
        assertThat(NightTableBuilder.median(listOf(4.0, 1.0, 3.0, 2.0))).isEqualTo(2.5)
        assertThat(NightTableBuilder.lowerMedian(listOf(4.0, 1.0, 3.0, 2.0))).isEqualTo(2.0)
        assertThat(NightTableBuilder.lowerMedian(listOf(5.0))).isEqualTo(5.0)
        assertThrows<IllegalArgumentException> { NightTableBuilder.lowerMedian(emptyList()) }
    }

    @Test
    fun `settings outside the calibrated ranges are rejected`() {
        assertThrows<IllegalArgumentException> { InsightConfig(windowNights = 27) }
        assertThrows<IllegalArgumentException> { InsightConfig(windowNights = 367) }
        assertThrows<IllegalArgumentException> { InsightConfig(maxRunGapDays = 6) }
        assertThrows<IllegalArgumentException> { InsightConfig(trialDays = 0) }
        assertThrows<IllegalArgumentException> { InsightConfig(trialDays = 91) }
        assertThrows<IllegalArgumentException> { InsightConfig(maxOpenProposals = 0) }
        assertThat(InsightConfig().weekendDays).containsExactly(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    }

    @Test
    fun `hypothesis ids and lookups`() {
        assertThat(Exposure.byId("E_screen45")).isEqualTo(Exposure.SCREEN_45)
        assertThat(Exposure.byId("E_nope")).isNull()
        assertThat(NightOutcome.byId("O_rhr")).isEqualTo(NightOutcome.HIGH_RESTING_HR)
        assertThat(NightOutcome.byId("O_nope")).isNull()
        assertThat(HypothesisFamily.byId("H18")).isEqualTo(Hypothesis(Exposure.STEPS_UNDER_5K, NightOutcome.HIGH_RESTING_HR))
        assertThat(HypothesisFamily.byId("H19")).isNull()
        assertThat(Hypothesis(Exposure.SCREEN_45, NightOutcome.LATE_BEDTIME).patternId(-0.3)).isEqualTo("H04:-")
        assertThat(Hypothesis(Exposure.SCREEN_45, NightOutcome.LATE_BEDTIME).patternId(0.0)).isEqualTo("H04:-")
    }
}
