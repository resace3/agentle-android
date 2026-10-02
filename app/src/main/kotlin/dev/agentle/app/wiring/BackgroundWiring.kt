package dev.agentle.app.wiring

import dev.agentle.background.port.Collectors
import dev.agentle.background.port.Maintenance
import dev.agentle.connectors.android.AndroidCollectorsGraph
import dev.agentle.connectors.api.SyncResult
import dev.agentle.connectors.api.SyncTrigger
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.data.retention.RetentionService
import javax.inject.Inject

/** The scheduler's collector sweeps, run by the Android collectors graph; results land in Room through its EventSink. */
internal class GraphCollectors @Inject constructor(private val graph: AndroidCollectorsGraph) : Collectors {
    override suspend fun collectUsage(): Outcome<Unit> = graph.usage.sync(SyncTrigger.SCHEDULED).toOutcome()

    override suspend fun collectDevice(): Outcome<Unit> =
        listOf(graph.battery, graph.network, graph.audio, graph.deviceState, graph.system)
            .map { it.sync(SyncTrigger.SCHEDULED) }
            .firstOrNull { it.status == SyncResult.Status.FAILED }
            ?.toOutcome()
            ?: Outcome.Success(Unit)

    // Activity transitions are not wired into the draft yet.
    override suspend fun reregisterActivityTransitions(): Outcome<Unit> =
        Outcome.failure(AppError.UnsupportedFeature("activity-transitions"))
}

/** Maintenance the draft supports: retention runs over Room; insights and media cleanup are not wired yet. */
internal class DraftMaintenance @Inject constructor(private val retention: RetentionService) : Maintenance {
    override suspend fun weeklyInsights(): Outcome<Unit> = Outcome.failure(AppError.UnsupportedFeature("weekly-insights"))

    override suspend fun applyRetention(): Outcome<Unit> {
        retention.run()
        return Outcome.Success(Unit)
    }

    override suspend fun cleanupMedia(): Outcome<Unit> = Outcome.failure(AppError.UnsupportedFeature("media-cleanup"))
}

private fun SyncResult.toOutcome(): Outcome<Unit> = when (status) {
    SyncResult.Status.FAILED -> Outcome.failure(error ?: AppError.Unexpected("sync failed: $connectorId"))
    else -> Outcome.Success(Unit)
}
