package dev.agentle.jitai.engine.ports

import dev.agentle.core.common.Outcome
import dev.agentle.jitai.dsl.model.JitaiEventType
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.SnoozeMode
import dev.agentle.jitai.engine.decision.DecisionRecord
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.JitaiResponse
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.decision.TriggerKind
import dev.agentle.jitai.engine.schedule.TimerRow
import dev.agentle.jitai.engine.time.MonotonicStamp
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * Per-JITAI runtime state (`jitai_runtime`, R10 §3.5; red team database-sync-06/07). It survives process death and is
 * not intervention history, so "delete intervention history" keeps it.
 *
 * @property snoozedUntil end of the snooze on both clocks (G03 compares within one boot by elapsed time).
 * @property snoozeMode the mode of the snooze in force.
 * @property followUpOf the decision whose `RE_EVALUATE_AFTER` follow-up is pending (one `R` key per original decision,
 *   jitai-correctness-14); the planner keeps a SNOOZE timer row for it until the follow-up key is used.
 * @property consecutiveIgnored the latest run of ignored deliveries (R10 §9.6), recomputed on every response.
 * @property lastEventEvaluation when an event last caused an evaluation of this JITAI (debounce, R10 §7.3 item 5).
 * @property pendingEvent an event folded by the debounce, evaluated by the next run once the debounce has elapsed.
 * @property lastEvalSignature the result signature of this JITAI's latest evaluation-log entry with a trace; an entry
 *   with the same result is written without its trace (jitai-correctness-10).
 */
public data class JitaiRuntimeState(
    val jitaiId: String,
    val snoozedUntil: MonotonicStamp? = null,
    val snoozeMode: SnoozeMode? = null,
    val followUpOf: String? = null,
    val consecutiveIgnored: Int = 0,
    val lastEventEvaluation: MonotonicStamp? = null,
    val pendingEvent: PendingTriggerEvent? = null,
    val lastEvalSignature: String? = null,
)

/** A trigger event held back by the debounce (R10 §7.3 item 5). */
public data class PendingTriggerEvent(
    val changeSeq: Long,
    val type: JitaiEventType,
    val eventAt: Instant,
    val activityState: String? = null,
)

/**
 * The store's `engine_state` row (red team database-sync-04).
 *
 * @property dbGeneration identifies this database; a commit prepared against another generation is rejected.
 * @property watermark the highest trigger-event `change_seq` already evaluated; advances inside the pass's commit.
 * @property dirty set on every trigger-relevant ingest; the event worker loops until it is clear.
 */
public data class EngineState(val dbGeneration: String, val watermark: Long, val dirty: Boolean)

/** What [DecisionTransaction.definitionState] reads back: enough to re-check G01/G02 and the version inside a transaction. */
public data class DefinitionState(val version: Int, val enabled: Boolean, val status: JitaiStatus, val expiresAt: Instant?) {
    public val isEffective: Boolean get() = enabled && status == JitaiStatus.ACTIVE
}

/**
 * A non-firing evaluation (`jitai_eval_log`, kept 30 days); it never consumes a decision key.
 *
 * @property traceJson the evaluation trace, written only when the result differs from this JITAI's previous entry
 *   (jitai-correctness-10); null otherwise and for entries without an evaluation.
 */
public data class EvalLogEntry(
    val at: Instant,
    val jitaiId: String,
    val triggerKind: TriggerKind,
    val eventType: JitaiEventType?,
    val outcome: EvalOutcome,
    val reason: ReasonCode? = null,
    val traceJson: String? = null,
)

/** Results recorded in the evaluation log. */
public enum class EvalOutcome {
    NOT_TRIGGERED,
    UNKNOWN,
    NOT_AVAILABLE,
    MISSED,
    OUTSIDE_WINDOW,
    DEBOUNCED,

    /** A scheduled point was deferred within its lateness bound (DND, global gap, lost arbitration; jitai-correctness-01/05). */
    DEFERRED,

    /** A `daily_at` point was UNKNOWN only because remote data was stale; it runs again after a sync (R10 §8.2). */
    RETRY,
}

/** Result of [DecisionTransaction.recordResponse]: the first response wins (R10 §8.7); later ones only reach the response log. */
public enum class ResponseWrite { RECORDED, ALREADY_RESPONDED, NOT_FOUND }

/** Retention cutoffs (R10 §8.8): rows older than each instant are removed or reduced. */
public data class RetentionCutoffs(val ledgerBefore: Instant, val evalLogBefore: Instant, val fullTraceBefore: Instant)

/** Counts of a retention run. */
public data class RetentionReport(val ledgerRowsDeleted: Int, val evalLogRowsDeleted: Int, val tracesReduced: Int)

/** Read-only queries over the decision ledger and the timer table, used inside one transaction. */
public interface LedgerView {
    /** The subset of [keys] that already has a row. */
    public suspend fun existingKeys(keys: Collection<String>): Set<String>

    public suspend fun decision(key: String): DecisionRecord?

    /** Counted rows ([DecisionState.counted]) whose engine day lies in [days]. */
    public suspend fun countedInEngineDays(days: ClosedRange<LocalDate>): List<DecisionRecord>

    /** The latest counted rows of [jitaiId], newest first by insertion order, at most [limit]. */
    public suspend fun recentCounted(jitaiId: String, limit: Int): List<DecisionRecord>

