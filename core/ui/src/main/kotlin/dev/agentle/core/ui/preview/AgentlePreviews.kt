package dev.agentle.core.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import dev.agentle.core.ui.theme.AgentleTheme

/**
 * Previews every screen must look right in: light, dark, and font scale 2.0 (spec §44-45). Wrap the preview body in
 * [AgentlePreviewSurface].
 */
@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(name = "Font scale 2.0", showBackground = true, fontScale = 2f)
public annotation class AgentlePreviews

/** [AgentleTheme] plus a surface; dark mode follows the preview's (or test's) configuration unless [darkTheme] is set. */
@Composable
public fun AgentlePreviewSurface(darkTheme: Boolean? = null, content: @Composable () -> Unit) {
    if (darkTheme == null) {
        AgentleTheme { Surface(color = MaterialTheme.colorScheme.background, content = content) }
    } else {
        AgentleTheme(darkTheme = darkTheme) { Surface(color = MaterialTheme.colorScheme.background, content = content) }
    }
}

/**
 * Renders [content] in the Agentle theme with an explicit [darkTheme] and [fontScale], on a background that fills the
 * window; used by screenshot and UI tests so the light/dark and font-scale variants do not depend on device qualifiers.
 */
@Composable
public fun AgentleTestFrame(darkTheme: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density = density.density, fontScale = fontScale)) {
        AgentleTheme(darkTheme = darkTheme) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}
