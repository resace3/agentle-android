package dev.agentle.feature.settings.testing

import android.content.Intent
import dev.agentle.ai.api.AiProviderState
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.EventType
import dev.agentle.core.model.PermissionState
import dev.agentle.core.testing.TestAgentleClock
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.feature.settings.port.CollectionControl
import dev.agentle.feature.settings.port.DebugOption
import dev.agentle.feature.settings.port.DebugToolsPort
import dev.agentle.feature.settings.port.DebugToolsState
import dev.agentle.feature.settings.port.DeleteAllCheck
import dev.agentle.feature.settings.port.DeleteAllCheckItem
import dev.agentle.feature.settings.port.DeleteAllReport
import dev.agentle.feature.settings.port.DeleteAllState
import dev.agentle.feature.settings.port.DeleteAllStep
import dev.agentle.feature.settings.port.DeletionItem
import dev.agentle.feature.settings.port.DeletionOverview
import dev.agentle.feature.settings.port.DeletionPort
import dev.agentle.feature.settings.port.DeletionReport
import dev.agentle.feature.settings.port.DeletionTarget
import dev.agentle.feature.settings.port.DeliveryLimitBounds
import dev.agentle.feature.settings.port.DeliveryLimits
import dev.agentle.feature.settings.port.FailureKind
import dev.agentle.feature.settings.port.JitaiPause
import dev.agentle.feature.settings.port.NotificationAccess
import dev.agentle.feature.settings.port.NotificationChannelInfo
import dev.agentle.feature.settings.port.NotificationSettingsPort
import dev.agentle.feature.settings.port.NotificationSettingsState
import dev.agentle.feature.settings.port.PauseOption
import dev.agentle.feature.settings.port.QuietHours
import dev.agentle.feature.settings.port.RemoteDisconnection
import dev.agentle.feature.settings.port.RemoteDisconnectionResult
import dev.agentle.feature.settings.port.RemoteService
import dev.agentle.feature.settings.port.RetentionImpact
import dev.agentle.feature.settings.port.RetentionPeriod
import dev.agentle.feature.settings.port.RetentionPort
import dev.agentle.feature.settings.port.RetentionSettings
import dev.agentle.feature.settings.port.SystemSettingsPort
import dev.agentle.feature.settings.port.SystemSettingsTarget
import dev.agentle.feature.settings.port.UserTimeZonePort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

internal val clock = TestAgentleClock()
internal val now: Instant = clock.wall.now()

internal class TestZonePort(private val zone: TimeZone = TimeZone.UTC) : UserTimeZonePort {
    override fun zone(): TimeZone = zone
}

internal class RecordingNavigator : AppNavigator {
    val navigated = mutableListOf<AppRoute>()
    val resets = mutableListOf<AppRoute>()
    var backs = 0

    override fun navigate(route: AppRoute) {
        navigated += route
    }

    override fun back() {
        backs++
    }

    override fun resetTo(route: AppRoute) {
        resets += route
    }
}

internal class FakeSystemSettingsPort : SystemSettingsPort {
    val requested = mutableListOf<SystemSettingsTarget>()

    override fun intentFor(target: SystemSettingsTarget): Outcome<Intent> {
        requested += target
        return Outcome.Success(Intent("test.settings"))
    }
}

internal class FakeRetentionPort(
    initial: Outcome<RetentionSettings> = Outcome.Success(RetentionSettings(RetentionPeriod.KEEP_INDEFINITELY, null)),
) : RetentionPort {
    val settingsFlow = MutableStateFlow(initial)
    var impactRecords = 0L
    var saveResult: Outcome<Unit> = Outcome.Success(Unit)
    val saved = mutableListOf<RetentionPeriod>()
    override val settings: Flow<Outcome<RetentionSettings>> = settingsFlow

    override suspend fun impactOf(period: RetentionPeriod): Outcome<RetentionImpact> =
        Outcome.Success(RetentionImpact(period, impactRecords, now - 30.days))

    override suspend fun setPeriod(period: RetentionPeriod): Outcome<Unit> {
        saved += period
        if (saveResult is Outcome.Success) settingsFlow.value = Outcome.Success(RetentionSettings(period, null))
        return saveResult
    }
}

internal class FakeDeletionPort(overview: DeletionOverview = Fixtures.overview) : DeletionPort {
    val overviewFlow = MutableStateFlow<Outcome<DeletionOverview>>(Outcome.Success(overview))
    val stateFlow = MutableStateFlow<DeleteAllState>(DeleteAllState.Idle)
    val deletes = mutableListOf<Pair<DeletionTarget, Boolean>>()
    var deleteEverythingCalls = 0
    var finishCalls = 0

