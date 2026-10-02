package dev.agentle.data.jitai

import dev.agentle.core.database.EngineStateKeys
import dev.agentle.core.database.LineageCodec
import dev.agentle.core.database.entity.JitaiDecisionEntity
import dev.agentle.core.database.entity.JitaiEvalLogEntity
import dev.agentle.core.database.entity.JitaiResponseLogEntity
import dev.agentle.core.database.entity.JitaiRuntimeEntity
import dev.agentle.core.database.entity.JitaiTimerEntity
import dev.agentle.core.model.Lineage
import dev.agentle.core.time.AgentleClock
import dev.agentle.data.DataAccess
import dev.agentle.data.Tx
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** One instant on both device clocks (R10 §8.6): wall ms, elapsed realtime ms and the boot count (null if unknown). */
data class Stamp(val wallMs: Long, val elapsedMs: Long, val bootCount: Int?)

/** The content of a decision: cleared by "delete intervention history", scrubbed by category deletions. */
data class DecisionContent(
    val snapshotJson: String? = null,
    val snapshotHash: String? = null,
    val traceJson: String? = null,
    val traceSummaryJson: String? = null,
    val contentRef: String? = null,
    val response: String = DecisionStates.RESPONSE_NONE,
    val respondedMs: Long? = null,
)

/**
 * One `jitai_decision` row (docs/research/10 §8.3; round 2 correction 4; round 3 correction 2). It mirrors
 * `DecisionRecord` of `:jitai:engine` with plain values, so the engine's `DecisionStore` binds to [JitaiLedger] through
 * a mapping adapter. [lineage] lists the data categories and families of the snapshot's inputs.
 */
data class DecisionRow(
    val decisionKey: String,
    val jitaiId: String,
    val jitaiVersion: Int,
    val triggerType: String,
    val category: String,
    val channel: String,
    val state: String,
    val decided: Stamp,
    val zoneId: String,
    val localDateTime: String,
    val engineDay: String,
    /** The nominal decision time the key names (slot start, event time, follow-up time); null on older rows. */
    val nominalMs: Long? = null,
    /** The delivery deadline on both clocks, anchored at the nominal time; null for rows that never deliver. */
    val deadline: Stamp? = null,
    val reason: String? = null,
    val reasonDetail: String? = null,
    val conditionsResult: String? = null,
    val contextResult: String? = null,
    val randProbability: Double? = null,
    val randDraw: Double? = null,
    val nonce: String? = null,
    val claimed: Stamp? = null,
    val leaseUntil: Stamp? = null,
    val delivered: Stamp? = null,
    val finishedMs: Long? = null,
    val recovered: Boolean = false,
    val impliedStateJson: String? = null,
    val content: DecisionContent = DecisionContent(),
    val lineage: Lineage = Lineage.NONE,
    val sequence: Long = 0,
)

/**
 * The decision states (R10 §8.3, full enum, plus CARD_PENDING of round 3 correction 2) and the legal transitions.
 * Counted states take part in caps, cooldowns and the global gap.
 */
object DecisionStates {
    const val NOT_TRIGGERED: String = "NOT_TRIGGERED"
    const val NOT_AVAILABLE: String = "NOT_AVAILABLE"
    const val UNKNOWN: String = "UNKNOWN"
    const val MISSED: String = "MISSED"
    const val SUPPRESSED: String = "SUPPRESSED"
    const val NOT_RANDOMIZED: String = "NOT_RANDOMIZED"
    const val DECIDED: String = "DECIDED"
    const val DELIVERING: String = "DELIVERING"
    const val CARD_PENDING: String = "CARD_PENDING"
    const val DELIVERED: String = "DELIVERED"
    const val DELIVERY_UNCERTAIN: String = "DELIVERY_UNCERTAIN"
    const val FAILED: String = "FAILED"
    const val EXPIRED: String = "EXPIRED"
    const val CANCELLED: String = "CANCELLED"
    const val RESPONSE_NONE: String = "NONE"

