package dev.agentle.analytics.insights

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A 2x2 table of complete nights (docs/research/10 §14.4): [a] exposed with the outcome, [b] exposed without, [c]
 * unexposed with, [d] unexposed without.
 */
public data class TwoByTwo(val a: Int, val b: Int, val c: Int, val d: Int) {
    init {
        require(a >= 0 && b >= 0 && c >= 0 && d >= 0) { "counts must not be negative" }
    }

    public val n: Int get() = a + b + c + d
    public val exposed: Int get() = a + b
    public val unexposed: Int get() = c + d
    public val withOutcome: Int get() = a + c
    public val withoutOutcome: Int get() = b + d

    /** `p1 = a / (a + b)`; null without exposed nights. */
    public val rateExposed: Double? get() = if (exposed == 0) null else a.toDouble() / exposed

    /** `p0 = c / (c + d)`; null without unexposed nights. */
    public val rateUnexposed: Double? get() = if (unexposed == 0) null else c.toDouble() / unexposed

    /** `RD = p1 - p0`; null when either arm is empty. */
    public val riskDifference: Double? get() = rateExposed?.let { p1 -> rateUnexposed?.let { p0 -> p1 - p0 } }

    /** `p1 / p0`; null ("not defined") when `p0 = 0` or an arm is empty. */
    public val lift: Double? get() = rateExposed?.let { p1 -> rateUnexposed?.takeIf { it > 0.0 }?.let { p1 / it } }

    public operator fun plus(other: TwoByTwo): TwoByTwo = TwoByTwo(a + other.a, b + other.b, c + other.c, d + other.d)

    public companion object {
        public val EMPTY: TwoByTwo = TwoByTwo(0, 0, 0, 0)

        /** The table of [rows] (exposed, outcome). */
        public fun of(rows: List<Pair<Boolean, Boolean>>): TwoByTwo {
            var a = 0
            var b = 0
            var c = 0
            var d = 0
            for ((exposed, outcome) in rows) {
                when {
                    exposed && outcome -> a++
                    exposed -> b++
                    outcome -> c++
                    else -> d++
                }
            }
            return TwoByTwo(a, b, c, d)
        }
    }
}

/** A closed interval `[lower, upper]`. */
public data class Interval(val lower: Double, val upper: Double)

/** Interval estimates, multiplicity control and display rounding of docs/research/10 §14.4. */
public object PatternStatistics {
    /** The 97.5 % standard normal quantile. */
    public const val Z95: Double = 1.959963984540054

    /** The Wilson score interval of `x / n` ([0, 1] when `n = 0`). */
    public fun wilson(x: Int, n: Int, z: Double = Z95): Interval {
        require(n >= 0 && x in 0..n) { "invalid proportion" }
        if (n == 0) return Interval(0.0, 1.0)
        val p = x.toDouble() / n
        val den = 1 + z * z / n
        val centre = (p + z * z / (2 * n)) / den
        val half = z * sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / den
        return Interval(max(0.0, centre - half), min(1.0, centre + half))
    }

    /** The Newcombe hybrid score interval of the crude risk difference (built from Wilson intervals); null for an empty arm. */
    public fun newcombe(table: TwoByTwo): Interval? {
        val p1 = table.rateExposed ?: return null
        val p0 = table.rateUnexposed ?: return null
        val w1 = wilson(table.a, table.exposed)
        val w0 = wilson(table.c, table.unexposed)
        val rd = p1 - p0
        val lower = rd - sqrt((p1 - w1.lower) * (p1 - w1.lower) + (w0.upper - p0) * (w0.upper - p0))
        val upper = rd + sqrt((w1.upper - p1) * (w1.upper - p1) + (p0 - w0.lower) * (p0 - w0.lower))
        return Interval(lower, upper)
    }

    /** Benjamini-Hochberg q-values of [p] (step-up, monotone, at most 1), in the order of [p]. */
    public fun benjaminiHochberg(p: List<Double>): List<Double> {
        val m = p.size
        val order = p.indices.sortedBy { p[it] }
        val q = DoubleArray(m)
        var previous = 1.0
        for (rank in m downTo 1) {
            val i = order[rank - 1]
            val value = min(previous, p[i] * m / rank)
            q[i] = value
            previous = value
        }
        return q.toList()
    }

    /** [x] rounded to [digits] significant digits (half up), as stored in proposal evidence. */
    public fun significant(x: Double, digits: Int = EVIDENCE_DIGITS): Double {
        require(x.isFinite()) { "not a finite number" }
        if (x == 0.0) return 0.0
        return BigDecimal(x).round(MathContext(digits, RoundingMode.HALF_UP)).toDouble()
    }

