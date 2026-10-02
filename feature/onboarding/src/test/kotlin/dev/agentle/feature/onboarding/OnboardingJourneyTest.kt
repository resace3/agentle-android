package dev.agentle.feature.onboarding

import android.app.Application
import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.agentle.core.ui.navigation.AppNavigator
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.core.ui.permission.LocalPermissionRationale
import dev.agentle.core.ui.permission.PermissionRationale
import dev.agentle.core.ui.preview.AgentleTestFrame
import dev.agentle.feature.onboarding.port.OnboardingProgress
import dev.agentle.feature.onboarding.port.OnboardingStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Journey 1 end to end on the screen: primers, deny twice, Settings, finish to the Dashboard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "en-rUS")
class OnboardingJourneyTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** Answers every permission dialog with [answer] and counts launches. */
    private class ScriptedRegistry(var answer: Boolean) : ActivityResultRegistry() {
        var launches = 0

        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches++
            @Suppress("UNCHECKED_CAST")
            val permissions = (input as Array<String>)
            dispatchResult(requestCode, permissions.associateWith { answer })
        }
    }

    private class RecordingNavigator : AppNavigator {
        val calls = mutableListOf<String>()

        override fun navigate(route: AppRoute) {
            calls += "navigate $route"
        }

        override fun back() {
            calls += "back"
        }

        override fun resetTo(route: AppRoute) {
            calls += "resetTo $route"
        }
    }

    private fun setContent(port: FakeOnboardingPort, registry: ScriptedRegistry, rationale: () -> Boolean, navigator: AppNavigator) {
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val viewModel = OnboardingViewModel(port, 37)
        compose.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalPermissionRationale provides PermissionRationale { rationale() },
            ) {
                AgentleTestFrame {
                    OnboardingRoute(viewModel, onFinished = { navigator.resetTo(AppRoute.Dashboard) })
                }
            }
        }
    }

    @Test
    fun `denying everything twice leads to Settings and onboarding still finishes on the dashboard`() {
        val port = FakeOnboardingPort()
        val registry = ScriptedRegistry(answer = false)
        var deniedBefore = false
        val navigator = RecordingNavigator()
        setContent(port, registry, rationale = { !deniedBefore.also { deniedBefore = true } }, navigator = navigator)

        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Your data stays on this phone").assertExists()
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        compose.onNodeWithText("Wearable (Google Health)").performScrollTo().performClick()
        compose.onNodeWithText("Continue").performScrollTo().performClick()

        compose.onNodeWithText("Notifications").assertExists()
        compose.onNodeWithText("Continue to Android's question").performScrollTo().performClick()
        compose.onNodeWithText("Denied").assertExists()
        compose.onNodeWithText("Ask again").performScrollTo().performClick()
        compose.onNodeWithText("Denied permanently").assertExists()
        compose.onNodeWithText("Open Android Settings").performScrollTo().performClick()
        val started = shadowOf(app).nextStartedActivity
        assertThat(started.action).isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertThat(registry.launches).isEqualTo(2)
        assertThat(port.reports.map { it.second }).containsExactly(false, false)

        compose.onNodeWithText("Not now").performScrollTo().performClick()
        compose.onNodeWithText("Physical activity").assertExists()
        compose.onNodeWithText("Not now").performScrollTo().performClick()

        compose.onNodeWithText("You are set").assertExists()
        compose.onNodeWithText("This phone, Wearable (Google Health)").assertExists()
        compose.onNodeWithText("Go to the dashboard").performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(navigator.calls).containsExactly("resetTo ${AppRoute.Dashboard}")
    }

    @Test
    fun `granting reports the result and moves to the next permission`() {
        val port = FakeOnboardingPort(OnboardingProgress(step = OnboardingStep.PERMISSIONS))
        val registry = ScriptedRegistry(answer = true)
        setContent(port, registry, rationale = { false }, navigator = RecordingNavigator())
        compose.onNodeWithText("Continue to Android's question").performScrollTo().performClick()
        compose.onNodeWithText("Physical activity").assertExists()
        assertThat(port.reports).containsExactly(Triple("post_notifications_jitai", true, false))
    }
}
