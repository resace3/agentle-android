package dev.agentle.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

internal val LocalAgentleStatusPalette = staticCompositionLocalOf { AgentleLightStatusPalette }

/**
 * The Agentle Material 3 theme: [AgentleLightColors] / [AgentleDarkColors], [AgentleTypography], [AgentleShapes] and the
 * status palette. [dynamicColor] (wallpaper colors, API 31+) is off by default: the fixed schemes are the ones whose
 * contrast is verified.
 */
@Composable
public fun AgentleTheme(darkTheme: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> AgentleDarkColors

        else -> AgentleLightColors
    }
    val statusPalette = if (darkTheme) AgentleDarkStatusPalette else AgentleLightStatusPalette
    CompositionLocalProvider(LocalAgentleStatusPalette provides statusPalette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AgentleTypography,
            shapes = AgentleShapes,
            content = content,
        )
    }
}

/** Theme values that Material 3 does not model. */
public object AgentleTheme {
    /** Colors of the status tones for the current light or dark theme. */
    public val statusPalette: AgentleStatusPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalAgentleStatusPalette.current
}
