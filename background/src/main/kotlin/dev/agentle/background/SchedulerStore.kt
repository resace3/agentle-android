package dev.agentle.background

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Per-work bookkeeping for diagnostics and the circuit breaker. Codes only, never messages. */
public data class WorkStats(
    val runs: Int = 0,
    val lastSuccessEpochMs: Long? = null,
    val lastFailureCode: String? = null,
    val consecutiveFailures: Int = 0,
)

/**
 * The scheduler's own operational state (like WorkManager's database, not user settings): pending reconcile reasons
 * and sync streams (so KEEP never loses a request), per-work stats, the process-start record and the battery-saver
 * debounce. Single process (§13), so a lock-guarded SharedPreferences file is enough; `clearApplicationUserData()`
 * erases it with everything else.
 */
public interface SchedulerStore {
    public val stats: StateFlow<Map<String, WorkStats>>

    public fun addReconcileReasons(reasons: Set<ReconcileReason>)

    /** Returns and clears the pending reasons. */
    public fun drainReconcileReasons(): Set<ReconcileReason>

    public fun addSyncStreams(streams: Set<String>)

    public fun drainSyncStreams(): Set<String>

    public fun recordSuccess(work: String, atEpochMs: Long)

    public fun recordFailure(work: String, code: String)

    /** Resets the circuit breaker of [work] (explicit user action). */
    public fun resetFailures(work: String)

    public fun getLong(key: String): Long?

    public fun putLong(key: String, value: Long?)

    public fun getString(key: String): String?

    public fun putString(key: String, value: String?)
}

/** [SchedulerStore] on a private SharedPreferences file, written with commit() (callers are on background threads). */
public class PrefsSchedulerStore(context: Context) : SchedulerStore {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val lock = Any()
    private val state = MutableStateFlow(readStats())

    override val stats: StateFlow<Map<String, WorkStats>> = state.asStateFlow()

    override fun addReconcileReasons(reasons: Set<ReconcileReason>): Unit = synchronized(lock) {
        val all = readSet(KEY_REASONS) + reasons.map { it.name }
        prefs.edit().putStringSet(KEY_REASONS, all).commit()
    }

    override fun drainReconcileReasons(): Set<ReconcileReason> = synchronized(lock) {
        val names = readSet(KEY_REASONS)
        prefs.edit().remove(KEY_REASONS).commit()
        names.mapNotNull { name -> ReconcileReason.entries.firstOrNull { it.name == name } }.toSet()
    }

    override fun addSyncStreams(streams: Set<String>): Unit = synchronized(lock) {
        prefs.edit().putStringSet(KEY_STREAMS, readSet(KEY_STREAMS) + streams.ifEmpty { setOf(ALL_STREAMS) }).commit()
    }

    override fun drainSyncStreams(): Set<String> = synchronized(lock) {
        val streams = readSet(KEY_STREAMS)
        prefs.edit().remove(KEY_STREAMS).commit()
        if (ALL_STREAMS in streams) emptySet() else streams
    }

    override fun recordSuccess(work: String, atEpochMs: Long): Unit = update(work) {
        it.copy(runs = it.runs + 1, lastSuccessEpochMs = atEpochMs, consecutiveFailures = 0)
    }

    override fun recordFailure(work: String, code: String): Unit = update(work) {
        it.copy(runs = it.runs + 1, lastFailureCode = code, consecutiveFailures = it.consecutiveFailures + 1)
    }

    override fun resetFailures(work: String): Unit = update(work) { it.copy(consecutiveFailures = 0) }

    override fun getLong(key: String): Long? = synchronized(lock) { if (prefs.contains(key)) prefs.getLong(key, 0) else null }

    override fun putLong(key: String, value: Long?): Unit = synchronized(lock) {
        prefs.edit().apply { if (value == null) remove(key) else putLong(key, value) }.commit()
    }

    override fun getString(key: String): String? = synchronized(lock) { prefs.getString(key, null) }

    override fun putString(key: String, value: String?): Unit = synchronized(lock) {
        prefs.edit().putString(key, value).commit()
    }

    private fun update(work: String, change: (WorkStats) -> WorkStats): Unit = synchronized(lock) {
        val next = change(state.value[work] ?: WorkStats())
        prefs.edit()
            .putInt("$work.runs", next.runs)
            .putLong("$work.success", next.lastSuccessEpochMs ?: -1)
            .putString("$work.code", next.lastFailureCode)
            .putInt("$work.fails", next.consecutiveFailures)
            .commit()
        state.value = state.value + (work to next)
    }

    private fun readStats(): Map<String, WorkStats> = WorkNames.ALL.associateWith { work ->
        WorkStats(
            runs = prefs.getInt("$work.runs", 0),
            lastSuccessEpochMs = prefs.getLong("$work.success", -1).takeIf { it >= 0 },
            lastFailureCode = prefs.getString("$work.code", null),
            consecutiveFailures = prefs.getInt("$work.fails", 0),
        )
    }

    private fun readSet(key: String): Set<String> = prefs.getStringSet(key, emptySet()).orEmpty().toSet()

    private companion object {
        const val FILE = "agentle_scheduler"
        const val KEY_REASONS = "reconcile.reasons"
        const val KEY_STREAMS = "sync.streams"
        const val ALL_STREAMS = "*"
    }
}
