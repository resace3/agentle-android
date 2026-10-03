package dev.agentle.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Toolchain smoke test: Hilt graph, Compose and Robolectric at the lowest and highest supported SDKs. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [29, 37])
class MainActivitySmokeTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesOnTheChatTab() {
        compose.onNodeWithText("Connect ChatGPT").assertIsDisplayed()
    }

    @Test
    fun flavorMatchesApplicationId() {
        val expectedSuffix = if (BuildConfig.FLAVOR == "fake") ".fake" else ""
        assertThat(BuildConfig.APPLICATION_ID).isEqualTo("dev.agentle.app$expectedSuffix")
    }
}
