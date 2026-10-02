package dev.agentle.connectors.android.core

import dev.agentle.connectors.api.CapabilityIds

/**
 * Coverage ids of every on-device collector (integrator correction after the realtime-features review): coverage is
 * recorded under capability ids ([CapabilityIds]), the ids the feature engines read, never under connector ids such as
 * `android.usage`. Each capability id has exactly one owner, the collector that opens and heartbeats it; other write
 * paths of the same capability may only close it when a write was lost (a gap), and the owner reopens it on its next
 * successful run. Live sources whose capability a sweep owns (screen, power, network, Bluetooth, audio, DND) are best
 * effort and record no coverage of their own (red team lifecycle-battery-07).
 */
public object CoverageIds {
    /** The usage sweep: usage events also carry screen, keyguard and boot/shutdown. */
    public val USAGE: List<String> = listOf(
        CapabilityIds.APP_USAGE_EVENTS,
        CapabilityIds.SCREEN_INTERACTIVE_EVENTS,
        CapabilityIds.UNLOCK_KEYGUARD_EVENTS,
        CapabilityIds.BOOT_SHUTDOWN_EVENTS,
    )

    /** The notification listener: open while connected. */
    public val NOTIFICATIONS: List<String> = listOf(CapabilityIds.NOTIFICATION_EVENTS_METADATA)

    /** Activity Recognition transitions: open while registered. */
    public val ACTIVITY: List<String> = listOf(CapabilityIds.ACTIVITY_RECOGNITION_TRANSITIONS)

    public val BATTERY: List<String> =
        listOf(CapabilityIds.BATTERY_STATE, CapabilityIds.POWER_SAVE_IDLE_STATE, CapabilityIds.THERMAL_STATUS)
    public val NETWORK: List<String> =
        listOf(CapabilityIds.NETWORK_CONNECTIVITY, CapabilityIds.WIFI_CONNECTION_METADATA, CapabilityIds.AIRPLANE_MODE)
    public val BLUETOOTH: List<String> = listOf(CapabilityIds.BLUETOOTH_ADAPTER_STATE, CapabilityIds.BLUETOOTH_CONNECTED_DEVICES)
    public val AUDIO: List<String> = listOf(CapabilityIds.AUDIO_VOLUME_RINGER, CapabilityIds.AUDIO_OUTPUT_DEVICES)
    public val DEVICE: List<String> =
        listOf(CapabilityIds.DND_STATE, CapabilityIds.NEXT_ALARM_CLOCK, CapabilityIds.APP_STANDBY_BUCKET, CapabilityIds.STORAGE_STATS)

    /** Boot rows of the system collector are a fallback without usage access; boot coverage belongs to [USAGE]. */
    public val SYSTEM: List<String> = listOf(CapabilityIds.TIMEZONE_TIME_CHANGES, CapabilityIds.LOCALE_TIME_FORMAT)
    public val STEPS: List<String> = listOf(CapabilityIds.STEP_COUNT_RECORDING_API)
    public val CALENDAR: List<String> = listOf(CapabilityIds.CALENDAR_EVENTS)
    public val HEALTH_CONNECT: List<String> = listOf(CapabilityIds.HEALTH_CONNECT_RECORDS)

    /** The call-state live source: open while its callback is registered. */
    public val CALL: List<String> = listOf(CapabilityIds.CALL_STATE)

    /** Every owned id with its owner (each id appears once). */
    public val OWNERS: Map<String, String> = buildMap {
        fun own(owner: String, ids: List<String>) = ids.forEach { id -> check(put(id, owner) == null) { "Two owners for $id" } }
        own(AndroidConnectorIds.USAGE, USAGE)
        own(AndroidConnectorIds.NOTIFICATIONS, NOTIFICATIONS)
        own(AndroidConnectorIds.ACTIVITY, ACTIVITY)
        own(AndroidConnectorIds.BATTERY, BATTERY)
        own(AndroidConnectorIds.NETWORK, NETWORK)
        own(AndroidConnectorIds.BLUETOOTH, BLUETOOTH)
        own(AndroidConnectorIds.AUDIO, AUDIO)
        own(AndroidConnectorIds.DEVICE, DEVICE)
        own(AndroidConnectorIds.SYSTEM, SYSTEM)
        own(AndroidConnectorIds.STEPS, STEPS)
        own(AndroidConnectorIds.CALENDAR, CALENDAR)
        own(AndroidConnectorIds.HEALTH_CONNECT, HEALTH_CONNECT)
        own(AndroidConnectorIds.CALL, CALL)
    }
}
