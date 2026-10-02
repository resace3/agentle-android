package dev.agentle.analytics.features.daily

import dev.agentle.core.time.ClosedOpenRange
import java.math.BigInteger
import kotlin.time.Duration.Companion.milliseconds

/** Millisecond arithmetic over instant ranges used by the daily features. */
public object IntervalMath {
    public const val MS_PER_MINUTE: Long = 60_000L

    /** Total length in milliseconds of disjoint [ranges]. */
    public fun totalMillis(ranges: List<ClosedOpenRange>): Long = ranges.sumOf { it.duration.inWholeMilliseconds }

    /** The parts of [intervals] inside [window] (both may be unsorted), merged into disjoint ranges. */
    public fun clip(intervals: List<ClosedOpenRange>, window: List<ClosedOpenRange>): List<ClosedOpenRange> =
        mergeRanges(intervals.flatMap { interval -> window.mapNotNull { interval.intersect(it) } })

    /** Milliseconds of the union of [intervals] inside [window]: overlapping intervals are counted once. */
    public fun unionMillis(intervals: List<ClosedOpenRange>, window: List<ClosedOpenRange>): Long = totalMillis(clip(intervals, window))

    /** Whole minutes of the union of [intervals] inside [window], floored (44 min 59.999 s is 44). */
    public fun unionMinutes(intervals: List<ClosedOpenRange>, window: List<ClosedOpenRange>): Long =
        unionMillis(intervals, window) / MS_PER_MINUTE

    /**
     * Merges intervals separated by gaps of at most [maxGapMillis] (activity switches inside one app,
     * docs/research/10 §5.5 step 3).
     */
    public fun mergeGaps(intervals: List<ClosedOpenRange>, maxGapMillis: Long): List<ClosedOpenRange> {
        val merged = mergeRanges(intervals)
        if (merged.size < 2) return merged
        val out = ArrayList<ClosedOpenRange>(merged.size)
        var current = merged.first()
        for (next in merged.drop(1)) {
            current = if (next.start - current.end <= maxGapMillis.milliseconds) {
                ClosedOpenRange(current.start, next.end)
            } else {
                out += current
                next
            }
        }
        out += current
        return out
    }

    /** Whether [instant] lies in one of [window]. */
    public fun contains(window: List<ClosedOpenRange>, instant: kotlin.time.Instant): Boolean = window.any { instant in it }

    /** Total length of [window] in milliseconds. */
    public fun windowMillis(window: List<ClosedOpenRange>): Long = totalMillis(mergeRanges(window))

    /** [part] milliseconds as a fraction of [whole], clamped to 0..1 (0 when [whole] is 0). */
    public fun fraction(part: Long, whole: Long): Double = if (whole <= 0L) 0.0 else (part.toDouble() / whole).coerceIn(0.0, 1.0)
}

/**
 * An exact sum of prorated counts: an interval `[s, e)` with count `c` that overlaps a window by `o` milliseconds adds
 * `c * o / (e - s)` as an exact rational, and the result is floored once at the end (docs/research/10 §5.4 F). Whole
 * contributions stay in a `Long`; only partial overlaps touch `BigInteger`.
 */
public class ExactSum {
    private var whole: Long = 0L
    private var numerator: BigInteger = BigInteger.ZERO
    private var denominator: BigInteger = BigInteger.ONE

    /** Adds a whole count. */
    public fun add(count: Long) {
        whole += count
    }

    /** Adds `count * part / total` exactly (`0 <= part <= total`, `total > 0`). */
    public fun addProrated(count: Long, part: Long, total: Long) {
        require(total > 0 && part in 0..total) { "invalid proration $part/$total" }
        when (part) {
            total -> whole += count
            0L -> Unit
            else -> addFraction(BigInteger.valueOf(count).multiply(BigInteger.valueOf(part)), BigInteger.valueOf(total))
        }
    }

    /** Adds the share of [count] over [interval] that lies inside [window] (a zero-length interval counts if inside). */
    public fun addInterval(count: Long, interval: ClosedOpenRange, window: List<ClosedOpenRange>) {
        val total = interval.duration.inWholeMilliseconds
        if (total == 0L) {
            if (IntervalMath.contains(window, interval.start)) add(count)
            return
        }
        val overlap = IntervalMath.unionMillis(listOf(interval), window)
        addProrated(count, overlap, total)
    }

    private fun addFraction(n: BigInteger, d: BigInteger) {
        var num = numerator.multiply(d).add(n.multiply(denominator))
        var den = denominator.multiply(d)
        val gcd = num.gcd(den)
        if (gcd.signum() != 0 && gcd != BigInteger.ONE) {
            num = num.divide(gcd)
            den = den.divide(gcd)
        }
        val (q, r) = num.divideAndRemainder(den).let { it[0] to it[1] }
        whole += q.toLong()
        numerator = r
        denominator = den
    }

    /** The floored total. */
    public fun floor(): Long = whole

    /** The exact total as a double (for display; [floor] is the stored value). */
    public fun toDouble(): Double = whole + numerator.toDouble() / denominator.toDouble()

    /** Whether any fractional part remains. */
    public val hasFraction: Boolean get() = numerator.signum() != 0
}
