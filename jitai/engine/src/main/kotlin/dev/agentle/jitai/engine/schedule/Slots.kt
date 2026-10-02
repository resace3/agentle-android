package dev.agentle.jitai.engine.schedule

import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.dsl.rule.ClockTime
import dev.agentle.jitai.engine.decision.DecisionKeys
import dev.agentle.jitai.engine.time.LocalWindow
import dev.agentle.jitai.engine.time.WindowInstance
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** One interval slot `[start, end)` of a window instance (R10 §7.4): its index counts in elapsed time from the start. */
public data class IntervalSlot(val instance: WindowInstance, val index: Int, val start: Instant, val end: Instant) {
    public fun key(jitaiId: String): String = DecisionKeys.interval(jitaiId, instance.startDate, index)
}

/**
 * Interval-trigger slot arithmetic (R10 §7.4, §10.3). For a window instance starting at instant `S`, slot `k` covers
 * `[S + k*everyMinutes, S + (k+1)*everyMinutes)` in elapsed time, so a DST night has more or fewer slots (R10 §12.O3).
 * A rule without an active window counts slots from local midnight of each calendar day.
 */
public object IntervalSlots {
    /** The slot of [instance] containing [t], or null when [t] is outside it. */
    public fun slotAt(instance: WindowInstance, everyMinutes: Int, t: Instant): IntervalSlot? {
        if (t !in instance || everyMinutes <= 0) return null
        val length = everyMinutes.minutes
        val index = ((t - instance.start) / length).toInt()
        return slot(instance, length, index)
    }

    /** Every slot of [instance] in order. */
    public fun slots(instance: WindowInstance, everyMinutes: Int): List<IntervalSlot> {
        if (everyMinutes <= 0) return emptyList()
        val length = everyMinutes.minutes
        return generateSequence(0) { it + 1 }.map { slot(instance, length, it) }.takeWhile { it != null }.filterNotNull().toList()
    }

    private fun slot(instance: WindowInstance, length: Duration, index: Int): IntervalSlot? {
        val start = instance.start + length * index
        if (start >= instance.end) return null
        val end = minOf(start + length, instance.end)
        return IntervalSlot(instance, index, start, end)
    }

    /** The slot of [definition] (an `interval` rule) at [t], or null when its window is closed. */
    public fun currentSlot(definition: JitaiDefinition, t: Instant, zone: TimeZone): IntervalSlot? {
        val trigger = definition.trigger as? Trigger.Interval ?: return null
        val instance = Effectiveness.instanceAt(definition, t, zone) ?: return null
        return slotAt(instance, trigger.everyMinutes, t)
    }

    /** The instance of [definition] starting on [date] (the local day for a rule without a window), or null. */
    public fun instanceStartingOn(definition: JitaiDefinition, date: LocalDate, zone: TimeZone): WindowInstance? {
        val window = definition.activeWindow ?: return LocalWindow.wholeDay(date, zone)
        return LocalWindow.of(window)?.instanceStartingOn(date, zone)
    }

    /** The instance of [definition] that ended at or before [t] most recently (unreached slots are backfilled as MISSED). */
    public fun previousInstance(definition: JitaiDefinition, t: Instant, zone: TimeZone): WindowInstance? {
        val window = definition.activeWindow ?: return LocalWindow.wholeDay(t.toLocalDateTime(zone).date.minus(1, DateTimeUnit.DAY), zone)
        return LocalWindow.of(window)?.previousInstanceBefore(t, zone)
    }

    /** The first instance of [definition] starting strictly after [after]. */
    public fun nextInstance(definition: JitaiDefinition, after: Instant, zone: TimeZone): WindowInstance? {
        val window =
            definition.activeWindow ?: return LocalWindow.wholeDay(after.toLocalDateTime(zone).date.plus(1, DateTimeUnit.DAY), zone)
        return LocalWindow.of(window)?.nextInstanceAfter(after, zone)
    }

    /**
     * Slots no tick reached (R10 §7.4): the slots before the current one in the current instance and every slot of the
     * previous instance, limited to slots that started after [notBefore] (the rule's `modifiedAt`, so a new or edited
     * rule has no history of missed slots). Keys that exist are skipped by the store's insert-if-absent.
     */
    public fun unreached(definition: JitaiDefinition, t: Instant, zone: TimeZone, notBefore: Instant): List<IntervalSlot> {
        val trigger = definition.trigger as? Trigger.Interval ?: return emptyList()
        val current = currentSlot(definition, t, zone)
        val earlierInCurrent = current?.let { slots(it.instance, trigger.everyMinutes).take(it.index) }.orEmpty()
        val previous = previousInstance(definition, t, zone)
            ?.takeIf { current == null || it.startDate != current.instance.startDate }
            ?.let { slots(it, trigger.everyMinutes) }
            .orEmpty()
        return (previous + earlierInCurrent).filter { it.start >= notBefore && it.start < t }
    }
}

/** `daily_at` slot arithmetic (R10 §7.4, §10.6; red team lifecycle-battery-04). */
public object DailyAtSlots {
    /** A slot evaluated earlier than this before its time re-plans without a decision (the coalescing window). */
    public val EARLY_TOLERANCE: Duration = 2.minutes

    /** The instant of [time] on local [date] in [zone] (gap: shifted later by the gap; overlap: earlier offset). */
    public fun slotInstant(date: LocalDate, time: String, zone: TimeZone): Instant? =
        ClockTime.minuteOfDay(time)?.let { LocalWindow.atMinute(date, it, zone) }

    /** Where a `daily_at` run stands relative to its slot. */
    public enum class Timing { TOO_EARLY, ON_TIME, TOO_LATE }

    /** TOO_EARLY when `now < slot - 2 min`, TOO_LATE when `now > slot + maxLateness`, else ON_TIME. */
    public fun timing(now: Instant, slot: Instant, maxLatenessMinutes: Int): Timing = when {
        now < slot - EARLY_TOLERANCE -> Timing.TOO_EARLY
        now > slot + maxLatenessMinutes.minutes -> Timing.TOO_LATE
        else -> Timing.ON_TIME
    }
}
