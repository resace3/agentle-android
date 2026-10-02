package dev.agentle.background.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.agentle.background.ReconcileReason
import dev.agentle.background.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Not exported; only the allow-listed system actions reach the scheduler, as a reconcile request (debounced except
 * BOOT and PACKAGE_REPLACED). Does no work itself: it only enqueues, inside a bounded goAsync window.
 */
@Suppress("InjectDispatcher") // a receiver has no injection point; the work is a bounded enqueue
public class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = reasonFor(intent.action) ?: return
        val scheduler = EntryPointAccessors.fromApplication(context.applicationContext, ReceiverEntryPoint::class.java)
            .workScheduler()
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(ENQUEUE_BUDGET) { scheduler.requestReconcile(setOf(reason)) }
            } finally {
                pending.finish()
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    public interface ReceiverEntryPoint {
        public fun workScheduler(): WorkScheduler
    }

    public companion object {
        /** API 37 `Intent.ACTION_TIMEZONE_OFFSET_CHANGED`; a literal so the module compiles against older SDKs. */
        public const val ACTION_TIMEZONE_OFFSET_CHANGED: String = "android.intent.action.TIMEZONE_OFFSET_CHANGED"
        private val ENQUEUE_BUDGET = 8.seconds
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** The allow-list; anything else (including a null action) is ignored. */
        public fun reasonFor(action: String?): ReconcileReason? = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> ReconcileReason.BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> ReconcileReason.PACKAGE_REPLACED
            Intent.ACTION_TIME_CHANGED -> ReconcileReason.CLOCK
            Intent.ACTION_TIMEZONE_CHANGED -> ReconcileReason.TIMEZONE
            Intent.ACTION_LOCALE_CHANGED -> ReconcileReason.LOCALE
            ACTION_TIMEZONE_OFFSET_CHANGED -> ReconcileReason.OFFSET
            else -> null
        }
    }
}
