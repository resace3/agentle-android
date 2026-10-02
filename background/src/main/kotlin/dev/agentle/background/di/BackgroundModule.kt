package dev.agentle.background.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.agentle.background.AndroidDeviceSignals
import dev.agentle.background.BackgroundDiagnostics
import dev.agentle.background.BackgroundJobs
import dev.agentle.background.BackgroundState
import dev.agentle.background.DeviceSignals
import dev.agentle.background.PrefsSchedulerStore
import dev.agentle.background.SchedulerStore
import dev.agentle.background.WorkGateway
import dev.agentle.background.WorkScheduler
import dev.agentle.background.port.AttentionNotifier
import dev.agentle.background.port.Collectors
import dev.agentle.background.port.DeletionMarker
import dev.agentle.background.port.FeatureRefresher
import dev.agentle.background.port.GapSink
import dev.agentle.background.port.JitaiRunner
import dev.agentle.background.port.Maintenance
import dev.agentle.background.port.SchedulerSettings
import dev.agentle.background.port.WearableSync
import dev.agentle.background.workManagerGateway
import dev.agentle.core.time.AgentleClock
import dev.agentle.core.time.SystemAgentleClock
import kotlinx.coroutines.flow.Flow
import javax.inject.Qualifier
import javax.inject.Singleton

/** The clock the scheduler reads; module-local so it never collides with the app's own binding. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class BackgroundClock

/** Wires :background; the ports themselves are bound by the app (staging until the owning teams land). */
@Module
@InstallIn(SingletonComponent::class)
public object BackgroundModule {
    @Provides @Singleton @BackgroundClock
    public fun clock(): AgentleClock = SystemAgentleClock()

    @Provides @Singleton
    public fun gateway(@ApplicationContext context: Context): WorkGateway = workManagerGateway(context)

    @Provides @Singleton
    public fun store(@ApplicationContext context: Context): SchedulerStore = PrefsSchedulerStore(context)

    @Provides @Singleton
    public fun signals(@ApplicationContext context: Context): DeviceSignals = AndroidDeviceSignals(context)

    @Provides @Singleton
    public fun scheduler(
        gateway: WorkGateway,
        store: SchedulerStore,
        deletion: DeletionMarker,
        runner: JitaiRunner,
        signals: DeviceSignals,
        @BackgroundClock clock: AgentleClock,
    ): WorkScheduler = WorkScheduler(gateway, store, deletion, runner, signals, clock)

    @Suppress("LongParameterList")
    @Provides
    @Singleton
    public fun jobs(
        scheduler: WorkScheduler,
        store: SchedulerStore,
        deletion: DeletionMarker,
        runner: JitaiRunner,
        wearable: WearableSync,
        collectors: Collectors,
        features: FeatureRefresher,
        maintenance: Maintenance,
        gaps: GapSink,
        settings: SchedulerSettings,
        notifier: AttentionNotifier,
        signals: DeviceSignals,
        @BackgroundClock clock: AgentleClock,
    ): BackgroundJobs = BackgroundJobs(
        scheduler, store, deletion, runner, wearable, collectors, features, maintenance, gaps, settings, notifier,
        signals, clock,
    )

    /** Worker-state Flow for the diagnostics screen. */
    @Provides @Singleton
    public fun state(gateway: WorkGateway, store: SchedulerStore): Flow<BackgroundState> = BackgroundDiagnostics.flow(gateway, store)
}
