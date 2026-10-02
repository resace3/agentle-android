package dev.agentle.core.database

import androidx.room3.Database
import androidx.room3.RoomDatabase
import dev.agentle.core.database.dao.AiDao
import dev.agentle.core.database.dao.AnalyticsDao
import dev.agentle.core.database.dao.EventDao
import dev.agentle.core.database.dao.JitaiDao
import dev.agentle.core.database.dao.MediaDao
import dev.agentle.core.database.dao.StateDao
import dev.agentle.core.database.dao.SyncDao
import dev.agentle.core.database.dao.SystemDao
import dev.agentle.core.database.dao.TermDao
import dev.agentle.core.database.dao.UserDao
import dev.agentle.core.database.entity.AiRequestEntity
import dev.agentle.core.database.entity.AiResultMetaEntity
import dev.agentle.core.database.entity.AiTextPoolEntity
import dev.agentle.core.database.entity.CollectorCoverageEntity
import dev.agentle.core.database.entity.ConnectorStateEntity
import dev.agentle.core.database.entity.DedupCollisionEntity
import dev.agentle.core.database.entity.DerivedFeatureEntity
import dev.agentle.core.database.entity.DiagnosticLogEntity
import dev.agentle.core.database.entity.DirtyDayEntity
import dev.agentle.core.database.entity.EngineDaySummaryEntity
import dev.agentle.core.database.entity.EngineStateEntity
import dev.agentle.core.database.entity.EventEntity
import dev.agentle.core.database.entity.EventTombstoneEntity
import dev.agentle.core.database.entity.GoogleHealthStateEntity
import dev.agentle.core.database.entity.IngestFloorEntity
import dev.agentle.core.database.entity.InsightEntity
import dev.agentle.core.database.entity.InterventionOutcomeEntity
import dev.agentle.core.database.entity.JitaiDecisionEntity
import dev.agentle.core.database.entity.JitaiDefinitionEntity
import dev.agentle.core.database.entity.JitaiDefinitionHistoryEntity
import dev.agentle.core.database.entity.JitaiEvalLogEntity
import dev.agentle.core.database.entity.JitaiResponseLogEntity
import dev.agentle.core.database.entity.JitaiRuntimeEntity
import dev.agentle.core.database.entity.JitaiTimerEntity
import dev.agentle.core.database.entity.KnownPlaceEntity
import dev.agentle.core.database.entity.MediaArtifactEntity
import dev.agentle.core.database.entity.MetricSourcePolicyEntity
import dev.agentle.core.database.entity.PermissionSnapshotEntity
import dev.agentle.core.database.entity.SourceCoverageEntity
import dev.agentle.core.database.entity.SyncCursorEntity
import dev.agentle.core.database.entity.TermEntity
import dev.agentle.core.database.entity.UpstreamDailyView
import dev.agentle.core.database.entity.UserGoalEntity
import dev.agentle.core.database.entity.UserLogEntity

/**
 * The Agentle database, schema v1 (docs/ARCHITECTURE.md §5.2 with the red-team rounds 1-4). The schema is exported to
 * `core/database/schemas/`; every later version ships a migration that only adds columns, tables or indexes, and a
 * migration test. Destructive fallback is never enabled.
 */
@Database(
    entities = [
        TermEntity::class,
        EventEntity::class,
        EventTombstoneEntity::class,
        DedupCollisionEntity::class,
        EngineStateEntity::class,
        DirtyDayEntity::class,
        IngestFloorEntity::class,
        SyncCursorEntity::class,
        SourceCoverageEntity::class,
        CollectorCoverageEntity::class,
        ConnectorStateEntity::class,
        GoogleHealthStateEntity::class,
        EngineDaySummaryEntity::class,
        MetricSourcePolicyEntity::class,
        DerivedFeatureEntity::class,
        InsightEntity::class,
        JitaiDefinitionEntity::class,
        JitaiDefinitionHistoryEntity::class,
        JitaiRuntimeEntity::class,
        JitaiDecisionEntity::class,
        InterventionOutcomeEntity::class,
        JitaiResponseLogEntity::class,
        JitaiEvalLogEntity::class,
        JitaiTimerEntity::class,
        AiRequestEntity::class,
        AiResultMetaEntity::class,
        AiTextPoolEntity::class,
        MediaArtifactEntity::class,
        UserGoalEntity::class,
        UserLogEntity::class,
        PermissionSnapshotEntity::class,
        DiagnosticLogEntity::class,
        KnownPlaceEntity::class,
    ],
    views = [UpstreamDailyView::class],
    version = AgentleDatabase.VERSION,
    exportSchema = true,
)
abstract class AgentleDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    abstract fun termDao(): TermDao

    abstract fun stateDao(): StateDao

    abstract fun syncDao(): SyncDao

    abstract fun analyticsDao(): AnalyticsDao

    abstract fun jitaiDao(): JitaiDao

    abstract fun aiDao(): AiDao

    abstract fun mediaDao(): MediaDao

    abstract fun userDao(): UserDao

    abstract fun systemDao(): SystemDao

    companion object {
        const val VERSION: Int = 1
    }
}

/** Kinds of rows in the `term` dictionary. The view `upstream_daily` relies on [TYPE] being 1. */
object TermKind {
    const val TYPE: Int = 1
    const val SOURCE: Int = 2
    const val ACCOUNT: Int = 3

    /** The account term id of data that is not bound to an upstream account. */
    const val NO_ACCOUNT: Long = 0
}

/** Names of the `engine_state` rows. */
object EngineStateKeys {
    /** The last value of the single change counter (`event.change_seq`, tombstones, dirty-day generations). */
    const val CHANGE_SEQ: String = "change_seq"

    /** Bumped by every deletion; a writer whose batch was read under an older epoch is rejected. */
    const val DATA_EPOCH: String = "data_epoch"

    /** Random id of this database file; a restored or recreated database has another one. */
    const val DB_GENERATION: String = "db_generation"

    /** The highest trigger-event `change_seq` the JITAI engine has evaluated. */
    const val JITAI_WATERMARK: String = "jitai_watermark"

    /** 1 while trigger-relevant events wait for the JITAI event worker. */
    const val JITAI_DIRTY: String = "jitai_dirty"

    /** JSON progress of a category or family deletion that has not finished (round 4 correction 3). */
    const val DELETION_MARKER: String = "deletion_marker"

    /** Wall time of the last completed retention run. */
    const val LAST_RETENTION_MS: String = "last_retention_ms"
}
