package dev.agentle.analytics.insights

import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.daily.DailyRowStatus
import dev.agentle.analytics.features.daily.DailySummaryRow
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.Lineage
import dev.agentle.core.model.SourceFamily
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.math.exp
import kotlin.math.ln
import kotlin.time.Instant

internal fun date(text: String): LocalDate = LocalDate.parse(text)

internal fun LocalDate.plusDays(n: Int): LocalDate = if (n >= 0) plus(DatePeriod(days = n)) else minus(DatePeriod(days = -n))

internal val WEEKEND: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

internal val T0: Instant = Instant.parse("2026-10-05T18:00:00Z")

internal val SCREEN_LINEAGE = Lineage(setOf(DataCategory.SCREEN), setOf(SourceFamily.ON_DEVICE))
internal val SLEEP_LINEAGE = Lineage(setOf(DataCategory.SLEEP), setOf(SourceFamily.GH_API))

/** A group of nights of one night type with the same exposure and outcome. */
internal data class Cells(val type: NightType, val count: Int, val exposed: Boolean, val outcome: Boolean)

/**
 * Night tables for one hypothesis (exposure [exposure], outcome [outcome]) with exact counts per night type: each group
 * of [Cells] is spread evenly over the nights of its type in date order (so both halves of the window hold about half
 * of every group). Every other exposure and outcome is unknown.
 */
internal object Controls {
    fun table(
        first: LocalDate,
        nights: Int,
        cells: List<Cells>,
        exposure: Exposure = Exposure.SCREEN_45,
        outcome: NightOutcome = NightOutcome.LATE_BEDTIME,
    ): NightTable {
        val dates = (0 until nights).map { first.plusDays(it) }
        val assigned = HashMap<LocalDate, Cells>()
        for (type in NightType.entries) {
            val slots = dates.filter { NightType.of(it, WEEKEND) == type }
            val groups = cells.filter { it.type == type }
            require(groups.sumOf { it.count } == slots.size) { "$type has ${slots.size} nights, cells give ${groups.sumOf { it.count }}" }
            val spread = groups.flatMapIndexed { g, cell -> (0 until cell.count).map { j -> Triple((j + 0.5) / cell.count, g, cell) } }
                .sortedWith(compareBy({ it.first }, { it.second }))
            slots.zip(spread).forEach { (d, item) -> assigned[d] = item.third }
        }
        val list = dates.map { d ->
            val cell = assigned.getValue(d)
            Night(d, NightType.of(d, WEEKEND), mapOf(exposure to cell.exposed), mapOf(outcome to cell.outcome))
        }
        return NightTable(
            first,
            dates.last(),
            list,
            mapOf(exposure.dailyFeatureId to SCREEN_LINEAGE, outcome.dailyFeatureId to SLEEP_LINEAGE),
        )
    }

    /** C1 (docs/research/10 §14.6): exact weekend confound, 56 nights from 2026-10-01. */
    val C1: List<Cells> = listOf(
        Cells(NightType.WEEKEND_NIGHT, 7, exposed = true, outcome = true),
        Cells(NightType.WEEKEND_NIGHT, 7, exposed = true, outcome = false),
        Cells(NightType.WEEKEND_NIGHT, 1, exposed = false, outcome = true),
        Cells(NightType.WEEKEND_NIGHT, 1, exposed = false, outcome = false),
        Cells(NightType.WORK_NIGHT, 6, exposed = true, outcome = false),
        Cells(NightType.WORK_NIGHT, 34, exposed = false, outcome = false),
    )

    /** C2: equal late rates for exposed and unexposed nights within each night type (56 nights). */
    val C2: List<Cells> = listOf(
        Cells(NightType.WEEKEND_NIGHT, 4, exposed = true, outcome = true),
        Cells(NightType.WEEKEND_NIGHT, 4, exposed = true, outcome = false),
        Cells(NightType.WEEKEND_NIGHT, 4, exposed = false, outcome = true),
        Cells(NightType.WEEKEND_NIGHT, 4, exposed = false, outcome = false),
        Cells(NightType.WORK_NIGHT, 2, exposed = true, outcome = true),
        Cells(NightType.WORK_NIGHT, 8, exposed = true, outcome = false),
        Cells(NightType.WORK_NIGHT, 6, exposed = false, outcome = true),
        Cells(NightType.WORK_NIGHT, 24, exposed = false, outcome = false),
    )

    /** C3: a strong association in only 27 complete nights (from 2026-10-01). */
    val C3: List<Cells> = listOf(
        Cells(NightType.WEEKEND_NIGHT, 3, exposed = true, outcome = true),
        Cells(NightType.WEEKEND_NIGHT, 2, exposed = false, outcome = false),
        Cells(NightType.WEEKEND_NIGHT, 3, exposed = true, outcome = false),
        Cells(NightType.WORK_NIGHT, 8, exposed = true, outcome = true),
        Cells(NightType.WORK_NIGHT, 2, exposed = false, outcome = true),
        Cells(NightType.WORK_NIGHT, 9, exposed = false, outcome = false),
    )

