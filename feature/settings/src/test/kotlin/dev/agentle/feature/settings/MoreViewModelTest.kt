package dev.agentle.feature.settings

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.AiProviderState
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.feature.settings.background.BackgroundAction
import dev.agentle.feature.settings.background.BackgroundBehaviorViewModel
import dev.agentle.feature.settings.diagnostics.DiagnosticsAction
import dev.agentle.feature.settings.diagnostics.DiagnosticsViewModel
import dev.agentle.feature.settings.hub.SettingsHubViewModel
import dev.agentle.feature.settings.port.AppBuildInfo
import dev.agentle.feature.settings.port.BackgroundBehaviorPort
import dev.agentle.feature.settings.port.BackgroundBehaviorState
import dev.agentle.feature.settings.port.BatteryOptimization
import dev.agentle.feature.settings.port.CollectionProfile
import dev.agentle.feature.settings.port.DiagnosticReport
import dev.agentle.feature.settings.port.DiagnosticsPort
import dev.agentle.feature.settings.port.DiagnosticsSnapshot
import dev.agentle.feature.settings.port.PrivacyPort
import dev.agentle.feature.settings.port.PrivacyState
import dev.agentle.feature.settings.port.RetentionPeriod
import dev.agentle.feature.settings.port.RetentionSettings
import dev.agentle.feature.settings.port.SystemSettingsTarget
import dev.agentle.feature.settings.privacy.PrivacyAction
import dev.agentle.feature.settings.privacy.PrivacyViewModel
import dev.agentle.feature.settings.testing.FakeDebugToolsPort
import dev.agentle.feature.settings.testing.FakeNotificationSettingsPort
import dev.agentle.feature.settings.testing.FakeRetentionPort
import dev.agentle.feature.settings.testing.FakeSystemSettingsPort
import dev.agentle.feature.settings.testing.TestZonePort
import dev.agentle.feature.settings.ui.Loadable
import dev.agentle.feature.settings.ui.SettingsEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

private val background = BackgroundBehaviorState(
    profile = CollectionProfile.BALANCED,
    effectiveProfile = CollectionProfile.BALANCED,
    batterySaverOn = false,
    batterySaverAdaptationActive = false,
    batteryOptimization = BatteryOptimization.OPTIMIZED,
    backgroundRestricted = false,
    standbyBucket = null,
    sources = emptyList(),
)

private class FakeBackgroundPort : BackgroundBehaviorPort {
    val flow = MutableStateFlow<Outcome<BackgroundBehaviorState>>(Outcome.Success(background))
    var result: Outcome<Unit> = Outcome.Success(Unit)
    val profiles = mutableListOf<CollectionProfile>()
    override val state: Flow<Outcome<BackgroundBehaviorState>> = flow

    override suspend fun setProfile(profile: CollectionProfile): Outcome<Unit> {
        profiles += profile
        if (result is Outcome.Success) flow.value = Outcome.Success(background.copy(profile = profile, effectiveProfile = profile))
        return result
    }
}

private class FakePrivacyPort : PrivacyPort {
    val flow = MutableStateFlow<Outcome<PrivacyState>>(
        Outcome.Success(PrivacyState(true, null, false, false, protectAllScreens = false, recentsPreviewHidden = true)),
    )
    val writes = mutableListOf<Boolean>()
    override val state: Flow<Outcome<PrivacyState>> = flow

    override suspend fun setProtectAllScreens(enabled: Boolean): Outcome<Unit> {
        writes += enabled
        return Outcome.Failure(AppError.DatabaseError())
    }
}

private class FakeDiagnosticsPort : DiagnosticsPort {
    val flow = MutableStateFlow<Outcome<DiagnosticsSnapshot>>(Outcome.Failure(AppError.UnsupportedFeature("diagnostics")))
    var refreshes = 0
    var export: Outcome<DiagnosticReport> = Outcome.Success(DiagnosticReport(Uri.parse("content://test/report.json"), "application/json"))
    override val snapshot: Flow<Outcome<DiagnosticsSnapshot>> = flow

    override suspend fun refresh() {
        refreshes++
    }

