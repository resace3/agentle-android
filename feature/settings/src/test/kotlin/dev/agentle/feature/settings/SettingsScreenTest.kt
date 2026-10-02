package dev.agentle.feature.settings

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import dev.agentle.feature.settings.debug.DebugPanelScreen
import dev.agentle.feature.settings.debug.DebugPanelUiState
import dev.agentle.feature.settings.deletion.DeleteDataScreen
import dev.agentle.feature.settings.deletion.DeleteDataUiState
import dev.agentle.feature.settings.deletion.DeleteDataViewModel
import dev.agentle.feature.settings.hub.SettingsHubScreen
import dev.agentle.feature.settings.hub.SettingsHubUiState
import dev.agentle.feature.settings.notifications.NotificationSettingsScreen
import dev.agentle.feature.settings.notifications.NotificationSettingsUiState
import dev.agentle.feature.settings.port.DeleteAllState
import dev.agentle.feature.settings.testing.FakeDeletionPort
import dev.agentle.feature.settings.testing.Fixtures
import dev.agentle.feature.settings.testing.TestZonePort
import dev.agentle.feature.settings.ui.Loadable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SettingsScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun str(@StringRes id: Int, vararg args: Any): String = rule.activity.getString(id, *args)

    @Test
    fun `delete everything needs the typed word, then shows progress, counts and the remote warning`() {
        val port = FakeDeletionPort()
        val vm = DeleteDataViewModel(port, TestZonePort())
        rule.setContent {
            val state by vm.state.collectAsState()
            MaterialTheme { DeleteDataScreen(state, vm::onAction, {}, SnackbarHostState()) }
        }
        rule.onNodeWithText(str(R.string.settings_delete_all_action)).performScrollTo().performClick()
        val confirm = rule.onNode(
            hasText(str(R.string.settings_delete_all_confirm_action)) and hasClickAction() and hasAnyAncestor(isDialog()),
        )
        confirm.assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput("delet")
        confirm.assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput("e")
        confirm.assertIsEnabled().performClick()
        rule.waitForIdle()
        assertThat(port.deleteEverythingCalls).isEqualTo(1)

        port.stateFlow.value = Fixtures.running
        rule.onNodeWithText(str(R.string.settings_delete_all_step_of, 6, 10)).assertExists()

        port.stateFlow.value = Fixtures.verifiedWithRemoteWarning
        rule.onNodeWithText(str(R.string.settings_delete_all_remote_warning)).assertExists()
        rule.onNodeWithText(str(R.string.settings_delete_all_resuming)).assertDoesNotExist()
    }

    @Test
    fun `a deletion resumed after a restart says so`() {
        val port = FakeDeletionPort().apply { stateFlow.value = Fixtures.running.copy(resumed = true) }
        val vm = DeleteDataViewModel(port, TestZonePort())
        rule.setContent {
            val state by vm.state.collectAsState()
            MaterialTheme { DeleteDataScreen(state, vm::onAction, {}, SnackbarHostState()) }
        }
        rule.onNodeWithText(str(R.string.settings_delete_all_resuming)).assertIsDisplayed()
    }

    @Test
    fun `per-category delete offers also stop collecting`() {
        val port = FakeDeletionPort()
        val vm = DeleteDataViewModel(port, TestZonePort())
        rule.setContent {
            val state by vm.state.collectAsState()
            MaterialTheme { DeleteDataScreen(state, vm::onAction, {}, SnackbarHostState()) }
        }
        rule.onNodeWithText(str(R.string.settings_category_sleep), substring = true).performScrollTo().performClick()
        rule.onNodeWithText(str(R.string.settings_delete_stop_collecting)).performClick()
        rule.onNodeWithText(str(R.string.settings_delete_action)).performClick()
        rule.waitForIdle()
        assertThat(port.deletes.single().second).isTrue()
    }

    @Test
    fun `daily cap at the ceiling cannot be increased`() {
        val state = NotificationSettingsUiState(content = Loadable.Ready(Fixtures.atCeilings))
        rule.setContent { MaterialTheme { NotificationSettingsScreen(state, {}, {}, SnackbarHostState()) } }
        rule.onNodeWithContentDescription(str(R.string.settings_increase, str(R.string.settings_notifications_daily_cap)))
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun `hub hides the debug link and the panel shows not available without controls`() {
        rule.setContent { MaterialTheme { SettingsHubScreen(SettingsHubUiState(debugAvailable = false), {}, {}) } }
        rule.onNodeWithText(str(R.string.settings_debug_title)).assertDoesNotExist()
    }

    @Test
    fun `debug panel not available shows no controls`() {
        rule.setContent { MaterialTheme { DebugPanelScreen(DebugPanelUiState.NotAvailable, {}, {}, SnackbarHostState()) } }
        rule.onNodeWithText(str(R.string.settings_debug_not_available)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.settings_delete_all_action)).assertDoesNotExist()
    }

    @Test
    fun `idle delete state is the default`() {
        assertThat(DeleteDataUiState().deleteAll).isEqualTo(DeleteAllState.Idle)
    }
}
