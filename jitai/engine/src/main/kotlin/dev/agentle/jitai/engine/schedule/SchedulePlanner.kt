package dev.agentle.jitai.engine.schedule

import dev.agentle.analytics.features.Freshness
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.engine.decision.DecisionKeys
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.eval.RuleRefs
import dev.agentle.jitai.engine.ports.TickProfile
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** What a planned one-time work runs. The background team maps these to WorkManager requests. */
public sealed interface WorkInput {
    /** Evaluate the `daily_at` slot ([date], [time]) of [jitaiId]. */
    public data class DailyAt(val jitaiId: String, val date: LocalDate, val time: String) : WorkInput

    /** Sync [featureIds]' sources ahead of a `daily_at` slot (R10 §7.4 prefetch; network constraint). */
    public data class Prefetch(val jitaiId: String, val date: LocalDate, val time: String, val featureIds: Set<String>) : WorkInput

    /** Run the `RE_EVALUATE_AFTER` follow-up of the snoozed decision [originalKey] (R10 §9.5). */
    public data class SnoozeFollowUp(val jitaiId: String, val originalKey: String) : WorkInput

    /** Run the event worker again (debounced or newly arrived events, red team lifecycle-battery-05). */
    public data object EventsFollowUp : WorkInput
}

/** How an enqueue treats an existing unique work of the same name. */
public enum class WorkPolicy { KEEP, REPLACE }

/** One unique one-time work to enqueue at [runAt] (now or earlier means "run now"). */
public data class PlannedWork(
    val uniqueName: String,
    val tags: Set<String>,
    val runAt: Instant,
    val input: WorkInput,
    val policy: WorkPolicy,
)

/**
 * The periodic `jitai-tick` (R10 §7.4): exists only while an effective `interval` rule exists. [nextRunOverride] is the
 * `setNextScheduleTimeOverride` instant that skips inactive windows (null: keep the normal period).
 */
public data class TickPlan(val periodMinutes: Int, val flexMinutes: Int, val nextRunOverride: Instant?) {
    public val uniqueName: String get() = TICK_NAME

    public companion object {
        public const val TICK_NAME: String = "jitai-tick"
    }
}

/** A scheduled slot that will never run on time: resolve it as MISSED (insert-if-absent). */
public data class MissedSlot(val jitaiId: String, val decisionKey: String, val slotAt: Instant, val reason: ReasonCode)

/** A rule whose interval is shorter than the tick period (red team lifecycle-battery-11): reported, never silent. */
public data class CadenceIssue(val jitaiId: String, val everyMinutes: Int, val tickMinutes: Int)

/** A rule none of whose trigger events can be observed now (red team lifecycle-battery-06/07). */
public data class UnavailableTrigger(val jitaiId: String, val unavailable: Set<dev.agentle.jitai.dsl.model.JitaiEventType>)

/** Why the schedule is being recomputed (R10 §7.5; red team lifecycle-battery-04). */
public enum class RescheduleSignal {
    PROCESS_START,
    BOOT_COMPLETED,
    TIMEZONE_CHANGED,
    TIME_SET,
    PACKAGE_REPLACED,
    PROFILE_CHANGED,
    DEFINITION_CHANGED,
}

/** Everything the background team must schedule or cancel. [cancelTags] are cancelled before [work] is enqueued. */
public data class SchedulePlan(
    val cancelTags: Set<String>,
    val tick: TickPlan?,
    val work: List<PlannedWork>,
    val missed: List<MissedSlot>,
    val cadenceIssues: List<CadenceIssue>,
    val unavailableTriggers: List<UnavailableTrigger>,
)

/**
 * The pure scheduling planner (R10 §7.4-7.6): what to schedule, never how. WorkManager specifics (expedited or not,
 * constraints, the `UPDATE` policy of the tick) are the background team's.
 */
public object SchedulePlanner {
    public const val TAG_AT: String = "jitai-at"
    public const val TAG_PREFETCH: String = "jitai-prefetch"
    public const val PREFETCH_LEAD_MINUTES: Int = 10
    private const val MIN_FLEX_MINUTES = 5
    private const val FLEX_DIVISOR = 3
    private const val LOOKAHEAD_DAYS = 3

    /** The tag every unit of work of one JITAI carries, so an edit or a disable cancels all of it. */
    public fun jitaiTag(jitaiId: String): String = "jitai:$jitaiId"

