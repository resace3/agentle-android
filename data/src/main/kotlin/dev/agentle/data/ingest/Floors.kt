package dev.agentle.data.ingest

import dev.agentle.core.database.SourceGroup
import dev.agentle.core.datastore.RetentionPeriod
import dev.agentle.core.datastore.RetentionSettings
import dev.agentle.core.datastore.SettingsStore
import dev.agentle.core.model.ConnectorIds
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventType
import dev.agentle.core.time.AgentleClock
import dev.agentle.data.Tx

/**
 * The retention families of the settings (docs/ARCHITECTURE.md §5.5): wearable (Google Health API, Health Connect),
 * Android collectors, the user's own logs and Agentle's records. A connector this version does not know is treated
 * as Android-collected.
 */
internal enum class RetentionFamily {
    WEARABLE,
    ANDROID,
    USER_LOGS,
    AGENTLE,
    ;

    fun period(settings: RetentionSettings): RetentionPeriod = when (this) {
        WEARABLE -> settings.wearable
        ANDROID -> settings.android
        USER_LOGS -> settings.userLogs
        AGENTLE -> settings.agentle
    }

    companion object {
        fun of(connectorId: String): RetentionFamily = when (connectorId) {
            ConnectorIds.GOOGLE_HEALTH, ConnectorIds.HEALTH_CONNECT -> WEARABLE
            ConnectorIds.USER -> USER_LOGS
            ConnectorIds.AGENTLE -> AGENTLE
            else -> ANDROID
        }
    }
}

/** Scope names of the `ingest_floor` table (round 2 correction 5). */
internal object FloorScopes {
    const val ALL: String = "ALL"

    fun group(group: SourceGroup): String = "G:${group.name}"

    fun category(category: DataCategory): String = "C:${category.name}"

    fun source(source: DataSourceId): String = "S:${source.value}"
}

/**
 * The import floors in force for one ingest transaction: the deletion floors stored in `ingest_floor` and the
 * retention cutoffs (now minus the retention of each family). A record that starts before its floor is dropped and
 * counts as ignored, so deleted or expired data never comes back from a re-sync (round 1 correction 5).
 */
internal class FloorSnapshot(private val stored: Map<String, Long>, private val retention: Map<RetentionFamily, Long>) {
    /** The floor of an event of [type] from [source], or null when nothing bounds it. */
    fun floorFor(type: EventType, source: DataSourceId): Long? =
        maxOfNullable(sourceFloor(source), stored[FloorScopes.category(type.category)])

    /** The floor of [source] alone: every scope except the categories. */
    fun sourceFloor(source: DataSourceId): Long? {
        var floor = maxOfNullable(stored[FloorScopes.ALL], stored[FloorScopes.source(source)])
        SourceGroup.entries.filter { SourceGroup.covers(it, source.connectorId) }.forEach { group ->
            floor = maxOfNullable(floor, stored[FloorScopes.group(group)])
        }
        return maxOfNullable(floor, retention[RetentionFamily.of(source.connectorId)])
    }

    companion object {
        val NONE: FloorSnapshot = FloorSnapshot(emptyMap(), emptyMap())
    }
}

internal fun maxOfNullable(a: Long?, b: Long?): Long? = when {
    a == null -> b
    b == null -> a
    else -> maxOf(a, b)
}

internal fun minOfNullable(a: Long?, b: Long?): Long? = when {
    a == null -> b
    b == null -> a
    else -> minOf(a, b)
}

/** Reads the retention settings (outside any transaction) and the stored floors (inside one). */
internal class ImportFloorReader(private val settings: SettingsStore?, private val clock: AgentleClock) {
    /** Now minus the retention of each family that does not keep data forever. */
    suspend fun retentionCutoffs(): Map<RetentionFamily, Long> {
        val retention = settings?.current()?.retention ?: return emptyMap()
        val nowMs = clock.now().toEpochMilliseconds()
        return RetentionFamily.entries.mapNotNull { family ->
            family.period(retention).days?.let { days -> family to nowMs - days * MS_PER_DAY }
        }.toMap()
    }

    suspend fun snapshot(tx: Tx, retention: Map<RetentionFamily, Long>): FloorSnapshot =
        FloorSnapshot(tx.db.stateDao().floors().associate { it.scope to it.floorMs }, retention)

    companion object {
        const val MS_PER_DAY: Long = 86_400_000L
    }
}
