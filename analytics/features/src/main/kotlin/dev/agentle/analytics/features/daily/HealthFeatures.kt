package dev.agentle.analytics.features.daily

import dev.agentle.analytics.features.MissingReason
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.HeartRatePayload
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.SleepSessionPayload
import dev.agentle.core.model.SleepStage
import dev.agentle.core.model.SleepStageKind
import dev.agentle.core.model.SourceFamily
import dev.agentle.core.model.family
import dev.agentle.core.time.ClosedOpenRange
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val SOURCE_ORDER: Comparator<DataSourceId> = compareBy { it.value }

/**
 * Exercise features of the local calendar day from the fused exercise sessions: overlapping sessions of two sources
 * count once (minute fusion), and touching selected parts form one exercise period. Inside a connected source's
 * asserted coverage a day without sessions is a true 0 ([NullSemantics.SOURCE_COVERAGE]).
 */
internal class ExerciseFeatures(private val day: DayContext) {
    suspend fun rows(): List<DailySummaryRow> {
        val window = day.window(DailyWindow.CALENDAR_DAY)
        val hull = day.hullOf(DailyWindow.CALENDAR_DAY)
        val pieces = day.fused(MetricFamily.EXERCISE, ClosedOpenRange(hull.start - SESSION_LOOKBACK, hull.end))
        val periods = mergeRanges(pieces.flatMap { it.selected })
        val used = pieces.filter {
            IntervalMath.clip(it.selected, window).isNotEmpty()
        }.mapTo(sortedSetOf(SOURCE_ORDER)) { it.event.source }
        val minutes = IntervalMath.unionMinutes(periods, window)
        val values = mapOf(
            "exercise_minutes" to minutes.toDouble(),
            "exercise_sessions" to periods.count { IntervalMath.contains(window, it.start) }.toDouble(),
            "exercise_day" to if (minutes >= day.config.minExerciseMinutes) 1.0 else 0.0,
        )
        if (used.isNotEmpty()) return values.map { (id, value) -> day.rows.sourced(feature(id), id, value, used) }
        val claims = day.sourceCoverage(MetricFamily.EXERCISE, DailyWindow.CALENDAR_DAY)
        val windowMillis = IntervalMath.windowMillis(window)
        val covering = claims.filterValues { IntervalMath.unionMillis(it, window) >= windowMillis }.keys
        return values.keys.map { id ->
            when {
                covering.isNotEmpty() -> day.rows.sourced(feature(id), id, 0.0, covering.toSortedSet(SOURCE_ORDER))
                claims.isEmpty() -> day.rows.noSource(feature(id), reason = MissingReason.SOURCE_DISCONNECTED)
                else -> day.rows.noSource(feature(id), reason = MissingReason.NOT_SYNCED, waitInGrace = true)
            }
        }
    }

    private companion object {
        /** Sessions that started the day before still add their minutes after midnight. */
        val SESSION_LOOKBACK = 24.hours
    }
}

/**
 * Heart features: the resting heart rate of the civil date (a daily value from the canonical source, never derived from
 * instants, docs/research/10 §5.4 H) and the mean and maximum of the day's fused heart-rate samples. Values outside the
 * valid ranges are `INVALID_VALUE`.
 */
internal class HeartFeatures(private val day: DayContext) {
    suspend fun rows(): List<DailySummaryRow> = listOf(restingRow()) + sampleRows()

    private suspend fun restingRow(): DailySummaryRow {
        val def = feature("resting_hr")
        val values = day.civil(MetricFamily.RESTING_HEART_RATE).filter { day.policy.isEligible(MetricFamily.RESTING_HEART_RATE, it.source) }
        val source = day.policy.choose(MetricFamily.RESTING_HEART_RATE, values.map { it.source }) ?: return day.rows.noSource(def)
        val value = values.filter { it.source == source }.maxOf { it.value }
        return day.rows.sourced(def, def.id, value.takeIf { it in day.config.restingHeartRateValid }, setOf(source))
    }

    private suspend fun sampleRows(): List<DailySummaryRow> {
        val window = day.window(DailyWindow.CALENDAR_DAY)
        val pieces = day.fused(MetricFamily.HEART_RATE, day.hullOf(DailyWindow.CALENDAR_DAY))
            .filter { StepFeatures.partsIn(it, window).isNotEmpty() && it.event.payload is HeartRatePayload }
        val meanDef = feature("hr_mean")
        val maxDef = feature("hr_max")
        if (pieces.isEmpty()) return listOf(day.rows.noSource(meanDef), day.rows.noSource(maxDef))
        val sources = pieces.mapTo(sortedSetOf(SOURCE_ORDER)) { it.event.source }
        val range = day.config.heartRateValid
        val valid = pieces.map { it.event.payload as HeartRatePayload }.filter { it.bpm in range }
        val mean = valid.map { it.bpm }.takeIf { it.isNotEmpty() }?.average()
        val max = valid.maxOfOrNull { sample -> maxOf(sample.bpm, sample.maxBpm?.takeIf { it in range } ?: sample.bpm) }
        return listOf(day.rows.sourced(meanDef, meanDef.id, mean, sources), day.rows.sourced(maxDef, maxDef.id, max, sources))
    }
}

