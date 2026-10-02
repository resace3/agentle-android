package dev.agentle.feature.hub

import android.content.Intent
import androidx.paging.PagingData
import dev.agentle.ai.api.AiProviderState
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.CapabilityCategory
import dev.agentle.core.model.CapabilityStatus
import dev.agentle.core.model.ConnectionStatus
import dev.agentle.core.model.ConnectorMetadata
import dev.agentle.core.model.DataCapability
import dev.agentle.core.model.PermissionState
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.PlannedStatus
import dev.agentle.core.model.SyncStatus
import dev.agentle.core.testing.TestAgentleClock
import dev.agentle.core.time.AgentleClock
import dev.agentle.feature.hub.port.ActiveJitai
import dev.agentle.feature.hub.port.CapabilityItem
import dev.agentle.feature.hub.port.DashboardData
import dev.agentle.feature.hub.port.DashboardPort
import dev.agentle.feature.hub.port.DataSourceItem
import dev.agentle.feature.hub.port.DataSourceKind
import dev.agentle.feature.hub.port.DataSourcesPort
import dev.agentle.feature.hub.port.DayCoverage
import dev.agentle.feature.hub.port.HubClockPort
import dev.agentle.feature.hub.port.PendingIntervention
import dev.agentle.feature.hub.port.PermissionCenterPort
import dev.agentle.feature.hub.port.TimelineFilter
import dev.agentle.feature.hub.port.TimelinePort
import dev.agentle.feature.hub.port.TodayMetricKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal val NOW: Instant = Instant.parse("2026-10-02T10:00:00Z")
internal val BERLIN: TimeZone = TimeZone.of("Europe/Berlin")

internal fun clockPort(clock: AgentleClock = TestAgentleClock(NOW, BERLIN)) = object : HubClockPort {
    override val clock: AgentleClock = clock
}

/** Records every call; [failWith] makes every action fail. */
internal open class Recorder {
    val calls = mutableListOf<String>()
    var failWith: AppError? = null

    fun record(call: String): Outcome<Unit> {
        calls += call
        return failWith?.let { Outcome.Failure(it) } ?: Outcome.Success(Unit)
    }
}

internal class FakeDashboardPort(data: DashboardData = DashboardData()) :
    Recorder(),
    DashboardPort {
    override val dashboard = MutableStateFlow(data)

    override suspend fun snooze(decisionKey: String) = record("snooze $decisionKey")

    override suspend fun notNow(decisionKey: String) = record("notNow $decisionKey")

    override suspend fun stopJitai(jitaiId: String) = record("stop $jitaiId")
}

internal class FakeDataSourcesPort(items: List<DataSourceItem> = emptyList()) :
    Recorder(),
    DataSourcesPort {
    override val sources = MutableStateFlow(items)

    override suspend fun setEnabled(connectorId: String, enabled: Boolean) = record("enable $connectorId $enabled")

    override suspend fun syncNow(connectorId: String) = record("sync $connectorId")
}

/** Applies the DENIED / DENIED_PERMANENTLY rule like the wiring will. */
internal class FakePermissionCenterPort(items: List<CapabilityItem> = emptyList()) :
    Recorder(),
    PermissionCenterPort {
    override val capabilities = MutableStateFlow(items)
    val refreshes = mutableListOf<Map<String, Boolean>>()

    override suspend fun refresh(rationale: Map<String, Boolean>): Outcome<Unit> {
        refreshes += rationale
        return Outcome.Success(Unit)
    }

    override suspend fun reportPermissionResult(
        capabilityId: String,
        granted: Map<String, Boolean>,
        showRationale: Map<String, Boolean>,
    ): Outcome<Unit> {
        val state = when {
            granted.values.all { it } -> PermissionState.ALLOWED
            showRationale.values.any { it } -> PermissionState.DENIED
            else -> PermissionState.DENIED_PERMANENTLY
        }
        setState(capabilityId, state, missing = granted.filterValues { !it }.keys.toList())
        return record("report $capabilityId $granted $showRationale")
    }

    fun setState(capabilityId: String, state: PermissionState, missing: List<String> = emptyList()) {
        capabilities.value = capabilities.value.map {
            if (it.capability.id == capabilityId) it.copy(status = it.status.copy(state = state), missingPermissions = missing) else it
        }
    }

    override suspend fun setCollectionEnabled(capabilityId: String, enabled: Boolean) = record("collect $capabilityId $enabled")

    override fun settingsIntent(capabilityId: String): Intent? = Intent("test.settings.$capabilityId")
}

