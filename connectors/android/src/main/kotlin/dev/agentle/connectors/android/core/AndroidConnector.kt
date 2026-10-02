package dev.agentle.connectors.android.core

import dev.agentle.connectors.api.CapabilityStatusProvider
import dev.agentle.connectors.api.CollectionSettings
import dev.agentle.connectors.api.Connector
import dev.agentle.connectors.api.CoverageEndCause
import dev.agentle.connectors.api.SyncResult
import dev.agentle.connectors.api.SyncTrigger
import dev.agentle.core.common.AppError
import dev.agentle.core.model.CapabilityStatus
import dev.agentle.core.model.ConnectionStatus
import dev.agentle.core.model.ConnectorMetadata
import dev.agentle.core.model.ErrorInfo
import dev.agentle.core.model.EventType
import dev.agentle.core.model.PermissionState
import dev.agentle.core.model.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/** What one collection run produced. */
public data class CollectOutcome(
    val fetched: Int = 0,
    val committed: Int = 0,
    val skippedInvalid: Int = 0,
    /** Some streams or pages were skipped (for example one Health Connect type was revoked). */
    val partial: Boolean = false,
    val error: AppError? = null,
    /** Per-stream permission for connectors whose grant is partial (Health Connect record types). */
    val streamPermissions: Map<String, PermissionState> = emptyMap(),
    /**
     * The run could not collect for a permission reason found only while collecting (for example a Health Connect
     * background read without the background permission): reported as SKIPPED_NO_PERMISSION with [error].
     */
    val skipped: Boolean = false,
    /**
     * False when the run deliberately collected nothing because another source serves the data (Recording API steps
     * while Health Connect on-device steps are available): the connector's coverage is closed (STOPPED), not renewed.
     */
    val covered: Boolean = true,
) {
    public companion object {
        public val EMPTY: CollectOutcome = CollectOutcome()
    }
}

/**
 * Base of every on-device connector (docs/ARCHITECTURE.md §6.1, §6.3). A run:
 * - is skipped when the user disabled the connector (opt-in connectors such as `android.call` start disabled);
 * - re-evaluates the connector's capabilities first ("before every collection run") and is skipped with
 *   [AppError.PermissionDenied] when a required one cannot collect, closing its coverage (PERMISSION_LOST);
 * - records coverage under capability ids ([coverageIds], see [CoverageIds]): a run without error opens and heartbeats
 *   each owned capability that can collect and closes the others (PERMISSION_LOST);
 * - turns a `SecurityException` thrown mid-run (a permission revoked while collecting) into PermissionDenied: a
 *   missing permission is a state, never a crash;
 * - never puts an exception's message anywhere (class name only, red team round 1 item 2).
 * [metadata] follows the live permission state, the enabled flag and the last run. Runs of one connector never overlap.
 */
