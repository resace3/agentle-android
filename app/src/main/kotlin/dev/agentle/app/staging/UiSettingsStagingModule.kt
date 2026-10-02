package dev.agentle.app.staging

import android.content.Intent
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.EventType
import dev.agentle.feature.settings.port.BackgroundBehaviorPort
import dev.agentle.feature.settings.port.BackgroundBehaviorState
import dev.agentle.feature.settings.port.CollectionProfile
import dev.agentle.feature.settings.port.DebugToolsPort
import dev.agentle.feature.settings.port.DebugToolsState
import dev.agentle.feature.settings.port.DeleteAllState
import dev.agentle.feature.settings.port.DeletionOverview
import dev.agentle.feature.settings.port.DeletionPort
import dev.agentle.feature.settings.port.DeletionReport
import dev.agentle.feature.settings.port.DeletionTarget
import dev.agentle.feature.settings.port.DeliveryLimits
import dev.agentle.feature.settings.port.DiagnosticReport
import dev.agentle.feature.settings.port.DiagnosticsPort
import dev.agentle.feature.settings.port.DiagnosticsSnapshot
import dev.agentle.feature.settings.port.FailureKind
import dev.agentle.feature.settings.port.NotificationSettingsPort
import dev.agentle.feature.settings.port.NotificationSettingsState
import dev.agentle.feature.settings.port.PauseOption
import dev.agentle.feature.settings.port.PrivacyPort
import dev.agentle.feature.settings.port.PrivacyState
import dev.agentle.feature.settings.port.QuietHours
import dev.agentle.feature.settings.port.RetentionImpact
import dev.agentle.feature.settings.port.RetentionPeriod
import dev.agentle.feature.settings.port.RetentionPort
import dev.agentle.feature.settings.port.RetentionSettings
import dev.agentle.feature.settings.port.SystemSettingsPort
import dev.agentle.feature.settings.port.SystemSettingsTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration

/**
 * STAGING (Team UI-SETTINGS): placeholder bindings for the `:feature:settings` ports until APP-WIRING implements them
 * on the real modules and deletes this package. Every read model emits `AppError.UnsupportedFeature` (the screens
 * show "not available") and every action fails the same way; nothing here invents data. The debug tools report
 * `available = false`, so the debug panel shows no control and the settings hub hides its link.
 */
@Module
@InstallIn(SingletonComponent::class)
object UiSettingsStagingModule {
    @Provides
    fun systemSettingsPort(): SystemSettingsPort = UnavailableSystemSettingsPort

    @Provides
    fun retentionPort(): RetentionPort = UnavailableRetentionPort

    @Provides
    fun deletionPort(): DeletionPort = UnavailableDeletionPort

    @Provides
    fun backgroundBehaviorPort(): BackgroundBehaviorPort = UnavailableBackgroundBehaviorPort

    @Provides
    fun notificationSettingsPort(): NotificationSettingsPort = UnavailableNotificationSettingsPort

    @Provides
    fun privacyPort(): PrivacyPort = UnavailablePrivacyPort

    @Provides
    fun diagnosticsPort(): DiagnosticsPort = UnavailableDiagnosticsPort

    @Provides
    fun debugToolsPort(): DebugToolsPort = UnavailableDebugToolsPort
}

private fun unavailable(feature: String): Outcome.Failure = Outcome.Failure(AppError.UnsupportedFeature(feature))

private object UnavailableSystemSettingsPort : SystemSettingsPort {
    override fun intentFor(target: SystemSettingsTarget): Outcome<Intent> = unavailable("settings.system_settings")
}

/**
 * Not a time source: the zone the screens format times in. UTC is a neutral placeholder (the default zone may not be
 * read); with every other port unavailable, no time is shown.
 */

private object UnavailableRetentionPort : RetentionPort {
    private const val FEATURE = "settings.retention"
    override val settings: Flow<Outcome<RetentionSettings>> = flowOf(unavailable(FEATURE))

    override suspend fun impactOf(period: RetentionPeriod): Outcome<RetentionImpact> = unavailable(FEATURE)

    override suspend fun setPeriod(period: RetentionPeriod): Outcome<Unit> = unavailable(FEATURE)
}