    /**
     * The full plan at [now]. [signal] decides what is cancelled first: a zone change or a manual clock change cancels
     * every `daily_at` and prefetch work by tag and re-plans it in the current zone; a definition change cancels that
     * JITAI's work ([changedJitaiId]). [usedKeys] (decision keys already resolved) only avoids pointless runs: a run for
     * a used key is a no-op anyway.
     */
    public fun plan(
        definitions: List<JitaiDefinition>,
        now: Instant,
        zone: TimeZone,
        profile: TickProfile,
        signal: RescheduleSignal = RescheduleSignal.PROCESS_START,
        changedJitaiId: String? = null,
        usedKeys: Set<String> = emptySet(),
        listenerConnected: Boolean = true,
    ): SchedulePlan {
        val effective = definitions.filter { Effectiveness.isIntervention(it) && Effectiveness.isEffective(it, now) }
        val dailyAt = effective.filter { it.trigger is Trigger.DailyAt }
        val work = mutableListOf<PlannedWork>()
        val missed = mutableListOf<MissedSlot>()
        dailyAt.forEach { definition ->
            val trigger = definition.trigger as Trigger.DailyAt
            trigger.times.distinct().forEach { time ->
                val (planned, lost) = planDailyAt(definition, time, now, zone, usedKeys)
                work += planned
                missed += lost
            }
        }
        val cancelTags = when (signal) {
            RescheduleSignal.TIMEZONE_CHANGED, RescheduleSignal.TIME_SET -> setOf(TAG_AT, TAG_PREFETCH)
            RescheduleSignal.DEFINITION_CHANGED -> setOfNotNull(changedJitaiId?.let(::jitaiTag))
            else -> emptySet()
        }
        return SchedulePlan(
            cancelTags = cancelTags,
            tick = tickPlan(effective, now, zone, profile, afterCurrentSlot = false),
            work = work,
            missed = missed,
            cadenceIssues = cadenceIssues(effective, profile),
            unavailableTriggers = unavailableTriggers(effective, listenerConnected),
        )
    }

    /**
     * The tick for [definitions] (null when no effective `interval` rule exists). When the next interval slot is further
     * away than one period (outside every active window), the override moves the next run to it (R10 §7.4).
     * [afterCurrentSlot] is true at the end of a tick, whose own slots are resolved.
     */
    public fun tickPlan(
        definitions: List<JitaiDefinition>,
        now: Instant,
        zone: TimeZone,
        profile: TickProfile,
        afterCurrentSlot: Boolean,
    ): TickPlan? {
        val interval = definitions.filter {
            it.trigger is Trigger.Interval && Effectiveness.isIntervention(it) && Effectiveness.isEffective(it, now)
        }
        if (interval.isEmpty()) return null
        val period = profile.tickMinutes
        val next = interval.mapNotNull { nextDue(it, now, zone, afterCurrentSlot) }.minOrNull()
        val override = next?.takeIf { it - now > period.minutes }
        return TickPlan(period, maxOf(MIN_FLEX_MINUTES, period / FLEX_DIVISOR), override)
    }

    /** The next instant an interval slot of [definition] is due: now inside a window, else the next instance start. */
    public fun nextDue(definition: JitaiDefinition, now: Instant, zone: TimeZone, afterCurrentSlot: Boolean): Instant? {
        if (definition.trigger !is Trigger.Interval) return null
        val current = IntervalSlots.currentSlot(definition, now, zone)
            ?: return IntervalSlots.nextInstance(definition, now, zone)?.start
        return when {
            !afterCurrentSlot -> now
            current.end < current.instance.end -> current.end
            else -> IntervalSlots.nextInstance(definition, current.instance.end - 1.milliseconds, zone)?.start
        }
    }

    /** Rules whose interval is shorter than the profile's tick period (lifecycle-battery-11): their extra slots become MISSED. */
    public fun cadenceIssues(definitions: List<JitaiDefinition>, profile: TickProfile): List<CadenceIssue> = definitions.mapNotNull {
        val trigger = it.trigger as? Trigger.Interval ?: return@mapNotNull null
        if (trigger.everyMinutes < profile.tickMinutes) CadenceIssue(it.id, trigger.everyMinutes, profile.tickMinutes) else null
    }