public abstract class AndroidConnector(
    final override val id: String,
    final override val name: String,
    final override val supportedEventTypes: Set<EventType>,
    final override val capabilityIds: List<String>,
    protected val runtime: CollectorRuntime,
    protected val permissions: CapabilityStatusProvider,
) : Connector {
    private val optIn: Boolean = id in AndroidConnectorIds.OPT_IN
    private val runLock = Mutex()
    private val runState = MutableStateFlow(RunState())

    /** Capabilities that must all be collectable for a run to start; the others only reduce what is collected. */
    protected open val requiredCapabilityIds: List<String> get() = capabilityIds

    /**
     * The capability ids whose coverage this connector owns (a subset of [capabilityIds], see [CoverageIds]); empty for
     * connectors whose rows come from a live source that owns its coverage, or that store nothing.
     */
    public open val coverageIds: List<String> get() = emptyList()

    /** Capabilities read but not owned, re-evaluated before each run with [capabilityIds] (for example a fallback check). */
    protected open val observedCapabilityIds: List<String> get() = emptyList()

    final override val metadata: StateFlow<ConnectorMetadata> by lazy {
        combine(runState, permissions.statuses, runtime.settings.flow) { run, statuses, settings -> buildMetadata(run, statuses, settings) }
            .stateIn(runtime.scope, SharingStarted.Eagerly, buildMetadata(RunState(), permissions.statuses.value, CollectionSettings()))
    }

    /** Collects once; called only while enabled and with every required capability collectable. */
    protected abstract suspend fun collect(trigger: SyncTrigger, statuses: Map<String, CapabilityStatus>): CollectOutcome

    /** Called after the user's enabled flag changed (live sources start or stop through the live controller). */
    protected open suspend fun onEnabledChanged(enabled: Boolean): Unit = Unit

    public fun isEnabled(settings: CollectionSettings): Boolean =
        if (optIn) id in settings.enabledOptInConnectors else id !in settings.disabledConnectors

    /** Collects one of [streamIds] only; the default collects everything. */
    protected open suspend fun collectStream(
        stream: String,
        trigger: SyncTrigger,
        statuses: Map<String, CapabilityStatus>,
    ): CollectOutcome = collect(trigger, statuses)

    final override suspend fun sync(trigger: SyncTrigger): SyncResult = runLock.withLock { runOnce(trigger, stream = null) }

    final override suspend fun syncStream(stream: String, trigger: SyncTrigger): SyncResult =
        if (stream in streamIds) runLock.withLock { runOnce(trigger, stream) } else sync(trigger)

    final override suspend fun setEnabled(enabled: Boolean) {
        runtime.settings.update { settings ->
            when {
                optIn && enabled -> settings.copy(enabledOptInConnectors = settings.enabledOptInConnectors + id)
                optIn -> settings.copy(enabledOptInConnectors = settings.enabledOptInConnectors - id)
                enabled -> settings.copy(disabledConnectors = settings.disabledConnectors - id)
                else -> settings.copy(disabledConnectors = settings.disabledConnectors + id)
            }
        }
        if (!enabled) runtime.coverage.close(coverageIds, runtime.clock.now(), CoverageEndCause.DISABLED_BY_USER)
        onEnabledChanged(enabled)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runOnce(trigger: SyncTrigger, stream: String?): SyncResult {
        val started = runtime.clock.now()
        if (!isEnabled(runtime.settings.current())) {
            runtime.coverage.close(coverageIds, started, CoverageEndCause.DISABLED_BY_USER)
            return finish(started, SyncResult.Status.SKIPPED_DISABLED)
        }
        val statuses = refreshStatuses()
        val blocked = requiredCapabilityIds.firstOrNull { statuses[it]?.state?.canCollect != true }
        if (blocked != null) {
            runtime.coverage.close(coverageIds, started, CoverageEndCause.PERMISSION_LOST)
            return finish(started, SyncResult.Status.SKIPPED_NO_PERMISSION, error = AppError.PermissionDenied(blocked))
        }
        runState.update { it.copy(syncing = true, lastAttempt = started) }
        return try {
            val outcome = if (stream == null) collect(trigger, statuses) else collectStream(stream, trigger, statuses)
            if (outcome.skipped) return finish(started, SyncResult.Status.SKIPPED_NO_PERMISSION, outcome.error, outcome)
            val status = when {
                outcome.error != null && outcome.committed == 0 && !outcome.partial -> SyncResult.Status.FAILED
                outcome.partial || outcome.error != null -> SyncResult.Status.PARTIAL
                else -> SyncResult.Status.SUCCESS
            }
            recordCoverage(statuses, outcome, started, runtime.clock.now())
            finish(started, status, outcome.error, outcome)
        } catch (e: CancellationException) {
            runState.update { it.copy(syncing = false) }
            throw e
        } catch (e: SecurityException) {
            // A grant was revoked while collecting: a state, never a crash.
            runtime.coverage.close(coverageIds, runtime.clock.now(), CoverageEndCause.PERMISSION_LOST)
            refreshStatuses()
            val error = AppError.PermissionDenied(
                requiredCapabilityIds.firstOrNull() ?: capabilityIds.first(),
                detail = e::class.simpleName,
            )
            finish(started, SyncResult.Status.SKIPPED_NO_PERMISSION, error)
        } catch (e: Exception) {
            runtime.logger.w(COMPONENT, "Collection run failed", fields = mapOf("connector" to id, "error" to e::class.simpleName))
            runtime.coverage.close(coverageIds, runtime.clock.now(), CoverageEndCause.UNKNOWN)
            finish(started, SyncResult.Status.FAILED, AppError.Unexpected(e::class.simpleName))
        }
    }

    /**
     * A run without error renews the coverage of each owned capability that can collect and closes the others; a run
     * with an error leaves it alone (a lost write already closed it where it happened).
     */
    private suspend fun recordCoverage(
        statuses: Map<String, CapabilityStatus>,
        outcome: CollectOutcome,
        started: Instant,
        finished: Instant,
    ) {
        if (coverageIds.isEmpty() || outcome.error != null) return
        if (!outcome.covered) {
            runtime.coverage.close(coverageIds, finished, CoverageEndCause.STOPPED)
            return
        }
        val (live, lost) = coverageIds.partition { statuses[it]?.state?.canCollect == true }
        runtime.coverage.open(live, started)
        runtime.coverage.heartbeat(live, finished)
        runtime.coverage.close(lost, finished, CoverageEndCause.PERMISSION_LOST)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun refreshStatuses(): Map<String, CapabilityStatus> = try {
        permissions.refresh((capabilityIds + observedCapabilityIds).distinct())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        runtime.logger.w(COMPONENT, "Permission refresh failed", fields = mapOf("connector" to id, "error" to e::class.simpleName))
        permissions.statuses.value
    }

    private fun finish(
        started: Instant,
        status: SyncResult.Status,
        error: AppError? = null,
        outcome: CollectOutcome = CollectOutcome.EMPTY,
    ): SyncResult {
        val finished = runtime.clock.now()
        runState.update { run ->
            val ok = status == SyncResult.Status.SUCCESS || status == SyncResult.Status.PARTIAL
            run.copy(
                syncing = false,
                lastAttempt = started,
                lastSuccess = if (ok) finished else run.lastSuccess,
                lastError = error?.let { ErrorInfo(it.code, finished) } ?: if (ok) null else run.lastError,
                lastStatus = status,
                streamPermissions = outcome.streamPermissions.ifEmpty { run.streamPermissions },
            )
        }
        return SyncResult(
            connectorId = id,
            status = status,
            startedAt = started,
            finishedAt = finished,
            fetched = outcome.fetched,
            committed = outcome.committed,
            skippedInvalid = outcome.skippedInvalid,
            error = error,
        )
    }

    private fun buildMetadata(run: RunState, statuses: Map<String, CapabilityStatus>, settings: CollectionSettings): ConnectorMetadata {
        val enabled = isEnabled(settings)
        val summary = summarize(capabilityIds.mapNotNull { statuses[it]?.state })
        val requiredOk = requiredCapabilityIds.all { statuses[it]?.state?.canCollect == true }
        val connection = when {
            !enabled -> ConnectionStatus.DISABLED
            statuses.isEmpty() -> ConnectionStatus.NOT_CONNECTED
            !requiredOk && requiredCapabilityIds.any { statuses[it]?.state in UNREACHABLE } -> ConnectionStatus.UNAVAILABLE
            !requiredOk -> ConnectionStatus.NOT_CONNECTED
            run.lastStatus == SyncResult.Status.FAILED -> ConnectionStatus.ERROR
            else -> ConnectionStatus.CONNECTED
        }
        return ConnectorMetadata(
            connectorId = id,
            name = name,
            enabled = enabled,
            connection = connection,
            permissionSummary = summary,
            lastSuccessfulCollection = run.lastSuccess,
            lastAttemptedCollection = run.lastAttempt,
            lastError = run.lastError,
            supportedEventTypes = supportedEventTypes,
            syncState = when {
                run.syncing -> SyncStatus.RUNNING
                run.lastStatus == SyncResult.Status.SUCCESS -> SyncStatus.SUCCEEDED
                run.lastStatus == SyncResult.Status.PARTIAL -> SyncStatus.PARTIAL
                run.lastStatus == SyncResult.Status.FAILED -> SyncStatus.FAILED
                else -> SyncStatus.IDLE
            },
            streamPermissions = run.streamPermissions,
        )
    }

    private data class RunState(
        val syncing: Boolean = false,
        val lastAttempt: Instant? = null,
        val lastSuccess: Instant? = null,
        val lastError: ErrorInfo? = null,
        val lastStatus: SyncResult.Status? = null,
        val streamPermissions: Map<String, PermissionState> = emptyMap(),
    )

    public companion object {
        private const val COMPONENT = "collectors.connector"
        private val UNREACHABLE = setOf(PermissionState.UNSUPPORTED_ON_DEVICE, PermissionState.RESTRICTED_BY_ANDROID)

        /**
         * One summary state for several capabilities: ALLOWED (or BACKGROUND_ALLOWED) when all are, PARTIALLY_ALLOWED
         * when at least one can collect, otherwise the strongest blocker by the §5.1 precedence. UNAVAILABLE when nothing
         * was evaluated yet.
         */
        public fun summarize(states: List<PermissionState>): PermissionState = when {
            states.isEmpty() -> PermissionState.UNAVAILABLE
            states.all { it == PermissionState.BACKGROUND_ALLOWED } -> PermissionState.BACKGROUND_ALLOWED
            states.all { it == PermissionState.ALLOWED || it == PermissionState.BACKGROUND_ALLOWED } -> PermissionState.ALLOWED
            states.any { it.canCollect } -> PermissionState.PARTIALLY_ALLOWED
            else -> states.minBy { dev.agentle.connectors.android.permissions.StateBuilder.tier(it) }
        }
    }
}