private object UnavailableDeletionPort : DeletionPort {
    private const val FEATURE = "settings.deletion"
    override val overview: Flow<Outcome<DeletionOverview>> = flowOf(unavailable(FEATURE))

    /** No deletion marker can exist without the real implementation. */
    override val deleteAllState: Flow<DeleteAllState> = flowOf(DeleteAllState.Idle)

    override suspend fun delete(target: DeletionTarget, stopCollecting: Boolean): Outcome<DeletionReport> = unavailable(FEATURE)

    override suspend fun deleteEverything(): Outcome<Unit> = unavailable(FEATURE)

    override suspend fun finishDeleteEverything(): Outcome<Unit> = unavailable(FEATURE)
}

private object UnavailableBackgroundBehaviorPort : BackgroundBehaviorPort {
    private const val FEATURE = "settings.background"
    override val state: Flow<Outcome<BackgroundBehaviorState>> = flowOf(unavailable(FEATURE))

    override suspend fun setProfile(profile: CollectionProfile): Outcome<Unit> = unavailable(FEATURE)
}

private object UnavailableNotificationSettingsPort : NotificationSettingsPort {
    private const val FEATURE = "settings.notifications"
    override val state: Flow<Outcome<NotificationSettingsState>> = flowOf(unavailable(FEATURE))

    override suspend fun pause(option: PauseOption): Outcome<Unit> = unavailable(FEATURE)

    override suspend fun resume(): Outcome<Unit> = unavailable(FEATURE)

    override suspend fun setQuietHours(quietHours: QuietHours): Outcome<Unit> = unavailable(FEATURE)

    override suspend fun setLimits(limits: DeliveryLimits): Outcome<Unit> = unavailable(FEATURE)

    override suspend fun setDetailedNotifications(enabled: Boolean): Outcome<Unit> = unavailable(FEATURE)

    override suspend fun setShowOnWearables(enabled: Boolean): Outcome<Unit> = unavailable(FEATURE)
}

private object UnavailablePrivacyPort : PrivacyPort {
    private const val FEATURE = "settings.privacy"
    override val state: Flow<Outcome<PrivacyState>> = flowOf(unavailable(FEATURE))

    override suspend fun setProtectAllScreens(enabled: Boolean): Outcome<Unit> = unavailable(FEATURE)
}

private object UnavailableDiagnosticsPort : DiagnosticsPort {
    private const val FEATURE = "settings.diagnostics"
    override val snapshot: Flow<Outcome<DiagnosticsSnapshot>> = flowOf(unavailable(FEATURE))

    override suspend fun refresh(): Unit = Unit

    override suspend fun exportReport(): Outcome<DiagnosticReport> = unavailable(FEATURE)
}

/** Debug tools never exist in a staging binding: the panel shows "not available" and calls nothing. */
private object UnavailableDebugToolsPort : DebugToolsPort {
    private const val FEATURE = "settings.debug_tools"
    override val available: Boolean = false
    override val state: Flow<Outcome<DebugToolsState>> = flowOf(unavailable(FEATURE))

    override suspend fun selectWearableScenario(id: String): Outcome<Unit> = unavailable(FEATURE)

    override suspend fun selectChatGptScenario(id: String): Outcome<Unit> = unavailable(FEATURE)

    override suspend fun generateSyntheticEvents(type: EventType, count: Int): Outcome<Int> = unavailable(FEATURE)

    override suspend fun generateDataset(days: Int): Outcome<Int> = unavailable(FEATURE)

    override suspend fun simulateJitaiTrigger(jitaiId: String): Outcome<String> = unavailable(FEATURE)

    override suspend fun setTimeOffset(offset: Duration): Outcome<Unit> = unavailable(FEATURE)

    override suspend fun clearTimeOffset(): Outcome<Unit> = unavailable(FEATURE)

    override suspend fun forceSync(connectorId: String): Outcome<Unit> = unavailable(FEATURE)

    override suspend fun forceWorker(uniqueName: String): Outcome<Unit> = unavailable(FEATURE)

    override suspend fun clearDatabase(): Outcome<Unit> = unavailable(FEATURE)

    override suspend fun setFailureInjection(kind: FailureKind, enabled: Boolean): Outcome<Unit> = unavailable(FEATURE)
}
