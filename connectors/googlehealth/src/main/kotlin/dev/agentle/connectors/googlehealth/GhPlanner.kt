package dev.agentle.connectors.googlehealth

import dev.agentle.connectors.api.SyncTrigger
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The windows one run fetches for one stream.
 *
 * @property forward ascending windows from the overlap (or hot-load, or deep re-sync) start up to now.
 * @property backward descending backfill windows below [backfilledFrom].
 * @property baseThrough the stored forward position still in force (null on a first sync or after a clock reset).
 * @property firstSync no position was stored for this account.
 * @property deep the forward pass is the weekly 30-day deep re-sync.
 * @property backfilledFrom where the backfill continues downward.
 */
internal data class GhPlan(
    val forward: List<GhRange>,
    val backward: List<GhRange>,
    val baseThrough: Instant?,
    val firstSync: Boolean,
    val deep: Boolean,
    val backfilledFrom: Instant?,
)

/**
 * Window planning (docs/research/05 §5.6, §7.4 with the round-2 corrections):
 * - first sync: the 14-day hot load; later: from the cursor minus the overlap (48 h, 7 days for sessions and daily
 *   values), shortened by the tracker's last upload when known, and skipped when the cursor's run started less than
 *   15 minutes ago;
 * - a stored cursor more than 5 minutes after now (the clock moved back) restarts at now minus the overlap; a window
 *   never has start >= end;
 * - the weekly deep re-sync reaches 30 days back; backfill walks down in 30-day steps (one per scheduled run after the
 *   hot load, all at once on a BACKFILL run) to min(horizon, retention);
 * - every window start is clamped to the store's import floor (retention or the last deletion);
 * - windows are cut to the stream's cap; daily streams use whole civil days in the account zone; rollup windows start
 *   and end on whole minutes, because `:rollUp` buckets start at the range start and their bounds are the dedup key.
 */
internal object GhPlanner {
    /** A cursor this far after now means the clock moved back. */
    val CLOCK_SKEW: Duration = 5.minutes

    /** A cursor written by a run that started this recently needs no overlap re-read. */
    val RECENT_RUN: Duration = 15.minutes

    /** Margin before the tracker's last upload when it bounds the overlap. */
    val DEVICE_MARGIN: Duration = 1.hours

    /** Rollup windows (and so their 60-second buckets) are aligned to this. */
    val ROLLUP_ALIGN: Duration = 1.minutes

    @Suppress("CyclomaticComplexMethod", "LongParameterList")
    fun plan(
        stream: GhStream,
        state: GhStreamState,
        trigger: SyncTrigger,
        now: Instant,
        floor: Instant?,
        zone: TimeZone,
        config: GoogleHealthConfig,
    ): GhPlan {
        val lowest = floor ?: Instant.DISTANT_PAST
        val aligned = stream.kind == GhKind.ROLL_UP
        val end = if (aligned) floorTo(now, ROLLUP_ALIGN) else now
        val stored = state.through
        val firstSync = stored == null
        val jumpedBack = stored != null && stored > now + CLOCK_SKEW
        val base = if (stored == null || jumpedBack) null else minOf(stored, now)
        val fetchedAgo = state.fetchedAt?.let { now - it }
        var start = when {
            base == null && firstSync -> now - config.hotLoad
            base == null -> now - stream.overlap
            fetchedAgo != null && !fetchedAgo.isNegative() && fetchedAgo < RECENT_RUN -> base
            else -> overlapStart(stream, base, state.deviceLastSync)
        }
        val deepAgo = state.deepResyncAt?.let { now - it }
        val deep = !firstSync && (trigger == SyncTrigger.DEEP_RESYNC || deepAgo == null || deepAgo >= config.deepResyncEvery)
        if (deep) start = minOf(start, now - config.deepResyncWindow)
        if (stream.kind == GhKind.SLEEP) start -= GhFetcher.SESSION_LEAD
        start = maxOf(start, lowest)
        if (aligned) start = ceilTo(start, ROLLUP_ALIGN)
        val forward = if (stream.civilDays) {
            splitDays(alignDay(start, zone, floor), nextDayStart(now, zone), zone, stream.chunk)
        } else {
            split(start, end, stream.chunk)
        }.let { if (deep) it else it.take(config.maxChunksPerRun) }

        val from = minOf(state.backfilledFrom ?: forward.firstOrNull()?.start ?: start, forward.firstOrNull()?.start ?: start)
        val horizon = maxOf(now - config.backfillHorizon, lowest).let { if (aligned) ceilTo(it, ROLLUP_ALIGN) else it }
        val target = when {
            trigger == SyncTrigger.BACKFILL -> horizon

            // The run that hot-loads stays short; scheduled runs then walk down one chunk each.
            trigger == SyncTrigger.SCHEDULED && !firstSync -> maxOf(horizon, from - config.backfillChunk)

            else -> null
        }
        val backward = when {
            target == null -> emptyList()
            stream.civilDays -> splitDaysDown(alignDay(target, zone, floor), from, zone, stream.chunk)
            else -> splitDown(target, from, stream.chunk)
        }
        return GhPlan(forward, backward, base, firstSync, deep, from)
    }

