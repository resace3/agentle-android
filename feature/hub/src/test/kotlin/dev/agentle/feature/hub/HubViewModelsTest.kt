package dev.agentle.feature.hub

import androidx.paging.testing.asSnapshot
import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.core.common.AppException
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventId
import dev.agentle.core.model.EventMetadata
import dev.agentle.core.model.EventType
import dev.agentle.core.model.NoPayload
import dev.agentle.core.model.PermissionState
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.core.ui.permission.PermissionDialogResult
import dev.agentle.feature.hub.dashboard.DashboardViewModel
import dev.agentle.feature.hub.permissions.PermissionCenterEffect
import dev.agentle.feature.hub.permissions.PermissionCenterViewModel
import dev.agentle.feature.hub.port.DashboardData
import dev.agentle.feature.hub.port.TimelineFilter
import dev.agentle.feature.hub.sources.DataSourcesViewModel
import dev.agentle.feature.hub.timeline.TimelineRow
import dev.agentle.feature.hub.timeline.TimelineViewModel
import dev.agentle.feature.hub.timeline.toRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HubViewModelsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `dashboard goes from loading to content and runs the card actions`() = runTest(dispatcher) {
        val port = FakeDashboardPort(fullDashboard())
        val vm = DashboardViewModel(port, clockPort())
        assertThat(vm.state.value.content).isEqualTo(Load.Loading)
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()
        assertThat((vm.state.value.content as Load.Ready).value).isEqualTo(fullDashboard())
        val card = fullDashboard().pendingInterventions.single()
        vm.snooze(card)
        vm.notNow(card)
        vm.stopJitai(card)
        advanceUntilIdle()
        assertThat(port.calls).containsExactly("snooze k1", "notNow k1", "stop j1").inOrder()
    }

    @Test
    fun `dashboard shows a port error, retries, and reports failed actions`() = runTest(dispatcher) {
        var attempts = 0
        val port = object : dev.agentle.feature.hub.port.DashboardPort by FakeDashboardPort() {
            override val dashboard = flow {
                attempts++
                if (attempts == 1) throw AppException(AppError.DatabaseError())
                emit(DashboardData())
            }
        }
        val vm = DashboardViewModel(port, clockPort())
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()
        assertThat(vm.state.value.content).isEqualTo(Load.Failed(AppError.DatabaseError()))
        vm.retry()
        advanceUntilIdle()
        assertThat(vm.state.value.content).isEqualTo(Load.Ready(DashboardData()))

        val failing = FakeDashboardPort().apply { failWith = AppError.ValidationError(listOf("snooze_limit")) }
        val vm2 = DashboardViewModel(failing, clockPort())
        backgroundScope.launch(dispatcher) { vm2.state.collect {} }
        vm2.snooze(fullDashboard().pendingInterventions.single())
        advanceUntilIdle()
        assertThat(vm2.state.value.actionError).isEqualTo(AppError.ValidationError(listOf("snooze_limit")))
        vm2.dismissError()
        advanceUntilIdle()
        assertThat(vm2.state.value.actionError).isNull()
    }

    @Test
    fun `data sources toggle and sync through the port, errors surface`() = runTest(dispatcher) {
        val port = FakeDataSourcesPort(sourceItems())
        val vm = DataSourcesViewModel(port, clockPort())
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()
        val items = (vm.state.value.content as Load.Ready).value
        vm.setEnabled(items[2], true)
        vm.syncNow(items[1])
        advanceUntilIdle()
        assertThat(port.calls).containsExactly("enable android.activity true", "sync googlehealth").inOrder()
        port.failWith = AppError.AuthenticationRequired("googlehealth")
        vm.syncNow(items[1])
        advanceUntilIdle()
        assertThat(vm.state.value.actionError).isEqualTo(AppError.AuthenticationRequired("googlehealth"))
    }

    @Test
    fun `permission center groups by category, requests, reports and opens Settings`() = runTest(dispatcher) {
        val port = FakePermissionCenterPort(mixedCapabilities())
        val vm = PermissionCenterViewModel("app_usage_events", port, clockPort())
        val effects = mutableListOf<PermissionCenterEffect>()
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        backgroundScope.launch(dispatcher) { vm.effect.collect { effects += it } }
        advanceUntilIdle()
        val groups = (vm.state.value.content as Load.Ready).value
        assertThat(groups.map { it.category }).isInOrder()
        assertThat(groups.sumOf { it.items.size }).isEqualTo(10)
        assertThat(vm.state.value.focusId).isEqualTo("app_usage_events")

        val activity = mixedCapabilities().first()
        vm.request(activity)
        vm.onPermissionResult(
            PermissionDialogResult(
                mapOf("android.permission.ACTIVITY_RECOGNITION" to false),
                mapOf("android.permission.ACTIVITY_RECOGNITION" to false),
            ),
        )
        advanceUntilIdle()
        assertThat(port.capabilities.value.first().status.state).isEqualTo(PermissionState.DENIED_PERMANENTLY)
        vm.openSettings(port.capabilities.value.first())
        vm.refresh(mapOf("android.permission.ACTIVITY_RECOGNITION" to false))
        advanceUntilIdle()
        assertThat(effects.first()).isEqualTo(PermissionCenterEffect.Request(listOf("android.permission.ACTIVITY_RECOGNITION")))
        assertThat(
            (effects[1] as PermissionCenterEffect.OpenSettings).intent?.action,
        ).isEqualTo("test.settings.activity_recognition_transitions")
        assertThat(port.refreshes).hasSize(1)
    }

    @Test
    fun `permission center ignores a cancelled dialog and shows action errors`() = runTest(dispatcher) {
        val port = FakePermissionCenterPort(mixedCapabilities())
        val vm = PermissionCenterViewModel(null, port, clockPort())
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        vm.request(mixedCapabilities().first())
        vm.onPermissionResult(PermissionDialogResult(emptyMap(), emptyMap()))
        port.failWith = AppError.DatabaseError()
        vm.setCollectionEnabled(mixedCapabilities().first(), false)
        advanceUntilIdle()
        assertThat(port.calls).containsExactly("collect activity_recognition_transitions false")
        assertThat(vm.state.value.actionError).isEqualTo(AppError.DatabaseError())
        vm.showRevokeHelp(mixedCapabilities()[1])
        advanceUntilIdle()
        assertThat(vm.state.value.revokeHelpFor).isEqualTo(mixedCapabilities()[1])
    }

    @Test
    fun `primary actions follow the state and never offer exact alarms or health dialogs`() {
        val actions = mixedCapabilities().associate { it.capability.id to primaryAction(it) }
        assertThat(actions["activity_recognition_transitions"]).isEqualTo(CapabilityAction.REQUEST)
        assertThat(actions["step_count_recording_api"]).isEqualTo(CapabilityAction.NONE)
        assertThat(actions["app_usage_events"]).isEqualTo(CapabilityAction.OPEN_SETTINGS)
        assertThat(actions["post_notifications_jitai"]).isEqualTo(CapabilityAction.OPEN_SETTINGS)
        assertThat(actions["location_foreground"]).isEqualTo(CapabilityAction.REQUEST)
        assertThat(actions["bluetooth_adapter_state"]).isEqualTo(CapabilityAction.OPEN_SETTINGS)
        assertThat(actions["sms_metadata"]).isEqualTo(CapabilityAction.NONE)
        assertThat(actions["health_connect_on_device_steps"]).isEqualTo(CapabilityAction.NONE)
        val exact = capability(
            "exact_alarm_jitai_scheduling",
            "Exact",
            dev.agentle.core.model.CapabilityCategory.NOTIFICATIONS,
            PermissionState.REQUIRES_SETTINGS,
            specialAccess = "exact_alarm",
        )
        assertThat(primaryAction(exact)).isEqualTo(CapabilityAction.NONE)
        val health = capability(
            "health_connect_records",
            "HC",
            dev.agentle.core.model.CapabilityCategory.HEALTH,
            PermissionState.DENIED,
            listOf("android.permission.health.READ_STEPS"),
            specialAccess = "health_connect",
        )
        assertThat(primaryAction(health)).isEqualTo(CapabilityAction.OPEN_SETTINGS)
    }

    @Test
    fun `timeline groups by Berlin day, labels gaps and captures the upper bound once`() = runTest(dispatcher) {
        val events = listOf(
            event("e1", "2026-10-25T21:30:00Z"), // 25 Oct 22:30 in Berlin (CET)
            event("e2", "2026-10-25T00:30:00Z"), // 25 Oct 02:30 CEST, the 25-hour day
            event("e3", "2026-10-24T21:59:00Z"), // 24 Oct 23:59 CEST
            event("e4", "2026-10-21T08:00:00Z"), // 21 Oct: 22 and 23 Oct are empty
        )
        val rows = flowOf(androidx.paging.PagingData.from(events)).let { flow ->
            kotlinx.coroutines.flow.flow { flow.collect { emit(it.toRows(BERLIN)) } }
        }.asSnapshot()
        assertThat(rows.map { it.describe() }).containsExactly(
            "day 2026-10-25",
            "e1",
            "e2",
            "day 2026-10-24",
            "e3",
            "day 2026-10-21 gap 2026-10-22..2026-10-23",
            "e4",
        ).inOrder()

        val port = FakeTimelinePort(events)
        val vm = TimelineViewModel(AppRoute.Timeline(eventType = "SCREEN_ON"), port, clockPort())
        vm.rows.asSnapshot()
        vm.setSource("android.screen")
        vm.rows.asSnapshot()
        assertThat(port.requests.map { it.first }).containsExactly(
            TimelineFilter(eventType = EventType.SCREEN_ON),
            TimelineFilter(source = "android.screen", eventType = EventType.SCREEN_ON),
        ).inOrder()
        assertThat(port.requests.map { it.second }.distinct()).containsExactly(NOW)
        vm.clearFilters()
        assertThat(vm.filter.value).isEqualTo(TimelineFilter())
        assertThat(vm.today).isEqualTo(LocalDate(2026, 10, 2))
    }

    private fun TimelineRow.describe(): String = when (this) {
        is TimelineRow.DayHeader -> "day $date" + (gapAfter?.let { " gap ${it.start}..${it.endInclusive}" } ?: "")
        is TimelineRow.Event -> event.id.value
    }

    private fun event(id: String, at: String) = PersonalEvent(
        id = EventId(id),
        type = EventType.SCREEN_ON,
        source = DataSourceId("android.screen"),
        startTime = Instant.parse(at),
        zoneId = "Europe/Berlin",
        payload = NoPayload,
        dedupKey = id,
        metadata = EventMetadata(ingestedAt = Instant.parse(at)),
    )
}
