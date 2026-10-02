package dev.agentle.jitai.engine.schedule

import dev.agentle.analytics.features.Freshness
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiEventType
import dev.agentle.jitai.dsl.model.SnoozeMode
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.dsl.rule.ClockTime
import dev.agentle.jitai.engine.decision.DecisionKeys
import dev.agentle.jitai.engine.eval.RuleRefs
import dev.agentle.jitai.engine.ports.JitaiRuntimeState
import dev.agentle.jitai.engine.ports.TickProfile
import dev.agentle.jitai.engine.time.MonotonicStamp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** A rule none of whose trigger events can be observed now (red team lifecycle-battery-06/07). */
public data class UnavailableTrigger(val jitaiId: String, val unavailable: Set<JitaiEventType>)

/**
 * What the pure planner reads besides the clock.
 *
 * @property usedKeys the subset of [SchedulePlanner.candidateKeys] that already has a decision row.
 * @property existing the timer table as stored (deferrals, fired prefetches, outcome rows and the backstop survive).
 * @property runtimes runtime state by JITAI id (snooze follow-ups and debounced events).
 * @property backstopMinutes the BACKSTOP period: the collection profile's tick (jitai-correctness-10).
 */
public data class PlanInput(
    val definitions: List<JitaiDefinition>,
    val usedKeys: Set<String> = emptySet(),
    val existing: List<TimerRow> = emptyList(),
    val runtimes: Map<String, JitaiRuntimeState> = emptyMap(),
    val backstopMinutes: Int = TickProfile.BALANCED.tickMinutes,
    val listenerConnected: Boolean = true,
)

/**
 * The rebuilt timer table.
 *
 * @property intervalCadenceMinutes `min(everyMinutes)` (>= 15) of the effective interval rules: how often interval slots
 *   are due, independent of the collection profile (jitai-correctness-09); null without interval rules.
 */
public data class TimerPlan(
    val reason: ReplanReason,
    val rows: List<TimerRow>,
    val unavailableTriggers: List<UnavailableTrigger>,
    val intervalCadenceMinutes: Int?,
) {
    /** Where the `jitai-timer` work must aim next: the minimum `dueAt` (null: nothing to wake for). */
    public val nextDueAt: Instant? get() = rows.minOfOrNull { it.dueAt }
}

/**
 * The pure scheduling planner (jitai-correctness-04/05/07/09/10): the complete timer table for the current definitions
 * at a given instant and zone. WorkManager specifics are the background team's: it keeps one unique one-time work
 * [TIMER_WORK] aimed at [TimerPlan.nextDueAt].
 *
 * - SLOT rows: every `daily_at` occurrence of yesterday and today that no evaluation resolved (due now; the evaluator
 *   evaluates it within `maxLatenessMinutes` or resolves it as MISSED), the next occurrence of every time, and for every
 *   `interval` rule the slots no evaluation reached, the current slot and the start of the first later slot whose key is
 *   unused. Slots outside the active window are never planned. A deferred row keeps its later `dueAt` while its key and
 *   version are unchanged.
 * - PREFETCH rows: 10 minutes before a `daily_at` slot whose rule reads remote health data, planned once per slot.
 * - SNOOZE rows: the pending `RE_EVALUATE_AFTER` follow-up of each JITAI, at its snooze end on the monotonic clock.
 * - OUTCOME rows: kept as they are while their JITAI exists.
 * - BACKSTOP: while an event or interval rule is effective, every [PlanInput.backstopMinutes], earlier when a debounced
 *   event becomes due.
 */
public object SchedulePlanner {
    public const val TIMER_WORK: String = "jitai-timer"

    /** Points due within this window of the earliest one are evaluated together and arbitrated (jitai-correctness-05). */
    public val COALESCE: Duration = 2.minutes

    public const val PREFETCH_LEAD_MINUTES: Int = 10

    /** The shortest interval cadence (R10 §3.2: `everyMinutes` is a multiple of 15). Shorter stored values plan nothing. */
    public const val MIN_INTERVAL_MINUTES: Int = 15

    /** How long after its start an interval slot may still be deferred (never past the slot's end). */
    public const val INTERVAL_LATENESS_MINUTES: Int = Trigger.DEFAULT_MAX_LATENESS_MINUTES

    private const val LOOKAHEAD_DAYS = 3

