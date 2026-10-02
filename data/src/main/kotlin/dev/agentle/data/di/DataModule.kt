package dev.agentle.data.di

import android.content.Context
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.agentle.analytics.features.daily.DailyFeatureStore
import dev.agentle.connectors.api.EventSink
import dev.agentle.core.database.ContentScrubber
import dev.agentle.core.database.DatabaseProvider
import dev.agentle.core.database.TermCache
import dev.agentle.core.datastore.AiConsentStore
import dev.agentle.core.datastore.SettingsStore
import dev.agentle.core.security.BootCountSource
import dev.agentle.core.security.CryptoEraser
import dev.agentle.core.security.InstallIdProvider
import dev.agentle.core.time.AgentleClock
import dev.agentle.data.DataAccess
import dev.agentle.data.analytics.EventChangeFeed
import dev.agentle.data.analytics.RoomDailyFeatureStore
import dev.agentle.data.analytics.RoomEventChangeFeed
import dev.agentle.data.deletion.AndroidDeleteAllSteps
import dev.agentle.data.deletion.DataProducers
import dev.agentle.data.deletion.DeleteAllService
import dev.agentle.data.deletion.DeletionService
import dev.agentle.data.deletion.MediaFileDeleter
import dev.agentle.data.deletion.RemoteRevoker
import dev.agentle.data.deletion.RoomDeletionService
import dev.agentle.data.diagnostics.DiagnosticWriter
import dev.agentle.data.diagnostics.DiagnosticsRepository
import dev.agentle.data.diagnostics.RoomDiagnosticsRepository
import dev.agentle.data.events.EventRepository
import dev.agentle.data.events.RoomEventRepository
import dev.agentle.data.ingest.CursorWriter
import dev.agentle.data.ingest.EventBatchWriter
import dev.agentle.data.ingest.ImportFloorReader
import dev.agentle.data.ingest.RoomEventWriter
import dev.agentle.data.jitai.JitaiDefinitionStore
import dev.agentle.data.jitai.JitaiLedger
import dev.agentle.data.jitai.RoomJitaiDefinitionStore
import dev.agentle.data.jitai.RoomJitaiLedger
import dev.agentle.data.records.AiAuditRepository
import dev.agentle.data.records.AiTextPoolStore
import dev.agentle.data.records.RoomAiAuditRepository
import dev.agentle.data.records.RoomAiTextPoolStore
import dev.agentle.data.retention.ContentTextPurger
import dev.agentle.data.retention.NotificationContentPurger
import dev.agentle.data.retention.RetentionService
import dev.agentle.data.retention.RoomContentTextPurger
import dev.agentle.data.retention.RoomRetentionService
import dev.agentle.data.sync.CollectorCoverageStore
import dev.agentle.data.sync.RoomCollectorCoverageStore
import dev.agentle.data.sync.RoomSyncStateRepository
import dev.agentle.data.sync.SyncStateRepository
import java.io.File
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything `:data` builds, created once. Only ports leave this module (round 4 correction 5): the database, the
 * key material and the settings files stay behind them.
 */
