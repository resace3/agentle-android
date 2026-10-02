package dev.agentle.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.core.ui.status.StatusTone
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.2 AA (1.4.3): text needs a contrast ratio of at least 4.5:1 against its background, in light and in dark.
 * The pairs are the text roles the components and screens use.
 */
class ContrastTest {
    @Test
    fun `text roles meet AA in the light scheme`() {
        assertTextPairs(AgentleLightColors, "light")
    }

    @Test
    fun `text roles meet AA in the dark scheme`() {
        assertTextPairs(AgentleDarkColors, "dark")
    }

    @Test
    fun `every status tone meets AA in light and dark`() {
        for ((name, palette) in listOf("light" to AgentleLightStatusPalette, "dark" to AgentleDarkStatusPalette)) {
            for (tone in StatusTone.entries) {
                val colors = palette.colorsFor(tone)
                assertAtLeastAa(colors.content, colors.container, "$name $tone")
            }
        }
    }

    @Test
    fun `the five tones have distinct containers so tone changes stay visible next to the icon and label`() {
        for (palette in listOf(AgentleLightStatusPalette, AgentleDarkStatusPalette)) {
            val containers = StatusTone.entries.map { palette.colorsFor(it).container }
            assertWithMessage("containers").that(containers.toSet()).hasSize(StatusTone.entries.size)
        }
    }

    @Test
    fun `the contrast formula matches the WCAG reference values`() {
        assertWithMessage("black on white").that(contrast(Color.Black, Color.White)).isWithin(0.01).of(21.0)
        assertWithMessage("white on white").that(contrast(Color.White, Color.White)).isWithin(0.01).of(1.0)
        assertWithMessage("#767676 on white").that(contrast(Color(0xFF767676), Color.White)).isWithin(0.01).of(4.54)
    }

    private fun assertTextPairs(scheme: ColorScheme, name: String) {
        val pairs = listOf(
            "onBackground/background" to (scheme.onBackground to scheme.background),
            "onSurface/surface" to (scheme.onSurface to scheme.surface),
            "onSurfaceVariant/surface" to (scheme.onSurfaceVariant to scheme.surface),
            "onSurface/surfaceContainerLow" to (scheme.onSurface to scheme.surfaceContainerLow),
            "onSurfaceVariant/surfaceContainerLow" to (scheme.onSurfaceVariant to scheme.surfaceContainerLow),
            "onSurface/surfaceContainerHigh" to (scheme.onSurface to scheme.surfaceContainerHigh),
            "onSurfaceVariant/surfaceContainerHigh" to (scheme.onSurfaceVariant to scheme.surfaceContainerHigh),
            "onSurface/surfaceContainerHighest" to (scheme.onSurface to scheme.surfaceContainerHighest),
            "primary/surface" to (scheme.primary to scheme.surface),
            "primary/surfaceContainerLow" to (scheme.primary to scheme.surfaceContainerLow),
            "onPrimary/primary" to (scheme.onPrimary to scheme.primary),
            "onPrimaryContainer/primaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
            "onSecondaryContainer/secondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
            "onTertiaryContainer/tertiaryContainer" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
            "error/surface" to (scheme.error to scheme.surface),
            "error/surfaceContainerHigh" to (scheme.error to scheme.surfaceContainerHigh),
            "onError/error" to (scheme.onError to scheme.error),
            "onErrorContainer/errorContainer" to (scheme.onErrorContainer to scheme.errorContainer),
            "inverseOnSurface/inverseSurface" to (scheme.inverseOnSurface to scheme.inverseSurface),
        )
        for ((pair, colors) in pairs) {
            assertAtLeastAa(colors.first, colors.second, "$name $pair")
        }
    }

    private fun assertAtLeastAa(foreground: Color, background: Color, what: String) {
        assertWithMessage(what).that(contrast(foreground, background)).isAtLeast(AA_TEXT)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private companion object {
        const val AA_TEXT = 4.5
    }
}