    override suspend fun exportReport(): Outcome<DiagnosticReport> = export
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class MoreViewModelTest {
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
    fun `background profile change is stored and links open the app page and data sources`() = runTest {
        val port = FakeBackgroundPort()
        val system = FakeSystemSettingsPort()
        val vm = BackgroundBehaviorViewModel(port, system)
        collect(vm.state)
        val effects = effectsOf(vm.effects)
        vm.onAction(BackgroundAction.SelectProfile(CollectionProfile.HIGH))
        assertThat(port.profiles).containsExactly(CollectionProfile.HIGH)
        assertThat((vm.state.value.content as Loadable.Ready).value.profile).isEqualTo(CollectionProfile.HIGH)
        vm.onAction(BackgroundAction.OpenAppSettings)
        assertThat(system.requested).containsExactly(SystemSettingsTarget.AppDetails)
        vm.onAction(BackgroundAction.OpenDataSources)
        assertThat(effects.last()).isEqualTo(SettingsEffect.Navigate(AppRoute.DataSources))
    }

    @Test
    fun `background profile failure reports the error and a failed read can be retried`() = runTest {
        val port = FakeBackgroundPort().apply { result = Outcome.Failure(AppError.DatabaseError()) }
        port.flow.value = Outcome.Failure(AppError.DatabaseError())
        val vm = BackgroundBehaviorViewModel(port, FakeSystemSettingsPort())
        collect(vm.state)
        val effects = effectsOf(vm.effects)
        assertThat(vm.state.value.content).isInstanceOf(Loadable.Failed::class.java)
        port.flow.value = Outcome.Success(background)
        vm.onAction(BackgroundAction.Retry)
        assertThat(vm.state.value.content).isEqualTo(Loadable.Ready(background))
        vm.onAction(BackgroundAction.SelectProfile(CollectionProfile.LOW))
        assertThat(effects).contains(SettingsEffect.Message(R.string.settings_error_database))
    }

    @Test
    fun `privacy write failure is reported and links navigate`() = runTest {
        val port = FakePrivacyPort()
        val vm = PrivacyViewModel(port, TestZonePort())
        collect(vm.state)
        val effects = effectsOf(vm.effects)
        assertThat(vm.state.value.content).isInstanceOf(Loadable.Ready::class.java)
        vm.onAction(PrivacyAction.SetProtectAllScreens(true))
        assertThat(port.writes).containsExactly(true)
        vm.onAction(PrivacyAction.OpenAiDataSharing)
        vm.onAction(PrivacyAction.OpenNotificationSettings)
        assertThat(effects).containsAtLeast(
            SettingsEffect.Message(R.string.settings_error_database),
            SettingsEffect.Navigate(AppRoute.AiDataSharing),
            SettingsEffect.Navigate(AppRoute.NotificationSettings),
        ).inOrder()
    }

    @Test
    fun `diagnostics not available, refresh asks the port, export shares the report`() = runTest {
        val port = FakeDiagnosticsPort()
        val vm = DiagnosticsViewModel(port, TestZonePort())
        collect(vm.state)
        val effects = effectsOf(vm.effects)
        assertThat(vm.state.value.content).isEqualTo(Loadable.NotAvailable)
        vm.onAction(DiagnosticsAction.Refresh)
        assertThat(port.refreshes).isEqualTo(1)
        vm.onAction(DiagnosticsAction.Export)
        assertThat(effects.last()).isEqualTo(SettingsEffect.Share(Uri.parse("content://test/report.json"), "application/json"))
        port.export = Outcome.Failure(AppError.UnsupportedFeature("export"))
        vm.onAction(DiagnosticsAction.Export)
        assertThat(effects.last()).isEqualTo(SettingsEffect.Message(R.string.settings_error_not_available))
    }

    @Test
    fun `diagnostics shows a snapshot once the port emits one`() = runTest {
        val port = FakeDiagnosticsPort()
        val vm = DiagnosticsViewModel(port, TestZonePort())
        collect(vm.state)
        val snapshot = DiagnosticsSnapshot(
            AppBuildInfo("1.0", 1, "debug", "fake"), null, 37, emptyList(), emptyList(), null, null,
            AiProviderState.Disconnected, null, null, emptyList(),
        )
        port.flow.value = Outcome.Success(snapshot)
        vm.onAction(DiagnosticsAction.Retry)
        assertThat(vm.state.value.content).isEqualTo(Loadable.Ready(snapshot))
    }

    @Test
    fun `hub summarizes each port and shows the debug link only when debug tools exist`() = runTest {
        val retention = FakeRetentionPort(Outcome.Success(RetentionSettings(RetentionPeriod.DAYS_90, null)))
        val notifications = FakeNotificationSettingsPort()
        val vm = SettingsHubViewModel(retention, FakeBackgroundPort(), notifications, FakeDebugToolsPort(available = false), TestZonePort())
        collect(vm.state)
        with(vm.state.value) {
            assertThat(this.retention).isEqualTo(RetentionPeriod.DAYS_90)
            assertThat(profile).isEqualTo(CollectionProfile.BALANCED)
            assertThat(dailyCap).isEqualTo(6)
            assertThat(debugAvailable).isFalse()
        }
        val debugVm =
            SettingsHubViewModel(retention, FakeBackgroundPort(), notifications, FakeDebugToolsPort(available = true), TestZonePort())
        assertThat(debugVm.state.value.debugAvailable).isTrue()
    }

    @Test
    fun `hub keeps its links when a port fails`() = runTest {
        val retention = FakeRetentionPort(Outcome.Failure(AppError.DatabaseError()))
        val vm = SettingsHubViewModel(
            retention,
            FakeBackgroundPort(),
            FakeNotificationSettingsPort(),
            FakeDebugToolsPort(available = false),
            TestZonePort(),
        )
        collect(vm.state)
        assertThat(vm.state.value.retention).isNull()
        assertThat(vm.state.value.profile).isEqualTo(CollectionProfile.BALANCED)
    }
}
