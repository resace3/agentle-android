package dev.agentle.jitai.engine.schedule

import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.dsl.rule.ClockTime
import dev.agentle.jitai.engine.decision.DecisionKeys
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.decision.TriggerKind
import dev.agentle.jitai.engine.time.LocalWindow
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** What a due SLOT or SNOOZE row is after verification (jitai-correctness-05/07). */
public sealed interface TimerVerdict {
    /**
     * Evaluate decision point [key] whose nominal time is [nominalAt]. [latestAt] bounds deferrals of scheduled points
     * (`slot + maxLatenessMinutes` for `daily_at`; within the slot for `interval`); null for snooze follow-ups.
     */
    public data class Evaluate(val key: String, val kind: TriggerKind, val nominalAt: Instant, val latestAt: Instant?) : TimerVerdict

    /** Resolve [key] as MISSED with [reason], without evaluation. */
    public data class Missed(val key: String, val kind: TriggerKind, val nominalAt: Instant, val reason: ReasonCode) : TimerVerdict

    /** The row does not match the current definition or clock: drop it and re-plan ([why] is content-free). */
    public data class Replan(val why: String) : TimerVerdict
}

/**
 * Verification of a firing timer (jitai-correctness-05/07): the definition exists, is enabled and ACTIVE, has the
 * row's version, the time is one of the rule's times (or the slot one of its interval slots), and the point is within
 * its lateness. Anything else re-plans. Pure.
 */
public object TimerChecks {
    public fun verify(row: TimerRow, definition: JitaiDefinition?, now: Instant, zone: TimeZone): TimerVerdict {
        if (definition == null) return TimerVerdict.Replan("definition_missing")
        if (!Effectiveness.isArmed(definition) || !Effectiveness.isIntervention(definition)) return TimerVerdict.Replan("not_enabled")
        if (row.version != definition.version) return TimerVerdict.Replan("version_changed")
        return when (row.kind) {
            TimerKind.SLOT -> when (val slot = row.slot) {
                is TimerSlot.DailyAt -> dailyAt(row, definition, slot, now, zone)
                is TimerSlot.Interval -> interval(row, definition, slot, now, zone)
                null -> TimerVerdict.Replan("no_slot")
            }

            TimerKind.SNOOZE -> snooze(row, definition, now, zone)

            else -> TimerVerdict.Replan("not_a_decision_point")
        }
    }

    private fun dailyAt(row: TimerRow, definition: JitaiDefinition, slot: TimerSlot.DailyAt, now: Instant, zone: TimeZone): TimerVerdict {
        val trigger = definition.trigger as? Trigger.DailyAt ?: return TimerVerdict.Replan("trigger_changed")
        if (slot.time !in trigger.times || !ClockTime.isValid(slot.time)) return TimerVerdict.Replan("time_not_listed")
        val key = DecisionKeys.dailyAt(definition.id, slot.date, slot.time)
        val at = DailyAtSlots.slotInstant(slot.date, slot.time, zone)
        if (row.decisionKey != key || at == null) return TimerVerdict.Replan("key_mismatch")
        return when (DailyAtSlots.timing(now, at, trigger.maxLatenessMinutes)) {
            DailyAtSlots.Timing.TOO_EARLY -> TimerVerdict.Replan("too_early")

            DailyAtSlots.Timing.TOO_LATE -> TimerVerdict.Missed(key, TriggerKind.DAILY_AT, at, ReasonCode.TOO_LATE)

            // A slot evaluated early (coalesced) is checked against the window at its own time.
            DailyAtSlots.Timing.ON_TIME -> if (Effectiveness.windowOpen(definition, maxOf(now, at), zone)) {
                // Deferrals stay inside the active window, so a deferred point ends with its real gate reason.
                val windowEnd = definition.activeWindow?.let { LocalWindow.of(it)?.instanceAt(maxOf(now, at), zone)?.end }
                val lateness = at + trigger.maxLatenessMinutes.minutes
                TimerVerdict.Evaluate(key, TriggerKind.DAILY_AT, at, windowEnd?.let { minOf(lateness, it - 1.milliseconds) } ?: lateness)
            } else {
                TimerVerdict.Missed(key, TriggerKind.DAILY_AT, at, ReasonCode.OUTSIDE_WINDOW)
            }
        }
    }

    /** Interval slots are never evaluated before they start: the first slot of an instance would lie outside the window. */
    private fun interval(row: TimerRow, definition: JitaiDefinition, slot: TimerSlot.Interval, now: Instant, zone: TimeZone): TimerVerdict {
        val trigger = definition.trigger as? Trigger.Interval ?: return TimerVerdict.Replan("trigger_changed")
        val instance = IntervalSlots.instanceStartingOn(definition, slot.instanceDate, zone) ?: return TimerVerdict.Replan("no_instance")
        val current = IntervalSlots.slots(instance, trigger.everyMinutes).getOrNull(slot.index) ?: return TimerVerdict.Replan("no_slot")
        val key = current.key(definition.id)
        return when {
            row.decisionKey != key -> TimerVerdict.Replan("key_mismatch")

            now < current.start -> TimerVerdict.Replan("too_early")

            now >= current.end -> TimerVerdict.Missed(key, TriggerKind.INTERVAL, current.start, ReasonCode.SLOT_NOT_REACHED)

            else -> TimerVerdict.Evaluate(
                key,
                TriggerKind.INTERVAL,
                current.start,
                minOf(current.end - 1.milliseconds, current.start + SchedulePlanner.INTERVAL_LATENESS_MINUTES.minutes),
            )
        }
    }

    /** A follow-up that can only run after its window closed is MISSED(OUTSIDE_WINDOW), so its key is used once. */
    private fun snooze(row: TimerRow, definition: JitaiDefinition, now: Instant, zone: TimeZone): TimerVerdict {
        val key = row.decisionKey ?: return TimerVerdict.Replan("no_key")
        if (row.originalKey == null || DecisionKeys.snoozeFollowUp(definition.id, row.originalKey) != key) {
            return TimerVerdict.Replan("key_mismatch")
        }
        return if (Effectiveness.windowOpen(definition, maxOf(now, row.dueAt), zone)) {
            TimerVerdict.Evaluate(key, TriggerKind.SNOOZE_FOLLOW_UP, row.dueAt, latestAt = null)
        } else {
            TimerVerdict.Missed(key, TriggerKind.SNOOZE_FOLLOW_UP, row.dueAt, ReasonCode.OUTSIDE_WINDOW)
        }
    }
}