    val ALL: Set<String> = setOf(
        NOT_TRIGGERED, NOT_AVAILABLE, UNKNOWN, MISSED, SUPPRESSED, NOT_RANDOMIZED, DECIDED, DELIVERING, CARD_PENDING,
        DELIVERED, DELIVERY_UNCERTAIN, FAILED, EXPIRED, CANCELLED,
    )

    /** Rows that count toward their own JITAI's cooldown and caps. */
    val COUNTED: Set<String> = setOf(DECIDED, DELIVERING, CARD_PENDING, DELIVERED, DELIVERY_UNCERTAIN)

    /** Rows that also count toward the global gap and caps (an in-app card does not). */
    val COUNTED_GLOBALLY: Set<String> = setOf(DECIDED, DELIVERING, DELIVERED, DELIVERY_UNCERTAIN)

    /** Rows that count as a delivery in the intervention-history features (R10 §5.4 I). */
    val DELIVERIES: Set<String> = setOf(DELIVERED, DELIVERY_UNCERTAIN)

    /** States a row may be inserted in. */
    val INITIAL: Set<String> = setOf(NOT_TRIGGERED, NOT_AVAILABLE, UNKNOWN, MISSED, SUPPRESSED, NOT_RANDOMIZED, DECIDED)

    /**
     * The legal transitions (R10 §8.3 with the red-team corrections): the claim and its outcomes, the in-app card
     * fallback, and DELIVERING -> DECIDED when a worker is cancelled before it posted (the claim is undone).
     */
    private val NEXT: Map<String, Set<String>> = mapOf(
        DECIDED to setOf(DELIVERING, CARD_PENDING, EXPIRED, CANCELLED, SUPPRESSED),
        DELIVERING to setOf(DELIVERED, DELIVERY_UNCERTAIN, FAILED, SUPPRESSED, CARD_PENDING, DECIDED),
        CARD_PENDING to setOf(DELIVERED, EXPIRED, CANCELLED),
    )

    fun isLegal(from: String, to: String): Boolean = to in NEXT[from].orEmpty()
}

/** A trigger event held back by the debounce (R10 §7.3). */
data class PendingEventRow(val changeSeq: Long, val type: String, val eventAtMs: Long, val activityState: String? = null)

/** `jitai_runtime`: survives process death; not intervention history. */
data class RuntimeRow(
    val jitaiId: String,
    val snoozedUntil: Stamp? = null,
    val snoozeMode: String? = null,
    /** The decision whose `RE_EVALUATE_AFTER` follow-up is pending. */
    val followUpOf: String? = null,
    val consecutiveIgnored: Int = 0,
    val lastEventEvaluation: Stamp? = null,
    val pendingEvent: PendingEventRow? = null,
    /** Result signature of the latest evaluation-log entry written with a trace. */
    val lastEvalSignature: String? = null,
)

/**
 * One `jitai_timer` row (round 3 correction 1): it mirrors the engine's `TimerRow` with plain values. [slot] is the
 * engine's text form of the decision point; [jitaiId] and [version] are null for the BACKSTOP row.
 */
data class TimerRecord(
    val key: String,
    val kind: String,
    val dueAtMs: Long,
    val jitaiId: String? = null,
    val version: Int? = null,
    val slot: String? = null,
    val decisionKey: String? = null,
    val originalKey: String? = null,
    val role: String? = null,
    val featureIds: Set<String> = emptySet(),
    val deferrals: Int = 0,
)

/** The engine's `engine_state` values (red team database-sync-04). */
data class EngineStateRow(val dbGeneration: String, val watermark: Long, val dirty: Boolean)

/** The stored definition as a commit re-reads it (gate G01). */
data class DefinitionStateRow(val version: Int, val enabled: Boolean, val state: String, val expiresMs: Long?)

/** A non-firing evaluation of an event trigger (`jitai_eval_log`, 30 days). */
data class EvalLogRow(
    val atMs: Long,
    val jitaiId: String,
    val triggerKind: String,
    val eventType: String?,
    val result: String,
    val reason: String? = null,
    val traceJson: String? = null,
    val lineage: Lineage = Lineage.NONE,
)

/** The proximal outcome of a decision (R10 §8.7). */
data class OutcomeRow(val decisionKey: String, val state: String, val value: Double?, val metricJson: String?, val computedMs: Long)

