package dev.agentle.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * PROBE (integrator item K): proves that the CI `record_screenshots` dispatch records goldens into `src/screenshots`
 * and that normal CI runs verify them. Removed once proven: `:core:ui` has no theme or component to screenshot yet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w320dp-h120dp-mdpi")
class ScreenshotProbeTest {
    @Test
    fun lightSurfaceText() {
        captureRoboImage {
            MaterialTheme(colorScheme = lightColorScheme()) { Surface { Text("Agentle") } }
        }
    }

    @Test
    fun darkSurfaceText() {
        captureRoboImage {
            MaterialTheme(colorScheme = darkColorScheme()) { Surface { Text("Agentle") } }
        }
    }
}
