package dev.agentle.connectors.android.di

import android.content.Context
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import dev.agentle.connectors.android.AndroidCollectors
import dev.agentle.connectors.android.AndroidCollectorsGraph
import dev.agentle.connectors.android.CollectorPorts
import dev.agentle.connectors.android.permissions.PendingIntentFactory
import dev.agentle.connectors.android.permissions.PermissionCenter
import dev.agentle.connectors.android.permissions.SettingsIntentFactory
import dev.agentle.connectors.api.ActiveJitaiSignal
import dev.agentle.connectors.api.CapabilityStatusProvider
import dev.agentle.connectors.api.CollectionSettingsStore
import dev.agentle.connectors.api.CollectorEventWriter
import dev.agentle.connectors.api.Connector
import dev.agentle.connectors.api.CoverageRecorder
import dev.agentle.connectors.api.CurrentPlaceProvider
import dev.agentle.connectors.api.EventSink
import dev.agentle.connectors.api.LiveCollectionControl
import dev.agentle.connectors.api.NotificationContentPurger
import dev.agentle.connectors.api.NotificationDeliveryGate
import dev.agentle.connectors.api.PermissionRequestStore
import dev.agentle.connectors.api.SystemChangeListener
import dev.agentle.core.common.AppDispatchers
import dev.agentle.core.common.Logger
import dev.agentle.core.time.AgentleClock
import java.util.Optional
import javax.inject.Singleton

/**
 * Ports `:connectors:android` uses when another module binds them; each is optional, so this module works before
 * ANDROID-DATA, the JITAI engine or the scheduler bind theirs (see [CollectorPorts] for the fallbacks).
 */
@Module
@InstallIn(SingletonComponent::class)
public interface AndroidCollectorsOptionalPorts {
    @BindsOptionalOf
    public fun clock(): AgentleClock

    @BindsOptionalOf
    public fun logger(): Logger

    @BindsOptionalOf
    public fun dispatchers(): AppDispatchers

    @BindsOptionalOf
    public fun writer(): CollectorEventWriter

    @BindsOptionalOf
    public fun eventSink(): EventSink

    @BindsOptionalOf
    public fun coverage(): CoverageRecorder

    @BindsOptionalOf
    public fun settings(): CollectionSettingsStore

    @BindsOptionalOf
    public fun permissionRequests(): PermissionRequestStore

    @BindsOptionalOf
    public fun systemChanges(): SystemChangeListener

    @BindsOptionalOf
    public fun activeJitai(): ActiveJitaiSignal

    @BindsOptionalOf
    public fun notificationPurger(): NotificationContentPurger
}

/** Provides the collectors graph and the ports it implements (docs/ARCHITECTURE.md §6.1-6.3). */
@Module
@InstallIn(SingletonComponent::class)
public object AndroidCollectorsModule {
    @Provides
    @Singleton
    @Suppress("LongParameterList")
    public fun graph(
        @ApplicationContext context: Context,
        clock: Optional<AgentleClock>,
        logger: Optional<Logger>,
        dispatchers: Optional<AppDispatchers>,
        writer: Optional<CollectorEventWriter>,
        eventSink: Optional<EventSink>,
        coverage: Optional<CoverageRecorder>,
        settings: Optional<CollectionSettingsStore>,
        permissionRequests: Optional<PermissionRequestStore>,
        systemChanges: Optional<SystemChangeListener>,
        activeJitai: Optional<ActiveJitaiSignal>,
        notificationPurger: Optional<NotificationContentPurger>,
    ): AndroidCollectorsGraph {
        AndroidCollectors.installed()?.let { return it }
        val ports = CollectorPorts(
            clock = clock.orElse(null),
            logger = logger.orElse(null),
            dispatchers = dispatchers.orElse(null),
            writer = writer.orElse(null),
            eventSink = eventSink.orElse(null),
            coverage = coverage.orElse(null),
            settings = settings.orElse(null),
            permissionRequests = permissionRequests.orElse(null),
            systemChanges = systemChanges.orElse(null),
            activeJitai = activeJitai.orElse(null),
            notificationPurger = notificationPurger.orElse(null),
        )
        return AndroidCollectorsGraph(context, ports).also(AndroidCollectors::install)
    }

    @Provides
    public fun permissionCenter(graph: AndroidCollectorsGraph): PermissionCenter = graph.permissionCenter

    @Provides
    public fun capabilityStatusProvider(graph: AndroidCollectorsGraph): CapabilityStatusProvider = graph.permissionCenter

    @Provides
    public fun notificationDeliveryGate(graph: AndroidCollectorsGraph): NotificationDeliveryGate = graph.permissionCenter

    @Provides
    public fun liveCollectionControl(graph: AndroidCollectorsGraph): LiveCollectionControl = graph.liveControl

    @Provides
    public fun currentPlaceProvider(graph: AndroidCollectorsGraph): CurrentPlaceProvider = graph.location

    @Provides
    public fun settingsIntentFactory(graph: AndroidCollectorsGraph): SettingsIntentFactory = graph.settingsIntents

    @Provides
    public fun pendingIntentFactory(graph: AndroidCollectorsGraph): PendingIntentFactory = graph.pendingIntents

    @Provides
    @ElementsIntoSet
    public fun connectors(graph: AndroidCollectorsGraph): Set<@JvmSuppressWildcards Connector> = graph.connectors.toSet()
}

/** For components the system instantiates (receivers, the notification listener): see [AndroidCollectors.graph]. */
@EntryPoint
@InstallIn(SingletonComponent::class)
public interface AndroidCollectorsEntryPoint {
    public fun androidCollectorsGraph(): AndroidCollectorsGraph
}
