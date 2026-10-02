package dev.agentle.background

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkRequest
import dev.agentle.background.port.AttentionNotifier
import dev.agentle.background.port.CollectionProfile
import dev.agentle.background.port.Collectors
import dev.agentle.background.port.DeletionMarker
import dev.agentle.background.port.EventsRun
import dev.agentle.background.port.FeatureInvalidation
import dev.agentle.background.port.FeatureRefresher
import dev.agentle.background.port.GapSink
import dev.agentle.background.port.JitaiRunner
import dev.agentle.background.port.Maintenance
import dev.agentle.background.port.ProcessExit
import dev.agentle.background.port.ReplanCause
import dev.agentle.background.port.SchedulerSettings
import dev.agentle.background.port.TimerRun
import dev.agentle.background.port.WearableConnection
import dev.agentle.background.port.WearableSync
import dev.agentle.core.common.Outcome
import dev.agentle.core.testing.TestAgentleClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.offsetAt
import java.util.UUID
import kotlin.time.Instant

/** One recorded gateway call. */
data class Call(val op: String, val name: String?, val policy: String?, val request: WorkRequest?)

/** In-memory WorkManager stand-in with the unique-work semantics the scheduler relies on. */
class FakeGateway : WorkGateway {
    val calls = mutableListOf<Call>()
    val works = mutableMapOf<String, MutableList<Pair<UUID, WorkInfo.State>>>()
    val requests = mutableMapOf<UUID, WorkRequest>()

    fun setState(name: String, vararg states: WorkInfo.State) {
        works[name] = states.map { UUID.randomUUID() to it }.toMutableList()
    }

    fun live(name: String) = works[name].orEmpty().filter { !it.second.isFinished }

    override suspend fun enqueuePeriodic(name: String, policy: ExistingPeriodicWorkPolicy, request: PeriodicWorkRequest) {
        calls += Call("periodic", name, policy.name, request)
        val existing = live(name)
        when {
            existing.isEmpty() -> add(name, request, WorkInfo.State.ENQUEUED, replace = true)
            policy == ExistingPeriodicWorkPolicy.UPDATE -> requests[existing.first().first] = request
            else -> Unit
        }
    }

    override suspend fun enqueueOneTime(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest) {
        calls += Call("oneTime", name, policy.name, request)
        val existing = live(name)
        when (policy) {
            ExistingWorkPolicy.KEEP -> if (existing.isEmpty()) add(name, request, WorkInfo.State.ENQUEUED, replace = true)

            ExistingWorkPolicy.REPLACE -> add(name, request, WorkInfo.State.ENQUEUED, replace = true)

            else -> add(
                name,
                request,
                if (existing.isEmpty()) WorkInfo.State.ENQUEUED else WorkInfo.State.BLOCKED,
                replace = existing.isEmpty(),
            )
        }
    }

    private fun add(name: String, request: WorkRequest, state: WorkInfo.State, replace: Boolean) {
        val list = works.getOrPut(name) { mutableListOf() }
        if (replace) list.clear()
        list += request.id to state
        requests[request.id] = request
    }

    override suspend fun update(request: OneTimeWorkRequest) {
        calls += Call("update", null, null, request)
        requests[request.id] = request
    }

    override suspend fun cancelUnique(name: String) {
        calls += Call("cancel", name, null, null)
        works[name] = works[name].orEmpty().map { it.first to WorkInfo.State.CANCELLED }.toMutableList()
    }

    override suspend fun cancelAll() {
        calls += Call("cancelAll", null, null, null)
        works.replaceAll { _, list -> list.map { it.first to WorkInfo.State.CANCELLED }.toMutableList() }
    }

    override suspend fun infos(name: String): List<WorkInfo> = infosNow(name)

    fun infosNow(name: String): List<WorkInfo> =
        works[name].orEmpty().map { (id, state) -> WorkInfo(id, state, setOf(WorkNames.TAG, name)) }

    override fun observe(names: List<String>): Flow<List<WorkInfo>> = flowOf(names.flatMap { infosNow(it) })

    fun enqueues() = calls.filter { it.op == "periodic" || it.op == "oneTime" || it.op == "update" }
}

class MemoryStore : SchedulerStore {
    private val statsFlow = MutableStateFlow<Map<String, WorkStats>>(emptyMap())
    private val reasons = mutableSetOf<ReconcileReason>()
    private val streams = mutableSetOf<String>()
    private val longs = mutableMapOf<String, Long>()
    private val strings = mutableMapOf<String, String>()
    override val stats: StateFlow<Map<String, WorkStats>> = statsFlow
    override fun addReconcileReasons(reasons: Set<ReconcileReason>) {
        this.reasons += reasons
    }
    override fun drainReconcileReasons(): Set<ReconcileReason> = reasons.toSet().also { reasons.clear() }
    override fun addSyncStreams(streams: Set<String>) {
        this.streams += streams.ifEmpty { setOf("*") }
    }
    override fun drainSyncStreams(): Set<String> = streams.toSet().also { streams.clear() }
    override fun recordSuccess(work: String, atEpochMs: Long) = update(work) {
        it.copy(runs = it.runs + 1, lastSuccessEpochMs = atEpochMs, consecutiveFailures = 0)
    }
    override fun recordFailure(work: String, code: String) = update(work) {
        it.copy(runs = it.runs + 1, lastFailureCode = code, consecutiveFailures = it.consecutiveFailures + 1)
    }
    override fun resetFailures(work: String) = update(work) { it.copy(consecutiveFailures = 0) }
    override fun getLong(key: String): Long? = longs[key]
    override fun putLong(key: String, value: Long?) {
        if (value == null) longs.remove(key) else longs[key] = value
    }
    override fun getString(key: String): String? = strings[key]
    override fun putString(key: String, value: String?) {
        if (value == null) strings.remove(key) else strings[key] = value
    }
    private fun update(work: String, f: (WorkStats) -> WorkStats) {
        statsFlow.value = statsFlow.value + (work to f(statsFlow.value[work] ?: WorkStats()))
    }
}

