package dev.agentle.feature.connections.port

import kotlinx.datetime.TimeZone

/**
 * The user's current time zone for showing times on the connection screens (ui-common: times in the user's zone from
 * the clock, never the JVM default). Wiring: `AgentleClock.zone()`.
 *
 * Main-safe and cheap: the ViewModels read it whenever they build a new screen state, so a zone change (travel) shows
 * on the next update.
 */
public fun interface DisplayZonePort {
    public fun zone(): TimeZone
}
