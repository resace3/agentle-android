package dev.agentle.analytics.features.daily

import dev.agentle.core.time.ClosedOpenRange
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** A zone change: from [at] on, the user's local time is [zone]. */
public data class ZoneChange(val at: Instant, val zone: TimeZone)

/** A constant-zone piece of a range. */
public data class ZonePiece(val range: ClosedOpenRange, val zone: TimeZone)

/**
 * The user's time zone as a function of the instant (home, a trip, home again). Local dates are read through it, so
 * every instant belongs to exactly one local date: a trip never counts an hour twice or drops one, and DST days
 * naturally have 23 or 25 hours (docs/research/08 §6.1, docs/research/05 §5.10).
 */
public class ZoneTimeline(public val initialZone: TimeZone, changes: List<ZoneChange> = emptyList()) {
    public val changes: List<ZoneChange> = changes.sortedBy { it.at }

    /** The zone in force at [instant]. */
    public fun zoneAt(instant: Instant): TimeZone = changes.lastOrNull { it.at <= instant }?.zone ?: initialZone

    /** The constant-zone pieces of [range], in order. */
    public fun pieces(range: ClosedOpenRange): List<ZonePiece> {
        val cuts = changes.map { it.at }.filter { it > range.start && it < range.end }
        val bounds = listOf(range.start) + cuts + range.end
        return bounds.zipWithNext().filter { (s, e) -> e > s }.map { (s, e) -> ZonePiece(ClosedOpenRange(s, e), zoneAt(s)) }
    }

    override fun equals(other: Any?): Boolean = other is ZoneTimeline && other.initialZone == initialZone && other.changes == changes

    override fun hashCode(): Int = 31 * initialZone.hashCode() + changes.hashCode()

    override fun toString(): String = "ZoneTimeline($initialZone, $changes)"

    public companion object {
        public fun fixed(zone: TimeZone): ZoneTimeline = ZoneTimeline(zone)
    }
}

/**
 * The local-time window a daily feature aggregates for row date `d`: from `d + startDays` at [start] to
 * `d + endDays` at [end]. Local times that fall into a DST gap resolve forward (kotlinx-datetime), so the engine day of
 * a spring-forward night is 23 hours and that of a fall-back night 25 hours.
 */
public enum class DailyWindow(
    private val startDays: Int,
    private val start: LocalTime,
    private val endDays: Int,
    private val end: LocalTime,
) {
    /** `[d 00:00, d+1 00:00)`. */
    CALENDAR_DAY(0, LocalTime(0, 0), 1, LocalTime(0, 0)),

    /** `[d 04:00, d+1 04:00)` (docs/research/10 §10.2). */
    ENGINE_DAY(0, LocalTime(4, 0), 1, LocalTime(4, 0)),

    /** `[d 22:00, d+1 04:00)`. */
    LATE_NIGHT(0, LocalTime(22, 0), 1, LocalTime(4, 0)),

    /** `[d 22:00, d+1 00:00)`. */
    EVENING_22_24(0, LocalTime(22, 0), 1, LocalTime(0, 0)),

    /** `[d 21:00, d+1 00:00)`. */
    EVENING_21_24(0, LocalTime(21, 0), 1, LocalTime(0, 0)),

    /** `[d 08:00, d 21:00)`, the daytime window of the sedentary features. */
    DAYTIME_08_21(0, LocalTime(8, 0), 0, LocalTime(21, 0)),

    /**
     * `[d 18:00, d+1 14:00)`: the nominal night of `d`. Sleep is attributed by the wake date (a main session that ends
     * on `d + 1` in its own offset belongs to night `d`); this range only decides when the night's row stops being
     * provisional (the next local day at 14:00, docs/research/10 §15.1 `BEDTIME_NEXT`).
     */
    SLEEP_NIGHT(0, LocalTime(18, 0), 1, LocalTime(14, 0)),
    ;

    /** The window of [date] when the whole window is in [zone]. */
    public fun bounds(date: LocalDate, zone: TimeZone): ClosedOpenRange = ClosedOpenRange(
        instant(date.plus(DatePeriod(days = startDays)), start, zone),
        instant(date.plus(DatePeriod(days = endDays)), end, zone),
    )

    /**
     * The instants of [date]'s window under [timeline]: in each constant-zone piece, the part that is inside the window
     * read in that piece's zone. Usually one range; on a travel day the window can be shorter or longer than 24 hours,
     * and consecutive dates never overlap.
     */
    public fun ranges(date: LocalDate, timeline: ZoneTimeline): List<ClosedOpenRange> {
        val nominal = bounds(date, TimeZone.UTC)
        val envelope = ClosedOpenRange(nominal.start - ZONE_SLACK, nominal.end + ZONE_SLACK)
        return mergeRanges(timeline.pieces(envelope).mapNotNull { piece -> bounds(date, piece.zone).intersect(piece.range) })
    }

    private fun instant(date: LocalDate, time: LocalTime, zone: TimeZone): Instant =
        if (time == LocalTime(0, 0)) date.atStartOfDayIn(zone) else LocalDateTime(date, time).toInstant(zone)

    public companion object {
        /** Offsets range from UTC-12 to UTC+14: any window instance lies within this distance of its UTC reading. */
        internal val ZONE_SLACK = 15.hours
    }
}

/** Merges overlapping or touching ranges; the result is sorted and disjoint. */
public fun mergeRanges(ranges: List<ClosedOpenRange>): List<ClosedOpenRange> {
    if (ranges.size < 2) return ranges
    val sorted = ranges.sortedBy { it.start }
    val out = ArrayList<ClosedOpenRange>(sorted.size)
    var current = sorted.first()
    for (r in sorted.drop(1)) {
        current = if (r.start <= current.end) {
            ClosedOpenRange(current.start, maxOf(current.end, r.end))
        } else {
            out += current
            r
        }
    }
    out += current
    return out
}

/** The smallest single range containing all of [ranges], or null when empty. */
public fun hull(ranges: List<ClosedOpenRange>): ClosedOpenRange? =
    if (ranges.isEmpty()) null else ClosedOpenRange(ranges.minOf { it.start }, ranges.maxOf { it.end })
