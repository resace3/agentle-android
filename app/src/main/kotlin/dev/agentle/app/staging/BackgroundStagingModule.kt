package dev.agentle.app.staging

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.agentle.background.port.AttentionNotifier
import dev.agentle.background.port.CollectionProfile
import dev.agentle.background.port.Collectors
import dev.agentle.background.port.DeletionMarker
import dev.agentle.background.port.EventsRun
import dev.agentle.background.port.FeatureInvalidation
import dev.agentle.background.port.FeatureRefresher
import dev.agentle.background.port.GapSink
import dev.agentle.background.port.JitaiRunner
import dev.agentle.background.port.Maintenance
import dev.agentle.background.port.ProcessExit
import dev.agentle.background.port.ReplanCause
import dev.agentle.background.port.SchedulerSettings
import dev.agentle.background.port.TimerRun
import dev.agentle.background.port.WearableConnection
import dev.agentle.background.port.WearableSync
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * Staging: every :background port is Unavailable until the owning team's adapter replaces its binding. Each call
 * returns AppError.UnsupportedFeature (a permanent failure, so no retry storm); nothing is invented.
 */
@Module
@InstallIn(SingletonComponent::class)
object BackgroundStagingModule {
    private fun <T> unavailable(port: String): Outcome<T> = Outcome.failure(AppError.UnsupportedFeature(port))

    @Provides @Singleton
    fun jitai(): JitaiRunner = object : JitaiRunner {
        override suspend fun runTimer(): Outcome<TimerRun> = unavailable("JitaiRunner")
        override suspend fun runEvents(): Outcome<EventsRun> = unavailable("JitaiRunner")
        override suspend fun replan(cause: ReplanCause): Outcome<Instant?> = unavailable("JitaiRunner")
        override suspend fun nextDueAt(): Outcome<Instant?> = unavailable("JitaiRunner")
        override suspend fun revalidateStoredDefinitions(): Outcome<Int> = unavailable("JitaiRunner")
        override suspend fun plannedOffsetSeconds(): Outcome<Int?> = unavailable("JitaiRunner")
    }

    @Provides @Singleton
    fun wearable(): WearableSync = object : WearableSync {
        override suspend fun connection(): WearableConnection = WearableConnection.NOT_CONNECTED
        override suspend fun syncAll(): Outcome<Unit> = unavailable("WearableSync")
        override suspend fun syncStreams(streams: Set<String>): Outcome<Unit> = unavailable("WearableSync")
        override suspend fun clampFutureCursors(now: Instant): Outcome<Unit> = unavailable("WearableSync")
    }

    @Provides @Singleton
    fun collectors(): Collectors = object : Collectors {
        override suspend fun collectUsage(): Outcome<Unit> = unavailable("Collectors")
        override suspend fun collectDevice(): Outcome<Unit> = unavailable("Collectors")
        override suspend fun reregisterActivityTransitions(): Outcome<Unit> = unavailable("Collectors")
    }

    @Provides @Singleton
    fun features(): FeatureRefresher = object : FeatureRefresher {
        override suspend fun refreshDirtyDays(): Outcome<Unit> = unavailable("FeatureRefresher")
        override suspend fun invalidate(reason: FeatureInvalidation): Outcome<Unit> = unavailable("FeatureRefresher")
    }

    @Provides @Singleton
    fun maintenance(): Maintenance = object : Maintenance {
        override suspend fun weeklyInsights(): Outcome<Unit> = unavailable("Maintenance")
        override suspend fun applyRetention(): Outcome<Unit> = unavailable("Maintenance")
        override suspend fun cleanupMedia(): Outcome<Unit> = unavailable("Maintenance")
    }

    @Provides @Singleton
    fun gaps(): GapSink = object : GapSink {
        override suspend fun recordProcessExits(exits: List<ProcessExit>): Outcome<Unit> = unavailable("GapSink")
    }

    /** Default profile only (the settings owner replaces it); not user data. */
    @Provides @Singleton
    fun settings(): SchedulerSettings = object : SchedulerSettings {
        override val profile: Flow<CollectionProfile> = flowOf(CollectionProfile.BALANCED)
    }

    @Provides @Singleton
    fun deletion(): DeletionMarker = object : DeletionMarker {
        override fun isDeletionInProgress(): Boolean = false
    }

    @Provides @Singleton
    fun notifier(): AttentionNotifier = object : AttentionNotifier {
        override suspend fun reconnectWearable() = Unit
    }
}
