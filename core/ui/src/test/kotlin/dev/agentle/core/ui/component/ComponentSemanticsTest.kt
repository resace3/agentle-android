package dev.agentle.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.core.model.PermissionState
import dev.agentle.core.ui.icon.AgentleIcons
import dev.agentle.core.ui.preview.AgentleTestFrame
import dev.agentle.core.ui.status.StatusBadge
import dev.agentle.core.ui.status.StatusChip
import dev.agentle.core.ui.status.toStatusSpec
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What accessibility services and tests see: text, headings, roles, click actions, states and touch target sizes. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class ComponentSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a status chip is read as its label and never relies on the icon`() {
        compose.setContent {
            AgentleTestFrame {
                Column {
                    StatusChip(PermissionState.DENIED_PERMANENTLY.toStatusSpec())
                    StatusBadge(PermissionState.FOREGROUND_ONLY.toStatusSpec())
                }
            }
        }
        compose.onNodeWithText("Denied permanently").assertIsDisplayed()
        compose.onNodeWithText("Foreground only").assertIsDisplayed()
        compose.onNode(hasContentDescriptionAny()).assertDoesNotExist()
    }

    @Test
    fun `the scaffold title is a heading and the back button is labelled`() {
        var backClicks = 0
        compose.setContent {
            AgentleTestFrame {
                AgentleScaffold(title = "Permission Center", onBack = { backClicks++ }) { }
            }
        }
        compose.onNode(hasText("Permission Center") and isHeading()).assertIsDisplayed()
        compose.onNodeWithContentDescription("Navigate up")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertThat(backClicks).isEqualTo(1)
    }

    @Test
    fun `the scaffold has no back button on a root screen`() {
        compose.setContent { AgentleTestFrame { AgentleScaffold(title = "Dashboard") { } } }
        compose.onNodeWithContentDescription("Navigate up").assertDoesNotExist()
    }

    @Test
    fun `an error state shows the message of its code, the code and a retry`() {
        var retries = 0
        compose.setContent {
            AgentleTestFrame { ErrorState(error = AppError.NetworkUnavailable(detail = "dns failure"), onRetry = { retries++ }) }
        }
        compose.onNode(hasText("Something went wrong") and isHeading()).assertIsDisplayed()
        compose.onNodeWithText("No internet connection. Your data on this phone is not affected.").assertIsDisplayed()
        compose.onNodeWithText("Error code: network_unavailable").assertIsDisplayed()
        compose.onNodeWithText("dns failure", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Try again").assertHeightIsAtLeast(48.dp).performClick()
        assertThat(retries).isEqualTo(1)
    }

    @Test
    fun `an unknown error code shows the generic message and no retry when none is given`() {
        compose.setContent { AgentleTestFrame { ErrorState(errorCode = "added_later") } }
        compose.onNodeWithText("An unexpected error occurred. Please try again.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `an empty state explains why and offers the one action that fixes it`() {
        var opened = 0
        compose.setContent {
            AgentleTestFrame {
                EmptyState(
                    title = "No activity data yet",
                    message = "Activity recognition is off.",
                    action = StateAction("Open Permission Center") { opened++ },
                )
            }
        }
        compose.onNode(hasText("No activity data yet") and isHeading()).assertIsDisplayed()
        compose.onNodeWithText("Activity recognition is off.").assertIsDisplayed()
        compose.onNodeWithText("Open Permission Center").performClick()
        assertThat(opened).isEqualTo(1)
    }

    @Test
    fun `loading is announced`() {
        compose.setContent { AgentleTestFrame { LoadingState() } }
        compose.onNodeWithContentDescription("Loading").assertExists()
    }

    @Test
    fun `a settings row is one button at least 48 dp high`() {
        var clicks = 0
        compose.setContent {
            AgentleTestFrame {
                SettingsRow(title = "Data sources", subtitle = "3 connected", icon = AgentleIcons.tune, onClick = { clicks++ })
            }
        }
        compose.onNodeWithText("Data sources")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertThat(clicks).isEqualTo(1)
    }

    @Test
    fun `a switch row toggles from anywhere on the row`() {
        val changes = mutableListOf<Boolean>()
        compose.setContent {
            AgentleTestFrame { SettingsSwitchRow(title = "Collect steps", checked = false, onCheckedChange = { changes += it }) }
        }
        compose.onNodeWithText("Collect steps")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOff()
            .performClick()
        assertThat(changes).containsExactly(true)
    }

    @Test
    fun `a checked switch row reports on and a disabled row is not enabled`() {
        compose.setContent {
            AgentleTestFrame {
                Column {
                    SettingsSwitchRow(title = "Collect sleep", checked = true, onCheckedChange = {})
                    SettingsSwitchRow(title = "Collect location", checked = false, onCheckedChange = {}, enabled = false)
                }
            }
        }
        compose.onNodeWithText("Collect sleep").assertIsOn()
        compose.onNodeWithText("Collect location").assertIsNotEnabled()
    }

    @Test
    fun `a destructive confirm dialog names the action and reports the choice`() {
        val choices = mutableListOf<String>()
        compose.setContent {
            AgentleTestFrame {
                ConfirmDialog(
                    title = "Delete all data?",
                    message = "This removes every event stored on this phone.",
                    confirmLabel = "Delete",
                    onConfirm = { choices += "confirm" },
                    onDismiss = { choices += "dismiss" },
                    destructive = true,
                )
            }
        }
        compose.onNode(hasText("Delete all data?") and isHeading()).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Delete").performClick()
        assertThat(choices).containsExactly("dismiss", "confirm").inOrder()
    }

    @Test
    fun `a metric without data shows the reason instead of zero`() {
        compose.setContent {
            AgentleTestFrame {
                MetricTile(label = "Steps", value = "No data yet", valueKnown = false, supportingText = "Activity recognition is off")
            }
        }
        compose.onNodeWithText("No data yet").assertIsDisplayed()
        compose.onNodeWithText("0").assertDoesNotExist()
    }

    private fun hasContentDescriptionAny(): SemanticsMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)
}
