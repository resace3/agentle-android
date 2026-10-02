package dev.agentle.connectors.android.receivers

import android.app.AlarmManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import dev.agentle.connectors.android.AndroidCollectors
import dev.agentle.connectors.api.SystemChange
import dev.agentle.connectors.api.SystemChangeListener
import dev.agentle.core.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Delivers [SystemChange]s to the bound [SystemChangeListener] (the schedule reconciler). BOOT_COMPLETED and
 * PACKAGE_REPLACED go out at once; bursts of TIME_SET, TIMEZONE_CHANGED and LOCALE_CHANGED are debounced: each new one
 * replaces the pending one of its kind, and the last is delivered after [debounce] (red team oauth-security-12).
 * [dispatch] returns once its change was delivered or replaced, so a receiver's `goAsync()` window covers delivery.
 */
public class SystemChangeDispatcher(
    private val scope: CoroutineScope,
    private val listener: SystemChangeListener?,
    private val logger: Logger,
    private val debounce: Duration = DEFAULT_DEBOUNCE,
) {
    private val pending = HashMap<SystemChange, Job>()

    public suspend fun dispatch(change: SystemChange, at: Instant) {
        val target = listener ?: return
        if (change in IMMEDIATE) {
            deliver(target, change, at)
            return
        }
        val job = synchronized(pending) {
            pending[change]?.cancel()
            scope.launch {
                delay(debounce)
                withContext(NonCancellable) { deliver(target, change, at) }
            }.also { pending[change] = it }
        }
        job.join()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun deliver(target: SystemChangeListener, change: SystemChange, at: Instant) {
        try {
            target.onSystemChange(change, at)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(COMPONENT, "System change listener failed", fields = mapOf("change" to change.name, "error" to e::class.simpleName))
        }
    }

    public companion object {
        private const val COMPONENT = "collectors.system"
        public val DEFAULT_DEBOUNCE: Duration = 2.seconds

        /** Delivered without debouncing (red team oauth-security-12). */
        public val IMMEDIATE: Set<SystemChange> =
            setOf(SystemChange.BOOT_COMPLETED, SystemChange.LOCKED_BOOT_COMPLETED, SystemChange.PACKAGE_REPLACED)
    }
}

/**
 * The manifest receiver for system broadcasts (exported="false": these are protected broadcasts the system delivers to
 * manifest receivers; red team oauth-security-12). Every action goes through the allow-list in [ACTIONS]; anything else
 * is ignored, and extras are never trusted (each handler re-reads the live state). Work runs inside `goAsync()`.
 */
public class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED,
            -> intent.action ?: return

            else -> return
        }
        val graph = AndroidCollectors.graph(context) ?: return
        val at = graph.runtime.clock.now()
        val pending = goAsync()
        graph.runtime.scope.launch {
            try {
                withTimeoutOrNull(RECEIVER_BUDGET) { graph.onSystemBroadcast(action, at) }
            } finally {
                pending.finish()
            }
        }
    }

    public companion object {
        /** The manifest receiver's actions (the allow-list). */
        public val ACTIONS: Set<String> = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED,
        )

        internal val RECEIVER_BUDGET: Duration = 8.seconds
    }
}

/**
 * Bluetooth ACL connections (the only exported receiver: the sender is the Bluetooth stack's UID and the broadcasts are
 * protected, so they cannot be spoofed; red team oauth-security-12). Only ACL_CONNECTED and ACL_DISCONNECTED are
 * handled. The device extra is a hint: the row is written only while `bluetooth_connected_devices` can collect, and
 * the address is stored only as a salted hash.
 */
public class BluetoothAclReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val connected = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            else -> return
        }
        val graph = AndroidCollectors.graph(context) ?: return
        val at = graph.runtime.clock.now()
        val device = readDevice(intent)
        val pending = goAsync()
        graph.runtime.scope.launch {
            try {
                withTimeoutOrNull(SystemEventReceiver.RECEIVER_BUDGET) { graph.bluetooth.onAcl(connected, device, at) }
            } finally {
                pending.finish()
            }
        }
    }

    public companion object {
        public val ACTIONS: Set<String> = setOf(BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED)

        @Suppress("TooGenericExceptionCaught")
        private fun readDevice(intent: Intent): BluetoothDevice? = try {
            IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } catch (ignored: RuntimeException) {
            null
        }
    }
}
