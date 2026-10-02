package dev.agentle.jitai.engine.testing

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.jitai.engine.decision.DecisionRecord
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.DecisionStateMachine
import dev.agentle.jitai.engine.decision.JitaiResponse
import dev.agentle.jitai.engine.ports.DecisionStore
import dev.agentle.jitai.engine.ports.DecisionTransaction
import dev.agentle.jitai.engine.ports.DefinitionState
import dev.agentle.jitai.engine.ports.EngineState
import dev.agentle.jitai.engine.ports.EvalLogEntry
import dev.agentle.jitai.engine.ports.JitaiRuntimeState
import dev.agentle.jitai.engine.ports.LedgerView
import dev.agentle.jitai.engine.ports.ResponseWrite
import dev.agentle.jitai.engine.ports.RetentionCutoffs
import dev.agentle.jitai.engine.ports.RetentionReport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlinx.datetime.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/** Thrown by the fakes to simulate the process dying at a protocol step. An [Error], so no engine code catches it. */
public class SimulatedCrash(public val point: CrashPoint) : Error("simulated crash at $point")

/** Runs [block] and returns the [SimulatedCrash] it must throw; fails when it returns normally. */
public suspend fun expectCrash(block: suspend () -> Unit): SimulatedCrash {
    try {
        block()
    } catch (crash: SimulatedCrash) {
        return crash
    }
    throw AssertionError("expected a simulated crash")
}

/** Where a fake simulates process death (R10 §12.P). */
public enum class CrashPoint {
    /** The commit transaction never starts. */
    BEFORE_COMMIT,

    /** The commit transaction committed; the process dies before the claim. */
    AFTER_COMMIT,

    /** DECIDED -> DELIVERING was written; the process dies before posting. */
    AFTER_CLAIM,

    /** The notification was posted; the process dies before DELIVERING -> DELIVERED ([FakeDeliveryPort]). */
    AFTER_POST,

    /** DELIVERING -> DELIVERED was written; the process dies right after. */
    AFTER_MARK,
}

/** One response that did not win (R10 §8.7: later responses go to the append-only response log). */
public data class LoggedResponse(val decisionKey: String, val response: JitaiResponse, val at: Instant)

/**
 * In-memory [DecisionStore] with the semantics the Room implementation must have:
 * - every write runs under one process-wide [Mutex] (the serialized commit of red team database-sync-05);
 * - [commit] works on a copy and swaps it in only when the block returns, so a failure or crash rolls back;
 * - [readSnapshot] reads one consistent copy;
 * - the decision key is UNIQUE, rows are inserted only in an initial state and every update is a conditional update
 *   checked against [DecisionStateMachine];
 * - [deleteInterventionHistory] clears content but keeps the content-free ledger;
 * - a commit for another database generation is rejected.
 *
 * Suspension points ([yield]) sit where a database call would suspend, so passes running concurrently on a
 * `StandardTestDispatcher` really interleave. [crashAt] injects process death once.
 *
 * @param definitionState what `jitai_definition` holds now (the transaction re-reads G01/G02 through it).
 */
