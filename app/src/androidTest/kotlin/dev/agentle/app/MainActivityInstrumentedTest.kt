package dev.agentle.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator smoke test: the app starts with the Hilt test application and draws its first screen. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesAndShowsAppName() {
        compose.onNodeWithText("Agentle").assertIsDisplayed()
    }
}
