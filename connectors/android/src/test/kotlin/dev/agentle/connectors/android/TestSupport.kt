package dev.agentle.connectors.android

import androidx.test.core.app.ApplicationProvider
import dev.agentle.connectors.android.core.CollectorRuntime
import dev.agentle.connectors.android.core.EventFactory
import dev.agentle.connectors.android.core.IdentifierHasher
import dev.agentle.connectors.android.core.SafeCoverage
import dev.agentle.connectors.android.core.SettingsSource
import dev.agentle.connectors.api.CollectionSettings
import dev.agentle.connectors.api.CollectionSettingsStore
import dev.agentle.connectors.api.CollectorEventWriter
import dev.agentle.connectors.api.CommitResult
import dev.agentle.connectors.api.CoverageEndCause
import dev.agentle.connectors.api.CoverageRecorder
import dev.agentle.connectors.api.SyncCursor
import dev.agentle.connectors.api.WriteBatch
import dev.agentle.connectors.api.WriteResult
import dev.agentle.core.common.AppDispatchers
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Logger
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.testing.TestAgentleClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** In-memory [CollectorEventWriter] with the port's dedup, window, cursor and epoch semantics. */
class FakeWriter : CollectorEventWriter {
    var epoch = 1L
    var available = true
    var transactions = 0
    val rows = LinkedHashMap<String, PersonalEvent>()
    val cursors = HashMap<Pair<String, String>, SyncCursor>()

    override suspend fun currentEpoch(): Long = epoch

    override suspend fun write(batch: WriteBatch): WriteResult {
        if (!available) return WriteResult.Unavailable(AppError.DatabaseError("test"))
        if (batch.epoch != epoch) return WriteResult.StaleEpoch
        transactions += 1
        batch.window?.let { w -> rows.values.removeAll { it.source == w.source && it.startTime >= w.start && it.startTime < w.end } }
        var inserted = 0
        var updated = 0
        var ignored = 0
        batch.events.forEach { event ->
            val old = rows[event.dedupKey]
            when {
                old == null -> inserted += 1
                old.payload != event.payload -> updated += 1
                else -> ignored += 1
            }
            if (old == null || old.payload != event.payload) rows[event.dedupKey] = event
        }
        batch.deleteDedupKeys.forEach { rows.remove(it) }
        batch.cursor?.let { cursors[it.connectorId to it.stream] = it }
        return WriteResult.Committed(CommitResult(inserted, updated, ignored))
    }

    override suspend fun cursor(connectorId: String, stream: String): SyncCursor? = cursors[connectorId to stream]

    override suspend fun importFloor(source: DataSourceId): Instant? = null
}

class RecordingCoverage : CoverageRecorder {
    val calls = ArrayList<String>()

    override suspend fun open(collector: String, at: Instant) {
        calls += "open:$collector"
    }

    override suspend fun heartbeat(collector: String, at: Instant) {
        calls += "heartbeat:$collector"
    }

    override suspend fun close(collector: String, at: Instant, cause: CoverageEndCause) {
        calls += "close:$collector:$cause"
    }

    override suspend fun openIntervals(): Map<String, Instant> = emptyMap()
}

class MemorySettingsStore(initial: CollectionSettings = CollectionSettings()) : CollectionSettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<CollectionSettings> get() = state

    override suspend fun update(transform: (CollectionSettings) -> CollectionSettings) {
        state.update(transform)
    }
}

class TestRuntime(
    scope: CoroutineScope,
    val writer: FakeWriter = FakeWriter(),
    val clock: TestAgentleClock = TestAgentleClock(zone = TimeZone.of("America/St_Johns")),
    val coverage: RecordingCoverage = RecordingCoverage(),
    val settings: MemorySettingsStore = MemorySettingsStore(),
) {
    val runtime = CollectorRuntime(
        context = ApplicationProvider.getApplicationContext(),
        clock = clock,
        logger = Logger.NONE,
        dispatchers = AppDispatchers(),
        writer = writer,
        coverage = SafeCoverage(coverage, Logger.NONE),
        settings = SettingsSource(settings),
        events = EventFactory(clock),
        hasher = IdentifierHasher(ByteArray(16) { 7 }),
        scope = scope,
    )
}

/** Every capability ALLOWED unless listed in [denied]. */
class FakePermissions(private val clock: TestAgentleClock, private val denied: Set<String> = emptySet()) :
    dev.agentle.connectors.api.CapabilityStatusProvider {
    private fun status(id: String) = dev.agentle.core.model.CapabilityStatus(
        capabilityId = id,
        state = if (id in denied) dev.agentle.core.model.PermissionState.DENIED else dev.agentle.core.model.PermissionState.ALLOWED,
        evaluatedAt = clock.now(),
    )

    private val all = dev.agentle.connectors.api.CapabilityRegistry.load().all.map { it.id }

    override val statuses = MutableStateFlow(all.associateWith(::status))

    override suspend fun refresh(): Map<String, dev.agentle.core.model.CapabilityStatus> = statuses.value

    override suspend fun refresh(capabilityIds: Collection<String>): Map<String, dev.agentle.core.model.CapabilityStatus> =
        statuses.value.filterKeys { it in capabilityIds }

    override val unusedAppRestrictions = MutableStateFlow<dev.agentle.connectors.api.UnusedAppRestrictionsStatus?>(null)

    override suspend fun onPermissionResult(results: Map<String, Boolean>) = Unit

    override suspend fun onReturnedFromSettings(specialAccess: String) = Unit

    override fun requestablePermissions(capabilityId: String): List<String> = emptyList()
}
