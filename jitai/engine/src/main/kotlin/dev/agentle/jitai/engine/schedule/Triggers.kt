package dev.agentle.jitai.engine.schedule

import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiEventType
import dev.agentle.jitai.dsl.model.JitaiKind
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.engine.decision.ImpliedState
import dev.agentle.jitai.engine.ports.EngineSettings
import dev.agentle.jitai.engine.ports.TriggerEvent
import dev.agentle.jitai.engine.time.LocalWindow
import dev.agentle.jitai.engine.time.MonotonicStamp
import dev.agentle.jitai.engine.time.WindowInstance
import dev.agentle.jitai.engine.time.elapsedBetween
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Whether an event type can be observed now (R10 §7.2; red team lifecycle-battery-06/07). */
public enum class EventAvailability {
    /** Observed in the background (geofence, activity transitions, Agentle's own sync worker, the listener). */
    AVAILABLE,

    /** Observed only by a runtime receiver while the process is alive, which the connected listener keeps it. */
    BEST_EFFORT,

    /** Cannot be observed: `LOCATION_CLASS_CHANGED` in v1, or a listener-dependent type while it is disconnected. */
    UNAVAILABLE,
}

/** The candidate set and the effectiveness rule shared by every pass. */
public object Effectiveness {
    /** `enabled && status == ACTIVE` (G01 semantics; expiry is G02 and still writes a SUPPRESSED row, R10 §12.M2). */
    public fun isArmed(definition: JitaiDefinition): Boolean = definition.enabled && definition.status == JitaiStatus.ACTIVE

    /** Armed and not past `expiresAt` at [at] (R10 §2.2 "effective"). */
    public fun isEffective(definition: JitaiDefinition, at: Instant): Boolean =
        isArmed(definition) && definition.expiresAt.let { it == null || at < it }

    public fun isIntervention(definition: JitaiDefinition): Boolean = definition.kind == JitaiKind.INTERVENTION

    /**
     * The window instance of [definition]'s active window containing [t], or for a rule without a window the whole local
     * day (so `interval` slots then count from local midnight). An invalid window never opens (fails closed).
     */
    public fun instanceAt(definition: JitaiDefinition, t: Instant, zone: TimeZone): WindowInstance? {
        val window = definition.activeWindow ?: return LocalWindow.wholeDayInstance(t, zone)
        return LocalWindow.of(window)?.instanceAt(t, zone)
    }

    /** Whether the active window (if any) is open at [t]. */
    public fun windowOpen(definition: JitaiDefinition, t: Instant, zone: TimeZone): Boolean =
        definition.activeWindow == null || instanceAt(definition, t, zone) != null
}

/** Event-trigger policy: availability, dispatch, age bound and the live state an event implies. */
public object EventPolicy {
    private val BEST_EFFORT_TYPES = setOf(
        JitaiEventType.POWER_CONNECTED,
        JitaiEventType.POWER_DISCONNECTED,
        JitaiEventType.SCREEN_INTERACTIVE,
        JitaiEventType.USER_PRESENT,
    )

    /**
     * R10 §7.2 with the red-team corrections: LOCATION_CLASS_CHANGED does not exist in v1; the best-effort types and
     * NOTIFICATION_POSTED follow the notification listener's connection state.
     */
    public fun availability(type: JitaiEventType, listenerConnected: Boolean): EventAvailability = when {
        !type.isAvailable -> EventAvailability.UNAVAILABLE
        type in BEST_EFFORT_TYPES -> if (listenerConnected) EventAvailability.BEST_EFFORT else EventAvailability.UNAVAILABLE
        type == JitaiEventType.NOTIFICATION_POSTED -> if (listenerConnected) EventAvailability.AVAILABLE else EventAvailability.UNAVAILABLE
        else -> EventAvailability.AVAILABLE
    }

    /** Age of [event] at [now]: monotonic when the event carries a same-boot stamp, else wall time clamped at zero. */
    public fun age(event: TriggerEvent, now: MonotonicStamp): Duration =
        event.stamp?.let { elapsedBetween(it, now) } ?: (now.wall - event.eventAt).coerceAtLeast(Duration.ZERO)

    /** MISSED(EVENT_TOO_OLD) when the age exceeds the type's `maxEventAgeMinutes` (red team lifecycle-battery-05). */
    public fun isTooOld(event: TriggerEvent, now: MonotonicStamp, settings: EngineSettings): Boolean =
        age(event, now) > settings.maxEventAge(event.type)

    /** The live state [event] implies, re-read before posting (POWER_CONNECTED -> `charging == true`, ...). */
    public fun impliedState(type: JitaiEventType, activityState: String?): ImpliedState? = when (type) {
        JitaiEventType.POWER_CONNECTED -> ImpliedState(FeatureRef(CHARGING), FeatureScalar.BoolValue(true))

        JitaiEventType.POWER_DISCONNECTED -> ImpliedState(FeatureRef(CHARGING), FeatureScalar.BoolValue(false))

        JitaiEventType.SCREEN_INTERACTIVE, JitaiEventType.USER_PRESENT ->
            ImpliedState(FeatureRef(INTERACTIVE), FeatureScalar.BoolValue(true))

        JitaiEventType.ACTIVITY_STATE_CHANGED -> activityState?.let { ImpliedState(FeatureRef(ACTIVITY), FeatureScalar.EnumValue(it)) }

        else -> null
    }

    /** Effective INTERVENTION rules with an event trigger listing [type] whose window is open at [t] (R10 §7.3 item 2). */
    public fun candidates(definitions: List<JitaiDefinition>, type: JitaiEventType, t: Instant, zone: TimeZone): List<JitaiDefinition> =
        definitions.filter { definition ->
            val trigger = definition.trigger
            Effectiveness.isIntervention(definition) && Effectiveness.isEffective(definition, t) &&
                trigger is Trigger.Event && type in trigger.events && Effectiveness.windowOpen(definition, t, zone)
        }

    /**
     * Whether ingesting [event] should enqueue `jitai-eval-events` (R10 §7.3; red team database-sync-04 and
     * lifecycle-battery-05): only a semantic transition of an observable type that is still within its age bound (events
     * inserted by polling, sync replay or backfill included) and that some candidate rule listens to.
     */
    public fun shouldWake(
        event: TriggerEvent,
        definitions: List<JitaiDefinition>,
        now: MonotonicStamp,
        zone: TimeZone,
        settings: EngineSettings,
    ): Boolean = event.isSemantic && event.type.isAvailable && !isTooOld(event, now, settings) &&
        candidates(definitions, event.type, now.wall, zone).isNotEmpty()

    /** Debounce window of an event trigger (R10 §7.3 item 5). */
    public fun debounce(trigger: Trigger.Event): Duration = trigger.debounceSeconds.coerceAtLeast(0).seconds

    private const val CHARGING = "charging"
    private const val INTERACTIVE = "device_interactive"
    private const val ACTIVITY = "activity_state"
}
