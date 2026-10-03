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
    primary = Color(0xFF0057CE),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDAE2FF),
    onPrimaryContainer = Color(0xFF001946),
    inversePrimary = Color(0xFFB1C5FF),
    secondary = Color(0xFF4F5B79),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E2FF),
    onSecondaryContainer = Color(0xFF0A1733),
    tertiary = Color(0xFF00639A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCEE5FF),
    onTertiaryContainer = Color(0xFF001D32),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF191C22),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191C22),
    surfaceVariant = Color(0xFFDFE2EE),
    onSurfaceVariant = Color(0xFF424753),
    surfaceTint = Color(0xFF0057CE),
    inverseSurface = Color(0xFF2E3037),
    inverseOnSurface = Color(0xFFEFF0F8),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF727784),
    outlineVariant = Color(0xFFC2C6D4),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF8F9FF),
    surfaceContainer = Color(0xFFECEDF6),
    surfaceContainerHigh = Color(0xFFE6E8F0),
    surfaceContainerHighest = Color(0xFFE1E2EB),
    surfaceContainerLow = Color(0xFFF2F3FC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD8D9E2),
)

/** Dark counterpart of [AgentleLightColors]; the same contrast guarantees apply. */
public val AgentleDarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFB1C5FF),
    onPrimary = Color(0xFF002C71),
    primaryContainer = Color(0xFF00419F),
    onPrimaryContainer = Color(0xFFDAE2FF),
    inversePrimary = Color(0xFF0057CE),
    secondary = Color(0xFFB8C6EA),
    onSecondary = Color(0xFF212F4A),
    secondaryContainer = Color(0xFF384561),
    onSecondaryContainer = Color(0xFFD7E2FF),
    tertiary = Color(0xFF96CCFF),
    onTertiary = Color(0xFF003353),
    tertiaryContainer = Color(0xFF004A75),
    onTertiaryContainer = Color(0xFFCEE5FF),
    background = Color(0xFF0F131A),
    onBackground = Color(0xFFE1E2EB),
    surface = Color(0xFF0F131A),
    onSurface = Color(0xFFE1E2EB),
    surfaceVariant = Color(0xFF424753),
    onSurfaceVariant = Color(0xFFC2C6D4),
    surfaceTint = Color(0xFFB1C5FF),
    inverseSurface = Color(0xFFE1E2EB),
    inverseOnSurface = Color(0xFF2E3037),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8C909E),
    outlineVariant = Color(0xFF424753),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF353940),
    surfaceContainer = Color(0xFF1D2027),
    surfaceContainerHigh = Color(0xFF272A31),
    surfaceContainerHighest = Color(0xFF32353C),
    surfaceContainerLow = Color(0xFF191C22),
    surfaceContainerLowest = Color(0xFF0A0E14),
    surfaceDim = Color(0xFF0F131A),
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
