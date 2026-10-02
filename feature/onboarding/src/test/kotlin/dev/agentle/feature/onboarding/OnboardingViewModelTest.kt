package dev.agentle.feature.onboarding

import android.Manifest
import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.core.model.PermissionState
import dev.agentle.core.ui.permission.PermissionDialogResult
import dev.agentle.feature.onboarding.port.OnboardingProgress
import dev.agentle.feature.onboarding.port.OnboardingSource
import dev.agentle.feature.onboarding.port.OnboardingStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(port: FakeOnboardingPort, sdk: Int = 37): OnboardingViewModel {
        val vm = OnboardingViewModel(port, sdk)
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        backgroundScope.launch(dispatcher) { vm.effect.collect { collected += it } }
        advanceUntilIdle()
        return vm
    }

    /** Effects of the most recently created view model. */
    private val collected = mutableListOf<OnboardingEffect>()

    @Suppress("UnusedReceiverParameter")
    private fun TestScope.effectsOf(vm: OnboardingViewModel): List<OnboardingEffect> =
        collected.also { check(vm.state.value.loading.not()) }

    private fun denied(permission: String, rationale: Boolean) =
        PermissionDialogResult(granted = mapOf(permission to false), showRationale = mapOf(permission to rationale))

    @Test
    fun `starts on the welcome page and walks forward and back`() = runTest(dispatcher) {
        val vm = viewModel(FakeOnboardingPort())
        assertThat(vm.state.value.loading).isFalse()
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.WELCOME)
        vm.next()
        advanceUntilIdle()
        vm.next()
        advanceUntilIdle()
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.SOURCES)
        vm.back()
        advanceUntilIdle()
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.PROMISES)
    }

    @Test
    fun `primers depend on the API level`() = runTest(dispatcher) {
        assertThat(viewModel(FakeOnboardingPort(), 37).state.value.primers.map { it.primer })
            .containsExactly(Primer.NOTIFICATIONS, Primer.ACTIVITY).inOrder()
        assertThat(viewModel(FakeOnboardingPort(), 29).state.value.primers.map { it.primer }).containsExactly(Primer.ACTIVITY)
        val old = viewModel(FakeOnboardingPort(OnboardingProgress(step = OnboardingStep.SOURCES)), 28)
        old.next()
        advanceUntilIdle()
        assertThat(old.state.value.step).isEqualTo(OnboardingStep.SUMMARY)
    }

    @Test
    fun `sources toggle through the port`() = runTest(dispatcher) {
        val vm = viewModel(FakeOnboardingPort())
        vm.toggleSource(OnboardingSource.WEARABLE)
        advanceUntilIdle()
        vm.toggleSource(OnboardingSource.PHONE)
        advanceUntilIdle()
        assertThat(vm.state.value.sources).containsExactly(OnboardingSource.WEARABLE)
    }

    @Test
    fun `continue on a primer requests its permission`() = runTest(dispatcher) {
        val vm = viewModel(FakeOnboardingPort(OnboardingProgress(step = OnboardingStep.PERMISSIONS)))
        val effects = effectsOf(vm)
        vm.requestCurrent()
        advanceUntilIdle()
        assertThat(effects).containsExactly(OnboardingEffect.RequestPermission(Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test
    fun `a grant is reported and moves on, the last one to the summary`() = runTest(dispatcher) {
        val port = FakeOnboardingPort(OnboardingProgress(step = OnboardingStep.PERMISSIONS))
        val vm = viewModel(port)
        vm.onPermissionResult(PermissionDialogResult(mapOf(Manifest.permission.POST_NOTIFICATIONS to true), emptyMap()))
        advanceUntilIdle()
        assertThat(port.reports).containsExactly(Triple("post_notifications_jitai", true, false))
        assertThat(vm.state.value.currentPrimer?.primer).isEqualTo(Primer.ACTIVITY)
        vm.onPermissionResult(PermissionDialogResult(mapOf(Manifest.permission.ACTIVITY_RECOGNITION to true), emptyMap()))
        advanceUntilIdle()
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.SUMMARY)
        assertThat(vm.state.value.primers.map { it.state }).containsExactly(PermissionState.ALLOWED, PermissionState.ALLOWED)
    }

    @Test
    fun `a first denial is denied, a second is denied permanently and offers Settings`() = runTest(dispatcher) {
        val vm = viewModel(FakeOnboardingPort(OnboardingProgress(step = OnboardingStep.PERMISSIONS)))
        vm.onPermissionResult(denied(Manifest.permission.POST_NOTIFICATIONS, rationale = true))
        advanceUntilIdle()
        assertThat(vm.state.value.currentPrimer?.state).isEqualTo(PermissionState.DENIED)
        vm.onPermissionResult(denied(Manifest.permission.POST_NOTIFICATIONS, rationale = false))
        advanceUntilIdle()
        assertThat(vm.state.value.currentPrimer?.state).isEqualTo(PermissionState.DENIED_PERMANENTLY)
        val effects = effectsOf(vm)
        vm.openSettings()
        advanceUntilIdle()
        assertThat(effects).containsExactly(OnboardingEffect.OpenAppSettings)
    }

    @Test
    fun `a cancelled dialog reports nothing`() = runTest(dispatcher) {
        val port = FakeOnboardingPort(OnboardingProgress(step = OnboardingStep.PERMISSIONS))
        val vm = viewModel(port)
        vm.onPermissionResult(PermissionDialogResult(emptyMap(), emptyMap()))
        advanceUntilIdle()
        assertThat(port.reports).isEmpty()
    }

    @Test
    fun `skipping every primer reaches the summary with nothing asked`() = runTest(dispatcher) {
        val vm = viewModel(FakeOnboardingPort(OnboardingProgress(step = OnboardingStep.PERMISSIONS)))
        vm.skipCurrent()
        vm.skipCurrent()
        advanceUntilIdle()
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.SUMMARY)
        assertThat(vm.state.value.primers.map { it.state }).containsExactly(null, null)
    }

    @Test
    fun `a permission granted in Settings is picked up on resume`() = runTest(dispatcher) {
        val port = FakeOnboardingPort(
            OnboardingProgress(
                step = OnboardingStep.PERMISSIONS,
                permissionStates = mapOf("post_notifications_jitai" to PermissionState.DENIED_PERMANENTLY),
            ),
        )
        val vm = viewModel(port)
        vm.onResume(mapOf(Manifest.permission.POST_NOTIFICATIONS to true, Manifest.permission.ACTIVITY_RECOGNITION to false))
        advanceUntilIdle()
        assertThat(vm.state.value.primers.first().state).isEqualTo(PermissionState.ALLOWED)
        assertThat(vm.state.value.currentPrimer?.primer).isEqualTo(Primer.ACTIVITY)
    }

    @Test
    fun `finish completes onboarding and emits Finished`() = runTest(dispatcher) {
        val port = FakeOnboardingPort(OnboardingProgress(step = OnboardingStep.SUMMARY))
        val vm = viewModel(port)
        val effects = effectsOf(vm)
        vm.finish()
        advanceUntilIdle()
        assertThat(port.completed).isTrue()
        assertThat(effects).containsExactly(OnboardingEffect.Finished)
    }

    @Test
    fun `a port failure shows its error and can be dismissed`() = runTest(dispatcher) {
        val port = FakeOnboardingPort()
        val vm = viewModel(port)
        port.failWith = AppError.DatabaseError()
        vm.next()
        advanceUntilIdle()
        assertThat(vm.state.value.error).isEqualTo(AppError.DatabaseError())
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.WELCOME)
        vm.dismissError()
        advanceUntilIdle()
        assertThat(vm.state.value.error).isNull()
    }
}
