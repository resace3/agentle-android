package dev.agentle.core.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The icons Agentle uses, as 24 dp vectors (Material Design icon geometry, Apache 2.0). They are bundled here because
 * Material 3 no longer brings in the `material-icons` artifacts, and the extended set is large. Icons that point in a
 * reading direction ([arrowBack], [chevronEnd]) mirror automatically in right-to-left layouts.
 *
 * Status icons always come with a text label (`StatusChip`); an icon alone never carries a status.
 */
public object AgentleIcons {
    // ---- status
    public val checkCircle: ImageVector by lazy {
        icon(
            "CheckCircle",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM10,17l-5,-5 1.41,-1.41L10,14.17" +
                "l7.59,-7.59L19,8l-9,9z",
        )
    }
    public val block: ImageVector by lazy {
        icon(
            "Block",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM4,12" +
                "c0,-4.42 3.58,-8 8,-8 1.85,0 3.55,0.63 4.9,1.69L5.69,16.9C4.63,15.55 4,13.85 4,12zM12,20" +
                "c-1.85,0 -3.55,-0.63 -4.9,-1.69L18.31,7.1C19.37,8.45 20,10.15 20,12c0,4.42 -3.58,8 -8,8z",
        )
    }
    public val error: ImageVector by lazy {
        icon("Error", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-2h2v2zM13,13h-2L11,7h2v6z")
    }
    public val warning: ImageVector by lazy { icon("Warning", "M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z") }
    public val info: ImageVector by lazy {
        icon("Info", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,9h-2L11,7h2v2z")
    }
    public val lock: ImageVector by lazy {
        icon(
            "Lock",
            "M18,8h-1L17,6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2L6,8c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12" +
                "c1.1,0 2,-0.9 2,-2L20,10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2" +
                "s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM15.1,8L8.9,8L8.9,6" +
                "c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z",
        )
    }
    public val tune: ImageVector by lazy {
        icon(
            "Tune",
            "M3,17v2h6v-2L3,17zM3,5v2h10L13,5L3,5zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2L3,11v2h4v2h2L9,9L7,9zM21,13" +
                "v-2L11,11v2h10zM15,9h2L17,7h4L21,5h-4L17,3h-2v6z",
        )
    }
    public val halfCircle: ImageVector by lazy {
        icon(
            "HalfCircle",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20L12,4c4.41,0 8,3.59 8,8" +
                "s-3.59,8 -8,8z",
        )
    }
    public val visibility: ImageVector by lazy {
        icon(
            "Visibility",
            "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5z" +
                "M12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,9c-1.66,0 -3,1.34 -3,3" +
                "s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z",
        )
    }
    public val schedule: ImageVector by lazy {
        icon(
            "Schedule",
            "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20" +
                "c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7L11,7v6" +
                "l5.25,3.15 0.75,-1.23 -4.5,-2.67z",
        )
    }
    public val pauseCircle: ImageVector by lazy {
        icon("PauseCircle", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM11,16L9,16L9,8h2v8zM15,16h-2L13,8h2v8z")
    }
    public val removeCircle: ImageVector by lazy {
        icon("RemoveCircle", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM17,13L7,13v-2h10v2z")
    }
    public val circleOutline: ImageVector by lazy {
        icon(
            "CircleOutline",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.42,0 -8,-3.58 -8,-8" +
                "s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8z",
        )
    }
    public val sync: ImageVector by lazy {
        icon(
            "Sync",
            "M12,4L12,1L8,5l4,4L12,6c3.31,0 6,2.69 6,6 0,1.01 -0.25,1.97 -0.7,2.8l1.46,1.46" +
                "C19.54,15.03 20,13.57 20,12c0,-4.42 -3.58,-8 -8,-8zM12,18" +
                "c-3.31,0 -6,-2.69 -6,-6 0,-1.01 0.25,-1.97 0.7,-2.8L5.24,7.74C4.46,8.97 4,10.43 4,12" +
                "c0,4.42 3.58,8 8,8v3l4,-4 -4,-4v3z",
        )
    }

    // ---- navigation and actions
    public val arrowBack: ImageVector by lazy {
        icon("ArrowBack", "M20,11L7.83,11l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13L20,13v-2z", autoMirror = true)
    }
    public val chevronEnd: ImageVector by lazy { icon("ChevronEnd", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z", autoMirror = true) }
    public val expandMore: ImageVector by lazy { icon("ExpandMore", "M16.59,8.59L12,13.17 7.41,8.59 6,10l6,6 6,-6z") }
    public val close: ImageVector by lazy {
        icon("Close", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")
    }
    public val check: ImageVector by lazy { icon("Check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z") }
    public val openExternal: ImageVector by lazy {
        icon(
            "OpenExternal",
            "M19,19L5,19L5,5h7L12,3L5,3c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7z" +
                "M14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41L19,10h2L21,3h-7z",
        )
    }
    public val filter: ImageVector by lazy { icon("Filter", "M10,18h4v-2h-4v2zM3,6v2h18L21,6L3,6zM6,13h12v-2L6,11v2z") }
    public val calendar: ImageVector by lazy {
        icon(
            "Calendar",
            "M19,3h-1L18,1h-2v2L8,3L8,1L6,1v2L5,3c-1.11,0 -1.99,0.9 -1.99,2L3,19c0,1.1 0.89,2 2,2h14" +
                "c1.1,0 2,-0.9 2,-2L21,5c0,-1.1 -0.9,-2 -2,-2zM19,19L5,19L5,8h14v11z",
        )
    }
    public val lightbulb: ImageVector by lazy {
        icon(
            "Lightbulb",
            "M9,21c0,0.55 0.45,1 1,1h4c0.55,0 1,-0.45 1,-1v-1L9,20v1zM12,2C8.14,2 5,5.14 5,9" +
                "c0,2.38 1.19,4.47 3,5.74L8,17c0,0.55 0.45,1 1,1h6c0.55,0 1,-0.45 1,-1v-2.26" +
                "c1.81,-1.27 3,-3.36 3,-5.74 0,-3.86 -3.14,-7 -7,-7z",
        )
    }
    public val shield: ImageVector by lazy {
        icon(
            "Shield",
            "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12L21,5l-9,-4zM10,17l-4,-4 1.41,-1.41" +
                "L10,14.17l6.59,-6.59L18,9l-8,8z",
        )
    }
    public val phone: ImageVector by lazy {
        icon(
            "Phone",
            "M17,1.01L7,1c-1.1,0 -2,0.9 -2,2v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2L19,3" +
                "c0,-1.1 -0.9,-1.99 -2,-1.99zM17,19L7,19L7,5h10v14z",
        )
    }
    public val watch: ImageVector by lazy {
        icon(
            "Watch",
            "M20,12c0,-2.54 -1.19,-4.81 -3.04,-6.27L16,0L8,0l-0.95,5.73C5.19,7.19 4,9.45 4,12s1.19,4.81 3.05,6.27" +
                "L8,24h8l0.96,-5.73C18.81,16.81 20,14.54 20,12zM6,12c0,-3.31 2.69,-6 6,-6" +
                "s6,2.69 6,6 -2.69,6 -6,6 -6,-2.69 -6,-6z",
        )
    }
    public val notifications: ImageVector by lazy {
        icon(
            "Notifications",
            "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32L13.5,4" +
                "c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z",
        )
    }
    public val sparkle: ImageVector by lazy { icon("Sparkle", "M12,2l2.4,6.6L21,11l-6.6,2.4L12,20l-2.4,-6.6L3,11l6.6,-2.4z") }

    // ---- sidebar
    public val menu: ImageVector by lazy { icon("Menu", "M3,18h18v-2H3v2zM3,13h18v-2H3v2zM3,6v2h18V6H3z") }
    public val gridView: ImageVector by lazy {
        icon(
            "GridView",
            "M3,3v8h8V3H3zM9,9H5V5h4V9zM3,13v8h8v-8H3zM9,19H5v-4h4V19zM13,3v8h8V3H13zM19,9h-4V5h4V9zM13,13v8" +
                "h8v-8H13zM19,19h-4v-4h4V19z",
        )
    }
    public val add: ImageVector by lazy { icon("Add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z") }
    public val chatBubble: ImageVector by lazy {
        icon(
            "ChatBubble",
            "M20,2H4C2.9,2 2,2.9 2,4v18l4,-4h14c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2zM20,16H6l-2,2V4h16V16z",
        )
    }
    public val barChart: ImageVector by lazy { icon("BarChart", "M5,9.2h3V19H5V9.2zM10.6,5h2.8v14h-2.8V5zM16.2,13H19v6h-2.8V13z") }
    public val settings: ImageVector by lazy {
        icon(
            "Settings",
            "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58" +
                "c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96" +
                "c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84" +
                "c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33" +
                "c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48" +
                "l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58" +
                "c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96" +
                "c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84" +
                "c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96" +
                "c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6" +
                "c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z",
        )
    }
}

private fun icon(name: String, pathData: String, autoMirror: Boolean = false): ImageVector = ImageVector.Builder(
    name = "Agentle.$name",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
    autoMirror = autoMirror,
).addPath(
    pathData = PathParser().parsePathString(pathData).toNodes(),
    pathFillType = PathFillType.EvenOdd,
    fill = SolidColor(Color.Black),
).build()
