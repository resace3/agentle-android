package dev.agentle.connectors.android.core

import dev.agentle.connectors.api.CapabilityStatusProvider
import dev.agentle.connectors.api.CollectionSettings
import dev.agentle.connectors.api.CoverageEndCause
import dev.agentle.core.model.CapabilityStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A collector that only runs while Agentle's process is alive: runtime receivers and platform callbacks (screen and
 * unlock, power, connectivity, Bluetooth adapter, audio devices, DND, call state). Its events are best effort: the
 * process is usually kept alive by the bound notification listener, but nothing guarantees it (red team
 * lifecycle-battery-07). [start] and [stop] must not throw and must be idempotent.
 */
public interface LiveSource {
    /** This source's own id (`android.screen_live`, ...), for the controller and logs; never a coverage id. */
    public val sourceId: String

    /**
     * The capability ids whose coverage this source owns ([CoverageIds]): opened when it starts, closed when it stops.
     * Empty for best-effort sources whose capabilities a sweep owns.
     */
    public val coverageIds: List<String> get() = emptyList()

    /** The connector whose enabled flag gates this source. */
    public val connectorId: String

    /** Capabilities that must all be collectable while the source runs. */
    public val requiredCapabilityIds: List<String>

    /** Registers receivers or callbacks; false when registration failed (nothing is then registered). */
    public fun start(): Boolean

    public fun stop()

    /** Writes everything this source buffered (before the process may die). */
    public suspend fun flush() {}

    /** Drops everything this source buffered without writing it (delete-all). */
    public suspend fun discard() {}
}

/**
 * Starts and stops every [LiveSource] from the Permission Center's statuses and the user's settings, and records the
 * coverage of the sources that own some (red team lifecycle-battery-01): an interval opens when a source starts and
 * closes with PERMISSION_LOST, DISABLED_BY_USER or DATA_DELETED when it stops. While [suspend]ed (delete-all), nothing
 * runs.
 */
public class LiveController(
    private val runtime: CollectorRuntime,
    private val permissions: CapabilityStatusProvider,
    private val sources: List<LiveSource>,
    private val enabled: (connectorId: String, settings: CollectionSettings) -> Boolean,
) {
    private val lock = Mutex()
    private val running = mutableSetOf<String>()
    private val suspended = MutableStateFlow(false)
    private var job: Job? = null

    /** Starts observing; idempotent. */
    public fun start() {
        if (job?.isActive == true) return
        job = runtime.scope.launch {
            combine(permissions.statuses, runtime.settings.flow, suspended) { statuses, settings, paused ->
                Triple(statuses, settings, paused)
            }
                .collect { (statuses, settings, paused) -> apply(statuses, settings, paused) }
        }
    }

    /** Stops every source (DATA_DELETED) until [resume]. */
    public suspend fun suspend() {
        suspended.value = true
        apply(permissions.statuses.value, runtime.settings.current(), paused = true)
    }

    public fun resume() {
        suspended.value = false
    }

    public suspend fun flushAll() {
        sources.forEach { it.flush() }
    }

    /** Re-evaluates now (tests and the first pass after start). */
    public suspend fun apply(
        statuses: Map<String, CapabilityStatus>,
        settings: CollectionSettings,
        paused: Boolean = suspended.value,
    ): Unit = lock.withLock {
        val now = runtime.clock.now()
        sources.forEach { source ->
            val isEnabled = enabled(source.connectorId, settings)
            val permitted = source.requiredCapabilityIds.all { statuses[it]?.state?.canCollect == true }
            val desired = !paused && isEnabled && permitted
            val isRunning = source.sourceId in running
            when {
                desired && !isRunning -> if (source.start()) {
                    running += source.sourceId
                    runtime.coverage.open(source.coverageIds, now)
                }

                !desired && isRunning -> {
                    source.stop()
                    // Nothing collected before a deletion is written after it.
                    if (paused) source.discard() else source.flush()
                    running -= source.sourceId
                    val cause = when {
                        paused -> CoverageEndCause.DATA_DELETED
                        !isEnabled -> CoverageEndCause.DISABLED_BY_USER
                        else -> CoverageEndCause.PERMISSION_LOST
                    }
                    runtime.coverage.close(source.coverageIds, now, cause)
                }
            }
        }
    }

    /** Source ids currently running. */
    public suspend fun runningSources(): Set<String> = lock.withLock { running.toSet() }
}
