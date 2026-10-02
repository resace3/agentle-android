package dev.agentle.jitai.engine.pipeline

import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiEventType
import dev.agentle.jitai.engine.decision.DecisionRecord
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.ImpliedState
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.decision.TriggerKind
import dev.agentle.jitai.engine.outcome.OutcomeResult
import dev.agentle.jitai.engine.ports.EvalLogEntry
import dev.agentle.jitai.engine.schedule.TimerRow
import kotlin.time.Instant

/**
 * One decision point a pass resolves (R10 §8.1 step 1).
 *
 * @property nominalAt the time the key names: the `daily_at` slot, the interval slot start, the event time or the end of
 *   the snooze (jitai-correctness-15). The delivery deadline is anchored here.
 * @property latestAt for scheduled points, the last instant a deferral may aim at (`slot + maxLatenessMinutes` for
 *   `daily_at`, inside the slot for `interval`); null for events and snooze follow-ups, which are never deferred.
 * @property timer the timer row that brought the point to the evaluator; deferrals move it (jitai-correctness-05).
 * @property eventType for event triggers, the type of the latest matching event.
 * @property impliedState for event triggers, the live state to re-check before posting.
 */
public data class DecisionPoint(
    val definition: JitaiDefinition,
    val key: String,
    val kind: TriggerKind,
    val nominalAt: Instant,
    val latestAt: Instant? = null,
    val timer: TimerRow? = null,
    val eventType: JitaiEventType? = null,
    val impliedState: ImpliedState? = null,
) {
    /** A `RE_EVALUATE_AFTER` follow-up: skips G09-G11 (R10 §9.5). */
    public val snoozeFollowUp: Boolean get() = kind == TriggerKind.SNOOZE_FOLLOW_UP
}

/** A scheduled point resolved as MISSED without evaluation (R10 §7.4: every scheduled slot gets exactly one row). */
public data class MissedPoint(
    val definition: JitaiDefinition,
    val key: String,
    val kind: TriggerKind,
    val nominalAt: Instant,
    val reason: ReasonCode,
    val timer: TimerRow? = null,
)

/**
 * A `daily_at` point that was UNKNOWN only because remote data was stale (R10 §8.2 staleness retry): no row is written,
 * its timer row moves to [retryAt] and the background team syncs [syncFeatures] meanwhile.
 */
public data class StalenessRetry(val evaluation: CandidateEvaluation, val retryAt: Instant, val syncFeatures: Set<String>) {
    public val point: DecisionPoint get() = evaluation.point
}

/**
 * A scheduled point deferred within its lateness bound instead of being suppressed (jitai-correctness-01/05): Do Not
 * Disturb (G07), the global gap (G12) or a lost arbitration (G16). Its timer row is due again at [until].
 */
public data class Deferral(val decisionKey: String, val jitaiId: String, val until: Instant, val reason: ReasonCode)

/** Why the background team should sync remote health data now. */
public enum class SyncReason {
    /** 10 minutes before a `daily_at` slot whose rule reads remote data (R10 §7.4). */
    PREFETCH,

    /** A `daily_at` point was UNKNOWN only because remote data was stale; it runs again after the sync (R10 §8.2). */
    STALENESS_RETRY,
}

/** A sync of the remote features [featureIds] that the background team runs (the engine holds no network port). */
public data class SyncRequest(val jitaiId: String, val decisionKey: String, val featureIds: Set<String>, val reason: SyncReason)

/** Which pass produced a report. */
public enum class PassKind { TIMER, EVENTS }

/** What happened to the delivery of a DECIDED row (R10 §8.5). */
public sealed interface DeliveryResult {
    public val decisionKey: String

    public data class Delivered(override val decisionKey: String, val recovered: Boolean, val downgradeReason: String? = null) :
        DeliveryResult

    /**
     * The row ended without a notification, or moved to the in-app card (CARD_PENDING): EXPIRED, CANCELLED, SUPPRESSED
     * (a live gate or the implied state failed at the claim), FAILED or DELIVERY_UNCERTAIN.
     */
    public data class Ended(override val decisionKey: String, val state: DecisionState, val reason: ReasonCode?) : DeliveryResult

    /** Another worker owns the row (the conditional update changed nothing) or it already moved on. */
    public data class Skipped(override val decisionKey: String, val state: DecisionState?) : DeliveryResult

    /** A port failed; the row stays where it was and recovery retries within the deadline. */
    public data class Error(override val decisionKey: String, val code: String) : DeliveryResult
}

/**
 * The result of one evaluation pass.
 *
 * @property written rows inserted by this pass (keys that already existed are not repeated).
 * @property deferred scheduled points moved to a later time within their lateness (no row yet).
 * @property expired JITAIs whose `expiresAt` passed: their status moved to EXPIRED (G02).
 * @property syncRequests staleness retries: sync these remote features before the point runs again.
 * @property nextDueAt the minimum `dueAt` of the timer table after this pass: where `jitai-timer` must aim.
 */
public data class PassReport(
    val kind: PassKind,
    val at: Instant,
    val written: List<DecisionRecord> = emptyList(),
    val evalLog: List<EvalLogEntry> = emptyList(),
    val deliveries: List<DeliveryResult> = emptyList(),
    val deferred: List<Deferral> = emptyList(),
    val expired: List<String> = emptyList(),
    val syncRequests: List<SyncRequest> = emptyList(),
    val nextDueAt: Instant? = null,
) {
    /** The state of [key] after this pass: the delivery outcome for a DECIDED row, else the state it was written in. */
    public fun stateOf(key: String): DecisionState? {
        val written = written.firstOrNull { it.decisionKey == key }?.state ?: return null
        return when (val delivery = deliveries.firstOrNull { it.decisionKey == key }) {
            is DeliveryResult.Delivered -> DecisionState.DELIVERED
            is DeliveryResult.Ended -> delivery.state
            else -> written
        }
    }
}

/** Crash recovery (R10 §8.5): what each DECIDED, expired-lease DELIVERING or CARD_PENDING row became. */
public data class RecoveryReport(val at: Instant, val results: List<DeliveryResult>, val error: String? = null)

/**
 * One run of the `jitai-timer` work (jitai-correctness-05/07): crash recovery, the ignored sweep, the due PREFETCH,
 * OUTCOME, SLOT and SNOOZE rows (the decision points evaluated together and arbitrated), an event pass when the
 * BACKSTOP was due, and the re-plan.
 *
 * @property ignored decision keys marked IGNORED by the sweep (R10 §9.6).
 * @property pass the evaluation of the due decision points (null when none was due).
 * @property events the event pass of a due BACKSTOP row.
 * @property syncRequests prefetches and staleness retries to run now.
 * @property outcomes outcomes computed and recorded by this run.
 * @property replanned content-free reasons of due rows that no longer matched their definition or clock and were re-planned.
 * @property nextDueAt where `jitai-timer` must aim next (null: no timer row left).
 */
public data class TimerReport(
    val at: Instant,
    val recovery: RecoveryReport,
    val ignored: List<String>,
    val pass: PassReport?,
    val events: PassReport?,
    val syncRequests: List<SyncRequest>,
    val outcomes: List<OutcomeResult>,
    val replanned: List<String>,
    val nextDueAt: Instant?,
)

/**
 * Result of the event worker loop (R10 §7.3; red team database-sync-04). [followUpNeeded] asks the caller to enqueue one
 * more run; [nextDueAt] is where `jitai-timer` must aim (a debounced event moves the BACKSTOP row earlier).
 */
public data class EventsReport(val passes: List<PassReport>, val followUpNeeded: Boolean, val nextDueAt: Instant?)
