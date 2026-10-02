package dev.agentle.jitai.engine.pipeline

import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiEventType
import dev.agentle.jitai.engine.decision.DecisionRecord
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.ImpliedState
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.decision.TriggerKind
import dev.agentle.jitai.engine.outcome.PlannedOutcome
import dev.agentle.jitai.engine.ports.EvalLogEntry
import dev.agentle.jitai.engine.schedule.PlannedWork
import dev.agentle.jitai.engine.schedule.TickPlan
import kotlin.time.Instant

/**
 * One decision point a pass resolves (R10 §8.1 step 1).
 *
 * @property eventType for event triggers, the type of the latest matching event.
 * @property impliedState for event triggers, the live state to re-check before posting.
 * @property snoozeFollowUp a `RE_EVALUATE_AFTER` follow-up (skips G09-G11, R10 §9.5).
 */
public data class DecisionPoint(
    val definition: JitaiDefinition,
    val key: String,
    val kind: TriggerKind,
    val eventType: JitaiEventType? = null,
    val impliedState: ImpliedState? = null,
    val snoozeFollowUp: Boolean = false,
)

/** A slot resolved as MISSED without evaluation (R10 §7.4: every scheduled slot gets exactly one row). */
public data class MissedPoint(
    val definition: JitaiDefinition,
    val key: String,
    val kind: TriggerKind,
    val slotAt: Instant,
    val reason: ReasonCode,
)

/** Which pass produced a report. */
public enum class PassKind { TICK, DAILY_AT, EVENTS, SNOOZE_FOLLOW_UP, MISSED }

/** What happened to the delivery of a DECIDED row (R10 §8.5). */
public sealed interface DeliveryResult {
    public val decisionKey: String

    public data class Delivered(override val decisionKey: String, val recovered: Boolean, val downgradeReason: String? = null) :
        DeliveryResult

    /** The row ended without a notification: EXPIRED, CANCELLED, SUPPRESSED(STATE_CHANGED), FAILED or DELIVERY_UNCERTAIN. */
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
 * @property expired JITAIs whose `expiresAt` passed: their status moved to EXPIRED and their work must be cancelled
 *   ([cancelTags]).
 * @property retryAt `daily_at` staleness retry (R10 §8.2): run the same slot again at this instant after a sync of
 *   [syncFeatures]; no row was written.
 * @property followUp extra work to enqueue: debounced or newly arrived events (red team lifecycle-battery-05).
 * @property outcomes outcome computations to schedule (`jitai-outcome-<key>`, KEEP, R10 §8.7).
 */
public data class PassReport(
    val kind: PassKind,
    val at: Instant,
    val written: List<DecisionRecord> = emptyList(),
    val evalLog: List<EvalLogEntry> = emptyList(),
    val deliveries: List<DeliveryResult> = emptyList(),
    val expired: List<String> = emptyList(),
    val cancelTags: Set<String> = emptySet(),
    val retryAt: Instant? = null,
    val syncFeatures: Set<String> = emptySet(),
    val followUp: List<PlannedWork> = emptyList(),
    val outcomes: List<PlannedOutcome> = emptyList(),
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

/** Crash recovery (R10 §8.5): what each DECIDED or expired-lease DELIVERING row became; [error] when the scan failed. */
public data class RecoveryReport(val at: Instant, val results: List<DeliveryResult>, val error: String? = null)

/** A tick: recovery, the interval pass, an event pass when events are pending, and the next tick. */
public data class TickReport(val recovery: RecoveryReport, val pass: PassReport, val events: PassReport?, val nextTick: TickPlan?)

/** Where a `daily_at` run stood (red team lifecycle-battery-04). */
public enum class DailyAtStatus { NOT_SCHEDULED, TOO_EARLY, MISSED, OUTSIDE_WINDOW, RETRY, EVALUATED }

/**
 * Result of a `daily_at` run.
 *
 * @property replan for TOO_EARLY, the occurrence to enqueue again at the recomputed slot.
 */
public data class DailyAtReport(val status: DailyAtStatus, val slotAt: Instant?, val pass: PassReport?, val replan: PlannedWork? = null)

/** Result of the event worker loop (R10 §7.3; red team database-sync-04). */
public data class EventsReport(val passes: List<PassReport>, val followUpNeeded: Boolean)
