package dev.agentle.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import dev.agentle.core.ui.status.StatusTone

/**
 * Agentle's fixed color schemes (dynamic color is off by default so status colors and contrast stay predictable).
 * Every text role meets WCAG AA (4.5:1) against the backgrounds it is used on, in light and dark: `ContrastTest` checks
 * each pair.
 */
public val AgentleLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF006A60),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9EF2E4),
    onPrimaryContainer = Color(0xFF00201C),
    inversePrimary = Color(0xFF82D5C8),
    secondary = Color(0xFF4A635F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E2),
    onSecondaryContainer = Color(0xFF05201C),
    tertiary = Color(0xFF456179),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCCE5FF),
    onTertiaryContainer = Color(0xFF001E31),
    background = Color(0xFFF8FAF9),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFF8FAF9),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    surfaceTint = Color(0xFF006A60),
    inverseSurface = Color(0xFF2D3130),
    inverseOnSurface = Color(0xFFEFF1EF),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF6F7977),
    outlineVariant = Color(0xFFBEC9C5),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF8FAF9),
    surfaceContainer = Color(0xFFECEEED),
    surfaceContainerHigh = Color(0xFFE6E9E7),
    surfaceContainerHighest = Color(0xFFE1E3E2),
    surfaceContainerLow = Color(0xFFF2F4F3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD8DBD9),
)

/** Dark counterpart of [AgentleLightColors]; the same contrast guarantees apply. */
public val AgentleDarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF82D5C8),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFF9EF2E4),
    inversePrimary = Color(0xFF006A60),
    secondary = Color(0xFFB1CCC6),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF334B47),
    onSecondaryContainer = Color(0xFFCCE8E2),
    tertiary = Color(0xFFADCAE6),
    onTertiary = Color(0xFF153349),
    tertiaryContainer = Color(0xFF2D4961),
    onTertiaryContainer = Color(0xFFCCE5FF),
    background = Color(0xFF0F1513),
    onBackground = Color(0xFFDEE4E1),
    surface = Color(0xFF0F1513),
    onSurface = Color(0xFFDEE4E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C5),
    surfaceTint = Color(0xFF82D5C8),
    inverseSurface = Color(0xFFDEE4E1),
    inverseOnSurface = Color(0xFF2B3230),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4946),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF353B39),
    surfaceContainer = Color(0xFF1B211F),
    surfaceContainerHigh = Color(0xFF252B29),
    surfaceContainerHighest = Color(0xFF303634),
    surfaceContainerLow = Color(0xFF171D1B),
    surfaceContainerLowest = Color(0xFF0A0F0E),
    surfaceDim = Color(0xFF0F1513),
)

/** Background ([container]) and foreground ([content], used for the icon and the text) of one status tone. */
@Immutable
public data class StatusColors(val container: Color, val content: Color)

/** Colors of the five status tones. Status is never shown by color alone: components pair it with an icon and text. */
@Immutable
public data class AgentleStatusPalette(
    val positive: StatusColors,
    val caution: StatusColors,
    val negative: StatusColors,
    val neutral: StatusColors,
    val info: StatusColors,
) {
    public fun colorsFor(tone: StatusTone): StatusColors = when (tone) {
        StatusTone.POSITIVE -> positive
        StatusTone.CAUTION -> caution
        StatusTone.NEGATIVE -> negative
        StatusTone.NEUTRAL -> neutral
        StatusTone.INFO -> info
    }
}

public val AgentleLightStatusPalette: AgentleStatusPalette = AgentleStatusPalette(
    positive = StatusColors(container = Color(0xFFD4F3DC), content = Color(0xFF0E5223)),
    caution = StatusColors(container = Color(0xFFFFE8C2), content = Color(0xFF5A3A00)),
    negative = StatusColors(container = Color(0xFFFFDAD6), content = Color(0xFF7A1012)),
    neutral = StatusColors(container = Color(0xFFE1E3E2), content = Color(0xFF3F4946)),
    info = StatusColors(container = Color(0xFFD6E7FF), content = Color(0xFF0C3B66)),
)

public val AgentleDarkStatusPalette: AgentleStatusPalette = AgentleStatusPalette(
    positive = StatusColors(container = Color(0xFF1C4528), content = Color(0xFFB6F0C4)),
    caution = StatusColors(container = Color(0xFF4A3400), content = Color(0xFFFFDDA8)),
    negative = StatusColors(container = Color(0xFF5E1A17), content = Color(0xFFFFDAD6)),
    neutral = StatusColors(container = Color(0xFF303634), content = Color(0xFFDCE4E0)),
    info = StatusColors(container = Color(0xFF1B3A57), content = Color(0xFFD0E5FF)),
)
