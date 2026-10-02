package dev.agentle.feature.settings.port

import kotlinx.datetime.TimeZone

/**
 * The zone the settings screens format times in. The wiring team returns `AgentleClock.zone()`, so a zone change
 * (travel, manual change) shows on the next state the screens build. Screens never use the JVM default zone.
 */
public fun interface UserTimeZonePort {
    /** The user's current zone. Not suspending, no I/O: callable from the main thread. */
    public fun zone(): TimeZone
}