sealed interface LedgerCommit<out T> {
    data class Committed<out T>(val value: T) : LedgerCommit<T>

    /** The database is not the one the pass read (restored or recreated): nothing was written. */
    data object GenerationMismatch : LedgerCommit<Nothing>
}

enum class CasResult { UPDATED, STATE_MOVED, ILLEGAL_TRANSITION, NOT_FOUND }

enum class ResponseResult { RECORDED, ALREADY_RESPONDED, NOT_FOUND }

data class LedgerRetention(val ledgerRowsDeleted: Int, val evalLogRowsDeleted: Int, val tracesReduced: Int)

/** Read-only ledger queries inside one transaction. */
interface LedgerReader {
    suspend fun existingKeys(keys: Collection<String>): Set<String>

    suspend fun decision(key: String): DecisionRow?

    /** Counted rows whose engine day lies in `fromDay..toDay` (ISO dates). */
    suspend fun countedInEngineDays(fromDay: String, toDay: String): List<DecisionRow>

    /** The latest counted rows of [jitaiId], newest first by insertion order. */
    suspend fun recentCounted(jitaiId: String, limit: Int): List<DecisionRow>

    suspend fun countedCount(jitaiId: String): Long

    suspend fun runtime(jitaiId: String): RuntimeRow

    /** Every timer row, ordered by due time then key. */
    suspend fun timers(): List<TimerRecord>
}

/** The write side of one serialized commit (R10 §8.4). */
interface LedgerWriter : LedgerReader {
    suspend fun definitionState(jitaiId: String): DefinitionStateRow?

    /** Inserts [row] unless its key exists; false means "already decided". [row] must be in an initial state. */
    suspend fun insertIfAbsent(row: DecisionRow): Boolean

    /**
     * Writes [updated] only while its row is in [expectedState]; false when the state moved on. An illegal transition
     * throws [IllegalStateException], which rolls the whole commit back.
     */
    suspend fun compareAndSet(expectedState: String, updated: DecisionRow): Boolean

    /** First response wins (R10 §8.7); later responses only go to the append-only response log. */
    suspend fun recordResponse(key: String, response: String, atMs: Long): ResponseResult

    suspend fun appendEvalLog(entry: EvalLogRow)

    suspend fun currentWatermark(): Long

    /** Moves the trigger-event watermark forward, never backward. */
    suspend fun advanceWatermark(changeSeq: Long)

    suspend fun putRuntime(state: RuntimeRow)

    /** Inserts or replaces the timer row with [timer]'s key. */
    suspend fun putTimer(timer: TimerRecord)

    suspend fun deleteTimer(key: String)

    /** Replaces the whole timer table with [timers] (a re-plan), in this transaction. */
    suspend fun replaceTimers(timers: List<TimerRecord>)
}

/**
 * The decision ledger (round 2 correction 4): mirrors the engine's `DecisionStore`. [commit] is the decision-commit
 * runner: one process-wide mutex and one IMMEDIATE transaction, so counts read inside it cannot be passed by a
 * concurrent pass; [readSnapshot] is the read-transaction runner. The content-free ledger is kept 400 days and survives
 * "delete intervention history" and the retention of other data.
 */
interface JitaiLedger {
    suspend fun <T> readSnapshot(block: suspend (LedgerReader) -> T): T

    suspend fun <T> commit(expectedGeneration: String, block: suspend (LedgerWriter) -> T): LedgerCommit<T>

    suspend fun decision(key: String): DecisionRow?

    /** Writes [updated] only while its row is in [expectedState] and the transition is legal. */
    suspend fun compareAndSet(expectedState: String, updated: DecisionRow): CasResult

    suspend fun decisionsInStates(states: Set<String>): List<DecisionRow>

    /** The newest decisions decided at or after [fromMs] (history screens). */
    suspend fun recent(fromMs: Long, limit: Int): List<DecisionRow>

    /**
     * The newest [limit] deliveries ([DecisionStates.DELIVERIES]) of [jitaiId], or of every JITAI of [category], or of
     * every JITAI when both are null; newest first by insertion order, never by wall time (R10 §8.6).
     */
    suspend fun latestDeliveries(jitaiId: String?, category: String?, limit: Int): List<DecisionRow>

