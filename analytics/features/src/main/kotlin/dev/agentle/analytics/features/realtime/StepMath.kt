package dev.agentle.analytics.features.realtime

import dev.agentle.core.time.ClosedOpenRange
import java.math.BigInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** An exact, non-negative number of steps as a reduced fraction (R10 §5.4 F: exact proration, one floor at the end). */
internal class StepSum private constructor(private val num: BigInteger, private val den: BigInteger) {
    /** Adds `count * part / whole`. */
    fun plus(count: Long, part: Long, whole: Long): StepSum {
        val n = num * BigInteger.valueOf(whole) + BigInteger.valueOf(count) * BigInteger.valueOf(part) * den
        val d = den * BigInteger.valueOf(whole)
        val g = n.gcd(d) // d > 0, so g >= 1
        return StepSum(n / g, d / g)
    }

    fun floor(): Long = (num / den).toLong()

    /** True if the exact value is at least [value]. */
    fun atLeast(value: Long): Boolean = num >= den * BigInteger.valueOf(value)

    companion object {
        val ZERO: StepSum = StepSum(BigInteger.ZERO, BigInteger.ONE)
    }
}

/** The activity-level inputs of one 30-minute window (R10 §5.4 F). */
internal data class CadenceWindow(val observedMinutes: Int, val activeMinutes: Int, val steps: Long)

/** Step arithmetic of R10 §5.4 F over a fused series (each minute from one source only). */
internal object StepMath {
    const val CADENCE_STEPS_PER_MINUTE: Long = 100
    const val ACTIVE_MINUTES_FOR_MVPA: Int = 10
    const val LIGHT_STEPS: Long = 300
    const val MIN_OBSERVED_MINUTES: Int = 24
    const val CADENCE_MINUTES: Int = 30

    /** The observed share a windowed step feature needs: 4/5, the 24 of 30 minutes of R10 §5.4 F. */
    private const val OBSERVED_NUMERATOR = 4L
    private const val OBSERVED_DENOMINATOR = 5L

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

    /** Exact steps in [window]: in each segment, every overlapping interval adds `count * overlap / length`. */
    fun prorated(series: FusedStepSeries, window: ClosedOpenRange): StepSum = series.segments.fold(StepSum.ZERO) { sum, segment ->
        val part = segment.range.intersect(window)
        if (part == null) sum else segment.intervals.fold(sum) { acc, interval -> addProrated(acc, interval, part) }
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

    private fun addProrated(sum: StepSum, interval: StepInterval, window: ClosedOpenRange): StepSum {
        val overlap = overlapNanos(interval, window) ?: return sum
        return if (interval.end == interval.start) {
            sum.plus(interval.count, 1, 1)
        } else {
            sum.plus(interval.count, overlap, (interval.end - interval.start).inWholeNanoseconds)
        }
    }
}