class FakeSignals : DeviceSignals {
    var saver = false
    var bucket: Int? = 10
    var exits = listOf<ProcessExit>()
    var boot = 1
    var version = 1L
    override fun isPowerSaveMode() = saver
    override fun standbyBucket() = bucket
    override fun processExits() = exits
    override fun bootCount() = boot
    override fun appVersionCode() = version
}

class FakeRunner(private val clock: TestAgentleClock) : JitaiRunner {
    var due: Instant? = null
    var timerResult: Outcome<TimerRun> = Outcome.success(TimerRun(null))
    var eventsResults = ArrayDeque<Outcome<EventsRun>>()
    var plannedOffset: Int? = null
    val replans = mutableListOf<ReplanCause>()
    var revalidations = 0
    var replanResult: Outcome<Instant?>? = null
    var replanThrows: Throwable? = null
    var eventsResult: Outcome<EventsRun>? = null
    override suspend fun runTimer() = timerResult
    override suspend fun runEvents() = eventsResults.removeFirstOrNull() ?: eventsResult ?: Outcome.success(EventsRun(false, due))
    override suspend fun replan(cause: ReplanCause): Outcome<Instant?> {
        replanThrows?.let { throw it }
        replans += cause
        plannedOffset = clock.zone().offsetAt(clock.now()).totalSeconds
        return replanResult ?: Outcome.success(due)
    }
    override suspend fun nextDueAt(): Outcome<Instant?> = Outcome.success(due)
    override suspend fun revalidateStoredDefinitions(): Outcome<Int> = Outcome.success(0).also { revalidations++ }
    override suspend fun plannedOffsetSeconds(): Outcome<Int?> = Outcome.success(plannedOffset)
}

class FakeWearable : WearableSync {
    var connection = WearableConnection.CONNECTED
    var result: Outcome<Unit> = Outcome.success(Unit)
    val synced = mutableListOf<Set<String>>()
    var syncAllCount = 0
    var clamps = 0
    override suspend fun connection() = connection
    override suspend fun syncAll() = result.also { syncAllCount++ }
    var syncThrows: Throwable? = null
    override suspend fun syncStreams(streams: Set<String>): Outcome<Unit> {
        syncThrows?.let { throw it }
        synced += streams
        return result
    }
    override suspend fun clampFutureCursors(now: Instant) = Outcome.success(Unit).also { clamps++ }
}

class FakeCollectors : Collectors {
    var result: Outcome<Unit> = Outcome.success(Unit)
    var reregisters = 0
    var usage = 0
    var device = 0
    override suspend fun collectUsage() = result.also { usage++ }
    override suspend fun collectDevice() = result.also { device++ }
    override suspend fun reregisterActivityTransitions() = Outcome.success(Unit).also { reregisters++ }
}

class FakeFeatures : FeatureRefresher {
    val invalidations = mutableListOf<FeatureInvalidation>()
    var refreshes = 0
    override suspend fun refreshDirtyDays() = Outcome.success(Unit).also { refreshes++ }
    override suspend fun invalidate(reason: FeatureInvalidation) = Outcome.success(Unit).also { invalidations += reason }
}

class FakeMaintenance : Maintenance {
    var calls = 0
    override suspend fun weeklyInsights() = Outcome.success(Unit).also { calls++ }
    override suspend fun applyRetention() = Outcome.success(Unit).also { calls++ }
    override suspend fun cleanupMedia() = Outcome.success(Unit).also { calls++ }
}

class FakeGaps : GapSink {
    val recorded = mutableListOf<ProcessExit>()
    override suspend fun recordProcessExits(exits: List<ProcessExit>) = Outcome.success(Unit).also { recorded += exits }
}

class FakeNotifier : AttentionNotifier {
    var count = 0
    override suspend fun reconnectWearable() {
        count++
    }
}

class FakeDeletion : DeletionMarker {
    var inProgress = false
    override fun isDeletionInProgress() = inProgress
}

/** Everything wired together over fakes. */
class Harness {
    val clock = TestAgentleClock()
    val gateway = FakeGateway()
    val store = MemoryStore()
    val signals = FakeSignals()
    val runner = FakeRunner(clock)
    val wearable = FakeWearable()
    val collectors = FakeCollectors()
    val features = FakeFeatures()
    val maintenance = FakeMaintenance()
    val gaps = FakeGaps()
    val notifier = FakeNotifier()
    val deletion = FakeDeletion()
    val profile = MutableStateFlow(CollectionProfile.BALANCED)
    val settings = object : SchedulerSettings {
        override val profile = this@Harness.profile
    }
    val scheduler = WorkScheduler(gateway, store, deletion, runner, signals, clock)
    val jobs = BackgroundJobs(
        scheduler, store, deletion, runner, wearable, collectors, features, maintenance, gaps, settings, notifier,
        signals, clock,
    )
}