    /** How many counted rows [jitaiId] has ever had (variant rotation, R10 §3.3). */
    public suspend fun countedCount(jitaiId: String): Long

    public suspend fun runtime(jitaiId: String): JitaiRuntimeState

    /** Every timer row (jitai-correctness-05). */
    public suspend fun timers(): List<TimerRow>
}

/** The write side of one serialized transaction (R10 §8.4; red team database-sync-05). */
public interface DecisionTransaction : LedgerView {
    /** The definition as stored now, read inside this transaction (G01 and the version are re-checked here, R10 §12.M1). */
    public suspend fun definitionState(jitaiId: String): DefinitionState?

    /**
     * Inserts [record] unless its key exists (UNIQUE index). Returns false on a conflict: "already decided". Rows must be
     * in a [dev.agentle.jitai.engine.decision.DecisionStateMachine.INITIAL] state; the store assigns [DecisionRecord.sequence].
     */
    public suspend fun insertIfAbsent(record: DecisionRecord): Boolean

    /**
     * Conditional update inside this transaction: writes [updated] only when the row is still in [expected] (the claim,
     * jitai-correctness-12). An illegal transition throws, which rolls the transaction back.
     */
    public suspend fun compareAndSet(expected: DecisionState, updated: DecisionRecord): Boolean

    public suspend fun appendEvalLog(entry: EvalLogEntry)

    /**
     * Sets the response of [key] if it is still NONE (R10 §8.7: the first response wins); a later response only goes to
     * the append-only response log.
     */
    public suspend fun recordResponse(key: String, response: JitaiResponse, at: Instant): ResponseWrite

    /** The trigger-event watermark as stored now; an event pass commits only if it did not move (red team database-sync-04). */
    public suspend fun currentWatermark(): Long

    /** Moves the trigger-event watermark forward (never backwards). */
    public suspend fun advanceWatermark(changeSeq: Long)

    public suspend fun putRuntime(state: JitaiRuntimeState)

    /** Inserts or replaces the timer row with [row]'s key. */
    public suspend fun putTimer(row: TimerRow)

    public suspend fun deleteTimer(key: String)

    /** Replaces the whole timer table with [rows] (a re-plan). */
    public suspend fun replaceTimers(rows: List<TimerRow>)
}

/**
 * The decision ledger, runtime state, timer table and engine state (`jitai_decision`, `jitai_runtime`, `jitai_timer`,
 * `jitai_eval_log`, `engine_state`). The Room implementation belongs to ANDROID-DATA; `InMemoryDecisionStore` in this
 * module's test fixtures has the same semantics. Expected failures (I/O, a generation mismatch, an illegal transition)
 * are [Outcome.Failure]; implementations never throw for them.
 *
 * Definition changes (save, enable, disable, delete, expiry, backoff pause) delete the JITAI's timer rows except OUTCOME
 * rows in the same transaction as the change ([JitaiRepositoryPort]); the engine re-plans afterwards
 * (jitai-correctness-05/07).
 */
public interface DecisionStore {
    /**
     * Runs [block] inside one read transaction. A pass builds its whole FeatureSnapshot and its key lookups here, so every
     * value comes from one consistent database state (red team database-sync-05).
     */
    public suspend fun <T> readSnapshot(block: suspend (LedgerView) -> T): Outcome<T>

    /**
     * Serialized write transaction (red team database-sync-05): under one process-wide Mutex and one IMMEDIATE write
     * transaction, runs [block]. Fails without side effects when the database generation is not [expectedGeneration] or
     * [block] throws (rollback).
     */
    public suspend fun <T> commit(expectedGeneration: String, block: suspend (DecisionTransaction) -> T): Outcome<T>

    public suspend fun decision(key: String): Outcome<DecisionRecord?>

    /**
     * Conditional update: writes [updated] only when the row [DecisionRecord.decisionKey] is still in [expected].
     * Returns true when one row changed and false when the state had moved on (another worker owns it). An illegal
     * transition ([dev.agentle.jitai.engine.decision.DecisionStateMachine]) is a failure and changes nothing.
     */
    public suspend fun compareAndSet(expected: DecisionState, updated: DecisionRecord): Outcome<Boolean>

    /** Rows in any of [states] (crash recovery scans DECIDED, DELIVERING and CARD_PENDING). */
    public suspend fun decisionsInStates(states: Set<DecisionState>): Outcome<List<DecisionRecord>>

    public suspend fun runtime(jitaiId: String): Outcome<JitaiRuntimeState>

    public suspend fun engineState(): Outcome<EngineState>

    /** Clears the dirty flag and reports whether it was set (the event worker's loop condition). */
    public suspend fun clearDirtyIfSet(): Outcome<Boolean>

    /** Sets the dirty flag (ingestion of a trigger-relevant event, in the ingest transaction). */
    public suspend fun markDirty(): Outcome<Unit>

    /** Every timer row, ordered by `dueAt` then key. */
    public suspend fun timers(): Outcome<List<TimerRow>>

    /**
     * "Delete intervention history": clears [DecisionRecord.content] of every row and empties the evaluation and response
     * logs, but keeps the content-free ledger so used keys, cooldowns and caps survive. Returns the rows scrubbed.
     */
    public suspend fun deleteInterventionHistory(): Outcome<Int>

    /** Applies [cutoffs]: deletes old ledger and log rows and drops full traces (keeping the trace summary). */
    public suspend fun applyRetention(cutoffs: RetentionCutoffs): Outcome<RetentionReport>
}