    /** When true, deleteEverything() suspends until [release] completes, so "starting" can be observed. */
    var holdDeleteEverything = false
    val release = kotlinx.coroutines.CompletableDeferred<Unit>()
    var finishResult: Outcome<Unit> = Outcome.Success(Unit)
    override val overview: Flow<Outcome<DeletionOverview>> = overviewFlow
    override val deleteAllState: Flow<DeleteAllState> = stateFlow

    override suspend fun delete(target: DeletionTarget, stopCollecting: Boolean): Outcome<DeletionReport> {
        deletes += target to stopCollecting
        return Outcome.Success(Fixtures.report(target, stopCollecting))
    }

    override suspend fun deleteEverything(): Outcome<Unit> {
        deleteEverythingCalls++
        if (holdDeleteEverything) release.await()
        stateFlow.value = DeleteAllState.Running(DeleteAllStep.WRITE_MARKER, emptySet(), resumed = false)
        return Outcome.Success(Unit)
    }

    override suspend fun finishDeleteEverything(): Outcome<Unit> {
        finishCalls++
        return finishResult
    }
}

internal class FakeNotificationSettingsPort(initial: NotificationSettingsState = Fixtures.notifications) : NotificationSettingsPort {
    val stateFlow = MutableStateFlow<Outcome<NotificationSettingsState>>(Outcome.Success(initial))
    val limitWrites = mutableListOf<DeliveryLimits>()
    val pauses = mutableListOf<PauseOption>()
    val quietWrites = mutableListOf<QuietHours>()
    override val state: Flow<Outcome<NotificationSettingsState>> = stateFlow

    private fun current() = (stateFlow.value as Outcome.Success).value

    override suspend fun pause(option: PauseOption): Outcome<Unit> {
        pauses += option
        stateFlow.value = Outcome.Success(current().copy(pause = JitaiPause.UntilResumed))
        return Outcome.Success(Unit)
    }

    override suspend fun resume(): Outcome<Unit> {
        stateFlow.value = Outcome.Success(current().copy(pause = JitaiPause.NotPaused))
        return Outcome.Success(Unit)
    }

    override suspend fun setQuietHours(quietHours: QuietHours): Outcome<Unit> {
        if (quietHours.start == quietHours.end) return Outcome.Failure(AppError.ValidationError(listOf("E025")))
        quietWrites += quietHours
        stateFlow.value = Outcome.Success(current().copy(quietHours = quietHours))
        return Outcome.Success(Unit)
    }

    override suspend fun setLimits(limits: DeliveryLimits): Outcome<Unit> {
        if (!DeliveryLimitBounds.contains(limits)) return Outcome.Failure(AppError.ValidationError(listOf("limits")))
        limitWrites += limits
        stateFlow.value = Outcome.Success(current().copy(limits = limits))
        return Outcome.Success(Unit)
    }

    override suspend fun setDetailedNotifications(enabled: Boolean): Outcome<Unit> {
        stateFlow.value = Outcome.Success(current().copy(detailedNotifications = enabled))
        return Outcome.Success(Unit)
    }

    override suspend fun setShowOnWearables(enabled: Boolean): Outcome<Unit> {
        stateFlow.value = Outcome.Success(current().copy(showOnWearables = enabled))
        return Outcome.Success(Unit)
    }
}

internal class FakeDebugToolsPort(override val available: Boolean) : DebugToolsPort {
    var calls = 0
    override val state: Flow<Outcome<DebugToolsState>> = MutableStateFlow(
        if (available) Outcome.Success(Fixtures.debugTools) else Outcome.Failure(AppError.UnsupportedFeature("debug")),
    )

    private fun ok(): Outcome<Unit> {
        calls++
        return Outcome.Success(Unit)
    }

    override suspend fun selectWearableScenario(id: String) = ok()

    override suspend fun selectChatGptScenario(id: String) = ok()

    override suspend fun generateSyntheticEvents(type: EventType, count: Int): Outcome<Int> = ok().let { Outcome.Success(count) }

    override suspend fun generateDataset(days: Int): Outcome<Int> = ok().let { Outcome.Success(days) }

    override suspend fun simulateJitaiTrigger(jitaiId: String): Outcome<String> = ok().let { Outcome.Success("DELIVERED") }

    override suspend fun setTimeOffset(offset: Duration) = ok()

