package dev.agentle.jitai.engine.gates

import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.QuietHoursPolicy
import dev.agentle.jitai.dsl.model.RuleOrigin
import dev.agentle.jitai.engine.decision.DecisionRecord
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.eval.Tri
import dev.agentle.jitai.engine.ports.DefinitionState
import dev.agentle.jitai.engine.ports.EngineSettings
import dev.agentle.jitai.engine.ports.InterruptionFilter
import dev.agentle.jitai.engine.ports.JitaiRuntimeState
import dev.agentle.jitai.engine.time.EngineDays
import dev.agentle.jitai.engine.time.LocalWindow
import dev.agentle.jitai.engine.time.MonotonicStamp
import dev.agentle.jitai.engine.time.elapsedBetween
import dev.agentle.jitai.engine.time.isBefore
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** One gate's verdict with a content-free [detail] (`count=3 limit=3`, `elapsed=45m cooldown=60m`). */
@Serializable
public data class GateCheck(val gate: ReasonCode, val passed: Boolean, val detail: String? = null)

/** Every gate G01-G15 in order (R10 §9.1: all are evaluated for the trace; the first failure is the recorded reason). */
@Serializable
public data class GateReport(val checks: List<GateCheck>) {
    public val firstFailure: GateCheck? get() = checks.firstOrNull { !it.passed }
    public val passed: Boolean get() = firstFailure == null
}

/**
 * Per-JITAI limits as enforced at run time (R10 §9.2), clamped to the origin's range so a stored rule that slipped past the
 * validator still gets the stricter AI limits: AI rules cooldown >= 60 min, at most 3 per day, 14 per week, priority <= 60.
 */
public data class EffectiveLimits(val cooldownMinutes: Int, val maxPerDay: Int, val maxPerWeek: Int, val priority: Int) {
    public companion object {
        public const val MAX_COOLDOWN_MINUTES: Int = 10_080
        private const val DEFAULT_COOLDOWN_MINUTES = 60

        public fun of(definition: JitaiDefinition): EffectiveLimits {
            val ai = RuleOrigin.of(definition.createdBy) == RuleOrigin.AI
            val minCooldown = if (ai) 60 else 15
            val maxDay = if (ai) 3 else 12
            val maxWeek = if (ai) 14 else 60
            val maxPriority = if (ai) 60 else 100
            val perDay = (definition.maxPerDay ?: 1).coerceIn(1, maxDay)
            return EffectiveLimits(
                cooldownMinutes = (definition.cooldownMinutes ?: DEFAULT_COOLDOWN_MINUTES).coerceIn(minCooldown, MAX_COOLDOWN_MINUTES),
                maxPerDay = perDay,
                maxPerWeek = (definition.maxPerWeek ?: perDay).coerceIn(1, maxWeek),
                priority = definition.priority.coerceIn(0, maxPriority),
            )
        }
    }
}

/**
 * Engagement-aware backoff (R10 §9.6): from 3 consecutive ignored or dismissed deliveries the cooldown doubles per
 * delivery (`cooldown * 2^(n-2)`), capped at 7 days; at 5 the JITAI is paused and the app asks in-app.
 */
public object Backoff {
    public const val START_AT: Int = 3
    public const val PAUSE_AT: Int = 5
    public val CAP: Duration = 7.days
    private const val MAX_SHIFT = 20

    public fun effectiveCooldown(cooldown: Duration, consecutiveIgnored: Int): Duration {
        if (consecutiveIgnored < START_AT) return cooldown
        val shift = consecutiveIgnored - 2
        if (shift >= MAX_SHIFT) return CAP
        return (cooldown * (1 shl shift).toDouble()).coerceAtMost(CAP)
    }
}

