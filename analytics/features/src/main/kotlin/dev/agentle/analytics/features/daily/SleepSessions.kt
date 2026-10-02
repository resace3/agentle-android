package dev.agentle.analytics.features.daily

import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.SleepSessionPayload
import dev.agentle.core.model.SleepStage
import dev.agentle.core.model.SleepStageKind
import dev.agentle.core.model.SourceFamily
import dev.agentle.core.model.family
import dev.agentle.core.time.ClosedOpenRange
import kotlinx.datetime.LocalDate
import kotlinx.datetime.offsetAt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * What one sleep session says (docs/research/10 §5.4 G), read in the record's own offsets so travel and DST never shift
 * history.
 *
 * @property wakeDate the local date of the session end in its end offset (the night is the date before).
 * @property asleepMinutes upstream minutes asleep, else the asleep-type stages, else the session minus out-of-bed
 *   segments; floored.
 * @property bedtime sleep onset (first asleep stage, else the session start) on the night clock (minutes after noon).
 * @property wakeTime the session end as a minute of the day.
 * @property midpoint midpoint between onset and the session end on the night clock.
 * @property processed the upstream processing flag (`false`: stages may still change).
 */
public data class SleepReading(
    val session: ClosedOpenRange,
    val wakeDate: LocalDate,
    val asleepMinutes: Long,
    val bedtime: Int,
    val wakeTime: Int,
    val midpoint: Int,
    val processed: Boolean?,
)

/** Shared sleep-session rules of the daily sleep features and the trial outcome metrics. */
public object SleepSessions {
    /** Asleep stage types; `RESTLESS` is excluded (conservative, UNVERIFIED upstream). */
    public val ASLEEP: Set<SleepStageKind> =
        setOf(SleepStageKind.LIGHT, SleepStageKind.DEEP, SleepStageKind.REM, SleepStageKind.ASLEEP_UNSPECIFIED)

    /** Main session first: marked main, then the longest, then the earliest start. */
    public val MAIN_ORDER: Comparator<PersonalEvent> = compareBy<PersonalEvent>(
        { !(it.payload as SleepSessionPayload).isMainSleep },
        { -it.interval().duration.inWholeMilliseconds },
        { it.startTime },
        { it.dedupKey },
    )

    /** Whether [event] is a sleep session with a positive length. */
    public fun isSession(event: PersonalEvent): Boolean = event.payload is SleepSessionPayload && event.interval().duration.isPositive()

    /** A nap: the upstream nap flag, or a Health Connect session shorter than [healthConnectNapMax] (no nap flag there). */
    public fun isNap(event: PersonalEvent, healthConnectNapMax: Duration): Boolean {
        val p = event.payload as SleepSessionPayload
        return p.isNap || (event.source.family == SourceFamily.HEALTH_CONNECT && event.interval().duration < healthConnectNapMax)
    }

    /** The local date of the session end in its own end offset. */
    public fun wakeDate(event: PersonalEvent): LocalDate = localDateAt(event.interval().end, endOffset(event))

    /** Reads [event], which must be a sleep session. */
    public fun read(event: PersonalEvent): SleepReading {
        val p = event.payload as SleepSessionPayload
        val session = event.interval()
        val onset = asleepStages(p, session).minOfOrNull { it.start } ?: session.start
        val onsetOffset = p.stages.filter { it.stage in ASLEEP }.minByOrNull { it.startEpochMs }?.startUtcOffsetSeconds
            ?: p.startUtcOffsetSeconds ?: offsetAt(event, onset)
        val endOffset = endOffset(event)
        val mid = onset + ((session.end - onset).inWholeMilliseconds / 2).milliseconds
        val midOffset = if (onsetOffset == endOffset) endOffset else offsetAt(event, mid)
        return SleepReading(
            session = session,
            wakeDate = localDateAt(session.end, endOffset),
            asleepMinutes = asleepMinutes(p, session),
            bedtime = nightMinute(minuteOfDayAt(onset, onsetOffset)),
            wakeTime = minuteOfDayAt(session.end, endOffset),
            midpoint = nightMinute(minuteOfDayAt(mid, midOffset)),
            processed = p.processed,
        )
    }

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
        IntervalMath.clip(p.stages.filter { it.stage in ASLEEP }.mapNotNull { rangeOf(it) }, listOf(session))

    private fun endOffset(e: PersonalEvent): Int = (e.payload as SleepSessionPayload).endUtcOffsetSeconds ?: offsetAt(e, e.interval().end)

    private fun offsetAt(e: PersonalEvent, at: Instant): Int = zoneOf(e).offsetAt(at).totalSeconds

    private fun rangeOf(stage: SleepStage): ClosedOpenRange? = if (stage.endEpochMs > stage.startEpochMs) {
        ClosedOpenRange(Instant.fromEpochMilliseconds(stage.startEpochMs), Instant.fromEpochMilliseconds(stage.endEpochMs))
    } else {
        null
    }
}