    /** The decision keys [replan] needs to look up (whether each is used decides which rows exist). */
    public fun candidateKeys(
        definitions: List<JitaiDefinition>,
        now: Instant,
        zone: TimeZone,
        runtimes: Map<String, JitaiRuntimeState> = emptyMap(),
    ): Set<String> {
        val keys = linkedSetOf<String>()
        val today = now.toLocalDateTime(zone).date
        planned(definitions).forEach { definition ->
            when (val trigger = definition.trigger) {
                is Trigger.DailyAt -> validTimes(trigger).forEach { time ->
                    for (offset in -1..LOOKAHEAD_DAYS) {
                        keys +=
                            DecisionKeys.dailyAt(definition.id, today.plus(offset, DateTimeUnit.DAY), time)
                    }
                }

                is Trigger.Interval -> intervalSlots(definition, now, zone).forEach { keys += it.key(definition.id) }

                else -> Unit
            }
        }
        runtimes.values.forEach { runtime ->
            runtime.followUpOf?.let { keys += DecisionKeys.snoozeFollowUp(runtime.jitaiId, it) }
        }
        return keys
    }

    /** The pure re-plan: every timer row from the current definitions at [now] in [zone] (jitai-correctness-04). */
    public fun replan(reason: ReplanReason, now: MonotonicStamp, zone: TimeZone, input: PlanInput): TimerPlan {
        val wall = now.wall
        val effective = planned(input.definitions)
        val existing = input.existing.associateBy { it.key }
        val rows = mutableListOf<TimerRow>()
        effective.forEach { definition ->
            when (definition.trigger) {
                is Trigger.DailyAt -> rows += dailyAtRows(definition, wall, zone, input.usedKeys, existing)
                is Trigger.Interval -> rows += intervalRows(definition, wall, zone, input.usedKeys)
                else -> Unit
            }
        }
        // After a system broadcast the stored wall instants may name other moments: deferrals and the backstop start over.
        val merged = rows.distinctBy { it.key }.map { if (reason.external) it else merge(it, existing[it.key]) }.toMutableList()
        merged += snoozeRows(effective, now, input)
        val known = input.definitions.mapTo(hashSetOf()) { it.id }
        merged += input.existing.filter { it.kind == TimerKind.OUTCOME && it.jitaiId in known }
        backstop(effective, now, input, keepStored = !reason.external)?.let { merged += it }
        val offset = zone.offsetAt(wall).totalSeconds
        return TimerPlan(
            reason = reason,
            rows = merged.distinctBy { it.key }
                .map { it.copy(offsetSeconds = offset) }
                .sortedWith(compareBy<TimerRow>({ it.dueAt }, { it.key })),
            unavailableTriggers = unavailableTriggers(effective, input.listenerConnected),
            intervalCadenceMinutes = intervalCadence(effective),
        )
    }

    /** `min(everyMinutes)` of [definitions]' interval rules, at least 15 (jitai-correctness-09). */
    public fun intervalCadence(definitions: List<JitaiDefinition>): Int? = definitions
        .mapNotNull { (it.trigger as? Trigger.Interval)?.everyMinutes?.takeIf { minutes -> minutes >= MIN_INTERVAL_MINUTES } }
        .minOrNull()

    /** Event rules none of whose event types can be observed now. */
    public fun unavailableTriggers(definitions: List<JitaiDefinition>, listenerConnected: Boolean): List<UnavailableTrigger> =
        definitions.mapNotNull {
            val trigger = it.trigger as? Trigger.Event ?: return@mapNotNull null
            val unavailable = trigger.events.filter { type ->
                EventPolicy.availability(type, listenerConnected) == EventAvailability.UNAVAILABLE
            }
            if (unavailable.isNotEmpty() && unavailable.size == trigger.events.distinct().size) {
                UnavailableTrigger(it.id, unavailable.toSet())
            } else {
                null
            }
        }

    /** The wall instant at which [stamp] falls when seen from [now] (same boot: by elapsed time; otherwise its wall time). */
    public fun wallOf(stamp: MonotonicStamp, now: MonotonicStamp): Instant =
        if (stamp.sameBootAs(now)) now.wall + (stamp.elapsedMillis - now.elapsedMillis).milliseconds else stamp.wall

    /** The remote health features a rule reads (a sync can refresh them): `SourceLag` and `DailyValue` freshness. */
    public fun remoteFeatures(definition: JitaiDefinition): Set<String> = RuleRefs.of(definition).map { it.featureId }.filter { id ->
        when (RealtimeFeatureCatalog[id]?.freshness) {
            is Freshness.SourceLag, Freshness.DailyValue -> true
            else -> false
        }
    }.toSortedSet()

