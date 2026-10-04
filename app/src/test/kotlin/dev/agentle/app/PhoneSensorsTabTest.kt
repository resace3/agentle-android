package dev.agentle.app

import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorBuilder
import org.robolectric.shadows.SensorEventBuilder
import java.time.Duration

/** The Phone sensors tab over the real gateway and Robolectric's sensor manager, on the phone's Android 13 and on 17. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [33, 37], qualifiers = "w411dp-h891dp")
class PhoneSensorsTabTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theSidebarOpensPhoneSensorsAndEachSensorShowsWhetherItCanBeRead() {
        val manager = compose.activity.getSystemService(SensorManager::class.java)
        val accelerometer = SensorBuilder.newBuilder().setType(Sensor.TYPE_ACCELEROMETER).setName("BMI160 Accelerometer").build()
        shadowOf(manager).addSensor(accelerometer)
        shadowOf(manager).addSensor(SensorBuilder.newBuilder().setType(Sensor.TYPE_STEP_COUNTER).setName("Step Counter").build())

        compose.onNodeWithText("☰").performClick()
        compose.onNodeWithText("Phone sensors").performClick()

        compose.onNodeWithText("Accelerometer").assertIsDisplayed()
        compose.onNodeWithText("Allow physical activity").assertIsDisplayed()

        shadowOf(manager).sendSensorEventToListeners(SensorEventBuilder.newBuilder(accelerometer, floatArrayOf(0f, 9.81f, 0f)).build())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SNAPSHOT_WAIT_MS))
        compose.waitForIdle()

        compose.onNodeWithText("Sending data").assertIsDisplayed()
        compose.onNodeWithText("x 0, y 9.81, z 0 m/s²").assertIsDisplayed()
        compose.onNodeWithText("2 sensors: 1 sending data, 1 need permission").assertIsDisplayed()
    }

    private companion object {
        const val SNAPSHOT_WAIT_MS = 300L
    }
}
