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
import dev.agentle.jitai.engine.schedule.TimerRow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlinx.datetime.LocalDate
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/** One response that did not win (R10 §8.7: later responses go to the append-only response log). */
public data class LoggedResponse(val decisionKey: String, val response: JitaiResponse, val at: Instant)

/**
 * In-memory [DecisionStore] with the semantics the Room implementation must have:
 * - every write runs under one process-wide [Mutex] (the serialized commit of red team database-sync-05);
 * - the stored state is an immutable value swapped in at the end of a write, so a failed or crashed transaction rolls
 *   back, a reader sees one consistent state ([readSnapshot]) and the store is safe on real threads (the stress test runs
 *   passes on `Dispatchers.Default`);
 * - the decision key is UNIQUE, rows are inserted only in an initial state and every update is a conditional update
 *   checked against [DecisionStateMachine];
 * - the timer table is part of the same state, so a commit's decisions and timer rows change together;
 * - [deleteInterventionHistory] clears content but keeps the content-free ledger;
 * - a commit for another database generation is rejected.
 *
 * Suspension points ([yield]) sit where a database call would suspend, so passes running concurrently on a
 * `StandardTestDispatcher` really interleave. [death] simulates process death at a protocol step.
 *
 * @param definitionState what `jitai_definition` holds now (the transaction re-reads G01/G02 through it).
 */