@Singleton
class DataGraph @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val provider: DatabaseProvider,
    terms: TermCache,
    private val clock: AgentleClock,
    private val settings: SettingsStore,
    private val consent: AiConsentStore,
    private val installIds: InstallIdProvider,
    private val eraser: CryptoEraser,
    private val boots: BootCountSource,
    private val scrubber: Optional<ContentScrubber>,
    private val producers: Optional<DataProducers>,
    private val remote: Optional<RemoteRevoker>,
) {
    internal val access = DataAccess(provider, terms)
    private val diagnosticWriter = DiagnosticWriter(clock)
    private val writer by lazy { RoomEventWriter(access, clock, ImportFloorReader(settings, clock), CursorWriter(clock, boots)) }

    val eventSink: EventSink get() = writer
    val eventBatchWriter: EventBatchWriter get() = writer
    val events: EventRepository by lazy { RoomEventRepository(access) }
    val changeFeed: EventChangeFeed by lazy { RoomEventChangeFeed(access) }
    val dailyFeatures: DailyFeatureStore by lazy { RoomDailyFeatureStore(access) }
    val syncState: SyncStateRepository by lazy { RoomSyncStateRepository(access, clock, diagnosticWriter) }
    val collectorCoverage: CollectorCoverageStore by lazy { RoomCollectorCoverageStore(access) }
    val diagnostics: DiagnosticsRepository by lazy { RoomDiagnosticsRepository(access, diagnosticWriter) }
    val ledger: JitaiLedger by lazy { RoomJitaiLedger(access, clock) }
    val definitions: JitaiDefinitionStore by lazy { RoomJitaiDefinitionStore(access, clock) }
    val aiAudit: AiAuditRepository by lazy { RoomAiAuditRepository(access) { sub -> installIds.pseudonymize(SUB_PURPOSE, sub) } }
    val textPool: AiTextPoolStore by lazy { RoomAiTextPoolStore(access, consent, clock) }
    val textPurger: ContentTextPurger by lazy { RoomContentTextPurger(access) }
    val mediaFiles: MediaFileDeleter = MediaFileDeleter { uri -> File(uri.removePrefix(FILE_SCHEME)).delete() }
    val deletion: DeletionService by lazy {
        RoomDeletionService(access, consent, clock, scrubber.orElse(ContentScrubber { json, _ -> json }), mediaFiles)
    }
    val retention: RetentionService by lazy { RoomRetentionService(access, settings, clock, textPurger, textPool, mediaFiles) }
    val deleteAll: DeleteAllService by lazy {
        val marker = File(context.noBackupFilesDir, "delete-all/marker")
        val steps = AndroidDeleteAllSteps(
            context,
            provider,
            eraser,
            producers.orElse(DataProducers { }),
            remote.orElse(RemoteRevoker { }),
            marker,
        )
        DeleteAllService(marker, steps)
    }

    private companion object {
        const val SUB_PURPOSE = "ai-account-sub"
        const val FILE_SCHEME = "file://"
    }
}

/** Optional bindings the app wiring supplies; absent ones fall back to a safe default in [DataGraph]. */
@Module
@InstallIn(SingletonComponent::class)
interface DataOptionalBindings {
    /** The JITAI engine's precise scrubber of derived JSON; absent means derived rows are deleted by lineage only. */
    @BindsOptionalOf
    fun contentScrubber(): ContentScrubber

    @BindsOptionalOf
    fun dataProducers(): DataProducers

    @BindsOptionalOf
    fun remoteRevoker(): RemoteRevoker
}

/** The ports of `:data`. */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides fun eventSink(graph: DataGraph): EventSink = graph.eventSink

    @Provides fun eventBatchWriter(graph: DataGraph): EventBatchWriter = graph.eventBatchWriter

    @Provides fun events(graph: DataGraph): EventRepository = graph.events

    @Provides fun changeFeed(graph: DataGraph): EventChangeFeed = graph.changeFeed

    @Provides fun dailyFeatures(graph: DataGraph): DailyFeatureStore = graph.dailyFeatures

    @Provides fun syncState(graph: DataGraph): SyncStateRepository = graph.syncState

    @Provides fun collectorCoverage(graph: DataGraph): CollectorCoverageStore = graph.collectorCoverage

    @Provides fun diagnostics(graph: DataGraph): DiagnosticsRepository = graph.diagnostics

    @Provides fun ledger(graph: DataGraph): JitaiLedger = graph.ledger

    @Provides fun definitions(graph: DataGraph): JitaiDefinitionStore = graph.definitions

    @Provides fun aiAudit(graph: DataGraph): AiAuditRepository = graph.aiAudit

    @Provides fun textPool(graph: DataGraph): AiTextPoolStore = graph.textPool

    @Provides fun textPurger(graph: DataGraph): ContentTextPurger = graph.textPurger

    @Provides fun notificationContentPurger(graph: DataGraph): NotificationContentPurger = graph.textPurger

    @Provides fun deletion(graph: DataGraph): DeletionService = graph.deletion

    @Provides fun retention(graph: DataGraph): RetentionService = graph.retention

    @Provides fun deleteAll(graph: DataGraph): DeleteAllService = graph.deleteAll
}