    /** Deliveries whose stored engine day lies in `fromDay..toDay` (ISO dates). */
    suspend fun deliveriesInEngineDays(fromDay: String, toDay: String): List<DecisionRow>

    /** Every timer row, ordered by due time then key. */
    suspend fun timers(): List<TimerRecord>

    /** The earliest due time of all timer rows; emits on every change (the one unique timer work targets it). */
    fun observeNextDue(): Flow<Long?>

    /** First response wins (R10 §8.7); later responses only go to the append-only response log. */
    suspend fun recordResponse(key: String, response: String, atMs: Long): ResponseResult

    suspend fun runtime(jitaiId: String): RuntimeRow

    suspend fun updateRuntime(jitaiId: String, transform: (RuntimeRow) -> RuntimeRow): RuntimeRow

    suspend fun engineState(): EngineStateRow

    /** Clears the JITAI dirty flag; true when it was set. */
    suspend fun clearDirtyIfSet(): Boolean

    suspend fun markDirty()

    suspend fun appendEvalLog(entries: List<EvalLogRow>)

    /** Stores the outcome of [decisionKey] unless one exists (first computation wins). */
    suspend fun recordOutcome(outcome: OutcomeRow, lineage: Lineage): Boolean

    suspend fun outcome(decisionKey: String): OutcomeRow?

    /** Clears decision content and empties the evaluation and response logs and the outcomes; the ledger stays. */
    suspend fun deleteInterventionHistory(): Int

    suspend fun applyRetention(ledgerBeforeMs: Long, evalLogBeforeMs: Long, fullTraceBeforeMs: Long): LedgerRetention
}

internal class RoomJitaiLedger(private val access: DataAccess, private val clock: AgentleClock) : JitaiLedger {
    override suspend fun <T> readSnapshot(block: suspend (LedgerReader) -> T): T = access.read { block(Reader(this)) }

    override suspend fun <T> commit(expectedGeneration: String, block: suspend (LedgerWriter) -> T): LedgerCommit<T> = access.write {
        val generation = db.stateDao().state(EngineStateKeys.DB_GENERATION)?.textValue
        if (generation != expectedGeneration) LedgerCommit.GenerationMismatch else LedgerCommit.Committed(block(Writer(this)))
    }

    override suspend fun decision(key: String): DecisionRow? = access.read { db.jitaiDao().decision(key)?.let(::rowOf) }

    override suspend fun compareAndSet(expectedState: String, updated: DecisionRow): CasResult =
        access.write { casOf(this, expectedState, updated) }

    override suspend fun decisionsInStates(states: Set<String>): List<DecisionRow> =
        access.read { db.jitaiDao().inStates(states.toList()).map(::rowOf) }

    override suspend fun recent(fromMs: Long, limit: Int): List<DecisionRow> = access.read {
        db.jitaiDao().recent(fromMs, limit).map(::rowOf)
    }

    override suspend fun latestDeliveries(jitaiId: String?, category: String?, limit: Int): List<DecisionRow> = access.read {
        val states = DecisionStates.DELIVERIES.toList()
        val rows = when {
            jitaiId != null -> db.jitaiDao().recentOf(jitaiId, states, limit)
            category != null -> db.jitaiDao().recentOfCategory(category, states, limit)
            else -> db.jitaiDao().recentInStates(states, limit)
        }
        rows.map(::rowOf)
    }

    override suspend fun deliveriesInEngineDays(fromDay: String, toDay: String): List<DecisionRow> = access.read {
        db.jitaiDao().inEngineDays(fromDay, toDay, DecisionStates.DELIVERIES.toList()).map(::rowOf)
    }

    override suspend fun timers(): List<TimerRecord> = access.read { db.jitaiDao().timers().map(::timerOf) }

    override fun observeNextDue(): Flow<Long?> = flow { emitAll(access.database().jitaiDao().observeNextDue()) }

    override suspend fun recordResponse(key: String, response: String, atMs: Long): ResponseResult =
        access.write { responseOf(this, key, response, atMs) }

