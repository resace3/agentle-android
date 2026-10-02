package dev.agentle.connectors.android.permissions

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * In-memory connection state of Agentle's notification listener (docs/research/02 §6.10). The listener reports
 * [onConnected] and [onDisconnected]; the Permission Center reads [connected] and asks [rebindIfDue] on each pass:
 * `requestRebind` is the only call that is safe while disconnected, and it is rate-limited to once per [REBIND_AFTER]
 * after more than [REBIND_AFTER] without a connection.
 */
public class ListenerConnection(private val component: ComponentName, private val rebind: (ComponentName) -> Unit = ::defaultRebind) {
    private val state = MutableStateFlow(false)
    private var disconnectedSince: Instant? = null
    private var lastRebind: Instant? = null

    public val connected: StateFlow<Boolean> = state.asStateFlow()

    public val isConnected: Boolean get() = state.value

    @Synchronized
    public fun onConnected() {
        state.value = true
        disconnectedSince = null
    }

    @Synchronized
    public fun onDisconnected(at: Instant) {
        state.value = false
        disconnectedSince = at
    }

    /**
     * Requests a rebind when access is [granted], the listener has been disconnected (or never connected since
     * [processStart]) for more than [REBIND_AFTER], and the last request is older than that. Returns whether it asked.
     */
    @Synchronized
    public fun rebindIfDue(granted: Boolean, now: Instant, processStart: Instant): Boolean {
        if (!granted || state.value) return false
        val since = disconnectedSince ?: processStart
        val last = lastRebind
        if (now - since < REBIND_AFTER || (last != null && now - last < REBIND_AFTER)) return false
        lastRebind = now
        return try {
            rebind(component)
            true
        } catch (ignored: RuntimeException) {
            false
        }
    }

    /** Requests a rebind now (after delete-all re-enabled the component); false when the platform refused. */
    @Synchronized
    public fun rebindNow(now: Instant): Boolean {
        lastRebind = now
        return try {
            rebind(component)
            true
        } catch (ignored: RuntimeException) {
            false
        }
    }

    public companion object {
        public val REBIND_AFTER: Duration = 10.minutes

        private fun defaultRebind(component: ComponentName) = NotificationListenerService.requestRebind(component)
    }
}
