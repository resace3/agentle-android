package dev.agentle.feature.hub

import android.app.Application
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.agentle.core.model.CapabilityCategory
import dev.agentle.core.model.PermissionState
import dev.agentle.core.ui.permission.LocalPermissionRationale
import dev.agentle.core.ui.permission.PermissionRationale
import dev.agentle.core.ui.preview.AgentleTestFrame
import dev.agentle.feature.hub.permissions.PermissionCenterRoute
import dev.agentle.feature.hub.permissions.PermissionCenterViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Journey 2: request, deny twice, Settings, and the state flipping when the user comes back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "en-rUS")
class PermissionCenterJourneyTest {
    @get:Rule
    val compose = createComposeRule()

    private class TestLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private class DenyingRegistry : ActivityResultRegistry() {
        var launches = 0

        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches++
            @Suppress("UNCHECKED_CAST")
            dispatchResult(requestCode, (input as Array<String>).associateWith { false })
        }
    }

    @Test
    fun `a second denial leads to Settings and the grant shows after resume`() {
        val port = FakePermissionCenterPort(
            listOf(
                capability(
                    "activity_recognition_transitions",
                    "Physical activity",
                    CapabilityCategory.ACTIVITY,
                    PermissionState.DENIED,
                    listOf("android.permission.ACTIVITY_RECOGNITION"),
                ),
            ),
        )
        // RequestMultiplePermissions answers held permissions without a dialog: make sure this one is not held.
        shadowOf(app).denyPermissions("android.permission.ACTIVITY_RECOGNITION")
        val registry = DenyingRegistry()
        var permanently = false
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val viewModel = PermissionCenterViewModel(null, port, clockPort())
        val lifecycle = TestLifecycle()
        compose.setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides lifecycle,
                LocalActivityResultRegistryOwner provides owner,
                // First denial: Android would ask again; after the second it would not.
                LocalPermissionRationale provides PermissionRationale { !permanently },
            ) {
                AgentleTestFrame { PermissionCenterRoute(viewModel, onBack = {}) }
            }
        }
        compose.onNodeWithText("Denied").assertExists()
        assertThat(app.checkSelfPermission("android.permission.ACTIVITY_RECOGNITION"))
            .isEqualTo(android.content.pm.PackageManager.PERMISSION_DENIED)
        compose.onNodeWithText("Allow").performClick()
        // The launch runs from a ViewModel effect, so wait for it (and for the reported result) before asserting.
        compose.waitUntil(timeoutMillis = 5_000) { registry.launches == 1 }
        compose.waitUntil(timeoutMillis = 5_000) { port.calls.isNotEmpty() }
        assertThat(port.calls).hasSize(1)
        compose.onNodeWithText("Denied").assertExists()
        permanently = true
        compose.onNodeWithText("Allow").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { registry.launches == 2 }
        compose.onNodeWithText("Denied permanently").assertExists()
        assertThat(registry.launches).isEqualTo(2)

        compose.onNodeWithText("Open Android Settings").performClick()
        assertThat(shadowOf(app).nextStartedActivity.action).isEqualTo("test.settings.activity_recognition_transitions")

        // The user allows it in Settings and comes back: the screen re-evaluates on resume.
        port.setState("activity_recognition_transitions", PermissionState.ALLOWED)
        compose.runOnIdle {
            lifecycle.registry.currentState = Lifecycle.State.CREATED
            lifecycle.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        compose.onNodeWithText("Allowed").assertExists()
        compose.onAllNodesWithText("Open Android Settings").assertCountEquals(0)
        assertThat(port.refreshes.size).isAtLeast(2)
    }

    @Test
    fun `all ten states render with distinct labels and a capability link scrolls to it`() {
        val port = FakePermissionCenterPort(mixedCapabilities())
        val viewModel = PermissionCenterViewModel("health_connect_on_device_steps", port, clockPort())
        compose.setContent {
            AgentleTestFrame {
                PermissionCenterRoute(viewModel, onBack = {})
            }
        }
        compose.onNodeWithTag("capability-health_connect_on_device_steps").assertExists()
        listOf(
            "Allowed", "Denied", "Denied permanently", "Requires Settings screen", "Restricted by Android", "Unavailable right now",
            "Partially allowed", "Foreground only", "Background allowed", "Unsupported on this device",
        ).forEach { label ->
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
            compose.onNodeWithText(label).assertExists()
        }
    }
}