    override suspend fun runtime(jitaiId: String): RuntimeRow = access.read { runtimeOf(this, jitaiId) }

    override suspend fun updateRuntime(jitaiId: String, transform: (RuntimeRow) -> RuntimeRow): RuntimeRow = access.write {
        val next = transform(runtimeOf(this, jitaiId)).copy(jitaiId = jitaiId)
        putRuntime(this, next)
        next
    }

    override suspend fun engineState(): EngineStateRow = access.read {
        val state = db.stateDao()
        EngineStateRow(
            dbGeneration = state.state(EngineStateKeys.DB_GENERATION)?.textValue.orEmpty(),
            watermark = state.state(EngineStateKeys.JITAI_WATERMARK)?.intValue ?: 0L,
            dirty = (state.state(EngineStateKeys.JITAI_DIRTY)?.intValue ?: 0L) != 0L,
        )
    }

    override suspend fun clearDirtyIfSet(): Boolean = access.write {
        val dirty = (db.stateDao().state(EngineStateKeys.JITAI_DIRTY)?.intValue ?: 0L) != 0L
        if (dirty) db.stateDao().putState(EngineStateKeys.JITAI_DIRTY, 0L, null)
        dirty
    }

    override suspend fun markDirty() {
        access.write { db.stateDao().putState(EngineStateKeys.JITAI_DIRTY, 1L, null) }
    }

    override suspend fun appendEvalLog(entries: List<EvalLogRow>) {
        if (entries.isEmpty()) return
        access.write { db.jitaiDao().insertEvalLog(entries.map(::evalEntity)) }
    }

    override suspend fun recordOutcome(outcome: OutcomeRow, lineage: Lineage): Boolean = access.write {
        db.jitaiDao().insertOutcomeIfAbsent(
            decisionKey = outcome.decisionKey,
            outcomeState = outcome.state,
            outcomeValue = outcome.value,
            metricJson = outcome.metricJson,
            computedMs = outcome.computedMs,
            lineage = LineageCodec.encode(lineage),
        )
        sql.changes() > 0
    }

    override suspend fun outcome(decisionKey: String): OutcomeRow? = access.read {
        db.jitaiDao().outcome(decisionKey)?.let {
            OutcomeRow(it.decisionKey, it.outcomeState, it.outcomeValue, it.metricJson, it.computedMs)
        }
    }

    override suspend fun deleteInterventionHistory(): Int = access.write {
        val dao = db.jitaiDao()
        val cleared = dao.clearAllContent()
        sql.execute("UPDATE jitai_decision SET lineage = ?", LineageCodec.EMPTY)
        dao.clearEvalLog()
        dao.clearResponseLog()
        dao.clearOutcomes()
        cleared
    }

    override suspend fun applyRetention(ledgerBeforeMs: Long, evalLogBeforeMs: Long, fullTraceBeforeMs: Long): LedgerRetention =
        access.write {
            val dao = db.jitaiDao()
            val ledger = dao.deleteDecisionsBefore(ledgerBeforeMs)
            val eval = dao.pruneEvalLog(evalLogBeforeMs)
            val traces = dao.reduceTracesBefore(fullTraceBeforeMs)
            dao.deleteOrphanOutcomes()
            dao.deleteOrphanResponses()
            LedgerRetention(ledger, eval, traces)
        }

    // ------------------------------------------------------------------------------------------- transaction views

    private open inner class Reader(protected val tx: Tx) : LedgerReader {
        override suspend fun existingKeys(keys: Collection<String>): Set<String> =
            keys.chunked(MAX_IN_ARGS).flatMap { tx.db.jitaiDao().existingKeys(it) }.toSet()

        override suspend fun decision(key: String): DecisionRow? = tx.db.jitaiDao().decision(key)?.let(::rowOf)

        override suspend fun countedInEngineDays(fromDay: String, toDay: String): List<DecisionRow> =
            tx.db.jitaiDao().inEngineDays(fromDay, toDay, DecisionStates.COUNTED.toList()).map(::rowOf)

        override suspend fun recentCounted(jitaiId: String, limit: Int): List<DecisionRow> =
            tx.db.jitaiDao().recentOf(jitaiId, DecisionStates.COUNTED.toList(), limit).map(::rowOf)

        override suspend fun countedCount(jitaiId: String): Long = tx.db.jitaiDao().countOf(jitaiId, DecisionStates.COUNTED.toList())

        override suspend fun runtime(jitaiId: String): RuntimeRow = runtimeOf(tx, jitaiId)

        override suspend fun timers(): List<TimerRecord> = tx.db.jitaiDao().timers().map(::timerOf)
    }

