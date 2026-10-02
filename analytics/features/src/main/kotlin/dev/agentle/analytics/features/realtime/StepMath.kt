package dev.agentle.analytics.features.realtime

import dev.agentle.core.time.ClosedOpenRange
import java.math.BigInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

/** An exact, non-negative number of steps as a reduced fraction (R10 §5.4 F: exact proration, one floor at the end). */
internal class StepSum private constructor(private val num: BigInteger, private val den: BigInteger) : Comparable<StepSum> {
    /** Adds `count * part / whole`. */
    fun plus(count: Long, part: Long, whole: Long): StepSum {
        val share = BigInteger.valueOf(count) * BigInteger.valueOf(part)
        return reduced(num * BigInteger.valueOf(whole) + share * den, den * BigInteger.valueOf(whole))
    }

    operator fun plus(other: StepSum): StepSum = reduced(num * other.den + other.num * den, den * other.den)

    override fun compareTo(other: StepSum): Int = (num * other.den).compareTo(other.num * den)

    fun floor(): Long = (num / den).toLong()

    /** True if the exact value is at least [value]. */
    fun atLeast(value: Long): Boolean = num >= den * BigInteger.valueOf(value)

    companion object {
        val ZERO: StepSum = StepSum(BigInteger.ZERO, BigInteger.ONE)

        /** `count * part / whole`. */
        fun of(count: Long, part: Long, whole: Long): StepSum = ZERO.plus(count, part, whole)

        private fun reduced(n: BigInteger, d: BigInteger): StepSum {
            val g = n.gcd(d) // d > 0, so g >= 1
            return StepSum(n / g, d / g)
        }
    }
}

/** The activity-level inputs of one 30-minute window (R10 §5.4 F). */
internal data class CadenceWindow(val observedMinutes: Int, val activeMinutes: Int, val steps: Long)

/**
 * Step arithmetic of R10 §5.4 F over a fused series: each minute from one source only, and inside that source the
 * intervals that overlap one another are never summed (one walk uploaded by two devices, REALTIME-FEATURES-R1-4).
 */
internal object StepMath {
    const val CADENCE_STEPS_PER_MINUTE: Long = 100
    const val ACTIVE_MINUTES_FOR_MVPA: Int = 10
    const val LIGHT_STEPS: Long = 300
    const val MIN_OBSERVED_MINUTES: Int = 24
    const val CADENCE_MINUTES: Int = 30

    /** The observed share a windowed step feature needs: 4/5, the 24 of 30 minutes of R10 §5.4 F. */
    private const val OBSERVED_NUMERATOR = 4L
    private const val OBSERVED_DENOMINATOR = 5L
    private const val SECONDS_PER_MINUTE = 60L

    /**
     * True if at least 80 % of [window] lies in fused segments, so some source observed it (R10 §5.4 F: 24 of 30
     * minutes; 48 of 60 by the same rule). Exact: compares nanoseconds.
     */
    fun mostlyObserved(series: FusedStepSeries, window: ClosedOpenRange): Boolean {
        val observed = series.segments.sumOf { it.range.intersect(window)?.duration?.inWholeNanoseconds ?: 0L }
        return observed * OBSERVED_DENOMINATOR >= window.duration.inWholeNanoseconds * OBSERVED_NUMERATOR
    }

    /** True if some segment has an interval with data inside [window] (a true zero counts; coverage alone does not). */
    fun hasData(series: FusedStepSeries, window: ClosedOpenRange): Boolean = series.segments.any { segment ->
        val part = segment.range.intersect(window)
        part != null && segment.intervals.any { overlapNanos(it, part) != null }
    }

    /**
     * Exact steps in [window], minute by minute. An interval adds `count * overlap / length` to each minute it meets,
     * a point record its count to its minute. In each minute, the owning source's intervals that overlap one another
     * are not summed: the minute counts the largest total of intervals that do not overlap one another, which is the
     * largest share when they all overlap and the plain sum when none do (REALTIME-FEATURES-R1-4). The result is never
     * more than the sum of the intervals and never less than any one device's records alone.
     */
    fun prorated(series: FusedStepSeries, window: ClosedOpenRange): StepSum = series.segments.fold(StepSum.ZERO) { sum, segment ->
        val part = segment.range.intersect(window)
        if (part == null) sum else sum + segmentSteps(segment.intervals, part)
    }