    /** Event rules none of whose event types can be observed now. */
    public fun unavailableTriggers(definitions: List<JitaiDefinition>, listenerConnected: Boolean): List<UnavailableTrigger> =
        definitions.mapNotNull {
            val trigger = it.trigger as? Trigger.Event ?: return@mapNotNull null
            val unavailable = trigger.events.filter { type ->
                EventPolicy.availability(type, listenerConnected) ==
                    EventAvailability.UNAVAILABLE
            }
            if (unavailable.isNotEmpty() &&
                unavailable.size == trigger.events.distinct().size
            ) {
                UnavailableTrigger(it.id, unavailable.toSet())
            } else {
                null
            }
        }

    /**
     * Work for one `daily_at` time (red team lifecycle-battery-04): a catch-up run now for a recent unresolved slot that is
     * still within `maxLatenessMinutes`, MISSED for one past it, then the next future occurrence (per-date unique name,
     * tags `jitai:<id>` and `jitai-at`) and its prefetch when the rule reads remote health data.
     */
    public fun planDailyAt(
        definition: JitaiDefinition,
        time: String,
        now: Instant,
        zone: TimeZone,
        usedKeys: Set<String> = emptySet(),
    ): Pair<List<PlannedWork>, List<MissedSlot>> {
        val trigger = definition.trigger as? Trigger.DailyAt ?: return emptyList<PlannedWork>() to emptyList()
        val today = now.toLocalDateTime(zone).date
        val work = mutableListOf<PlannedWork>()
        val missed = mutableListOf<MissedSlot>()
        listOf(today.plus(-1, DateTimeUnit.DAY), today).forEach { date ->
            val slot = DailyAtSlots.slotInstant(date, time, zone) ?: return@forEach
            val key = DecisionKeys.dailyAt(definition.id, date, time)
            if (slot > now || key in usedKeys || slot < definition.modifiedAt) return@forEach
            if (now - slot <= trigger.maxLatenessMinutes.minutes) {
                work += dailyAtWork(definition.id, date, time, runAt = now)
            } else {
                missed += MissedSlot(definition.id, key, slot, ReasonCode.TOO_LATE)
            }
        }
        nextOccurrence(definition, time, now, zone, usedKeys)?.let { (date, slot) ->
            work += dailyAtWork(definition.id, date, time, runAt = slot)
            prefetch(definition, date, time, slot, now)?.let { work += it }
        }
        return work to missed
    }

    /** The first date from today whose slot is after [now] and whose key is unused, with its slot instant. */
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
            .firstOrNull { (date, slot) -> slot > now && DecisionKeys.dailyAt(definition.id, date, time) !in usedKeys }
    }

    /**
     * The occurrence a `daily_at` worker for ([date], [time]) plans before it evaluates: the next date's slot in the
     * current zone (red team lifecycle-battery-04).
     */
    public fun nextAfterRun(definition: JitaiDefinition, date: LocalDate, time: String, now: Instant, zone: TimeZone): PlannedWork? {
        val next = (1..LOOKAHEAD_DAYS + 1).asSequence()
            .map { date.plus(it, DateTimeUnit.DAY) }
            .mapNotNull { d -> DailyAtSlots.slotInstant(d, time, zone)?.let { d to it } }
            .firstOrNull { (_, slot) -> slot > now } ?: return null
        return dailyAtWork(definition.id, next.first, time, runAt = next.second)
    }

    /** One `daily_at` work unit. */
    public fun dailyAtWork(jitaiId: String, date: LocalDate, time: String, runAt: Instant): PlannedWork = PlannedWork(
        uniqueName = DailyAtSlots.uniqueName(jitaiId, date, time),
        tags = setOf(jitaiTag(jitaiId), TAG_AT),
        runAt = runAt,
        input = WorkInput.DailyAt(jitaiId, date, time),
        policy = WorkPolicy.REPLACE,
    )

    private fun prefetch(definition: JitaiDefinition, date: LocalDate, time: String, slot: Instant, now: Instant): PlannedWork? {
        val remote = RuleRefs.of(definition).map { it.featureId }.filter { id ->
            when (RealtimeFeatureCatalog[id]?.freshness) {
                is Freshness.SourceLag, Freshness.DailyValue -> true
                else -> false
            }
        }.toSet()
        if (remote.isEmpty()) return null
        val runAt = slot - PREFETCH_LEAD_MINUTES.minutes
        if (runAt <= now) return null
        return PlannedWork(
            uniqueName = DailyAtSlots.prefetchName(definition.id, date, time),
            tags = setOf(jitaiTag(definition.id), TAG_PREFETCH),
            runAt = runAt,
            input = WorkInput.Prefetch(definition.id, date, time, remote),
            policy = WorkPolicy.REPLACE,
        )
    }
}