    /** P1: the planted positive control, 60 nights from 2026-08-06 to 2026-10-04. */
    val P1: List<Cells> = listOf(
        Cells(NightType.WORK_NIGHT, 10, exposed = true, outcome = true),
        Cells(NightType.WORK_NIGHT, 4, exposed = true, outcome = false),
        Cells(NightType.WORK_NIGHT, 7, exposed = false, outcome = true),
        Cells(NightType.WORK_NIGHT, 21, exposed = false, outcome = false),
        Cells(NightType.WEEKEND_NIGHT, 7, exposed = true, outcome = true),
        Cells(NightType.WEEKEND_NIGHT, 3, exposed = true, outcome = false),
        Cells(NightType.WEEKEND_NIGHT, 2, exposed = false, outcome = true),
        Cells(NightType.WEEKEND_NIGHT, 6, exposed = false, outcome = false),
    )

    val P1_FIRST: LocalDate = date("2026-08-06")
    val C_FIRST: LocalDate = date("2026-10-01")

    fun p1(): NightTable = table(P1_FIRST, 60, P1)
}

/** A daily row as the daily engine stores it. */
internal fun dailyRow(
    day: LocalDate,
    feature: String,
    value: Double?,
    status: DailyRowStatus = DailyRowStatus.FINAL,
    lineage: Lineage = SCREEN_LINEAGE,
): DailySummaryRow = DailySummaryRow(
    date = day,
    metric = feature,
    featureId = feature,
    value = value,
    coverage = if (status == DailyRowStatus.FINAL) 1.0 else 0.5,
    status = status,
    missingReason = if (status == DailyRowStatus.MISSING) MissingReason.NO_DATA else null,
    lineage = lineage,
    computedAt = T0,
)

/** The two-sided Fisher exact test of a 2x2 table (test-only: the crude test the stratified design replaces). */
internal fun fisherTwoSided(t: TwoByTwo): Double {
    fun logC(n: Int, k: Int): Double = (1..n).sumOf { ln(it.toDouble()) } - (1..k).sumOf { ln(it.toDouble()) } -
        (1..(n - k)).sumOf { ln(it.toDouble()) }
    val r1 = t.exposed
    val r2 = t.unexposed
    val c1 = t.withOutcome
    val n = t.n
    fun p(x: Int): Double = exp(logC(r1, x) + logC(r2, c1 - x) - logC(n, c1))
    val observed = p(t.a)
    return (maxOf(0, c1 - r2)..minOf(r1, c1)).map { p(it) }.filter { it <= observed * (1 + 1e-7) }.sum().coerceAtMost(1.0)
}

/** The night-type tables of control P1 (docs/research/10 §14.6). */
internal val P1_WORK = TwoByTwo(10, 4, 7, 21)
internal val P1_WEEKEND = TwoByTwo(7, 3, 2, 6)

/** A finding of [hypothesis] with the P1 counts and the given tier, stratified risk difference and q-value. */
internal fun claim(
    hypothesis: Hypothesis,
    tier: PatternTier = PatternTier.MODERATE,
    rdMh: Double = 0.46,
    q: Double = 0.0185,
    lineage: Lineage = SCREEN_LINEAGE + SLEEP_LINEAGE,
): HypothesisResult = HypothesisResult(
    hypothesis = hypothesis,
    table = P1_WORK + P1_WEEKEND,
    strata = listOf(StratumResult(NightType.WORK_NIGHT, P1_WORK), StratumResult(NightType.WEEKEND_NIGHT, P1_WEEKEND)),
    eligible = true,
    riskDifferenceMh = rdMh,
    pValue = q / HypothesisFamily.size,
    qValue = q,
    tier = tier,
    strataCheck = true,
    splitHalfCheck = true,
    lineage = lineage,
)

/** A hypothesis that could not be tested. */
internal fun untested(hypothesis: Hypothesis): HypothesisResult = HypothesisResult(
    hypothesis = hypothesis,
    table = TwoByTwo.EMPTY,
    strata = NightType.entries.map { StratumResult(it, TwoByTwo.EMPTY) },
    eligible = false,
    riskDifferenceMh = null,
    pValue = 1.0,
    qValue = 1.0,
    tier = PatternTier.INSUFFICIENT,
    strataCheck = false,
    splitHalfCheck = false,
    lineage = Lineage.NONE,
)

/** A run over the 60 nights ending [lastNight] with [results]; every other hypothesis is untested. */
internal fun runOf(lastNight: LocalDate, vararg results: HypothesisResult, familyVersion: Int = HypothesisFamily.VERSION): PatternRun {
    val byHypothesis = results.associateBy { it.hypothesis }
    return PatternRun(
        runAt = T0,
        firstNight = lastNight.plusDays(-59),
        lastNight = lastNight,
        results = HypothesisFamily.all.map { byHypothesis[it] ?: untested(it) },
        familyVersion = familyVersion,
    )
}

internal fun hypothesis(id: String): Hypothesis = requireNotNull(HypothesisFamily.byId(id))

/** The §14.7 finding text of control P1. */
internal const val P1_TEXT: String =
    "On 17 of 24 nights (71%) when your screen time between 10 PM and midnight was 45 minutes or more, you went to bed at " +
        "least 30 minutes later than your usual bedtime. On the other 36 nights this happened 9 times (25%). The pattern showed " +
        "up on work nights and on weekend nights. This is a pattern in your own data, not proof: something else, such as a busy " +
        "day, may explain both."

/** Runs [block] with the JVM default zone set to [id] (restored afterwards); results must never depend on it. */
internal inline fun <T> withJvmDefaultZone(id: String, block: () -> T): T {
    val saved = java.util.TimeZone.getDefault()
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(id))
    try {
        return block()
    } finally {
        java.util.TimeZone.setDefault(saved)
    }
}
