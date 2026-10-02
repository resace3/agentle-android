package dev.agentle.jitai.engine.schedule

import dev.agentle.jitai.dsl.model.OutcomeRole
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/** What a timer row wakes the serialized evaluator for (jitai-correctness-05/07). */
public enum class TimerKind {
    /** A `daily_at` or `interval` decision point. */
    SLOT,

    /** A sync of remote health data ahead of a `daily_at` slot (R10 §7.4 prefetch). */
    PREFETCH,

    /** An outcome computation (R10 §8.7, §15.1). */
    OUTCOME,

    /** The `RE_EVALUATE_AFTER` follow-up of a snoozed decision (R10 §9.5, key `R`). */
    SNOOZE,

    /** Drains the trigger-event watermark while event or interval rules exist (jitai-correctness-10). */
    BACKSTOP,
}

/** The scheduled decision point a SLOT or PREFETCH row names. */
public sealed interface TimerSlot {
    /** The `daily_at` occurrence ([date], [time]) in local time. */
    public data class DailyAt(val date: LocalDate, val time: String) : TimerSlot

    /** Interval slot [index] of the window instance that starts on [instanceDate]. */
    public data class Interval(val instanceDate: LocalDate, val index: Int) : TimerSlot
}

/**
 * One row of the timer table (jitai-correctness-05/07), persisted through [dev.agentle.jitai.engine.ports.DecisionStore].
 * The background team keeps exactly one unique one-time work, [SchedulePlanner.TIMER_WORK], aimed at the minimum
 * [dueAt] of all rows; every engine entry point returns that instant so the work can re-arm itself.
 *
 * @property key unique per row ([TimerKeys]).
 * @property dueAt when the row is due. A row is processed when `dueAt <= now + 2 min` ([SchedulePlanner.COALESCE]).
 * @property version the definition version the row was planned for; a firing row of another version re-plans.
 * @property slot SLOT and PREFETCH: the decision point.
 * @property decisionKey SLOT and PREFETCH: the slot's key; SNOOZE: the follow-up (`R`) key; OUTCOME: the decision.
 * @property originalKey SNOOZE: the snoozed decision whose follow-up this is.
 * @property role OUTCOME: proximal or distal.
 * @property featureIds PREFETCH: the remote features to sync.
 * @property deferrals SLOT: how often the point was deferred (DND, global gap, lost arbitration, stale data).
 */
public data class TimerRow(
    val key: String,
    val kind: TimerKind,
    val dueAt: Instant,
    val jitaiId: String? = null,
    val version: Int? = null,
    val slot: TimerSlot? = null,
    val decisionKey: String? = null,
    val originalKey: String? = null,
    val role: OutcomeRole? = null,
    val featureIds: Set<String> = emptySet(),
    val deferrals: Int = 0,
)

/** Timer row keys: one row per decision point, follow-up and outcome, and one backstop. */
public object TimerKeys {
    public const val BACKSTOP: String = "backstop"

    public fun slot(decisionKey: String): String = "slot|$decisionKey"

    public fun prefetch(decisionKey: String): String = "prefetch|$decisionKey"

    public fun snooze(followUpKey: String): String = "snooze|$followUpKey"

    public fun outcome(decisionKey: String, role: OutcomeRole): String = "outcome|${role.name}|$decisionKey"
}

/**
 * Why the timer table is rebuilt. The background team calls [dev.agentle.jitai.engine.JitaiEngine.replan] with the five
 * [external] reasons (jitai-correctness-04); the engine uses the others itself. Every reason rebuilds every row from the
 * current definitions.
 *
 * @property external a system broadcast: wall instants stored before it may name other moments now, so the rebuild drops
 *   deferred `dueAt`s and starts a new BACKSTOP period. The engine's own reasons keep both.
 */
public enum class ReplanReason(public val external: Boolean) {
    /** `TIME_SET`: the wall clock was moved. */
    CLOCK(external = true),

    /** `ACTION_TIMEZONE_CHANGED`. */
    TIMEZONE(external = true),

    /** `ACTION_TIMEZONE_OFFSET_CHANGED` (DST and other offset changes). */
    OFFSET(external = true),

    /** `BOOT_COMPLETED`: elapsed realtime restarted. */
    BOOT(external = true),

    /** `MY_PACKAGE_REPLACED`. */
    PACKAGE_REPLACED(external = true),

    /** A definition was saved, enabled, disabled, deleted or expired. */
    DEFINITION_CHANGED(external = false),

    /** A response changed the snooze state. */
    RESPONSE(external = false),

    /** Start and end of an evaluator run (reconciliation). */
    EVALUATION(external = false),
}
