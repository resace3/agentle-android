package dev.agentle.analytics.features.realtime

import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.Quality
import dev.agentle.analytics.features.realtime.testing.HealthSources
import dev.agentle.analytics.features.realtime.testing.StepSourceSpec
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Seeded property tests of the step features (R10 §5.4 F, database-sync-02, jitai-correctness-03): random sources,
 * intervals (point records, true zeros, intervals across the day start and past `t`) and coverage, checked against an
 * independent per-minute oracle with exact fractions. Every step value is the floor of the exact fused sum, and the
 * freshness, provisional quality and missing reasons follow the same inputs.
 */
class StepsPropertyTest {
    private val catalog = listOf(
        StepSourceSpec(HealthSources.GOOGLE_HEALTH_STEPS, reportsTrueZeros = true),
        StepSourceSpec(HealthSources.HEALTH_CONNECT_STEPS, reportsTrueZeros = false),
        StepSourceSpec(HealthSources.PHONE_STEPS, reportsTrueZeros = false),
    )

    /** How often each kind of result occurred, so the property cannot pass on one kind only. */
    private val seen = mutableMapOf<String, Int>()

    @Test
    fun `step features over random sources are the floor of the exact fused sum`() = runTest {
        for (seed in 1..SEEDS) check(seed)

        for (kind in listOf("known FINAL", "known PROVISIONAL", "stale", "missing NO_DATA", "missing NOT_SYNCED")) {
            assertWithMessage(kind).that(seen[kind] ?: 0).isAtLeast(MIN_CASES)
        }
    }

    private fun kindOf(value: FeatureValue): String = when (value) {
        is FeatureValue.Known -> "known ${value.quality}"
        is FeatureValue.Stale -> "stale"
        is FeatureValue.Missing -> "missing ${value.reason}"
    }

    private suspend fun check(seed: Int) {
        val rng = Random(seed)
        val f = RealtimeFixture(start = "2026-10-01T17:00")
        f.clock.advanceBy(rng.nextLong(0, 3_600_000).milliseconds)
        val t = f.now
        val dayStart = f.local("2026-10-01T00:00")
        val chosen = catalog.filter { rng.nextInt(3) > 0 }.ifEmpty { listOf(catalog.random(rng)) }
        f.inputs.steps.sources.clear()
        f.inputs.steps.sources += chosen
        val sources = chosen.map { spec ->
            val intervals = randomIntervals(rng, dayStart - 2.hours, t + 30.minutes, recent = t - 90.minutes)
            f.inputs.steps.addAll(spec.id, intervals)
            val through = if (rng.nextInt(5) == 0) null else t - rng.nextLong(-10 * 60_000L, 90 * 60_000L).milliseconds
            f.inputs.sourceCoverage.set(spec.id, HealthMetric.STEPS, through)
            Source(spec.reportsTrueZeros, intervals, through)
        }

        val cases = listOf(
            Triple("steps_today", dayStart, 30.minutes),
            Triple("steps_last_60m", t - 60.minutes, 20.minutes),
            Triple("steps_last_30m", t - 30.minutes, 20.minutes),
        )
        for ((id, start, maxLag) in cases) {
            val expected = expected(sources, id, start, t, maxLag)
            assertWithMessage("seed $seed $id").that(f.value(id)).isEqualTo(expected)
            seen.merge(kindOf(expected), 1, Int::plus)
        }
    }

    private class Source(val trueZeros: Boolean, val intervals: List<StepInterval>, val through: Instant?)

    /** Up to 24 intervals in `[from, until)`, about half of them starting after [recent] so short windows have data. */
    private fun randomIntervals(rng: Random, from: Instant, until: Instant, recent: Instant): List<StepInterval> =
        List(rng.nextInt(0, 25)) {
            val lower = if (rng.nextBoolean()) recent else from
            val start = lower + rng.nextLong(0, (until - lower).inWholeMilliseconds).milliseconds
            val length = if (rng.nextInt(10) == 0) Duration.ZERO else rng.nextLong(1, 20 * 60_000L).milliseconds
            val count = if (rng.nextInt(7) == 0) 0L else rng.nextLong(1, 400)
            StepInterval(start, start + length, count)
        }

    /** The per-minute oracle: owner = first source with a value in the minute or, without true zeros, coverage of it. */
    private fun expected(sources: List<Source>, id: String, start: Instant, t: Instant, maxLag: Duration): FeatureValue {
        val through = sources.mapNotNull { it.through }.maxOrNull()
        if (through == null || (id == "steps_today" && through < start)) return FeatureValue.Missing(MissingReason.NOT_SYNCED)
        val fresh = through >= t - maxLag
        val canonicalThrough = sources.first().through
        var sum = Fraction.ZERO
        var hasData = false
        var provisional = false
        var minute = floorMinute(start)
        while (minute < t) {
            val minuteEnd = minute + 1.minutes
            val owner = sources.indexOfFirst { s ->
                s.intervals.any { touches(it, minute, minuteEnd) } || (!s.trueZeros && s.through != null && minuteEnd <= s.through)
            }
            if (owner >= 0) {
                if (owner > 0 && (canonicalThrough == null || minuteEnd > canonicalThrough)) provisional = true
                val lo = maxOf(minute, start)
                val hi = minOf(minuteEnd, t)
                for (interval in sources[owner].intervals) {
                    if (interval.start == interval.end) {
                        if (interval.start >= lo && interval.start < hi) {
                            sum = sum.plus(interval.count, 1, 1)
                            hasData = true
                        }
                    } else {
                        val overlap = minOf(interval.end, hi) - maxOf(interval.start, lo)
                        if (overlap.isPositive()) {
                            sum = sum.plus(interval.count, overlap.inWholeNanoseconds, (interval.end - interval.start).inWholeNanoseconds)
                            hasData = true
                        }
                    }
                }
            }
            minute = minuteEnd
        }
        if (!hasData) return FeatureValue.Missing(if (fresh) MissingReason.NO_DATA else MissingReason.NOT_SYNCED)
        val value = FeatureScalar.IntValue(sum.floor())
        return if (fresh) {
            FeatureValue.Known(value, minOf(through, t), if (provisional) Quality.PROVISIONAL else Quality.FINAL)
        } else {
            FeatureValue.Stale(value, through, MissingReason.NOT_SYNCED)
        }
    }

    private fun touches(interval: StepInterval, from: Instant, until: Instant): Boolean = if (interval.start == interval.end) {
        interval.start >= from && interval.start < until
    } else {
        interval.start < until && interval.end > from
    }

    private fun floorMinute(at: Instant): Instant = Instant.fromEpochSeconds(Math.floorDiv(at.epochSeconds, 60L) * 60L)

    /** An exact non-negative fraction. */
    private class Fraction(val numerator: BigInteger, val denominator: BigInteger) {
        fun plus(count: Long, part: Long, whole: Long): Fraction {
            val d = denominator * BigInteger.valueOf(whole)
            val n = numerator * BigInteger.valueOf(whole) + BigInteger.valueOf(count) * BigInteger.valueOf(part) * denominator
            val g = n.gcd(d)
            return Fraction(n / g, d / g)
        }

        fun floor(): Long = (numerator / denominator).toLong()

        companion object {
            val ZERO = Fraction(BigInteger.ZERO, BigInteger.ONE)
        }
    }

    private companion object {
        const val SEEDS = 300
        const val MIN_CASES = 5
    }
}