    override suspend fun clearTimeOffset() = ok()

    override suspend fun forceSync(connectorId: String) = ok()

    override suspend fun forceWorker(uniqueName: String) = ok()

    override suspend fun clearDatabase() = ok()

    override suspend fun setFailureInjection(kind: FailureKind, enabled: Boolean) = ok()
}

/** Fixed data for tests and screenshots. */
internal object Fixtures {
    val overview = DeletionOverview(
        items = listOf(
            DeletionItem(DeletionTarget.WearableData, records = 12_400, collection = CollectionControl.ACTIVE),
            DeletionItem(DeletionTarget.PhoneData, records = 8_210, collection = CollectionControl.ACTIVE),
            DeletionItem(DeletionTarget.Category(DataCategory.SLEEP), records = 412, collection = CollectionControl.ACTIVE),
            DeletionItem(DeletionTarget.Category(DataCategory.APP_USAGE), records = 3_020, collection = CollectionControl.STOPPED),
            DeletionItem(DeletionTarget.Insights, records = 36),
            DeletionItem(DeletionTarget.InterventionHistory, records = 140),
            DeletionItem(DeletionTarget.GeneratedMedia, records = 5, files = 5, bytes = 2_400_000),
        ),
        ledgerEntries = 88,
    )

    fun report(target: DeletionTarget, stop: Boolean) = DeletionReport(
        target = target,
        deletedRecords = 412,
        deletedFiles = 0,
        remainingRecords = 0,
        remainingFiles = 0,
        importFloor = now,
        aiConsentRevoked = true,
        collectionStopped = stop,
    )

    val running = DeleteAllState.Running(
        step = DeleteAllStep.DELETE_DATABASE,
        completed = setOf(
            DeleteAllStep.WRITE_MARKER,
            DeleteAllStep.STOP_PRODUCERS,
            DeleteAllStep.REVOKE_REMOTE,
            DeleteAllStep.DRAIN_WRITERS,
            DeleteAllStep.CLOSE_DATABASE,
        ),
        resumed = false,
        remote = listOf(RemoteDisconnection(RemoteService.CHATGPT, RemoteDisconnectionResult.DISCONNECTED)),
    )

    val verifiedWithRemoteWarning = DeleteAllState.Verified(
        report = DeleteAllReport(
            checks = DeleteAllCheckItem.entries.map { DeleteAllCheck(it, 0) },
            remote = listOf(
                RemoteDisconnection(RemoteService.CHATGPT, RemoteDisconnectionResult.UNCONFIRMED),
                RemoteDisconnection(RemoteService.GOOGLE, RemoteDisconnectionResult.DISCONNECTED),
            ),
        ),
        resumed = true,
    )

    val notifications = NotificationSettingsState(
        pause = JitaiPause.NotPaused,
        quietHours = QuietHours(enabled = true, start = LocalTime(22, 0), end = LocalTime(7, 0)),
        limits = DeliveryLimits.DEFAULT,
        detailedNotifications = false,
        showOnWearables = false,
        access = NotificationAccess(PermissionState.DENIED, appNotificationsEnabled = true, pausedBySystem = false),
        channels = listOf(
            NotificationChannelInfo("jitai", "Check-ins", blocked = false),
            NotificationChannelInfo("sync", "Sync status", blocked = true),
        ),
    )

    val atCeilings = notifications.copy(
        limits = DeliveryLimits(
            dailyCap = DeliveryLimitBounds.DAILY_CAP_MAX,
            weeklyCap = DeliveryLimitBounds.WEEKLY_CAP_MAX,
            minGapMinutes = DeliveryLimitBounds.MIN_GAP_MINUTES_MIN,
            channelCaps = emptyMap(),
        ),
    )

    val debugTools = DebugToolsState(
        wearableScenarios = listOf(DebugOption("healthy", "Healthy week"), DebugOption("rate_limited", "Rate limited")),
        selectedWearableScenario = "healthy",
        chatGptScenarios = listOf(DebugOption("ok", "Responds")),
        selectedChatGptScenario = "ok",
        syntheticEventTypes = EventType.entries.take(2),
        jitais = listOf(DebugOption("walk", "Walk reminder")),
        appTime = now,
        timeOffset = null,
        syncTargets = listOf(DebugOption("googlehealth", "Google Health")),
        workers = listOf(DebugOption("retention", "retention")),
        activeFailures = emptySet(),
    )

    val chatGptDisconnected: AiProviderState = AiProviderState.Disconnected
}