    /** A rate as a whole percentage (half up), for display. */
    public fun percent(rate: Double): Int = BigDecimal(rate * PERCENT).setScale(0, RoundingMode.HALF_UP).toInt()

    public const val EVIDENCE_DIGITS: Int = 3
    private const val PERCENT = 100.0
}

/**
 * The exact within-night-type permutation test of the Mantel-Haenszel-type risk difference (docs/research/10 §14.4).
 *
 * With per-stratum weights `n1*n0/N`, `W * RD_MH = sum((a*N - Y*n1) / N)` over the strata that have both exposed and
 * unexposed nights, so `T = sum((a_s*N_s - Y_s*n1_s) * prod(N_t, t != s))` is an integer that orders the permutations
 * exactly like `abs(RD_MH)`. Under the null each stratum's exposed-with-outcome count is hypergeometric and the strata are
 * independent; the p-value sums the probability of every count vector with `abs(T) >= abs(T_observed)`. No randomness.
 */
public object ExactStratifiedTest {
    /** `RD_MH` of [strata]; null when no stratum has both exposed and unexposed nights. */
    public fun riskDifference(strata: List<TwoByTwo>): Double? {
        val informative = strata.filter { it.exposed > 0 && it.unexposed > 0 }
        if (informative.isEmpty()) return null
        val product = informative.fold(1L) { acc, s -> Math.multiplyExact(acc, s.n.toLong()) }
        var numerator = 0L
        var denominator = 0L
        for (s in informative) {
            val others = product / s.n
            numerator += Math.multiplyExact(s.a.toLong() * s.n - s.withOutcome.toLong() * s.exposed, others)
            denominator += Math.multiplyExact(s.exposed.toLong() * s.unexposed, others)
        }
        return numerator.toDouble() / denominator
    }

    /** The two-sided exact p-value; 1 when `RD_MH` is not defined. */
    public fun pValue(strata: List<TwoByTwo>): Double {
        val informative = strata.filter { it.exposed > 0 && it.unexposed > 0 }
        if (informative.isEmpty()) return 1.0
        val product = informative.fold(1L) { acc, s -> Math.multiplyExact(acc, s.n.toLong()) }
        val terms = informative.map { Term.of(it, product / it.n) }
        val observed = abs(terms.sumOf { it.observed })
        return min(1.0, tail(terms, 0, 0L, 1.0, observed))
    }

    private fun tail(terms: List<Term>, index: Int, partial: Long, probability: Double, observed: Long): Double {
        if (index == terms.size) return if (abs(partial) >= observed) probability else 0.0
        val term = terms[index]
        var sum = 0.0
        for (i in term.contributions.indices) {
            sum += tail(terms, index + 1, partial + term.contributions[i], probability * term.pmf[i], observed)
        }
        return sum
    }

    /** One stratum: the statistic contribution and null probability of every possible exposed-with-outcome count. */
    private class Term(val contributions: LongArray, val pmf: DoubleArray, val observed: Long) {
        companion object {
            fun of(s: TwoByTwo, others: Long): Term {
                val total = s.n
                val exposed = s.exposed
                val y = s.withOutcome
                val lo = max(0, exposed + y - total)
                val hi = min(exposed, y)
                fun contribution(k: Int): Long = Math.multiplyExact(k.toLong() * total - y.toLong() * exposed, others)
                val logDen = LogFactorials.logChoose(total, exposed)
                val pmf = DoubleArray(hi - lo + 1) { i ->
                    val k = lo + i
                    kotlin.math.exp(LogFactorials.logChoose(y, k) + LogFactorials.logChoose(total - y, exposed - k) - logDen)
                }
                return Term(LongArray(hi - lo + 1) { contribution(lo + it) }, pmf, contribution(s.a))
            }
        }
    }
}

/** `ln(k!)` from a table that grows on demand. */
internal object LogFactorials {
    @Volatile private var table: DoubleArray = build(INITIAL)

    fun logFactorial(k: Int): Double {
        require(k >= 0) { "negative factorial" }
        var current = table
        if (k >= current.size) {
            current = build(max(k + 1, current.size * 2))
            table = current
        }
        return current[k]
    }

    fun logChoose(n: Int, k: Int): Double = logFactorial(n) - logFactorial(k) - logFactorial(n - k)

    private fun build(size: Int): DoubleArray {
        val out = DoubleArray(size)
        for (i in 1 until size) out[i] = out[i - 1] + kotlin.math.ln(i.toDouble())
        return out
    }

    private const val INITIAL = 512
}
