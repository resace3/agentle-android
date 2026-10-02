package dev.agentle.connectors.android.collectors.activity

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import dev.agentle.connectors.android.AndroidCollectors
import dev.agentle.connectors.android.core.AndroidConnector
import dev.agentle.connectors.android.core.AndroidConnectorIds
import dev.agentle.connectors.android.core.AndroidSources
import dev.agentle.connectors.android.core.CollectOutcome
import dev.agentle.connectors.android.core.CollectorRuntime
import dev.agentle.connectors.android.core.CoverageIds
import dev.agentle.connectors.android.core.epochSafely
import dev.agentle.connectors.android.core.writeChunked
import dev.agentle.connectors.android.permissions.PendingIntentFactory
import dev.agentle.connectors.api.CapabilityIds
import dev.agentle.connectors.api.CapabilityStatusProvider
import dev.agentle.connectors.api.CoverageEndCause
import dev.agentle.connectors.api.SyncResult
import dev.agentle.connectors.api.SyncTrigger
import dev.agentle.core.common.AppError
import dev.agentle.core.model.ActivityKind
import dev.agentle.core.model.ActivityTransitionPayload
import dev.agentle.core.model.CapabilityStatus
import dev.agentle.core.model.EventType
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.TransitionKind
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** One transition as Play services delivered it. */
public data class RawTransition(val activityType: Int, val transitionType: Int, val elapsedRealtimeNanos: Long)

/** The Activity Recognition Transition API behind a seam (tests fake it). */
public interface ActivityTransitionGateway {
    /**
     * Requests transition updates for [ActivityTransitions.MONITORED] with the explicit, mutable PendingIntent
     * ([PendingIntentFactory.activityTransitions]). Throws `SecurityException` without ACTIVITY_RECOGNITION; false when
     * Play services refused.
     */
    public suspend fun register(): Boolean

    public suspend fun unregister(): Boolean
}

public class PlayServicesActivityTransitionGateway(private val context: Context, private val pendingIntents: PendingIntentFactory) :
    ActivityTransitionGateway {
    @SuppressLint("MissingPermission") // The connector runs only with ACTIVITY_RECOGNITION; a SecurityException is handled there.
    override suspend fun register(): Boolean = guard {
        val transitions = ActivityTransitions.MONITORED.flatMap { activity ->
            listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map { transition ->
                ActivityTransition.Builder().setActivityType(activity).setActivityTransition(transition).build()
            }
        }
        ActivityRecognition.getClient(context)
            .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntents.activityTransitions())
            .await()
    }

    /** Removes the updates, then cancels the PendingIntent (docs/research/03 §6.2 "Removal"). */
    @SuppressLint("MissingPermission")
    override suspend fun unregister(): Boolean = guard {
        val pendingIntent = pendingIntents.activityTransitions()
        ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pendingIntent).await()
        pendingIntent.cancel()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun guard(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: SecurityException) {
        throw e
    } catch (ignored: Exception) {
        false
    }
}

/** Activity types and mapping (docs/research/03 D7 and §6.2: the Transition API's five activities, ENTER and EXIT). */
public object ActivityTransitions {
    public val MONITORED: List<Int> = listOf(
        DetectedActivity.STILL,
        DetectedActivity.WALKING,
        DetectedActivity.RUNNING,
        DetectedActivity.ON_BICYCLE,
        DetectedActivity.IN_VEHICLE,
    )

    public fun kindOf(activityType: Int): ActivityKind = when (activityType) {
        DetectedActivity.STILL -> ActivityKind.STILL
        DetectedActivity.WALKING -> ActivityKind.WALKING
        DetectedActivity.RUNNING -> ActivityKind.RUNNING
        DetectedActivity.ON_BICYCLE -> ActivityKind.ON_BICYCLE
        DetectedActivity.IN_VEHICLE -> ActivityKind.IN_VEHICLE
        DetectedActivity.ON_FOOT -> ActivityKind.ON_FOOT
        DetectedActivity.TILTING -> ActivityKind.TILTING
        else -> ActivityKind.UNKNOWN
    }

    public fun transitionOf(transitionType: Int): TransitionKind? = when (transitionType) {
        ActivityTransition.ACTIVITY_TRANSITION_ENTER -> TransitionKind.ENTER
        ActivityTransition.ACTIVITY_TRANSITION_EXIT -> TransitionKind.EXIT
        else -> null
    }

    /** `ar|<activity>|<transition>|<eventMs>` (red team database-sync-17). */
    public fun key(activity: ActivityKind, transition: TransitionKind, eventMs: Long): String =
        "ar|${activity.name}|${transition.name}|$eventMs"
}

/**
 * Turns delivered transitions into ACTIVITY rows. The event time is the wall time of the transition's
 * `elapsedRealtimeNanos`: now minus the age measured on the monotonic clock (`AgentleClock.elapsed()` is
 * `SystemClock.elapsedRealtimeNanos()` on Android); a transition reported "in the future" is clamped to now.
 */
