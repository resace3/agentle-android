package dev.agentle.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The dashboards sidebar from the mockup, on a phone (it slides in) and on a tablet (it stays open). */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [33, 37])
class SidebarDashboardsTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun onAPhoneTheSidebarListsTheDashboardsAndOpensThem() {
        compose.onNodeWithContentDescription("Open sidebar").performClick()

        compose.onNodeWithText("Dashboards").assertIsDisplayed()
        compose.onNodeWithContentDescription("New dashboard").assertIsDisplayed()
        compose.onNodeWithText("New Dashboard").assertIsDisplayed()
        compose.onNodeWithText("Sleep Dashboard").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()

        compose.onNodeWithText("Activity Dashboard").performClick()
        drawn("Steps")

        compose.onNodeWithText("Steps").assertIsDisplayed()
        compose.onNodeWithText("Distance (m)").assertIsDisplayed()
        compose.onNodeWithText("Remove dashboard").assertExists()

        compose.onNodeWithContentDescription("Open sidebar").performClick()
        compose.onNodeWithText("New Dashboard").performClick()

        compose.onNodeWithText("Talk to ChatGPT about your data").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1000dp-h700dp")
    fun onATabletTheSidebarStaysOpenNextToTheChat() {
        compose.onNodeWithText("Dashboards").assertIsDisplayed()
        compose.onNodeWithText("Talk to ChatGPT about your data").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open sidebar").assertDoesNotExist()

        compose.onNodeWithText("Sleep Dashboard").performClick()
        drawn("Sleep (min)")

        compose.onNodeWithText("Sleep (min)").assertIsDisplayed()
        compose.onNodeWithText("Resting heart rate (bpm)").assertIsDisplayed()
    }

    /** Google's A2UI renderer draws a screen off the main thread, so the test waits for its first part. */
    private fun drawn(text: String) {
        compose.waitUntil(DRAW_TIMEOUT_MS) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val DRAW_TIMEOUT_MS = 10_000L
    }
}
