package dev.agentle.analytics.features.realtime.testing

import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.realtime.DailyMetric
import dev.agentle.analytics.features.realtime.DailySeries
import dev.agentle.analytics.features.realtime.DailySummaryPort
import dev.agentle.analytics.features.realtime.FusedStepSeries
import dev.agentle.analytics.features.realtime.HealthMetric
import dev.agentle.analytics.features.realtime.HeartRateSample
import dev.agentle.analytics.features.realtime.HeartRateSamplePort
import dev.agentle.analytics.features.realtime.HeartRateSeries
import dev.agentle.analytics.features.realtime.InputAnswer
import dev.agentle.analytics.features.realtime.SleepSeries
import dev.agentle.analytics.features.realtime.SleepSessionPort
import dev.agentle.analytics.features.realtime.SleepSessionRecord
import dev.agentle.analytics.features.realtime.SourceCoveragePort
import dev.agentle.analytics.features.realtime.StepFusion
import dev.agentle.analytics.features.realtime.StepInterval
import dev.agentle.analytics.features.realtime.StepSeriesPort
import dev.agentle.core.common.Outcome
import dev.agentle.core.time.ClosedOpenRange
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/** Default source ids of the in-memory health ports (the `DataSourceId` values the connectors write). */
public object HealthSources {
    public const val GOOGLE_HEALTH_STEPS: String = "googlehealth.steps"
    public const val HEALTH_CONNECT_STEPS: String = "healthconnect.steps"
    public const val PHONE_STEPS: String = "android.steps"
    public const val GOOGLE_HEALTH_SLEEP: String = "googlehealth.sleep"
    public const val GOOGLE_HEALTH_RESTING_HEART_RATE: String = "googlehealth.resting_heart_rate"
    public const val GOOGLE_HEALTH_HEART_RATE: String = "googlehealth.heart_rate"
}

/** Test support: `source_coverage(source, metric, coverageThrough)` in memory. */
public class InMemorySourceCoverage(log: ReadLog = ReadLog()) :
    InMemoryPort(log),
    SourceCoveragePort {
    private val rows = mutableMapOf<Pair<String, HealthMetric>, Instant>()

    /** Sets (or, with null, clears) what [source] asserts for [metric]. */
    public fun set(source: String, metric: HealthMetric, through: Instant?) {
        if (through == null) rows.remove(source to metric) else rows[source to metric] = through
    }

    /** The stored value, without recording a read (the in-memory step fusion uses it). */
    public fun through(source: String, metric: HealthMetric): Instant? = rows[source to metric]

    override suspend fun coverageThrough(source: String, metric: HealthMetric): Outcome<Instant?> =
        plain("sourceCoverage.coverageThrough") { rows[source to metric] }
}

/** One step source of [InMemoryStepSeries], in priority order. See [StepFusion.Source.reportsTrueZeros]. */
public data class StepSourceSpec(val id: String, val reportsTrueZeros: Boolean)

/**
 * Test support: step intervals per source, fused per minute with [StepFusion] over [sources] (canonical first), each
 * source's coverage read from [coverage]. Without any source the port answers `Unavailable(NO_DATA)` (R03 §5.3).
 */
public class InMemoryStepSeries(private val coverage: InMemorySourceCoverage, log: ReadLog = ReadLog()) :
    InMemoryPort(log),
    StepSeriesPort {
    /** The connected step sources, canonical first. Default: the Google Health API, Health Connect, the phone. */
    public val sources: MutableList<StepSourceSpec> = mutableListOf(
        StepSourceSpec(HealthSources.GOOGLE_HEALTH_STEPS, reportsTrueZeros = true),
        StepSourceSpec(HealthSources.HEALTH_CONNECT_STEPS, reportsTrueZeros = false),
        StepSourceSpec(HealthSources.PHONE_STEPS, reportsTrueZeros = false),
    )

    private val rows = mutableMapOf<String, MutableList<StepInterval>>()

    /** Stores [intervals] for [source]. */
    public fun add(source: String, vararg intervals: StepInterval) {
        rows.getOrPut(source) { mutableListOf() } += intervals
    }

    /** Stores [intervals] for [source]. */
    public fun addAll(source: String, intervals: Collection<StepInterval>) {
        rows.getOrPut(source) { mutableListOf() } += intervals
    }

    /** The stored intervals of [source]. */
    public fun intervalsOf(source: String): List<StepInterval> = rows[source].orEmpty().toList()

    override suspend fun fusedMinuteSeries(window: ClosedOpenRange): Outcome<InputAnswer<FusedStepSeries>> = answer(PORT) {
        if (sources.isEmpty()) {
            InputAnswer.Unavailable(MissingReason.NO_DATA)
        } else {
            val inputs = sources.map { spec ->
                StepFusion.Source(spec.id, spec.reportsTrueZeros, rows[spec.id].orEmpty(), coverage.through(spec.id, HealthMetric.STEPS))
            }
            InputAnswer.Available(StepFusion.fuse(window, inputs))
        }
    }

    private companion object {
        const val PORT = "steps.fusedMinuteSeries"
    }
}

/** Test support: sleep sessions of the canonical sleep source [source]. */
public class InMemorySleepSessions(public var source: String = HealthSources.GOOGLE_HEALTH_SLEEP, log: ReadLog = ReadLog()) :
    InMemoryPort(log),
    SleepSessionPort {
    public val rows: MutableList<SleepSessionRecord> = mutableListOf()

    override suspend fun sessionsEnding(endRange: ClosedOpenRange): Outcome<InputAnswer<SleepSeries>> =
        answer("sleep.sessionsEnding") { InputAnswer.Available(SleepSeries(source, rows.filter { it.end in endRange })) }
}

/** Test support: daily values by civil date of the canonical source [source]. */
public class InMemoryDailySummaries(public var source: String = HealthSources.GOOGLE_HEALTH_RESTING_HEART_RATE, log: ReadLog = ReadLog()) :
    InMemoryPort(log),
    DailySummaryPort {
    private val rows = mutableMapOf<DailyMetric, MutableMap<LocalDate, Long>>()

    /** Stores [value] for [metric] on [date]. */
    public fun put(metric: DailyMetric, date: LocalDate, value: Long) {
        rows.getOrPut(metric) { mutableMapOf() }[date] = value
    }

    /** Removes the value of [metric] on [date]. */
    public fun remove(metric: DailyMetric, date: LocalDate) {
        rows[metric]?.remove(date)
    }

    override suspend fun values(metric: DailyMetric, first: LocalDate, last: LocalDate): Outcome<InputAnswer<DailySeries>> =
        answer("dailySummaries.values") {
            InputAnswer.Available(DailySeries(source, rows[metric].orEmpty().filterKeys { it >= first && it <= last }))
        }
}

/** Test support: heart-rate samples of the canonical source [source]. */
public class InMemoryHeartRate(public var source: String = HealthSources.GOOGLE_HEALTH_HEART_RATE, log: ReadLog = ReadLog()) :
    InMemoryPort(log),
    HeartRateSamplePort {
    public val rows: MutableList<HeartRateSample> = mutableListOf()

    override suspend fun samples(range: ClosedOpenRange): Outcome<InputAnswer<HeartRateSeries>> =
        answer("heartRate.samples") { InputAnswer.Available(HeartRateSeries(source, rows.filter { it.at in range }.sortedBy { it.at })) }
}