    private inner class Writer(tx: Tx) :
        Reader(tx),
        LedgerWriter {
        override suspend fun definitionState(jitaiId: String): DefinitionStateRow? =
            tx.db.jitaiDao().definition(jitaiId)?.let { DefinitionStateRow(it.currentVersion, it.enabled, it.state, it.expiresMs) }

        override suspend fun insertIfAbsent(row: DecisionRow): Boolean {
            require(row.state in DecisionStates.INITIAL) { "rows are inserted in an initial state" }
            if (tx.db.jitaiDao().decision(row.decisionKey) != null) return false
            tx.db.jitaiDao().insertDecision(entityOf(row).copy(seq = 0))
            return true
        }

        override suspend fun compareAndSet(expectedState: String, updated: DecisionRow): Boolean =
            when (casOf(tx, expectedState, updated)) {
                CasResult.UPDATED -> true
                CasResult.STATE_MOVED, CasResult.NOT_FOUND -> false
                CasResult.ILLEGAL_TRANSITION -> throw IllegalStateException("illegal transition $expectedState -> ${updated.state}")
            }

        override suspend fun recordResponse(key: String, response: String, atMs: Long): ResponseResult = responseOf(tx, key, response, atMs)

        override suspend fun appendEvalLog(entry: EvalLogRow) {
            tx.db.jitaiDao().insertEvalLog(listOf(evalEntity(entry)))
        }

        override suspend fun putTimer(timer: TimerRecord) {
            tx.db.jitaiDao().deleteTimer(timer.key)
            tx.db.jitaiDao().insertTimer(timerEntity(timer, nowMs()))
        }

        override suspend fun deleteTimer(key: String) {
            tx.db.jitaiDao().deleteTimer(key)
        }

        override suspend fun replaceTimers(timers: List<TimerRecord>) {
            require(timers.map { it.key }.toSet().size == timers.size) { "timer keys must be unique" }
            tx.db.jitaiDao().deleteAllTimers()
            val now = nowMs()
            timers.forEach { tx.db.jitaiDao().insertTimer(timerEntity(it, now)) }
        }

        override suspend fun currentWatermark(): Long = tx.db.stateDao().state(EngineStateKeys.JITAI_WATERMARK)?.intValue ?: 0L

        override suspend fun advanceWatermark(changeSeq: Long) {
            if (changeSeq > currentWatermark()) tx.db.stateDao().putState(EngineStateKeys.JITAI_WATERMARK, changeSeq, null)
        }

        override suspend fun putRuntime(state: RuntimeRow) = putRuntime(tx, state)
    }

    private suspend fun casOf(tx: Tx, expectedState: String, updated: DecisionRow): CasResult {
        val stored = tx.db.jitaiDao().decision(updated.decisionKey) ?: return CasResult.NOT_FOUND
        return when {
            stored.state != expectedState -> CasResult.STATE_MOVED

            !DecisionStates.isLegal(expectedState, updated.state) -> CasResult.ILLEGAL_TRANSITION

            else -> {
                tx.db.jitaiDao().updateDecision(entityOf(updated).copy(seq = stored.seq))
                CasResult.UPDATED
            }
        }
    }

    private suspend fun responseOf(tx: Tx, key: String, response: String, atMs: Long): ResponseResult {
        val dao = tx.db.jitaiDao()
        if (dao.setResponseIfNone(key, response, atMs) > 0) return ResponseResult.RECORDED
        if (dao.decision(key) == null) return ResponseResult.NOT_FOUND
        dao.insertResponseLog(JitaiResponseLogEntity(decisionKey = key, response = response, atMs = atMs))
        return ResponseResult.ALREADY_RESPONDED
    }

