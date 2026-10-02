package dev.agentle.app.wiring

import dev.agentle.connectors.android.AndroidCollectorsGraph
import dev.agentle.connectors.api.SyncResult
import dev.agentle.connectors.api.SyncTrigger
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.feature.hub.port.DataSourceItem
import dev.agentle.feature.hub.port.DataSourceKind
import dev.agentle.feature.hub.port.DataSourcesPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/**
 * Data Sources over the Android collectors graph. The wearable is not in the draft yet, and coverage dates are not
 * wired (null and no gap days), so the screen shows the connectors' own status only.
 */
internal class GraphDataSourcesPort @Inject constructor(private val graph: AndroidCollectorsGraph) : DataSourcesPort {
    override val sources: Flow<List<DataSourceItem>> =
        combine(graph.connectors.map { it.metadata }) { metadata ->
            metadata.mapIndexed { index, meta ->
                DataSourceItem(meta, DataSourceKind.ANDROID_COLLECTOR, graph.connectors[index].capabilityIds)
            }
        }

    override suspend fun setEnabled(connectorId: String, enabled: Boolean): Outcome<Unit> {
        val connector = graph.connectors.firstOrNull { it.id == connectorId }
            ?: return Outcome.failure(AppError.UnsupportedFeature(connectorId))
        connector.setEnabled(enabled)
        return Outcome.Success(Unit)
    }

    override suspend fun syncNow(connectorId: String): Outcome<Unit> {
        val connector = graph.connectors.firstOrNull { it.id == connectorId }
            ?: return Outcome.failure(AppError.UnsupportedFeature(connectorId))
        val result = connector.sync(SyncTrigger.MANUAL)
        return if (result.status == SyncResult.Status.FAILED) {
            Outcome.failure(result.error ?: AppError.Unexpected("sync failed: $connectorId"))
        } else {
            Outcome.Success(Unit)
        }
    }
}