/**
 * Sleep of the night that starts on `d` (docs/research/10 §5.4 G): candidate sessions end on local date `d + 1` read in
 * their own end offset; naps are excluded (the upstream nap flag, or a Health Connect session shorter than
 * [DailyFeatureConfig.healthConnectNapMaxDuration]). The night's source is the highest-priority source with a
 * candidate; its main session is the one marked main, else the longest (ties: earliest start). Times are read in the
 * record's own offsets, so travel and DST never shift history.
 */
internal class SleepFeatures(private val day: DayContext) {
    suspend fun rows(): List<DailySummaryRow> {
        val wakeDate = day.date.plus(DatePeriod(days = 1))
        val range = ClosedOpenRange(
            wakeDate.atStartOfDayIn(TimeZone.UTC) - MAX_OFFSET - 1.minutes,
            wakeDate.plus(DatePeriod(days = 1)).atStartOfDayIn(TimeZone.UTC) + MAX_OFFSET,
        )
        val candidates = day.events(DailyGroupQueries.SLEEP, range).filter { e ->
            e.payload is SleepSessionPayload && day.policy.isEligible(MetricFamily.SLEEP, e.source) &&
                e.interval().duration.isPositive() && localDateAt(e.interval().end, endOffset(e)) == wakeDate && !isNap(e)
        }
        val source =
            day.policy.choose(MetricFamily.SLEEP, candidates.map { it.source }) ?: return IDS.map { day.rows.noSource(feature(it)) }
        val main = candidates.filter { it.source == source }.sortedWith(MAIN_ORDER).first()
        val p = main.payload as SleepSessionPayload
        val session = main.interval()
        val asleep = asleepMinutes(p, session)
        val onset = asleepStages(p, session).minOfOrNull { it.start } ?: session.start
        val onsetOffset = p.stages.filter { isAsleep(it.stage) }.minByOrNull { it.startEpochMs }?.startUtcOffsetSeconds
            ?: p.startUtcOffsetSeconds ?: offsetAt(main, onset)
        val endOffset = endOffset(main)
        val mid = onset + ((session.end - onset).inWholeMilliseconds / 2).milliseconds
        val midOffset = if (onsetOffset == endOffset) endOffset else offsetAt(main, mid)
        val values = mapOf(
            "sleep_minutes" to asleep.takeIf { it in 0..MINUTES_PER_DAY.toLong() }?.toDouble(),
            "bedtime" to nightMinute(minuteOfDayAt(onset, onsetOffset)).toDouble(),
            "wake_time" to minuteOfDayAt(session.end, endOffset).toDouble(),
            "sleep_midpoint" to nightMinute(minuteOfDayAt(mid, midOffset)).toDouble(),
        )
        return values.map { (id, value) -> day.rows.sourced(feature(id), id, value, setOf(source), pending = p.processed == false) }
    }

    private fun isNap(e: PersonalEvent): Boolean {
        val p = e.payload as SleepSessionPayload
        return p.isNap || (e.source.family == SourceFamily.HEALTH_CONNECT && e.interval().duration < day.config.healthConnectNapMaxDuration)
    }

    /** Upstream minutes asleep; else the asleep-type stages; else the session minus out-of-bed segments; floored. */
    private fun asleepMinutes(p: SleepSessionPayload, session: ClosedOpenRange): Long {
        p.minutesAsleep?.let { return it }
        val millis = if (p.stages.isNotEmpty()) {
            IntervalMath.totalMillis(asleepStages(p, session))
        } else {
            session.duration.inWholeMilliseconds - IntervalMath.unionMillis(p.outOfBedSegments.mapNotNull { rangeOf(it) }, listOf(session))
        }
        return millis / IntervalMath.MS_PER_MINUTE
    }

    private fun asleepStages(p: SleepSessionPayload, session: ClosedOpenRange): List<ClosedOpenRange> =
        IntervalMath.clip(p.stages.filter { isAsleep(it.stage) }.mapNotNull { rangeOf(it) }, listOf(session))

    private fun endOffset(e: PersonalEvent): Int = (e.payload as SleepSessionPayload).endUtcOffsetSeconds ?: offsetAt(e, e.interval().end)

    private fun offsetAt(e: PersonalEvent, at: Instant): Int = zoneOf(e).offsetAt(at).totalSeconds

    private companion object {
        val IDS = listOf("sleep_minutes", "bedtime", "wake_time", "sleep_midpoint")

        /** Local offsets lie within UTC-12 to UTC+14. */
        val MAX_OFFSET = 14.hours

        /** Asleep stage types (docs/research/10 §5.4 G); `RESTLESS` is excluded (conservative, UNVERIFIED upstream). */
        val ASLEEP = setOf(SleepStageKind.LIGHT, SleepStageKind.DEEP, SleepStageKind.REM, SleepStageKind.ASLEEP_UNSPECIFIED)

        val MAIN_ORDER: Comparator<PersonalEvent> = compareBy<PersonalEvent>(
            { !(it.payload as SleepSessionPayload).isMainSleep },
            { -it.interval().duration.inWholeMilliseconds },
            { it.startTime },
            { it.dedupKey },
        )

        fun isAsleep(kind: SleepStageKind): Boolean = kind in ASLEEP

        fun rangeOf(stage: SleepStage): ClosedOpenRange? = if (stage.endEpochMs > stage.startEpochMs) {
            ClosedOpenRange(Instant.fromEpochMilliseconds(stage.startEpochMs), Instant.fromEpochMilliseconds(stage.endEpochMs))
        } else {
            null
        }
    }
}

private fun feature(id: String): DailyFeatureDefinition = DailyFeatureCalculator.feature(id)