/**
 * Inputs of the gates for one candidate, read inside the commit (or claim) transaction (R10 §8.4).
 *
 * @property definitionState the definition as stored now (null: deleted).
 * @property interruptionFilter the live interruption filter (G07).
 * @property deliveryReady the live delivery prerequisite of the definition's category (G05, jitai-correctness-13).
 * @property interactive `device_interactive` from the pass snapshot (G06 needs a definite TRUE).
 * @property suppressedBy ids of effective SUPPRESSION rules blocking this candidate (G08).
 * @property recent counted rows of recent engine days ([GateEvaluator.RECENT_ENGINE_DAYS]).
 * @property latestOwn the latest counted row of this JITAI, however old (cooldowns longer than the recent window).
 * @property snoozeFollowUp a `RE_EVALUATE_AFTER` follow-up: skips G09-G11 (R10 §9.5), and its own snooze may still have
 *   up to [GateEvaluator.FOLLOW_UP_TOLERANCE] to run when its timer fires (G03).
 */
public data class GateInput(
    val definition: JitaiDefinition,
    val definitionState: DefinitionState?,
    val channel: DeliveryChannel,
    val now: MonotonicStamp,
    val zone: TimeZone,
    val runtime: JitaiRuntimeState,
    val settings: EngineSettings,
    val interruptionFilter: InterruptionFilter,
    val deliveryReady: Boolean,
    val interactive: Tri,
    val suppressedBy: List<String>,
    val recent: List<DecisionRecord> = emptyList(),
    val latestOwn: DecisionRecord? = null,
    val snoozeFollowUp: Boolean = false,
)

/**
 * Safety gates G01-G15 (R10 §9.1); G16 is decided by [Arbitration]. Pure. G01-G08 ([liveChecks]) depend only on the
 * live state and are evaluated again by the claim (jitai-correctness-12); G09-G15 count rows: CARD_PENDING rows count for
 * their own JITAI (G09-G11) but not for the global gap and caps (G12-G15, jitai-correctness-13).
 */
public object GateEvaluator {
    /** Engine days of counted rows the gates need: the weekly window plus a margin for 7-day cooldowns. */
    public const val RECENT_ENGINE_DAYS: Int = 9

    /** A snooze follow-up may fire this much before its snooze ends (the evaluator's coalescing window). */
    public val FOLLOW_UP_TOLERANCE: Duration = 2.minutes

    public fun evaluate(input: GateInput): GateReport = GateReport(liveChecks(input) + budgetChecks(input))

    /** G01-G08 in order. */
    public fun liveChecks(input: GateInput): List<GateCheck> {
        val settings = input.settings
        val snoozeNow = if (input.snoozeFollowUp) input.now + FOLLOW_UP_TOLERANCE else input.now
        return listOf(
            effective(input),
            expired(input),
            check(ReasonCode.SNOOZED, input.runtime.snoozedUntil?.let { isBefore(snoozeNow, it) } != true),
            check(ReasonCode.GLOBAL_PAUSE, settings.pauseUntil?.let { input.now.wall < it } != true),
            check(ReasonCode.NOTIFICATIONS_BLOCKED, input.deliveryReady),
            quietHours(input),
            check(ReasonCode.DND, !input.interruptionFilter.blocksDelivery, input.interruptionFilter.name),
            check(ReasonCode.SUPPRESSED_BY_RULE, input.suppressedBy.isEmpty(), "rules=${input.suppressedBy.size}"),
        )
    }

    /** G09-G15 in order. */
    public fun budgetChecks(input: GateInput): List<GateCheck> {
        val settings = input.settings
        val today = EngineDays.of(input.now.wall, input.zone, settings.rolloverMinute)
        val week = EngineDays.window(today, WEEK_DAYS)
        val limits = EffectiveLimits.of(input.definition)
        val own = input.recent.filter { it.jitaiId == input.definition.id }
        val global = input.recent.filter { it.state.countsGlobally }
        return listOf(
            cooldown(input, limits, own),
            count(ReasonCode.DAILY_CAP, own.count { it.engineDay == today }, limits.maxPerDay, input.snoozeFollowUp),
            count(ReasonCode.WEEKLY_CAP, own.count { it.engineDay in week }, limits.maxPerWeek, input.snoozeFollowUp),
            minGap(input, global),
            count(ReasonCode.GLOBAL_DAILY_CAP, global.count { it.engineDay == today }, settings.globalMaxPerDay),
            count(ReasonCode.GLOBAL_WEEKLY_CAP, global.count { it.engineDay in week }, settings.globalMaxPerWeek),
            count(
                ReasonCode.CHANNEL_CAP,
                global.count { it.engineDay == today && it.channel == input.channel },
                settings.channelCap(input.channel),
            ),
        )
    }