public class InMemoryDecisionStore(private val definitionState: (String) -> DefinitionState? = { null }, generation: String = "db-1") :
    DecisionStore {
    private val writeLock = Mutex()
    private var data = Data(generation = generation)
    private val responses = mutableListOf<LoggedResponse>()

    /** Process death to simulate once at the given point (cleared when it fires). */
    public var crashAt: CrashPoint? = null

    /** Operations that fail once with a database error (`"commit"`, `"cas"`, `"read"`, `"runtime"`, ...). */
    public val failNext: MutableSet<String> = mutableSetOf()

    /** How many commits ran to completion. */
    public var commits: Int = 0
        private set

    private class Data(
        val rows: LinkedHashMap<String, DecisionRecord> = LinkedHashMap(),
        val evalLog: MutableList<EvalLogEntry> = mutableListOf(),
        val runtime: MutableMap<String, JitaiRuntimeState> = mutableMapOf(),
        var generation: String,
        var watermark: Long = 0,
        var dirty: Boolean = false,
        var sequence: Long = 0,
    ) {
        fun copy(): Data =
            Data(LinkedHashMap(rows), evalLog.toMutableList(), runtime.toMutableMap(), generation, watermark, dirty, sequence)
    }

    // -- test access ---------------------------------------------------------------------------------------------

    public fun rows(): List<DecisionRecord> = data.rows.values.toList()

    public fun row(key: String): DecisionRecord? = data.rows[key]

    public fun evalLog(): List<EvalLogEntry> = data.evalLog.toList()

    public fun responseLog(): List<LoggedResponse> = responses.toList()

    public fun runtimeOf(jitaiId: String): JitaiRuntimeState = data.runtime[jitaiId] ?: JitaiRuntimeState(jitaiId)

    /** Seeds a row as if an earlier pass had written it (any state; the sequence is assigned here). */
    public fun seed(record: DecisionRecord) {
        data.sequence += 1
        data.rows[record.decisionKey] = record.copy(sequence = data.sequence)
    }

    /** Seeds runtime state directly. */
    public fun seedRuntime(state: JitaiRuntimeState) {
        data.runtime[state.jitaiId] = state
    }

    /** Simulates a restore from another device: the database generation changes. */
    public fun replaceGeneration(generation: String) {
        data.generation = generation
    }

    // -- DecisionStore ------------------------------------------------------------------------------------------

    override suspend fun <T> readSnapshot(block: suspend (LedgerView) -> T): Outcome<T> {
        yield()
        if (failNext.remove("read")) return injected()
        val snapshot = data.copy()
        val result = block(View(snapshot))
        yield()
        return Outcome.success(result)
    }

    override suspend fun <T> commit(expectedGeneration: String, block: suspend (DecisionTransaction) -> T): Outcome<T> {
        yield()
        return writeLock.withLock {
            if (crashAt == CrashPoint.BEFORE_COMMIT) crash()
            if (failNext.remove("commit")) return@withLock injected()
            if (data.generation != expectedGeneration) {
                return@withLock Outcome.failure(AppError.DatabaseError("generation_mismatch"))
            }
            val working = data.copy()
            val result = try {
                block(Tx(working))
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                return@withLock Outcome.failure(AppError.DatabaseError("rollback:${e::class.simpleName}"))
            }
            yield()
            data = working
            commits++
            if (crashAt == CrashPoint.AFTER_COMMIT) crash()
            Outcome.success(result)
        }
    }

    override suspend fun decision(key: String): Outcome<DecisionRecord?> {
        if (failNext.remove("decision")) return injected()
        return Outcome.success(data.rows[key])
    }

    override suspend fun compareAndSet(expected: DecisionState, updated: DecisionRecord): Outcome<Boolean> {
        yield()
        return writeLock.withLock {
            if (failNext.remove("cas")) return@withLock injected()
            val current = data.rows[updated.decisionKey] ?: return@withLock Outcome.success(false)
            if (!DecisionStateMachine.isLegal(expected, updated.state)) {
                return@withLock Outcome.failure(AppError.DatabaseError("illegal_transition:$expected->${updated.state}"))
            }
            if (current.state != expected) return@withLock Outcome.success(false)
            data.rows[updated.decisionKey] = updated.copy(sequence = current.sequence)
            when {
                updated.state == DecisionState.DELIVERING && crashAt == CrashPoint.AFTER_CLAIM -> crash()
                updated.state == DecisionState.DELIVERED && crashAt == CrashPoint.AFTER_MARK -> crash()
            }
            Outcome.success(true)
        }
    }

    override suspend fun decisionsInStates(states: Set<DecisionState>): Outcome<List<DecisionRecord>> {
        if (failNext.remove("scan")) return injected()
        return Outcome.success(data.rows.values.filter { it.state in states })
    }

    override suspend fun recordResponse(key: String, response: JitaiResponse, at: Instant): Outcome<ResponseWrite> = writeLock.withLock {
        val current = data.rows[key] ?: return@withLock Outcome.success(ResponseWrite.NOT_FOUND)
        if (current.content.response != JitaiResponse.NONE) {
            responses += LoggedResponse(key, response, at)
            return@withLock Outcome.success(ResponseWrite.ALREADY_RESPONDED)
        }
        data.rows[key] = current.copy(content = current.content.copy(response = response, respondedAt = at))
        Outcome.success(ResponseWrite.RECORDED)
    }

    override suspend fun runtime(jitaiId: String): Outcome<JitaiRuntimeState> {
        if (failNext.remove("runtime")) return injected()
        return Outcome.success(runtimeOf(jitaiId))
    }

    override suspend fun updateRuntime(jitaiId: String, transform: (JitaiRuntimeState) -> JitaiRuntimeState): Outcome<JitaiRuntimeState> =
        writeLock.withLock {
            val updated = transform(runtimeOf(jitaiId))
            data.runtime[jitaiId] = updated
            Outcome.success(updated)
        }

    override suspend fun engineState(): Outcome<EngineState> = Outcome.success(EngineState(data.generation, data.watermark, data.dirty))

    override suspend fun clearDirtyIfSet(): Outcome<Boolean> = writeLock.withLock {
        val was = data.dirty
        data.dirty = false
        Outcome.success(was)
    }

    override suspend fun markDirty(): Outcome<Unit> = writeLock.withLock {
        data.dirty = true
        Outcome.success(Unit)
    }

    override suspend fun appendEvalLog(entries: List<EvalLogEntry>): Outcome<Unit> = writeLock.withLock {
        data.evalLog += entries
        Outcome.success(Unit)
    }

    override suspend fun deleteInterventionHistory(): Outcome<Int> = writeLock.withLock {
        val count = data.rows.size
        data.rows.replaceAll { _, row -> row.withoutContent() }
        data.evalLog.clear()
        responses.clear()
        Outcome.success(count)
    }

    override suspend fun applyRetention(cutoffs: RetentionCutoffs): Outcome<RetentionReport> = writeLock.withLock {
        val before = data.rows.size
        data.rows.values.removeAll { it.decisionPointAt < cutoffs.ledgerBefore }
        val ledgerDeleted = before - data.rows.size
        val logBefore = data.evalLog.size
        data.evalLog.removeAll { it.at < cutoffs.evalLogBefore }
        var reduced = 0
        data.rows.replaceAll { _, row ->
            if (row.decisionPointAt < cutoffs.fullTraceBefore && row.content.traceJson != null) {
                reduced++
                row.copy(content = row.content.copy(traceJson = null, snapshotJson = null))
            } else {
                row
            }
        }
        Outcome.success(RetentionReport(ledgerDeleted, logBefore - data.evalLog.size, reduced))
    }

    private fun crash(): Nothing {
        val point = crashAt ?: error("no crash point")
        crashAt = null
        throw SimulatedCrash(point)
    }

    private fun <T> injected(): Outcome<T> = Outcome.failure(AppError.DatabaseError("injected"))

    private open inner class View(protected val state: Data) : LedgerView {
        override suspend fun existingKeys(keys: Collection<String>): Set<String> = keys.filterTo(linkedSetOf()) { it in state.rows }

        override suspend fun decision(key: String): DecisionRecord? = state.rows[key]

        override suspend fun countedInEngineDays(days: ClosedRange<LocalDate>): List<DecisionRecord> =
            state.rows.values.filter { it.state.counted && it.engineDay in days }

        override suspend fun recentCounted(jitaiId: String, limit: Int): List<DecisionRecord> =
            state.rows.values.filter { it.jitaiId == jitaiId && it.state.counted }.sortedByDescending { it.sequence }.take(limit)

        override suspend fun countedCount(jitaiId: String): Long = state.rows.values.count {
            it.jitaiId == jitaiId && it.state.counted
        }.toLong()

        override suspend fun runtime(jitaiId: String): JitaiRuntimeState = state.runtime[jitaiId] ?: JitaiRuntimeState(jitaiId)
    }

    private inner class Tx(state: Data) :
        View(state),
        DecisionTransaction {
        override suspend fun definitionState(jitaiId: String): DefinitionState? = this@InMemoryDecisionStore.definitionState(jitaiId)

        override suspend fun insertIfAbsent(record: DecisionRecord): Boolean {
            check(DecisionStateMachine.canInsert(record.state)) { "rows are inserted in an initial state" }
            if (record.decisionKey in state.rows) return false
            state.sequence += 1
            state.rows[record.decisionKey] = record.copy(sequence = state.sequence)
            return true
        }

        override suspend fun appendEvalLog(entry: EvalLogEntry) {
            state.evalLog += entry
        }

        override suspend fun currentWatermark(): Long = state.watermark

        override suspend fun advanceWatermark(changeSeq: Long) {
            state.watermark = maxOf(state.watermark, changeSeq)
        }

        override suspend fun putRuntime(state: JitaiRuntimeState) {
            this.state.runtime[state.jitaiId] = state
        }
    }
}
