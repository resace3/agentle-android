package dev.agentle.core.ui.status

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector

/** How serious a status is. The tone picks the colors; the icon and the label carry the meaning. */
public enum class StatusTone {
    /** Working as intended (allowed, connected, synced). */
    POSITIVE,

    /** Works with limits or needs attention soon (partial, foreground only, unavailable for now). */
    CAUTION,

    /** Not working until the user acts (denied, failed, needs sign-in). */
    NEGATIVE,

    /** Not applicable or not set up (unsupported, restricted, not connected, idle). */
    NEUTRAL,

    /** In progress or informational (connecting, syncing). */
    INFO,
}

/**
 * A resolved status: [tone], [icon] and a short [label] (a string resource of `:core:ui`). Never shown as color only:
 * `StatusChip` and `StatusBadge` always render the icon and the label text.
 */
@Immutable
public data class StatusSpec(val tone: StatusTone, val icon: ImageVector, @param:StringRes @field:StringRes val label: Int)