    /** When the global gap of [input] ends (null: no counted row, or it already ended); for deferrals (jitai-correctness-05). */
    public fun minGapEndsIn(input: GateInput): Duration? {
        val gap = input.settings.minGapMinutes.minutes
        val elapsed = sinceLatest(input.recent.filter { it.state.countsGlobally }, input.now) ?: return null
        return (gap - elapsed).takeIf { it.isPositive() }
    }

    private const val WEEK_DAYS = 7

    private fun check(gate: ReasonCode, passed: Boolean, detail: String? = null) = GateCheck(gate, passed, detail)

    private fun effective(input: GateInput): GateCheck {
        val state = input.definitionState
        val detail = if (state == null) "deleted" else "enabled=${state.enabled} status=${state.status}"
        return check(ReasonCode.NOT_EFFECTIVE, state?.isEffective == true, detail)
    }

    private fun expired(input: GateInput): GateCheck {
        val expiresAt = input.definitionState?.expiresAt ?: input.definition.expiresAt
        return check(ReasonCode.EXPIRED, expiresAt == null || input.now.wall < expiresAt)
    }

    private fun quietHours(input: GateInput): GateCheck {
        val quiet = input.settings.quietHours
        if (!quiet.enabled) return check(ReasonCode.QUIET_HOURS, true, "off")
        val window = LocalWindow.of(quiet.start, quiet.end)?.takeIf { it.isValid }
            ?: return check(ReasonCode.QUIET_HOURS, false, "invalid_setting")
        val local = input.now.wall.toLocalDateTime(input.zone)
        val inside = window.containsMinute(local.hour * MINUTES_PER_HOUR + local.minute)
        val allowed = input.definition.delivery.quietHoursPolicy == QuietHoursPolicy.ALLOW_WHEN_INTERACTIVE && input.interactive == Tri.TRUE
        return check(ReasonCode.QUIET_HOURS, !inside || allowed, "inside=$inside interactive=${input.interactive}")
    }

    private fun cooldown(input: GateInput, limits: EffectiveLimits, own: List<DecisionRecord>): GateCheck {
        if (input.snoozeFollowUp) return check(ReasonCode.COOLDOWN, true, SKIPPED)
        val effective = Backoff.effectiveCooldown(limits.cooldownMinutes.minutes, input.runtime.consecutiveIgnored)
        val elapsed = sinceLatest(own + listOfNotNull(input.latestOwn), input.now)
            ?: return check(ReasonCode.COOLDOWN, true, "never")
        return check(ReasonCode.COOLDOWN, elapsed >= effective, "elapsed=${elapsed.inWholeSeconds}s cooldown=${effective.inWholeMinutes}m")
    }

    private fun minGap(input: GateInput, global: List<DecisionRecord>): GateCheck {
        val gap = input.settings.minGapMinutes.minutes
        val elapsed = sinceLatest(global, input.now) ?: return check(ReasonCode.GLOBAL_MIN_GAP, true, "never")
        return check(ReasonCode.GLOBAL_MIN_GAP, elapsed >= gap, "elapsed=${elapsed.inWholeSeconds}s gap=${gap.inWholeMinutes}m")
    }

    private fun count(gate: ReasonCode, count: Int, limit: Int, skipped: Boolean = false): GateCheck =
        if (skipped) check(gate, true, SKIPPED) else check(gate, count < limit, "count=$count limit=$limit")

    /** Elapsed time since the most recent cooldown anchor among [rows] (R10 §8.3, §8.6), or null without rows. */
    public fun sinceLatest(rows: List<DecisionRecord>, now: MonotonicStamp): Duration? =
        rows.filter { it.state.counted }.minOfOrNull { elapsedBetween(it.cooldownAnchor, now) }

    /** The engine days whose counted rows [evaluate] needs. */
    public fun recentDays(today: LocalDate): ClosedRange<LocalDate> = EngineDays.window(today, RECENT_ENGINE_DAYS)

    private const val MINUTES_PER_HOUR = 60
    private const val SKIPPED = "skipped:snooze_follow_up"
}
