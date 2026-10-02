package dev.agentle.connectors.android.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dev.agentle.core.time.AgentleClock
import kotlin.time.Instant

/**
 * A runtime receiver with an action allow-list (red team oauth-security-12: `when (action)` over an allow-list, anything
 * else ignored; extras are hints only) and an explicit export flag (docs/research/02 §6.11): [exported] for broadcasts
 * sent by non-system UIDs (USER_PRESENT from SystemUI, Bluetooth from the Bluetooth UID; all protected, so they cannot be
 * spoofed), not exported otherwise. The receipt time is taken at once and carried as the event time.
 */
public class RuntimeReceiver(
    private val context: Context,
    public val actions: Set<String>,
    public val exported: Boolean,
    private val clock: AgentleClock,
    /** Data scheme for the filter (`package` for package broadcasts), or null for plain actions. */
    private val dataScheme: String? = null,
    private val onAction: (action: String, intent: Intent, at: Instant) -> Unit,
) {
    private var receiver: BroadcastReceiver? = null

    public val isRegistered: Boolean @Synchronized get() = receiver != null

    /** Registers once; false when the platform refused (nothing is then registered). */
    @Synchronized
    @Suppress("TooGenericExceptionCaught")
    public fun register(): Boolean {
        if (receiver != null) return true
        val created = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val at = clock.now()
                val action = intent.action ?: return
                if (action !in actions) return
                onAction(action, intent, at)
            }
        }
        val filter = IntentFilter().apply {
            actions.forEach(::addAction)
            dataScheme?.let(::addDataScheme)
        }
        val flags = if (exported) ContextCompat.RECEIVER_EXPORTED else ContextCompat.RECEIVER_NOT_EXPORTED
        return try {
            ContextCompat.registerReceiver(context, created, filter, flags)
            receiver = created
            true
        } catch (ignored: RuntimeException) {
            false
        }
    }

    @Synchronized
    @Suppress("TooGenericExceptionCaught")
    public fun unregister() {
        val registered = receiver ?: return
        receiver = null
        try {
            context.unregisterReceiver(registered)
        } catch (ignored: RuntimeException) {
            // Already unregistered by the platform.
        }
    }
}