public class InMemoryDecisionStore(
    private val definitionState: (String) -> DefinitionState? = { null },
    generation: String = "db-1",
    public val death: ProcessDeath = ProcessDeath(),
) : DecisionStore {
    private val writeLock = Mutex()

    @Volatile
    private var data = Data(generation = generation)

    /** Operations that fail once with a database error (`"commit"`, `"cas"`, `"read"`, `"decision"`, `"runtime"`, ...). */
    public val failNext: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** How many commits ran to completion. */
    @Volatile
    public var commits: Int = 0
        private set

    /** What the ledger queries read. */
    private interface State {
        val rows: Map<String, DecisionRecord>
        val runtime: Map<String, JitaiRuntimeState>
        val timers: Map<String, TimerRow>
    }

    /** One immutable state of the database; never changed after it was published. */
    private class Data(
        override val rows: Map<String, DecisionRecord> = emptyMap(),
        val evalLog: List<EvalLogEntry> = emptyList(),
        val responses: List<LoggedResponse> = emptyList(),
        override val runtime: Map<String, JitaiRuntimeState> = emptyMap(),
        override val timers: Map<String, TimerRow> = emptyMap(),
        val generation: String,
        val watermark: Long = 0,
        val dirty: Boolean = false,
        val sequence: Long = 0,
    ) : State

    /** The mutable copy one write works on. */
    private class Working(from: Data) : State {
        override val rows = LinkedHashMap(from.rows)
        val evalLog = from.evalLog.toMutableList()
        val responses = from.responses.toMutableList()
        override val runtime = LinkedHashMap(from.runtime)
        override val timers = LinkedHashMap(from.timers)
        var generation = from.generation
        var watermark = from.watermark
        var dirty = from.dirty
        var sequence = from.sequence
        var inserted = 0
        var claimed = 0

        fun freeze(): Data = Data(
            rows = LinkedHashMap(rows),
            evalLog = evalLog.toList(),
            responses = responses.toList(),
            runtime = LinkedHashMap(runtime),
            timers = LinkedHashMap(timers),
            generation = generation,
            watermark = watermark,
            dirty = dirty,
            sequence = sequence,
        )
    }

    // -- test access ---------------------------------------------------------------------------------------------

    public fun rows(): List<DecisionRecord> = data.rows.values.toList()

    public fun row(key: String): DecisionRecord? = data.rows[key]

    public fun evalLog(): List<EvalLogEntry> = data.evalLog

    public fun responseLog(): List<LoggedResponse> = data.responses

    public fun runtimeOf(jitaiId: String): JitaiRuntimeState = data.runtime[jitaiId] ?: JitaiRuntimeState(jitaiId)

    /** The timer table ordered by `dueAt`, then key. */
    public fun timerRows(): List<TimerRow> = sortedTimers(data)

    public fun timer(key: String): TimerRow? = data.timers[key]

    public fun watermark(): Long = data.watermark

    /** Seeds a row as if an earlier pass had written it (any state; the sequence is assigned here). Setup only. */
    public fun seed(record: DecisionRecord) {
        mutateNow { working ->
            working.sequence += 1
            working.rows[record.decisionKey] = record.copy(sequence = working.sequence)
        }
    }

    /** Seeds runtime state directly. Setup only. */
    public fun seedRuntime(state: JitaiRuntimeState) {
        mutateNow { it.runtime[state.jitaiId] = state }
    }

    /** Seeds a timer row directly. Setup only. */
    public fun seedTimer(row: TimerRow) {
        mutateNow { it.timers[row.key] = row }
    }

    /** Simulates a restore from another device: the database generation changes. */
    public fun replaceGeneration(generation: String) {
        mutateNow { it.generation = generation }
    }

    /**
     * Deletes the timer rows of [jitaiId]: what `jitai_definition` changes do in their own transaction
     * ([dev.agentle.jitai.engine.ports.JitaiRepositoryPort]).
     */
    public suspend fun deleteTimersOf(jitaiId: String) {
        write { working -> working.timers.values.removeAll { it.jitaiId == jitaiId } }
    }

    // -- DecisionStore ------------------------------------------------------------------------------------------

    override suspend fun <T> readSnapshot(block: suspend (LedgerView) -> T): Outcome<T> {
        death.check()
        yield()
        if (failNext.remove("read")) return injected()
        val result = block(Ledger(data))
        yield()
        return Outcome.success(result)
    }

    override suspend fun <T> commit(expectedGeneration: String, block: suspend (DecisionTransaction) -> T): Outcome<T> {
        death.check()
        yield()
        return writeLock.withLock {
            death.check()
            if (failNext.remove("commit")) return@withLock injected()
            if (data.generation != expectedGeneration) {
                return@withLock Outcome.failure(AppError.DatabaseError("generation_mismatch"))
            }
            val working = Working(data)
            val result = try {
                block(Tx(working))
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                return@withLock Outcome.failure(AppError.DatabaseError("rollback:${e::class.simpleName}"))
            }
            if (working.inserted > 0) death.fireIf(CrashPoint.BEFORE_COMMIT)
            yield()
            data = working.freeze()
            commits++
            if (working.inserted > 0) death.fireIf(CrashPoint.AFTER_COMMIT)
            if (working.claimed > 0) death.fireIf(CrashPoint.AFTER_CLAIM)
            Outcome.success(result)
        }
    }

    override suspend fun decision(key: String): Outcome<DecisionRecord?> {
        death.check()
        if (failNext.remove("decision")) return injected()
        return Outcome.success(data.rows[key])
    }

    override suspend fun compareAndSet(expected: DecisionState, updated: DecisionRecord): Outcome<Boolean> {
        death.check()
        yield()
        return writeLock.withLock {
            death.check()
            if (failNext.remove("cas")) return@withLock injected()
            if (!DecisionStateMachine.isLegal(expected, updated.state)) {
                return@withLock Outcome.failure(AppError.DatabaseError("illegal_transition:$expected->${updated.state}"))
            }
            val working = Working(data)
            val current = working.rows[updated.decisionKey] ?: return@withLock Outcome.success(false)
            if (current.state != expected) return@withLock Outcome.success(false)
            working.rows[updated.decisionKey] = updated.copy(sequence = current.sequence)
            data = working.freeze()
            when (updated.state) {
                DecisionState.DELIVERING -> death.fireIf(CrashPoint.AFTER_CLAIM)
                DecisionState.DELIVERED -> death.fireIf(CrashPoint.AFTER_MARK)
                else -> Unit
            }
            Outcome.success(true)
        }
    }

    override suspend fun decisionsInStates(states: Set<DecisionState>): Outcome<List<DecisionRecord>> {
        death.check()
        if (failNext.remove("scan")) return injected()
        return Outcome.success(data.rows.values.filter { it.state in states })
    }

    override suspend fun runtime(jitaiId: String): Outcome<JitaiRuntimeState> {
        death.check()
        if (failNext.remove("runtime")) return injected()
        return Outcome.success(runtimeOf(jitaiId))
    }

    override suspend fun engineState(): Outcome<EngineState> {
        death.check()
        if (failNext.remove("engine_state")) return injected()
        val current = data
        return Outcome.success(EngineState(current.generation, current.watermark, current.dirty))
    }

    override suspend fun clearDirtyIfSet(): Outcome<Boolean> = write { working ->
        val was = working.dirty
        working.dirty = false
        was
    }

    override suspend fun markDirty(): Outcome<Unit> = write { working -> working.dirty = true }

    override suspend fun timers(): Outcome<List<TimerRow>> {
        death.check()
        if (failNext.remove("timers")) return injected()
        return Outcome.success(sortedTimers(data))
    }

    override suspend fun deleteInterventionHistory(): Outcome<Int> = write { working ->
        val count = working.rows.size
        working.rows.replaceAll { _, row -> row.withoutContent() }
        working.evalLog.clear()
        working.responses.clear()
        count
    }

    override suspend fun applyRetention(cutoffs: RetentionCutoffs): Outcome<RetentionReport> = write { working ->
        val before = working.rows.size
        working.rows.values.removeAll { it.decisionPointAt < cutoffs.ledgerBefore }
        val ledgerDeleted = before - working.rows.size
        val logBefore = working.evalLog.size
        working.evalLog.removeAll { it.at < cutoffs.evalLogBefore }
        var reduced = 0
        working.rows.replaceAll { _, row ->
            if (row.decisionPointAt < cutoffs.fullTraceBefore && row.content.traceJson != null) {
                reduced++
                row.copy(content = row.content.copy(traceJson = null, snapshotJson = null))
            } else {
                row
            }
        }
        RetentionReport(ledgerDeleted, logBefore - working.evalLog.size, reduced)
    }

    // -- internals ----------------------------------------------------------------------------------------------

    /** One small write transaction under the lock. */
    private suspend fun <T> write(block: (Working) -> T): Outcome<T> {
        death.check()
        return writeLock.withLock {
            death.check()
            if (failNext.remove("write")) return@withLock injected()
            val working = Working(data)
            val result = block(working)
            data = working.freeze()
            Outcome.success(result)
        }
    }

    /** Setup writes from the test thread while no pass runs. */
    private fun mutateNow(block: (Working) -> Unit) {
        synchronized(this) {
            val working = Working(data)
            block(working)
            data = working.freeze()
        }
    }

    private fun <T> injected(): Outcome<T> = Outcome.failure(AppError.DatabaseError("injected"))

    private open inner class Ledger(private val view: State) : LedgerView {
        override suspend fun existingKeys(keys: Collection<String>): Set<String> = keys.filterTo(linkedSetOf()) { it in view.rows }

        override suspend fun decision(key: String): DecisionRecord? = view.rows[key]

        override suspend fun countedInEngineDays(days: ClosedRange<LocalDate>): List<DecisionRecord> =
            view.rows.values.filter { it.state.counted && it.engineDay in days }

        override suspend fun recentCounted(jitaiId: String, limit: Int): List<DecisionRecord> =
            view.rows.values.filter { it.jitaiId == jitaiId && it.state.counted }.sortedByDescending { it.sequence }.take(limit)

        override suspend fun countedCount(jitaiId: String): Long = view.rows.values.count {
            it.jitaiId == jitaiId && it.state.counted
        }.toLong()

        override suspend fun runtime(jitaiId: String): JitaiRuntimeState = view.runtime[jitaiId] ?: JitaiRuntimeState(jitaiId)

        override suspend fun timers(): List<TimerRow> = view.timers.values.sortedWith(TIMER_ORDER)
    }

    private inner class Tx(private val state: Working) :
        Ledger(state),
        DecisionTransaction {
        override suspend fun definitionState(jitaiId: String): DefinitionState? = this@InMemoryDecisionStore.definitionState(jitaiId)

        override suspend fun insertIfAbsent(record: DecisionRecord): Boolean {
            check(DecisionStateMachine.canInsert(record.state)) { "rows are inserted in an initial state" }
            if (record.decisionKey in state.rows) return false
            state.sequence += 1
            state.rows[record.decisionKey] = record.copy(sequence = state.sequence)
            state.inserted++
            return true
        }

        override suspend fun compareAndSet(expected: DecisionState, updated: DecisionRecord): Boolean {
            check(DecisionStateMachine.isLegal(expected, updated.state)) { "illegal transition" }
            val current = state.rows[updated.decisionKey] ?: return false
            if (current.state != expected) return false
            state.rows[updated.decisionKey] = updated.copy(sequence = current.sequence)
            if (updated.state == DecisionState.DELIVERING) state.claimed++
            return true
        }

        override suspend fun appendEvalLog(entry: EvalLogEntry) {
            state.evalLog += entry
        }

        override suspend fun recordResponse(key: String, response: JitaiResponse, at: Instant): ResponseWrite {
            val current = state.rows[key] ?: return ResponseWrite.NOT_FOUND
            if (current.content.response != JitaiResponse.NONE) {
                state.responses += LoggedResponse(key, response, at)
                return ResponseWrite.ALREADY_RESPONDED
            }
            state.rows[key] = current.copy(content = current.content.copy(response = response, respondedAt = at))
            return ResponseWrite.RECORDED
        }

        override suspend fun currentWatermark(): Long = state.watermark

        override suspend fun advanceWatermark(changeSeq: Long) {
            state.watermark = maxOf(state.watermark, changeSeq)
        }

        override suspend fun putRuntime(state: JitaiRuntimeState) {
            this.state.runtime[state.jitaiId] = state
        }

        override suspend fun putTimer(row: TimerRow) {
            state.timers[row.key] = row
        }

        override suspend fun deleteTimer(key: String) {
            state.timers.remove(key)
        }

        override suspend fun replaceTimers(rows: List<TimerRow>) {
            state.timers.clear()
            rows.forEach { state.timers[it.key] = it }
        }
    }

    private companion object {
        val TIMER_ORDER: Comparator<TimerRow> = compareBy<TimerRow>({ it.dueAt }, { it.key })

        fun sortedTimers(data: Data): List<TimerRow> = data.timers.values.sortedWith(TIMER_ORDER)
    }
}