    private fun nowMs(): Long = clock.now().toEpochMilliseconds()

    private suspend fun runtimeOf(tx: Tx, jitaiId: String): RuntimeRow {
        val row = tx.db.jitaiDao().runtime(jitaiId) ?: return RuntimeRow(jitaiId)
        return RuntimeRow(
            jitaiId = jitaiId,
            snoozedUntil = row.snoozedUntilMs?.let { Stamp(it, row.snoozedUntilElapsedMs ?: 0L, row.snoozedUntilBoot) },
            snoozeMode = row.snoozeMode,
            followUpOf = row.followUpOf,
            consecutiveIgnored = row.consecutiveIgnored,
            lastEventEvaluation = row.lastEventEvalMs?.let { Stamp(it, row.lastEventEvalElapsedMs ?: 0L, row.lastEventEvalBoot) },
            pendingEvent = row.pendingChangeSeq?.let { seq ->
                PendingEventRow(seq, row.pendingEventType.orEmpty(), row.pendingEventMs ?: 0L, row.pendingActivityState)
            },
            lastEvalSignature = row.lastEvalSignature,
        )
    }

    private suspend fun putRuntime(tx: Tx, state: RuntimeRow) {
        val entity = JitaiRuntimeEntity(
            jitaiId = state.jitaiId,
            snoozedUntilMs = state.snoozedUntil?.wallMs,
            snoozedUntilElapsedMs = state.snoozedUntil?.elapsedMs,
            snoozedUntilBoot = state.snoozedUntil?.bootCount,
            snoozeMode = state.snoozeMode,
            followUpOf = state.followUpOf,
            consecutiveIgnored = state.consecutiveIgnored,
            lastEventEvalMs = state.lastEventEvaluation?.wallMs,
            lastEventEvalElapsedMs = state.lastEventEvaluation?.elapsedMs,
            lastEventEvalBoot = state.lastEventEvaluation?.bootCount,
            pendingChangeSeq = state.pendingEvent?.changeSeq,
            pendingEventType = state.pendingEvent?.type,
            pendingEventMs = state.pendingEvent?.eventAtMs,
            pendingActivityState = state.pendingEvent?.activityState,
            lastEvalSignature = state.lastEvalSignature,
            updatedMs = nowMs(),
        )
        if (tx.db.jitaiDao().runtime(state.jitaiId) ==
            null
        ) {
            tx.db.jitaiDao().insertRuntime(entity)
        } else {
            tx.db.jitaiDao().updateRuntime(entity)
        }
    }

