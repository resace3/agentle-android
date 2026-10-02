package dev.agentle.core.ui.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.WeakHashMap

/**
 * Sets `FLAG_SECURE` on the hosting activity's window while this effect is in the composition (docs/ARCHITECTURE.md
 * §14, raw-content screens such as the timeline): no screenshots or screen recordings, and a blank preview in Recents.
 * Pair it with `Modifier.sensitiveContent()` (androidx.compose.ui) on the composables that show the raw content, which
 * hides them during screen sharing on Android 15+. Content shown in its own window (a dialog or a bottom sheet) sets
 * that window's secure policy instead (`SecureFlagPolicy.SecureOn`).
 *
 * Several screens may hold the flag at once (for example during a navigation transition): the flag is cleared when the
 * last holder leaves the composition, and never when it was set before the first holder arrived.
 */
@Composable
public fun SecureWindowEffect() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        if (window != null) SecureWindowHolds.acquire(window)
        onDispose { if (window != null) SecureWindowHolds.release(window) }
    }
}

/** Main-thread bookkeeping of [SecureWindowEffect] holders per window. */
private object SecureWindowHolds {
    private class Hold(var count: Int, val flagWasSet: Boolean)

    private val holds = WeakHashMap<Window, Hold>()

    fun acquire(window: Window) {
        val hold = holds[window]
        if (hold != null) {
            hold.count++
            return
        }
        val flagWasSet = (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        holds[window] = Hold(count = 1, flagWasSet = flagWasSet)
        if (!flagWasSet) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(window: Window) {
        val hold = holds[window] ?: return
        hold.count--
        if (hold.count > 0) return
        holds.remove(window)
        if (!hold.flagWasSet) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
