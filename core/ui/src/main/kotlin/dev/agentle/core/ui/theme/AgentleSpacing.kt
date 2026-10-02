package dev.agentle.core.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing tokens (a 4 dp grid) and fixed sizes shared by every screen. */
public object AgentleSpacing {
    public val xxs: Dp = 2.dp
    public val xs: Dp = 4.dp
    public val s: Dp = 8.dp
    public val m: Dp = 12.dp
    public val l: Dp = 16.dp
    public val xl: Dp = 24.dp
    public val xxl: Dp = 32.dp

    /** Horizontal padding between screen content and the screen edge. */
    public val screenGutter: Dp = 16.dp

    /** Minimum size of anything the user can touch (Material and WCAG 2.5.8 guidance: 48 dp). */
    public val minTouchTarget: Dp = 48.dp

    /** Icon size inside status chips and rows. */
    public val iconSmall: Dp = 18.dp
    public val icon: Dp = 24.dp
    public val iconLarge: Dp = 48.dp

    /** Maximum width of reading content on wide screens. */
    public val maxContentWidth: Dp = 640.dp
}