    /**
     * The INTERVENTION rules that get rows: armed ones. A rule past `expiresAt` keeps its rows until its status moves to
     * EXPIRED, so its next point is resolved as SUPPRESSED(EXPIRED) by G02 and the status change happens (R10 §12.M2/N7).
     */
    private fun planned(definitions: List<JitaiDefinition>): List<JitaiDefinition> =
        definitions.filter { Effectiveness.isIntervention(it) && Effectiveness.isArmed(it) }

    private fun validTimes(trigger: Trigger.DailyAt): List<String> = trigger.times.distinct().filter { ClockTime.isValid(it) }

    private fun dailyAtRows(
        definition: JitaiDefinition,
        now: Instant,
        zone: TimeZone,
        used: Set<String>,
        existing: Map<String, TimerRow>,
    ): List<TimerRow> {
        val trigger = definition.trigger as Trigger.DailyAt
        val today = now.toLocalDateTime(zone).date
        val rows = mutableListOf<TimerRow>()
        validTimes(trigger).forEach { time ->
            listOf(today.minus(1, DateTimeUnit.DAY), today).forEach { date ->
                val slot = DailyAtSlots.slotInstant(date, time, zone) ?: return@forEach
                val key = DecisionKeys.dailyAt(definition.id, date, time)
                val unresolved = slot <= now && key !in used && slot >= definition.modifiedAt
                if (unresolved && Effectiveness.windowOpen(definition, slot, zone)) {
                    rows += slotRow(definition, key, TimerSlot.DailyAt(date, time), slot)
                }
            }
            nextOccurrence(definition, time, now, zone, used)?.let { (date, slot) ->
                val key = DecisionKeys.dailyAt(definition.id, date, time)
                val timerSlot = TimerSlot.DailyAt(date, time)
                rows += slotRow(definition, key, timerSlot, slot)
                prefetchRow(definition, key, timerSlot, slot, now, existing)?.let { rows += it }
            }
        }
        return rows
    }

    /** The first date from today whose slot is after [now], inside the active window and whose key is unused. */
    public fun nextOccurrence(
        definition: JitaiDefinition,
        time: String,
        now: Instant,
        zone: TimeZone,
        usedKeys: Set<String> = emptySet(),
    ): Pair<LocalDate, Instant>? {
        val today = now.toLocalDateTime(zone).date
        return (0..LOOKAHEAD_DAYS).asSequence()
            .map { today.plus(it, DateTimeUnit.DAY) }
            .mapNotNull { date -> DailyAtSlots.slotInstant(date, time, zone)?.let { date to it } }
            .firstOrNull { (date, slot) ->
                slot > now &&
                    DecisionKeys.dailyAt(definition.id, date, time) !in usedKeys &&
                    Effectiveness.windowOpen(definition, slot, zone)
            }
    }

    private fun intervalSlots(definition: JitaiDefinition, now: Instant, zone: TimeZone): List<IntervalSlot> {
        val trigger = definition.trigger as? Trigger.Interval ?: return emptyList()
        if (trigger.everyMinutes < MIN_INTERVAL_MINUTES) return emptyList()
        val current = IntervalSlots.currentSlot(definition, now, zone)
        return IntervalSlots.unreached(definition, now, zone, notBefore = definition.modifiedAt) +
            listOfNotNull(current) + upcoming(definition, current, now, zone)
    }

    /**
     * Every unresolved slot that already started (backfilled as MISSED or evaluated now) and the first future slot whose
     * key is unused: after the wall clock moved back or the zone changed, the next slots may already be resolved
     * (R10 §12.O10b, O14), so the row aims at the first one that is not.
     */
    private fun intervalRows(definition: JitaiDefinition, now: Instant, zone: TimeZone, used: Set<String>): List<TimerRow> {
        val (future, started) = intervalSlots(definition, now, zone).filter { it.key(definition.id) !in used }.partition { it.start > now }
        return (started + listOfNotNull(future.firstOrNull()))
            .map { slotRow(definition, it.key(definition.id), TimerSlot.Interval(it.instance.startDate, it.index), it.start) }
    }

