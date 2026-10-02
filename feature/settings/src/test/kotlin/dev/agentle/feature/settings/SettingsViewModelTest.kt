package dev.agentle.feature.settings

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.feature.settings.debug.DebugAction
import dev.agentle.feature.settings.debug.DebugPanelUiState
import dev.agentle.feature.settings.debug.DebugPanelViewModel
import dev.agentle.feature.settings.deletion.DeleteDataAction
import dev.agentle.feature.settings.deletion.DeleteDataViewModel
import dev.agentle.feature.settings.notifications.NotificationAction
import dev.agentle.feature.settings.notifications.NotificationSettingsViewModel
import dev.agentle.feature.settings.port.DeleteAllState
import dev.agentle.feature.settings.port.DeletionTarget
import dev.agentle.feature.settings.port.DeliveryLimitBounds
import dev.agentle.feature.settings.port.RetentionPeriod
import dev.agentle.feature.settings.port.RetentionSettings
import dev.agentle.feature.settings.retention.RetentionAction
import dev.agentle.feature.settings.retention.RetentionViewModel
import dev.agentle.feature.settings.testing.FakeDebugToolsPort
import dev.agentle.feature.settings.testing.FakeDeletionPort
import dev.agentle.feature.settings.testing.FakeNotificationSettingsPort
import dev.agentle.feature.settings.testing.FakeRetentionPort
import dev.agentle.feature.settings.testing.FakeSystemSettingsPort
import dev.agentle.feature.settings.testing.Fixtures
import dev.agentle.feature.settings.testing.TestZonePort
import dev.agentle.feature.settings.ui.Loadable
import dev.agentle.feature.settings.ui.SettingsEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SettingsViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.collect(flow: Flow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    private fun TestScope.effectsOf(flow: Flow<SettingsEffect>): List<SettingsEffect> {
        val list = mutableListOf<SettingsEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { list += it } }
        return list
    }

    @Test
    fun `retention shows the stored period and a failed read as an error to retry`() = runTest {
        val port = FakeRetentionPort(Outcome.Failure(AppError.DatabaseError()))
        val vm = RetentionViewModel(port, TestZonePort())
        collect(vm.state)
        assertThat(vm.state.value.settings).isInstanceOf(Loadable.Failed::class.java)
        port.settingsFlow.value = Outcome.Success(RetentionSettings(RetentionPeriod.ONE_YEAR, null))
        vm.onAction(RetentionAction.Retry)
        assertThat(vm.state.value.settings).isEqualTo(Loadable.Ready(RetentionSettings(RetentionPeriod.ONE_YEAR, null)))
    }

    @Test
    fun `a shorter retention period that deletes data asks for confirmation first`() = runTest {
        val port = FakeRetentionPort().apply { impactRecords = 120 }
        val vm = RetentionViewModel(port, TestZonePort())
        collect(vm.state)
        vm.onAction(RetentionAction.Select(RetentionPeriod.DAYS_30))
        assertThat(port.saved).isEmpty()
        assertThat(vm.state.value.confirmation?.recordsToDelete).isEqualTo(120)
        vm.onAction(RetentionAction.ConfirmChange)
        assertThat(port.saved).containsExactly(RetentionPeriod.DAYS_30)
    }

    @Test
    fun `per-category delete passes also stop collecting and shows the verified report`() = runTest {
        val port = FakeDeletionPort()
        val vm = DeleteDataViewModel(port, TestZonePort())
        collect(vm.state)
        val sleep = Fixtures.overview.items[2].target
        vm.onAction(DeleteDataAction.RequestDelete(sleep))
        vm.onAction(DeleteDataAction.SetStopCollecting(true))
        vm.onAction(DeleteDataAction.ConfirmDelete)
        assertThat(port.deletes).containsExactly(sleep to true)
        assertThat(vm.state.value.report?.complete).isTrue()
    }

    @Test
    fun `stop collecting is never sent for targets nothing collects`() = runTest {
        val port = FakeDeletionPort()
        val vm = DeleteDataViewModel(port, TestZonePort())
        collect(vm.state)
        vm.onAction(DeleteDataAction.RequestDelete(DeletionTarget.Insights))
        vm.onAction(DeleteDataAction.SetStopCollecting(true))
        vm.onAction(DeleteDataAction.ConfirmDelete)
        assertThat(port.deletes).containsExactly(DeletionTarget.Insights to false)
    }

    @Test
    fun `delete everything starts, follows the port state and restarts at onboarding when verified`() = runTest {
        val port = FakeDeletionPort()
        val vm = DeleteDataViewModel(port, TestZonePort())
        collect(vm.state)
        val effects = effectsOf(vm.effects)
        vm.onAction(DeleteDataAction.RequestDeleteEverything)
        vm.onAction(DeleteDataAction.ConfirmDeleteEverything)
        assertThat(port.deleteEverythingCalls).isEqualTo(1)
        assertThat(vm.state.value.deleteAll).isInstanceOf(DeleteAllState.Running::class.java)
        vm.onAction(DeleteDataAction.FinishDeleteEverything)
        assertThat(port.finishCalls).isEqualTo(0)
        port.stateFlow.value = Fixtures.verifiedWithRemoteWarning
        vm.onAction(DeleteDataAction.FinishDeleteEverything)
        assertThat(port.finishCalls).isEqualTo(1)
        assertThat(effects).contains(SettingsEffect.ResetTo(AppRoute.Onboarding))
    }

    @Test
    fun `a resumed deletion found at start shows without any user action`() = runTest {
        val port = FakeDeletionPort()
        port.stateFlow.value = Fixtures.running.copy(resumed = true)
        val vm = DeleteDataViewModel(port, TestZonePort())
        collect(vm.state)
        assertThat((vm.state.value.deleteAll as DeleteAllState.Running).resumed).isTrue()
        assertThat(vm.state.value.busy).isTrue()
    }

    @Test
    fun `caps never go above the ceilings`() = runTest {
        val port = FakeNotificationSettingsPort(Fixtures.atCeilings)
        val vm = NotificationSettingsViewModel(port, FakeSystemSettingsPort(), TestZonePort())
        collect(vm.state)
        vm.onAction(NotificationAction.StepDailyCap(up = true))
        vm.onAction(NotificationAction.StepWeeklyCap(up = true))
        vm.onAction(NotificationAction.StepMinGap(up = false))
        port.limitWrites.forEach { assertThat(DeliveryLimitBounds.contains(it)).isTrue() }
        val limits = (vm.state.value.content as Loadable.Ready).value.limits
        assertThat(limits.dailyCap).isAtMost(DeliveryLimitBounds.DAILY_CAP_MAX)
        assertThat(limits.weeklyCap).isAtMost(DeliveryLimitBounds.WEEKLY_CAP_MAX)
        assertThat(limits.minGapMinutes).isAtLeast(DeliveryLimitBounds.MIN_GAP_MINUTES_MIN)
    }

    @Test
    fun `lowering the daily cap is stored and the denied permission opens the permission center`() = runTest {
        val port = FakeNotificationSettingsPort()
        val vm = NotificationSettingsViewModel(port, FakeSystemSettingsPort(), TestZonePort())
        collect(vm.state)
        val effects = effectsOf(vm.effects)
        vm.onAction(NotificationAction.StepDailyCap(up = false))
        assertThat(port.limitWrites.last().dailyCap).isEqualTo(5)
        vm.onAction(NotificationAction.FixPermission)
        assertThat(effects).contains(SettingsEffect.Navigate(AppRoute.PermissionCenter("post_notifications_jitai")))
    }

    @Test
    fun `debug panel is not available and calls nothing when the port says so`() = runTest {
        val port = FakeDebugToolsPort(available = false)
        val vm = DebugPanelViewModel(port, TestZonePort())
        collect(vm.state)
        vm.onAction(DebugAction.ConfirmClearDatabase)
        vm.onAction(DebugAction.GenerateDataset)
        assertThat(vm.state.value).isEqualTo(DebugPanelUiState.NotAvailable)
        assertThat(port.calls).isEqualTo(0)
    }

    @Test
    fun `debug panel runs a tool when available`() = runTest {
        val port = FakeDebugToolsPort(available = true)
        val vm = DebugPanelViewModel(port, TestZonePort())
        collect(vm.state)
        vm.onAction(DebugAction.GenerateDataset)
        assertThat(port.calls).isEqualTo(1)
    }

    @Test
    fun `confirming delete everything twice back to back starts it once`() = runTest {
        val port = FakeDeletionPort().apply { holdDeleteEverything = true }
        val vm = DeleteDataViewModel(port, TestZonePort())
        collect(vm.state)
        vm.onAction(DeleteDataAction.ConfirmDeleteEverything)
        vm.onAction(DeleteDataAction.ConfirmDeleteEverything)
        assertThat(port.deleteEverythingCalls).isEqualTo(1)
    }

    @Test
    fun `a per-target delete is refused while delete everything is starting`() = runTest {
        val port = FakeDeletionPort().apply { holdDeleteEverything = true }
        val vm = DeleteDataViewModel(port, TestZonePort())
        collect(vm.state)
        vm.onAction(DeleteDataAction.ConfirmDeleteEverything)
        vm.onAction(DeleteDataAction.RequestDelete(DeletionTarget.Insights))
        assertThat(vm.state.value.request).isNull()
        vm.onAction(DeleteDataAction.ConfirmDelete)
        assertThat(port.deletes).isEmpty()
    }
}
