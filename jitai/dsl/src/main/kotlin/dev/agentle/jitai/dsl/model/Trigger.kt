package dev.agentle.jitai.dsl.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** When a decision point exists (R10 §3.2, §7.1); sealed with discriminator `type`. */
@Serializable
public sealed interface Trigger {
    /** Each matching normalized event (after debounce) while the active window is open. */
    @Serializable
    @SerialName("event")
    public data class Event(val events: List<JitaiEventType>, val debounceSeconds: Int = DEFAULT_DEBOUNCE_SECONDS) : Trigger

    /** Slots every [everyMinutes] (a multiple of 15) from the start of each active-window instance. */
    @Serializable
    @SerialName("interval")
    public data class Interval(val everyMinutes: Int) : Trigger

    /** Each listed local `HH:mm` time once per local date, at most [maxLatenessMinutes] late. */
    @Serializable
    @SerialName("daily_at")
    public data class DailyAt(val times: List<String>, val maxLatenessMinutes: Int = DEFAULT_MAX_LATENESS_MINUTES) : Trigger

    public companion object {
        public const val DEFAULT_DEBOUNCE_SECONDS: Int = 60
        public const val DEFAULT_MAX_LATENESS_MINUTES: Int = 30
    }
}

/** The `type` of a trigger on the wire. */
public val Trigger.wireType: String
    get() = when (this) {
        is Trigger.Event -> "event"
        is Trigger.Interval -> "interval"
        is Trigger.DailyAt -> "daily_at"
    }