    /** The slots after [current] to the end of its window instance, then the first slot of the next instance. */
    private fun upcoming(definition: JitaiDefinition, current: IntervalSlot?, now: Instant, zone: TimeZone): List<IntervalSlot> {
        val every = (definition.trigger as Trigger.Interval).everyMinutes
        val rest = current?.let { IntervalSlots.slots(it.instance, every).drop(it.index + 1) }.orEmpty()
        val after = current?.let { it.instance.end - 1.milliseconds } ?: now
        val next = IntervalSlots.nextInstance(definition, after, zone)?.let { IntervalSlots.slots(it, every).firstOrNull() }
        return rest + listOfNotNull(next)
    }

    private fun slotRow(definition: JitaiDefinition, key: String, slot: TimerSlot, dueAt: Instant): TimerRow = TimerRow(
        key = TimerKeys.slot(key),
        kind = TimerKind.SLOT,
        dueAt = dueAt,
        jitaiId = definition.id,
        version = definition.version,
        slot = slot,
        decisionKey = key,
    )

    /**
     * One prefetch per slot: not planned again once the slot row exists without its prefetch (it already fired). A stored
     * prefetch row that is already due is kept until the timer handles it; a past one is never planned anew.
     */
    private fun prefetchRow(
        definition: JitaiDefinition,
        key: String,
        slot: TimerSlot,
        slotAt: Instant,
        now: Instant,
        existing: Map<String, TimerRow>,
    ): TimerRow? {
        val remote = remoteFeatures(definition)
        val at = slotAt - PREFETCH_LEAD_MINUTES.minutes
        val prefetchKey = TimerKeys.prefetch(key)
        val stored = prefetchKey in existing
        val fired = existing[TimerKeys.slot(key)]?.version == definition.version && !stored
        if (remote.isEmpty() || fired || (at <= now && !stored)) return null
        return TimerRow(
            key = prefetchKey,
            kind = TimerKind.PREFETCH,
            dueAt = at,
            jitaiId = definition.id,
            version = definition.version,
            slot = slot,
            decisionKey = key,
            featureIds = remote,
        )
    }

    /** A deferred SLOT row keeps its later `dueAt` and its deferral count while its key and version are unchanged. */
    private fun merge(planned: TimerRow, existing: TimerRow?): TimerRow {
        if (existing == null || existing.kind != planned.kind || existing.version != planned.version || existing.deferrals == 0) {
            return planned
        }
        return planned.copy(dueAt = maxOf(planned.dueAt, existing.dueAt), deferrals = existing.deferrals)
    }

    private fun snoozeRows(effective: List<JitaiDefinition>, now: MonotonicStamp, input: PlanInput): List<TimerRow> {
        val byId = effective.associateBy { it.id }
        return input.runtimes.values.mapNotNull { runtime ->
            val original = runtime.followUpOf
            val until = runtime.snoozedUntil
            val definition = byId[runtime.jitaiId]
            if (original == null || until == null || definition == null || runtime.snoozeMode != SnoozeMode.RE_EVALUATE_AFTER) {
                return@mapNotNull null
            }
            val followUp = DecisionKeys.snoozeFollowUp(definition.id, original)
            if (followUp in input.usedKeys) return@mapNotNull null
            TimerRow(
                key = TimerKeys.snooze(followUp),
                kind = TimerKind.SNOOZE,
                dueAt = wallOf(until, now),
                jitaiId = definition.id,
                version = definition.version,
                decisionKey = followUp,
                originalKey = original,
            )
        }
    }

    /**
     * The BACKSTOP row. A stored one keeps its `dueAt`, even when overdue, until the evaluator handles and deletes it, so a
     * late run still drains the events; [keepStored] is false after a system broadcast, which starts a new period.
     */
    private fun backstop(effective: List<JitaiDefinition>, now: MonotonicStamp, input: PlanInput, keepStored: Boolean): TimerRow? {
        if (effective.none { it.trigger is Trigger.Event || it.trigger is Trigger.Interval }) return null
        val stored = input.existing.firstOrNull { it.kind == TimerKind.BACKSTOP }?.dueAt?.takeIf { keepStored }
        val regular = stored ?: (now.wall + input.backstopMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES).minutes)
        val byId = effective.associateBy { it.id }
        val debounced = input.runtimes.values.mapNotNull { runtime ->
            val trigger = byId[runtime.jitaiId]?.trigger as? Trigger.Event ?: return@mapNotNull null
            val last = runtime.lastEventEvaluation
            if (runtime.pendingEvent == null || last == null) return@mapNotNull null
            wallOf(last + trigger.debounceSeconds.coerceAtLeast(0).seconds, now)
        }
        return TimerRow(TimerKeys.BACKSTOP, TimerKind.BACKSTOP, (debounced + regular).min())
    }
}