    companion object {
        private const val MAX_IN_ARGS = 500

        fun entityOf(row: DecisionRow): JitaiDecisionEntity = JitaiDecisionEntity(
            seq = row.sequence,
            decisionKey = row.decisionKey,
            jitaiId = row.jitaiId,
            jitaiVersion = row.jitaiVersion,
            triggerType = row.triggerType,
            category = row.category,
            channel = row.channel,
            state = row.state,
            decidedMs = row.decided.wallMs,
            decidedElapsedMs = row.decided.elapsedMs,
            bootCount = row.decided.bootCount,
            zoneId = row.zoneId,
            localDateTime = row.localDateTime,
            engineDay = row.engineDay,
            nominalMs = row.nominalMs,
            deadlineMs = row.deadline?.wallMs,
            deadlineElapsedMs = row.deadline?.elapsedMs,
            deadlineBoot = row.deadline?.bootCount,
            reason = row.reason,
            reasonDetail = row.reasonDetail,
            conditionsResult = row.conditionsResult,
            contextResult = row.contextResult,
            randProbability = row.randProbability,
            randDraw = row.randDraw,
            deliveryNonce = row.nonce,
            claimedMs = row.claimed?.wallMs,
            claimedElapsedMs = row.claimed?.elapsedMs,
            claimedBoot = row.claimed?.bootCount,
            leaseUntilMs = row.leaseUntil?.wallMs,
            leaseUntilElapsedMs = row.leaseUntil?.elapsedMs,
            leaseBootCount = row.leaseUntil?.bootCount,
            deliveredMs = row.delivered?.wallMs,
            deliveredElapsedMs = row.delivered?.elapsedMs,
            deliveredBoot = row.delivered?.bootCount,
            finishedMs = row.finishedMs,
            recovered = row.recovered,
            impliedStateJson = row.impliedStateJson,
            snapshotJson = row.content.snapshotJson,
            snapshotHash = row.content.snapshotHash,
            traceJson = row.content.traceJson,
            traceSummaryJson = row.content.traceSummaryJson,
            contentRef = row.content.contentRef,
            response = row.content.response,
            respondedMs = row.content.respondedMs,
            lineage = LineageCodec.encode(row.lineage),
        )

        fun rowOf(entity: JitaiDecisionEntity): DecisionRow = DecisionRow(
            decisionKey = entity.decisionKey,
            jitaiId = entity.jitaiId,
            jitaiVersion = entity.jitaiVersion,
            triggerType = entity.triggerType,
            category = entity.category,
            channel = entity.channel,
            state = entity.state,
            decided = Stamp(entity.decidedMs, entity.decidedElapsedMs, entity.bootCount),
            zoneId = entity.zoneId,
            localDateTime = entity.localDateTime,
            engineDay = entity.engineDay,
            nominalMs = entity.nominalMs,
            deadline = entity.deadlineMs?.let { Stamp(it, entity.deadlineElapsedMs ?: 0L, entity.deadlineBoot) },
            reason = entity.reason,
            reasonDetail = entity.reasonDetail,
            conditionsResult = entity.conditionsResult,
            contextResult = entity.contextResult,
            randProbability = entity.randProbability,
            randDraw = entity.randDraw,
            nonce = entity.deliveryNonce,
            claimed = entity.claimedMs?.let { Stamp(it, entity.claimedElapsedMs ?: 0L, entity.claimedBoot) },
            leaseUntil = entity.leaseUntilMs?.let { Stamp(it, entity.leaseUntilElapsedMs ?: 0L, entity.leaseBootCount) },
            delivered = entity.deliveredMs?.let { Stamp(it, entity.deliveredElapsedMs ?: 0L, entity.deliveredBoot) },
            finishedMs = entity.finishedMs,
            recovered = entity.recovered,
            impliedStateJson = entity.impliedStateJson,
            content = DecisionContent(
                snapshotJson = entity.snapshotJson,
                snapshotHash = entity.snapshotHash,
                traceJson = entity.traceJson,
                traceSummaryJson = entity.traceSummaryJson,
                contentRef = entity.contentRef,
                response = entity.response,
                respondedMs = entity.respondedMs,
            ),
            lineage = LineageCodec.decode(entity.lineage),
            sequence = entity.seq,
        )

        fun timerEntity(timer: TimerRecord, nowMs: Long): JitaiTimerEntity = JitaiTimerEntity(
            id = timer.key,
            dueAtMs = timer.dueAtMs,
            kind = timer.kind,
            jitaiId = timer.jitaiId,
            version = timer.version,
            slot = timer.slot,
            decisionKey = timer.decisionKey,
            originalKey = timer.originalKey,
            role = timer.role,
            featureIds = timer.featureIds.sorted().joinToString(","),
            deferrals = timer.deferrals,
            createdMs = nowMs,
        )

        fun timerOf(entity: JitaiTimerEntity): TimerRecord = TimerRecord(
            key = entity.id,
            kind = entity.kind,
            dueAtMs = entity.dueAtMs,
            jitaiId = entity.jitaiId,
            version = entity.version,
            slot = entity.slot,
            decisionKey = entity.decisionKey,
            originalKey = entity.originalKey,
            role = entity.role,
            featureIds = entity.featureIds.split(',').filter { it.isNotEmpty() }.toSet(),
            deferrals = entity.deferrals,
        )

        fun evalEntity(row: EvalLogRow): JitaiEvalLogEntity = JitaiEvalLogEntity(
            atMs = row.atMs,
            jitaiId = row.jitaiId,
            triggerKind = row.triggerKind,
            eventType = row.eventType,
            result = row.result,
            reason = row.reason,
            traceJson = row.traceJson,
            lineage = LineageCodec.encode(row.lineage),
        )
    }
}