internal class FakeTimelinePort(
    private val events: List<PersonalEvent> = emptyList(),
    private val coverage: Map<LocalDate, DayCoverage> = emptyMap(),
) : TimelinePort {
    val requests = mutableListOf<Pair<TimelineFilter, Instant>>()

    override fun events(filter: TimelineFilter, upperBound: Instant): Flow<PagingData<PersonalEvent>> {
        requests += filter to upperBound
        return flowOf(PagingData.from(events.filter { filter.eventType == null || it.type == filter.eventType }))
    }

    override fun dayCoverage(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, DayCoverage>> = flowOf(coverage)

    override val sources: Flow<List<String>> = flowOf(events.map { it.source.value }.distinct())
}

internal fun capability(
    id: String,
    name: String,
    category: CapabilityCategory,
    state: PermissionState,
    permissions: List<String> = emptyList(),
    specialAccess: String? = null,
    planned: PlannedStatus = PlannedStatus.IMPLEMENT,
    enabled: Boolean = true,
    lastUsed: Instant? = null,
) = CapabilityItem(
    capability = DataCapability(
        id = id,
        name = name,
        category = category,
        runtimePermissions = permissions,
        specialAccess = specialAccess,
        plannedStatus = planned,
    ),
    status = CapabilityStatus(capabilityId = id, state = state, evaluatedAt = NOW),
    missingPermissions = if (state == PermissionState.ALLOWED) emptyList() else permissions,
    collectionEnabled = enabled,
    lastUsedAt = lastUsed,
)

/** One capability in each of the ten states. */
internal fun mixedCapabilities(): List<CapabilityItem> = listOf(
    capability(
        "activity_recognition_transitions",
        "Physical activity",
        CapabilityCategory.ACTIVITY,
        PermissionState.DENIED,
        listOf("android.permission.ACTIVITY_RECOGNITION"),
    ),
    capability(
        "step_count_recording_api",
        "Steps",
        CapabilityCategory.ACTIVITY,
        PermissionState.ALLOWED,
        listOf("android.permission.ACTIVITY_RECOGNITION"),
        lastUsed = NOW - 2.hours,
    ),
    capability("app_usage_events", "App usage", CapabilityCategory.APPS, PermissionState.REQUIRES_SETTINGS, specialAccess = "usage_access"),
    capability(
        "notification_events_metadata",
        "Notification events",
        CapabilityCategory.NOTIFICATIONS,
        PermissionState.BACKGROUND_ALLOWED,
        specialAccess = "notification_listener",
        lastUsed = NOW - 5.minutes,
    ),
    capability(
        "post_notifications_jitai",
        "Posting nudges",
        CapabilityCategory.NOTIFICATIONS,
        PermissionState.DENIED_PERMANENTLY,
        listOf("android.permission.POST_NOTIFICATIONS"),
    ),
    capability(
        "location_foreground",
        "Location while in use",
        CapabilityCategory.LOCATION,
        PermissionState.FOREGROUND_ONLY,
        listOf("android.permission.ACCESS_COARSE_LOCATION"),
    ),
    capability(
        "calendar_events",
        "Calendar",
        CapabilityCategory.CALENDAR,
        PermissionState.PARTIALLY_ALLOWED,
        listOf("android.permission.READ_CALENDAR"),
    ),
    capability("bluetooth_adapter_state", "Bluetooth state", CapabilityCategory.BLUETOOTH, PermissionState.UNAVAILABLE),
    capability(
        "sms_metadata",
        "SMS metadata",
        CapabilityCategory.COMMUNICATION,
        PermissionState.RESTRICTED_BY_ANDROID,
        planned = PlannedStatus.DOCUMENT_UNAVAILABLE,
    ),
    capability(
        "health_connect_on_device_steps",
        "Health Connect steps",
        CapabilityCategory.HEALTH,
        PermissionState.UNSUPPORTED_ON_DEVICE,
        specialAccess = "health_connect",
    ),
)

internal fun connector(
    id: String,
    name: String,
    connection: ConnectionStatus = ConnectionStatus.CONNECTED,
    permission: PermissionState = PermissionState.ALLOWED,
    lastCollected: Instant? = NOW - 30.minutes,
    sync: SyncStatus = SyncStatus.SUCCEEDED,
) = ConnectorMetadata(
    connectorId = id,
    name = name,
    enabled = connection != ConnectionStatus.DISABLED,
    connection = connection,
    permissionSummary = permission,
    lastSuccessfulCollection = lastCollected,
    syncState = sync,
)

internal fun sourceItems(): List<DataSourceItem> = listOf(
    DataSourceItem(
        connector("android.usage", "App usage"),
        DataSourceKind.ANDROID_COLLECTOR,
        listOf("app_usage_events"),
        coverageThrough = NOW - 1.hours,
    ),
    DataSourceItem(
        connector("googlehealth", "Google Health", ConnectionStatus.NEEDS_REAUTH, PermissionState.DENIED, sync = SyncStatus.FAILED),
        DataSourceKind.WEARABLE,
        emptyList(),
        gapDays = listOf(LocalDate(2026, 9, 29), LocalDate(2026, 9, 30)),
    ),
    DataSourceItem(
        connector("android.activity", "Activity", ConnectionStatus.DISABLED, PermissionState.DENIED, lastCollected = null),
        DataSourceKind.ANDROID_COLLECTOR,
        listOf("activity_recognition_transitions"),
    ),
)

internal fun fullDashboard() = DashboardData(
    today = mapOf(
        TodayMetricKind.STEPS to FeatureValue.Known(FeatureScalar.IntValue(6_240), NOW),
        TodayMetricKind.SCREEN_TIME_MINUTES to FeatureValue.Known(FeatureScalar.IntValue(192), NOW),
        TodayMetricKind.SLEEP_MINUTES to FeatureValue.Stale(FeatureScalar.IntValue(410), NOW - 26.hours, MissingReason.COVERAGE_GAP),
        TodayMetricKind.UNLOCKS to FeatureValue.Missing(MissingReason.NO_PERMISSION),
    ),
    activeJitais = listOf(ActiveJitai("j1", "Evening wind-down", NOW + 3.hours)),
    wearable = connector("googlehealth", "Google Health", ConnectionStatus.NEEDS_REAUTH),
    chatGpt = AiProviderState.Connected("Personal account", null),
    collectors = listOf(
        connector("android.usage", "App usage"),
        connector("android.activity", "Activity", permission = PermissionState.DENIED),
    ),
    pendingInterventions = listOf(
        PendingIntervention("k1", "j1", "Time to wind down", "You planned to stop screens at 22:30.", NOW - 10.minutes),
    ),
)