    private fun overlapStart(stream: GhStream, through: Instant, deviceLastSync: Instant?): Instant {
        val full = through - stream.overlap
        if (!stream.deviceBoundOverlap || deviceLastSync == null) return full
        return maxOf(full, minOf(through, deviceLastSync) - DEVICE_MARGIN)
    }

    /** Ascending windows of at most [chunk] covering `[start, end)`; empty when start >= end. */
    fun split(start: Instant, end: Instant, chunk: Duration): List<GhRange> {
        val windows = ArrayList<GhRange>()
        var s = start
        while (s < end) {
            val e = minOf(s + chunk, end)
            windows += GhRange(s, e)
            s = e
        }
        return windows
    }

    /** Descending windows of at most [chunk] covering `[target, from)`. */
    fun splitDown(target: Instant, from: Instant, chunk: Duration): List<GhRange> {
        val windows = ArrayList<GhRange>()
        var e = from
        while (e > target) {
            val s = maxOf(e - chunk, target)
            windows += GhRange(s, e)
            e = s
        }
        return windows
    }

    /** Ascending windows of whole civil days (at most [chunk] days each) from [start] (a day start) to [end]. */
    fun splitDays(start: Instant, end: Instant, zone: TimeZone, chunk: Duration): List<GhRange> {
        val days = chunk.inWholeDays.toInt().coerceAtLeast(1)
        val first = start.toLocalDateTime(zone).date
        val last = end.toLocalDateTime(zone).date
        val windows = ArrayList<GhRange>()
        var d = first
        while (d < last) {
            val next = minOf(d.plus(DatePeriod(days = days)), last)
            windows += GhRange(d.atStartOfDayIn(zone), next.atStartOfDayIn(zone))
            d = next
        }
        return windows
    }

    /** Descending windows of whole civil days from the day of [from] down to [target] (a day start). */
    fun splitDaysDown(target: Instant, from: Instant, zone: TimeZone, chunk: Duration): List<GhRange> {
        val days = chunk.inWholeDays.toInt().coerceAtLeast(1)
        val low = target.toLocalDateTime(zone).date
        val high = from.toLocalDateTime(zone).date
        val windows = ArrayList<GhRange>()
        var e = high
        while (e > low) {
            val back = minOf(days, low.daysUntil(e))
            val s = e.plus(DatePeriod(days = -back))
            windows += GhRange(s.atStartOfDayIn(zone), e.atStartOfDayIn(zone))
            e = s
        }
        return windows
    }

    /** The start of the civil day of [at]; the next day when that start lies below the import [floor]. */
    fun alignDay(at: Instant, zone: TimeZone, floor: Instant?): Instant {
        val day = dayStart(at, zone)
        return if (floor != null && day < floor) nextDayStart(at, zone) else day
    }

    fun dayStart(at: Instant, zone: TimeZone): Instant = at.toLocalDateTime(zone).date.atStartOfDayIn(zone)

    fun nextDayStart(at: Instant, zone: TimeZone): Instant = at.toLocalDateTime(zone).date.plus(DatePeriod(days = 1)).atStartOfDayIn(zone)

    /** [at] rounded down to a whole multiple of [step] since the epoch. */
    fun floorTo(at: Instant, step: Duration): Instant {
        val ms = step.inWholeMilliseconds
        return Instant.fromEpochMilliseconds(Math.floorDiv(at.toEpochMilliseconds(), ms) * ms)
    }

    /** [at] rounded up to a whole multiple of [step] since the epoch. */
    fun ceilTo(at: Instant, step: Duration): Instant {
        val down = floorTo(at, step)
        return if (down == at) at else down + step
    }

    /** Whole seconds: filter literals carry no fractions. */
    fun truncate(at: Instant): Instant = Instant.fromEpochSeconds(at.epochSeconds)
}