    /**
     * The 30 whole minutes `[minutesStart, minutesStart + 30 min)`: observed minutes (in a segment, so some source had
     * coverage), minutes with at least 100 steps, and the floored step total.
     */
    fun cadence(series: FusedStepSeries, minutesStart: Instant): CadenceWindow {
        var observed = 0
        var active = 0
        for (k in 0 until CADENCE_MINUTES) {
            val minute = ClosedOpenRange(minutesStart + k.minutes, minutesStart + (k + 1).minutes)
            if (series.segments.any { it.range.overlaps(minute) }) observed++
            if (prorated(series, minute).atLeast(CADENCE_STEPS_PER_MINUTE)) active++
        }
        val window = ClosedOpenRange(minutesStart, minutesStart + CADENCE_MINUTES.minutes)
        return CadenceWindow(observed, active, prorated(series, window).floor())
    }

    /** `M >= 10` -> MODERATE_OR_VIGOROUS; else `S >= 300` -> LIGHT; else SEDENTARY. */
    fun level(window: CadenceWindow): String = when {
        window.activeMinutes >= ACTIVE_MINUTES_FOR_MVPA -> "MODERATE_OR_VIGOROUS"
        window.steps >= LIGHT_STEPS -> "LIGHT"
        else -> "SEDENTARY"
    }

    /** Overlap of [interval] with [window] in nanoseconds; 0 for a point record inside it; null when they do not meet. */
    fun overlapNanos(interval: StepInterval, window: ClosedOpenRange): Long? {
        if (interval.end == interval.start) return if (interval.start in window) 0L else null
        val start = maxOf(interval.start, window.start)
        val end = minOf(interval.end, window.end)
        return if (end > start) (end - start).inWholeNanoseconds else null
    }

    /** Steps of one source's [intervals] in [part], one minute (or the part of it inside [part]) at a time. */
    private fun segmentSteps(intervals: List<StepInterval>, part: ClosedOpenRange): StepSum {
        val byStart = intervals.sortedBy { it.start }
        val active = mutableListOf<StepInterval>()
        var next = 0
        var sum = StepSum.ZERO
        var from = part.start
        while (from < part.end) {
            val until = minOf(nextMinute(from), part.end)
            while (next < byStart.size && byStart[next].start < until) active += byStart[next++]
            active.removeAll { it.end < from || (it.end == from && it.end > it.start) }
            sum += minuteSteps(active, ClosedOpenRange(from, until))
            from = until
        }
        return sum
    }

    /** One interval's part of a minute: its clipped range (a point record occupies 1 ns) and its prorated steps. */
    private class Share(val start: Instant, val end: Instant, val steps: StepSum)

    private fun minuteSteps(candidates: List<StepInterval>, minute: ClosedOpenRange): StepSum {
        val shares = candidates.mapNotNull { interval ->
            overlapNanos(interval, minute)?.let { overlap ->
                if (interval.end == interval.start) {
                    Share(interval.start, interval.start + 1.nanoseconds, StepSum.of(interval.count, 1, 1))
                } else {
                    val whole = (interval.end - interval.start).inWholeNanoseconds
                    Share(maxOf(interval.start, minute.start), minOf(interval.end, minute.end), StepSum.of(interval.count, overlap, whole))
                }
            }
        }
        return when (shares.size) {
            0 -> StepSum.ZERO
            1 -> shares[0].steps
            else -> largestDisjointTotal(shares)
        }
    }

    /** Weighted interval scheduling: the largest total of shares whose ranges do not overlap one another. */
    private fun largestDisjointTotal(shares: List<Share>): StepSum {
        val byEnd = shares.sortedBy { it.end }
        val best = ArrayList<StepSum>(byEnd.size + 1)
        best += StepSum.ZERO
        for (i in byEnd.indices) {
            val share = byEnd[i]
            // Ends are sorted, so the shares that end by this one's start are a prefix of the list.
            val compatible = byEnd.subList(0, i).indexOfLast { it.end <= share.start } + 1
            best += maxOf(best[i], share.steps + best[compatible])
        }
        return best.last()
    }

    private fun nextMinute(at: Instant): Instant =
        Instant.fromEpochSeconds((Math.floorDiv(at.epochSeconds, SECONDS_PER_MINUTE) + 1) * SECONDS_PER_MINUTE)
}
