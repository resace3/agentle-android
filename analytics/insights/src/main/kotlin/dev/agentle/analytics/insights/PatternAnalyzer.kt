package dev.agentle.analytics.insights

import dev.agentle.core.model.Lineage
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.time.Instant

/** Evidence tier of a hypothesis in one run (docs/research/10 §14.4). Only STRONG and MODERATE are findings. */
public enum class PatternTier {
    STRONG,
    MODERATE,
    WEAK,
    NONE,
    INSUFFICIENT,
}

/** The counts of one night type. */
public data class StratumResult(val type: NightType, val table: TwoByTwo)

/**
 * The statistics of one hypothesis in one run.
 *
 * @property table counts over the complete nights (exposure and outcome both known).
 * @property riskDifferenceMh the stratified risk difference; null when the hypothesis is not eligible or no night type
 *   has both exposed and unexposed nights.
 * @property pValue the exact stratified p-value (1 when not tested).
 * @property qValue the Benjamini-Hochberg q-value over the whole family of the run.
 * @property lineage the categories and source families of the daily rows the hypothesis was computed from.
 */
public data class HypothesisResult(
    val hypothesis: Hypothesis,
    val table: TwoByTwo,
    val strata: List<StratumResult>,
    val eligible: Boolean,
    val riskDifferenceMh: Double?,
    val pValue: Double,
    val qValue: Double,
    val tier: PatternTier,
    val strataCheck: Boolean,
    val splitHalfCheck: Boolean,
    val lineage: Lineage,
) {
    /** The crude risk difference. */
    public val riskDifference: Double? get() = table.riskDifference

    /** The 95 % Newcombe interval of the crude risk difference. */
    public val interval: Interval? get() = PatternStatistics.newcombe(table)

    /** MODERATE or STRONG. */
    public val isClaim: Boolean get() = tier == PatternTier.STRONG || tier == PatternTier.MODERATE

    /** `H04:+` or `H04:-`; null without a stratified risk difference. */
    public val patternId: String? get() = riskDifferenceMh?.let { hypothesis.patternId(it) }

    /** Whether night type [type] had enough exposed and unexposed nights ([PatternAnalyzer.STRATUM_MIN_ARM]) to be checked. */
    public fun checked(type: NightType): Boolean = strata.any {
        it.type == type && it.table.exposed >= PatternAnalyzer.STRATUM_MIN_ARM && it.table.unexposed >= PatternAnalyzer.STRATUM_MIN_ARM
    }
}

/** The results of one weekly run over the nights [firstNight]..[lastNight]. */
public data class PatternRun(
    val runAt: Instant,
    val firstNight: LocalDate,
    val lastNight: LocalDate,
    val results: List<HypothesisResult>,
    val familyVersion: Int = HypothesisFamily.VERSION,
) {
    init {
        require(results.map { it.hypothesis } == HypothesisFamily.all) { "a run holds the whole family in order" }
    }

    public fun result(hypothesis: Hypothesis): HypothesisResult = results[hypothesis.number - 1]

    /** The findings of the run (MODERATE or STRONG), by pattern id. */
    public val claims: Map<String, HypothesisResult>
        get() = results.filter { it.isClaim }.associateBy { requireNotNull(it.patternId) }
}

/**
 * The statistics of docs/research/10 §14.4 for the whole family over one night table: eligibility, the exact
 * stratified test, Benjamini-Hochberg over all 18 hypotheses (untestable ones count with p = 1), the strata and
 * split-half checks, and the tier. Deterministic.
 */
public object PatternAnalyzer {
    public const val MIN_NIGHTS: Int = 28
    public const val MIN_ARM: Int = 7
    public const val MIN_OUTCOME: Int = 5
    public const val STRATUM_MIN_ARM: Int = 4
    public const val STRONG_Q: Double = 0.01
    public const val MODERATE_Q: Double = 0.10
    public const val STRONG_RD: Double = 0.25
    public const val MODERATE_RD: Double = 0.20
    public const val STRONG_MIN_NIGHTS: Int = 56

    public fun analyze(table: NightTable, runAt: Instant): PatternRun {
        val partial = HypothesisFamily.all.map { hypothesis ->
            test(hypothesis, rows(table.nights, hypothesis), table.lineageOf(hypothesis))
        }
        val q = PatternStatistics.benjaminiHochberg(partial.map { it.pValue })
        val results = partial.mapIndexed { i, result -> classify(result.copy(qValue = q[i])) }
        return PatternRun(runAt, table.firstNight, table.lastNight, results)
    }

    /** One complete night of a hypothesis. */
    private data class Cell(val exposed: Boolean, val outcome: Boolean, val type: NightType)

    private fun rows(nights: List<Night>, hypothesis: Hypothesis): List<Cell> = nights.mapNotNull { night ->
        val exposed = night.exposures[hypothesis.exposure] ?: return@mapNotNull null
        val outcome = night.outcomes[hypothesis.outcome] ?: return@mapNotNull null
        Cell(exposed, outcome, night.type)
    }

    private fun test(hypothesis: Hypothesis, rows: List<Cell>, lineage: Lineage): HypothesisResult {
        val table = TwoByTwo.of(rows.map { it.exposed to it.outcome })
        val strata = NightType.entries.map { type ->
            StratumResult(type, TwoByTwo.of(rows.filter { it.type == type }.map { it.exposed to it.outcome }))
        }
        val eligible = table.n >= MIN_NIGHTS && table.exposed >= MIN_ARM && table.unexposed >= MIN_ARM &&
            table.withOutcome >= MIN_OUTCOME && table.withoutOutcome >= MIN_OUTCOME
        val tables = strata.map { it.table }
        val rdMh = if (eligible) ExactStratifiedTest.riskDifference(tables) else null
        val p = if (rdMh != null) ExactStratifiedTest.pValue(tables) else 1.0
        val sign = if ((rdMh ?: 0.0) > 0) 1 else -1
        val strataCheck = rdMh != null && strata.all { s ->
            val rd = s.table.riskDifference
            s.table.exposed < STRATUM_MIN_ARM || s.table.unexposed < STRATUM_MIN_ARM || (rd != null && sign * rd > 0)
        }
        val half = rows.size / 2
        val older = TwoByTwo.of(rows.take(half).map { it.exposed to it.outcome }).riskDifference
        val newer = TwoByTwo.of(rows.drop(half).map { it.exposed to it.outcome }).riskDifference
        val splitHalf = rdMh != null && older != null && newer != null && sign * older > 0 && sign * newer > 0
        return HypothesisResult(
            hypothesis = hypothesis,
            table = table,
            strata = strata,
            eligible = eligible,
            riskDifferenceMh = rdMh,
            pValue = p,
            qValue = 1.0,
            tier = PatternTier.INSUFFICIENT,
            strataCheck = strataCheck,
            splitHalfCheck = splitHalf,
            lineage = lineage,
        )
    }

    private fun classify(result: HypothesisResult): HypothesisResult {
        val rd = result.riskDifferenceMh ?: return result.copy(tier = PatternTier.INSUFFICIENT)
        val checks = result.strataCheck && result.splitHalfCheck
        val enoughNights = result.table.n >= STRONG_MIN_NIGHTS
        val tier = when {
            result.qValue <= STRONG_Q && abs(rd) >= STRONG_RD && enoughNights && checks -> PatternTier.STRONG
            result.qValue <= MODERATE_Q && abs(rd) >= MODERATE_RD && checks -> PatternTier.MODERATE
            abs(rd) >= MODERATE_RD -> PatternTier.WEAK
            else -> PatternTier.NONE
        }
        return result.copy(tier = tier)
    }
}
