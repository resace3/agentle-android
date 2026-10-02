package dev.agentle.analytics.features.realtime

import dev.agentle.core.model.SleepStageKind
import dev.agentle.core.time.ClosedOpenRange
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * "Last night" (R10 §5.4 G): the main sleep session whose end, read in the session's own end offset, falls on today's
 * local date. Naps are excluded: `nap == true`, or, for a source without a nap flag (null), a session shorter than
 * 3 hours. The main session is one flagged `mainSleep` if any (longest of them), else the longest candidate; ties go to
 * the earliest start.
 */
internal object SleepNight {
    val NAP_LIMIT: Duration = 3.hours

    private val ASLEEP = setOf(SleepStageKind.LIGHT, SleepStageKind.DEEP, SleepStageKind.REM, SleepStageKind.ASLEEP_UNSPECIFIED)
    private val STAGED = ASLEEP + setOf(SleepStageKind.AWAKE, SleepStageKind.RESTLESS)
    private val EARLIEST_OFFSET = UtcOffset.parse("+14:00")
    private val LATEST_OFFSET = UtcOffset.parse("-12:00")

    /** Every end instant whose local date can be [date] in some UTC offset (-12:00..+14:00). */
    fun endRangeFor(date: LocalDate): ClosedOpenRange = ClosedOpenRange(
        LocalDateTime(date, LocalTime(0, 0)).toInstant(EARLIEST_OFFSET),
        LocalDateTime(date.plus(DatePeriod(days = 1)), LocalTime(0, 0)).toInstant(LATEST_OFFSET),
    )

    /** The main session of the night ending on [date], or null. */
    fun mainSession(sessions: List<SleepSessionRecord>, date: LocalDate): SleepSessionRecord? {
        val candidates = sessions.filter { it.end.toLocalDateTime(it.endOffset.asTimeZone()).date == date && !isNap(it) }
        val flagged = candidates.filter { it.mainSleep == true }
        val pool = flagged.ifEmpty { candidates }
        return pool.sortedWith(compareByDescending<SleepSessionRecord> { it.duration }.thenBy { it.start }).firstOrNull()
    }

    fun isNap(session: SleepSessionRecord): Boolean = session.nap ?: (session.duration < NAP_LIMIT)

    /**
     * Minutes asleep: the upstream summary; else the asleep stages (LIGHT, DEEP, REM, ASLEEP; RESTLESS and AWAKE
     * excluded); else the session minus its out-of-bed segments. Floored once.
     */
    fun minutesAsleep(session: SleepSessionRecord): Long {
        session.minutesAsleep?.let { return it }
        val staged = session.stages.any { it.kind in STAGED }
        val total = if (staged) {
            session.stages.filter { it.kind in ASLEEP }.fold(Duration.ZERO) { acc, s -> acc + (s.end - s.start) }
        } else {
            session.duration - session.stages.filter { it.kind == SleepStageKind.OUT_OF_BED }
                .fold(Duration.ZERO) { acc, s -> acc + (s.end - s.start) }
        }
        return total.inWholeMinutes
    }

    /** Minute of day of the first asleep stage (else the session start) in the session's own start offset. */
    fun bedtimeMinute(session: SleepSessionRecord): Int {
        val fellAsleep: Instant = session.stages.filter { it.kind in ASLEEP }.minOfOrNull { it.start } ?: session.start
        return minuteOfDay(fellAsleep, session.startOffset)
    }

    /** Minute of day of the session end in its own end offset. */
    fun wakeMinute(session: SleepSessionRecord): Int = minuteOfDay(session.end, session.endOffset)

    private fun minuteOfDay(at: Instant, offset: UtcOffset): Int {
        val time = at.toLocalDateTime(offset.asTimeZone()).time
        return time.hour * MINUTES_PER_HOUR + time.minute
    }

    private const val MINUTES_PER_HOUR = 60
}