public class ActivityTransitionCollector(private val runtime: CollectorRuntime) {
    @Suppress("ReturnCount")
    public suspend fun onTransitions(transitions: List<RawTransition>, at: Instant): Int {
        if (transitions.isEmpty()) return 0
        if (AndroidConnectorIds.ACTIVITY in runtime.settings.current().disabledConnectors) return 0
        val events = map(transitions, at)
        if (events.isEmpty()) return 0
        val epoch = runtime.writer.epochSafely() ?: run {
            runtime.coverage.close(CoverageIds.ACTIVITY, at, CoverageEndCause.DATABASE_UNAVAILABLE)
            return 0
        }
        val writes = runtime.writeChunked(CoverageIds.ACTIVITY, epoch, events, cursor = null)
        if (writes.ok) runtime.coverage.heartbeat(CoverageIds.ACTIVITY, at)
        return writes.written
    }

    public fun map(transitions: List<RawTransition>, at: Instant): List<PersonalEvent> {
        val nowMs = at.toEpochMilliseconds()
        val elapsedNowNanos = runtime.clock.elapsed().inWholeNanoseconds
        return transitions.mapNotNull { raw ->
            val transition = ActivityTransitions.transitionOf(raw.transitionType) ?: return@mapNotNull null
            val activity = ActivityTransitions.kindOf(raw.activityType)
            val ageMs = ((elapsedNowNanos - raw.elapsedRealtimeNanos) / NANOS_PER_MILLI).coerceAtLeast(0)
            val eventMs = nowMs - ageMs
            runtime.events.create(
                type = EventType.ACTIVITY,
                source = AndroidSources.ACTIVITY,
                start = Instant.fromEpochMilliseconds(eventMs),
                payload = ActivityTransitionPayload(activity, transition),
                dedupKey = ActivityTransitions.key(activity, transition, eventMs),
            )
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * Activity transitions through the Activity Recognition Transition API (§6.4 "Activity recognition",
 * docs/research/03 §6.2). A sync (re-)registers the updates and opens coverage; a failed registration closes it
 * (REGISTRATION_LOST). Registration is repeated at every app start, on BOOT_COMPLETED and on MY_PACKAGE_REPLACED
 * (whether registrations survive a reboot or an update on their own is UNVERIFIED); re-registering with the same
 * PendingIntent is idempotent. Rows arrive through [ActivityTransitionReceiver]. Coverage is recorded under
 * `activity_recognition_transitions` ([CoverageIds.ACTIVITY]).
 */
public class ActivityTransitionsConnector(
    runtime: CollectorRuntime,
    permissions: CapabilityStatusProvider,
    private val gateway: ActivityTransitionGateway,
) : AndroidConnector(
    id = AndroidConnectorIds.ACTIVITY,
    name = "Activity transitions",
    supportedEventTypes = setOf(EventType.ACTIVITY),
    capabilityIds = listOf(CapabilityIds.ACTIVITY_RECOGNITION_TRANSITIONS),
    runtime = runtime,
    permissions = permissions,
) {
    public val collector: ActivityTransitionCollector = ActivityTransitionCollector(runtime)

    override val coverageIds: List<String> = CoverageIds.ACTIVITY

    override suspend fun collect(trigger: SyncTrigger, statuses: Map<String, CapabilityStatus>): CollectOutcome {
        if (gateway.register()) return CollectOutcome.EMPTY
        runtime.coverage.close(coverageIds, runtime.clock.now(), CoverageEndCause.REGISTRATION_LOST)
        return CollectOutcome(error = AppError.Unexpected("activity_transitions_registration_failed"))
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun onEnabledChanged(enabled: Boolean) {
        if (enabled) return
        try {
            gateway.unregister()
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // Without the permission there is nothing to unregister.
        }
    }

    /** Re-registers after a boot or an app update. */
    public suspend fun ensureRegistered(): SyncResult = sync(SyncTrigger.EVENT)
}

/**
 * Receives the Activity Recognition PendingIntent (explicit, so the receiver is not exported). Only
 * [PendingIntentFactory.ACTION_ACTIVITY_TRANSITIONS] is handled; every other action is ignored.
 */
public class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            PendingIntentFactory.ACTION_ACTIVITY_TRANSITIONS -> handle(context, intent)
            else -> Unit
        }
    }

    private fun handle(context: Context, intent: Intent) {
        val graph = AndroidCollectors.graph(context) ?: return
        val at = graph.runtime.clock.now()
        val transitions = extract(intent) ?: return
        val pending = goAsync()
        graph.runtime.scope.launch {
            try {
                withTimeoutOrNull(RECEIVER_BUDGET) { graph.activity.collector.onTransitions(transitions, at) }
            } finally {
                pending.finish()
            }
        }
    }

    public companion object {
        private val RECEIVER_BUDGET = 8.seconds

        /** The transitions in [intent], or null when it carries no `ActivityTransitionResult`. */
        @Suppress("TooGenericExceptionCaught")
        public fun extract(intent: Intent): List<RawTransition>? = try {
            if (ActivityTransitionResult.hasResult(intent)) {
                ActivityTransitionResult.extractResult(intent)?.transitionEvents?.map {
                    RawTransition(it.activityType, it.transitionType, it.elapsedRealTimeNanos)
                }
            } else {
                null
            }
        } catch (ignored: RuntimeException) {
            null
        }
    }
}
