package dev.agentle.connectors.android.collectors.device

import dev.agentle.connectors.android.core.AndroidSources
import dev.agentle.connectors.android.core.Buckets
import dev.agentle.connectors.android.core.CollectorRuntime
import dev.agentle.connectors.android.core.LiveWriter
import dev.agentle.connectors.android.core.RunWrites
import dev.agentle.connectors.android.core.StateStream
import dev.agentle.connectors.android.core.epochSafely
import dev.agentle.connectors.android.core.writeChunked
import dev.agentle.connectors.api.CapabilityIds
import dev.agentle.connectors.api.WriteResult
import dev.agentle.core.common.AppError
import dev.agentle.core.model.AudioStatePayload
import dev.agentle.core.model.BatteryPayload
import dev.agentle.core.model.BluetoothPayload
import dev.agentle.core.model.ConnectivityPayload
import dev.agentle.core.model.DndPayload
import dev.agentle.core.model.EventType
import dev.agentle.core.model.NetworkKind
import dev.agentle.core.model.NextAlarmPayload
import dev.agentle.core.model.NoPayload
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.PlugType
import dev.agentle.core.model.PowerStatePayload
import dev.agentle.core.model.StandbyBucketPayload
import dev.agentle.core.model.StoragePayload
import dev.agentle.core.model.SystemEventPayload
import kotlin.time.Instant

/**
 * Rows of the device-state collectors, shared by the periodic sweeps and the live sources so both write the same keys
 * (red team database-sync-17): transitions `<kind>|<eventMs>`, snapshots by 5-minute bucket, change-detected states
 * through [StateStream] (a row only when the state differs from the stored one).
 */
public class PowerRecorder(private val runtime: CollectorRuntime) {
    private val plugged = StateStream(runtime, STREAM_PLUGGED, listOf(CapabilityIds.BATTERY_STATE))
    private val level = StateStream(runtime, STREAM_LEVEL, listOf(CapabilityIds.BATTERY_STATE))
    private val power = StateStream(runtime, STREAM_POWER, listOf(CapabilityIds.POWER_SAVE_IDLE_STATE, CapabilityIds.THERMAL_STATUS))

    /** A periodic snapshot keyed `battery|sample|<5-min bucket>` (the bucket start is the event time). */
    public fun sample(snapshot: BatterySnapshot, power: PowerSnapshot?, at: Instant): PersonalEvent {
        val bucket = Buckets.floor(at.toEpochMilliseconds())
        return runtime.events.create(
            type = EventType.BATTERY_SAMPLE,
            source = AndroidSources.BATTERY,
            start = Instant.fromEpochMilliseconds(bucket),
            payload = BatteryPayload(
                snapshot.levelPercent,
                snapshot.plugType,
                snapshot.charging,
                snapshot.temperatureCelsius,
                power?.powerSaveMode,
            ),
            dedupKey = "battery|sample|$bucket",
        )
    }

    /**
     * A BATTERY_SAMPLE ([sample]) only when the level moved to another 5% bucket (red team lifecycle-battery-17): a
     * charge from 20% to 100% writes at most 17 samples however often the battery broadcast fires.
     */
    public suspend fun recordLevel(
        snapshot: BatterySnapshot,
        power: PowerSnapshot?,
        at: Instant,
        via: LiveWriter.Channel? = null,
    ): WriteResult? = level.record((snapshot.levelPercent / LEVEL_BUCKET_PERCENT).toString(), via = via) { sample(snapshot, power, at) }

    /**
     * Records the confirmed plugged state; a change writes CHARGING_STARTED or CHARGING_STOPPED keyed
     * `battery|charging_started|<eventMs>`. [recordFirst] is true only for a confirmed live broadcast (a sweep's first
     * observation is not a transition).
     */
    public suspend fun recordPlugged(
        snapshot: BatterySnapshot,
        at: Instant,
        recordFirst: Boolean,
        via: LiveWriter.Channel? = null,
    ): WriteResult? {
        val isPlugged = snapshot.plugType != PlugType.NONE
        return plugged.record(if (isPlugged) "1" else "0", recordFirst, via = via) { transition(isPlugged, snapshot, at, hint = false) }
    }

    /** An unconfirmed POWER_CONNECTED/DISCONNECTED broadcast: a hint row; the stored state is not touched. */
    public fun hint(connected: Boolean, snapshot: BatterySnapshot?, at: Instant): PersonalEvent =
        transition(connected, snapshot, at, hint = true)

    /** Power save, device idle and thermal status; a change writes POWER_STATE_CHANGED keyed `battery|power_state|<eventMs>`. */
    public suspend fun recordPower(snapshot: PowerSnapshot, at: Instant, via: LiveWriter.Channel? = null): WriteResult? =
        power.record(snapshot.stateKey, via = via) {
            runtime.events.create(
                type = EventType.POWER_STATE_CHANGED,
                source = AndroidSources.BATTERY,
                start = at,
                payload = PowerStatePayload(snapshot.powerSaveMode, snapshot.deviceIdle, snapshot.thermalStatus),
                dedupKey = "battery|power_state|${at.toEpochMilliseconds()}",
            )
        }

    public fun forget() {
        level.forget()
        plugged.forget()
        power.forget()
    }

    private fun transition(connected: Boolean, snapshot: BatterySnapshot?, at: Instant, hint: Boolean): PersonalEvent {
        val kind = if (connected) "charging_started" else "charging_stopped"
        return runtime.events.create(
            type = if (connected) EventType.CHARGING_STARTED else EventType.CHARGING_STOPPED,
            source = if (hint) AndroidSources.BATTERY_HINT else AndroidSources.BATTERY,
            start = at,
            payload = BatteryPayload(
                levelPercent = snapshot?.levelPercent ?: UNKNOWN_LEVEL,
                plugType = snapshot?.plugType ?: PlugType.UNKNOWN,
                charging = snapshot?.charging ?: connected,
                temperatureCelsius = snapshot?.temperatureCelsius,
            ),
            dedupKey = if (hint) "battery|hint_$kind|${at.toEpochMilliseconds()}" else "battery|$kind|${at.toEpochMilliseconds()}",
            confidence = if (hint) HINT_CONFIDENCE else null,
        )
    }

    public companion object {
        public const val STREAM_PLUGGED: String = "battery_plugged"
        public const val STREAM_POWER: String = "power_state"
        public const val STREAM_LEVEL: String = "battery_level"
        public const val LEVEL_BUCKET_PERCENT: Int = 5
        private const val UNKNOWN_LEVEL = -1
    }
}

/** Default network and airplane mode (no SSID or BSSID: they need location). */
public class NetworkRecorder(private val runtime: CollectorRuntime) {
    private val connectivity =
        StateStream(runtime, STREAM_CONNECTIVITY, listOf(CapabilityIds.NETWORK_CONNECTIVITY, CapabilityIds.WIFI_CONNECTION_METADATA))
    private val airplane = StateStream(runtime, STREAM_AIRPLANE, listOf(CapabilityIds.AIRPLANE_MODE))

    /** CONNECTIVITY_CHANGED keyed `net|<eventMs>` when the coarse state (kind, metered, validated) changed. */
    public suspend fun recordNetwork(
        snapshot: NetworkSnapshot,
        airplaneMode: Boolean?,
        at: Instant,
        via: LiveWriter.Channel? = null,
    ): WriteResult? = connectivity.record(snapshot.stateKey, via = via) {
        runtime.events.create(
            type = EventType.CONNECTIVITY_CHANGED,
            source = AndroidSources.NETWORK,
            start = at,
            payload = ConnectivityPayload(snapshot.kind, snapshot.metered, snapshot.validated, airplaneMode, snapshot.downstreamKbps),
            dedupKey = "net|${at.toEpochMilliseconds()}",
        )
    }

    /** AIRPLANE_MODE_CHANGED keyed `net|airplane|<eventMs>`; [network] is the default network at that moment. */
    public suspend fun recordAirplane(on: Boolean, network: NetworkKind, at: Instant, via: LiveWriter.Channel? = null): WriteResult? =
        airplane.record(if (on) "1" else "0", via = via) {
            runtime.events.create(
                type = EventType.AIRPLANE_MODE_CHANGED,
                source = AndroidSources.NETWORK,
                start = at,
                payload = ConnectivityPayload(network = network, airplaneMode = on),
                dedupKey = "net|airplane|${at.toEpochMilliseconds()}",
            )
        }

    public fun forget() {
        connectivity.forget()
        airplane.forget()
    }

    public companion object {
        public const val STREAM_CONNECTIVITY: String = "connectivity"
        public const val STREAM_AIRPLANE: String = "airplane"
    }
}

/** Bluetooth adapter state and ACL connections (device addresses only as salted hashes). */
public class BluetoothRecorder(private val runtime: CollectorRuntime) {
    private val adapter = StateStream(runtime, STREAM_ADAPTER, listOf(CapabilityIds.BLUETOOTH_ADAPTER_STATE))

    /** BLUETOOTH_STATE_CHANGED keyed `bt|adapter|<eventMs>`. */
    public suspend fun recordAdapter(on: Boolean, at: Instant, via: LiveWriter.Channel? = null): WriteResult? =
        adapter.record(if (on) "1" else "0", via = via) {
            runtime.events.create(
                type = EventType.BLUETOOTH_STATE_CHANGED,
                source = AndroidSources.BLUETOOTH,
                start = at,
                payload = BluetoothPayload(adapterOn = on),
                dedupKey = "bt|adapter|${at.toEpochMilliseconds()}",
            )
        }

    /** BLUETOOTH_CONNECTED / BLUETOOTH_DISCONNECTED keyed `bt|<connected|disconnected>|<deviceHash>|<eventMs>`. */
    public fun acl(connected: Boolean, deviceHash: String?, deviceClass: Int?, at: Instant): PersonalEvent = runtime.events.create(
        type = if (connected) EventType.BLUETOOTH_CONNECTED else EventType.BLUETOOTH_DISCONNECTED,
        source = AndroidSources.BLUETOOTH,
        start = at,
        payload = BluetoothPayload(deviceHash = deviceHash, deviceClass = deviceClass),
        dedupKey = "bt|${if (connected) "connected" else "disconnected"}|${deviceHash ?: "unknown"}|${at.toEpochMilliseconds()}",
    )

    /** Writes one ACL row at once (the manifest receiver has a few seconds of `goAsync`). */
    public suspend fun writeAcl(event: PersonalEvent): RunWrites {
        val epoch = runtime.writer.epochSafely() ?: return RunWrites(0, error = AppError.DatabaseError("writer_unavailable"))
        return runtime.writeChunked(listOf(CapabilityIds.BLUETOOTH_CONNECTED_DEVICES), epoch, listOf(event), cursor = null)
    }

    public fun forget(): Unit = adapter.forget()

    public companion object {
        public const val STREAM_ADAPTER: String = "bt_adapter"
    }
}

/** Ringer mode, music volume and output route; headset arrivals and departures. */
public class AudioRecorder(private val runtime: CollectorRuntime) {
    private val audio = StateStream(runtime, STREAM_AUDIO, listOf(CapabilityIds.AUDIO_VOLUME_RINGER, CapabilityIds.AUDIO_OUTPUT_DEVICES))

    /** AUDIO_STATE keyed `audio|state|<eventMs>` when ringer, volume or route changed. */
    public suspend fun recordState(snapshot: AudioSnapshot, at: Instant, via: LiveWriter.Channel? = null): WriteResult? =
        audio.record(snapshot.stateKey, via = via) {
            runtime.events.create(
                type = EventType.AUDIO_STATE,
                source = AndroidSources.AUDIO,
                start = at,
                payload = AudioStatePayload(snapshot.ringerMode, snapshot.musicVolumePercent, snapshot.outputRoute),
                dedupKey = "audio|state|${at.toEpochMilliseconds()}",
            )
        }

    /** HEADSET_CONNECTED / HEADSET_DISCONNECTED keyed `audio|headset_<connected|disconnected>|<route>|<eventMs>`. */
    public fun headset(connected: Boolean, route: String, at: Instant): PersonalEvent = runtime.events.create(
        type = if (connected) EventType.HEADSET_CONNECTED else EventType.HEADSET_DISCONNECTED,
        source = AndroidSources.AUDIO,
        start = at,
        payload = AudioStatePayload(outputRoute = route),
        dedupKey = "audio|headset_${if (connected) "connected" else "disconnected"}|$route|${at.toEpochMilliseconds()}",
    )

    public fun forget(): Unit = audio.forget()

    public companion object {
        public const val STREAM_AUDIO: String = "audio"
    }
}

/** DND filter, next alarm clock, Agentle's standby bucket and storage samples. */
public class DeviceRecorder(private val runtime: CollectorRuntime) {
    private val dnd = StateStream(runtime, STREAM_DND, listOf(CapabilityIds.DND_STATE))
    private val alarm = StateStream(runtime, STREAM_NEXT_ALARM, listOf(CapabilityIds.NEXT_ALARM_CLOCK))
    private val standby = StateStream(runtime, STREAM_STANDBY, listOf(CapabilityIds.APP_STANDBY_BUCKET))

    /** DND_CHANGED keyed `dnd|<eventMs>`. */
    public suspend fun recordDnd(filter: Int, at: Instant, via: LiveWriter.Channel? = null): WriteResult? =
        dnd.record(filter.toString(), via = via) {
            runtime.events.create(EventType.DND_CHANGED, AndroidSources.DEVICE, at, DndPayload(filter), "dnd|${at.toEpochMilliseconds()}")
        }

    /** NEXT_ALARM_CHANGED keyed `alarm|<eventMs>` (the alarm's app is never recorded). */
    public suspend fun recordNextAlarm(snapshot: NextAlarmSnapshot, at: Instant): WriteResult? =
        alarm.record(snapshot.triggerAtMs?.toString() ?: NONE) {
            runtime.events.create(
                EventType.NEXT_ALARM_CHANGED,
                AndroidSources.DEVICE,
                at,
                NextAlarmPayload(snapshot.triggerAtMs),
                "alarm|${at.toEpochMilliseconds()}",
            )
        }

    /** STANDBY_BUCKET_CHANGED for Agentle itself (package null), keyed `standby|<eventMs>`. */
    public suspend fun recordStandby(bucket: Int, at: Instant): WriteResult? = standby.record(bucket.toString()) {
        runtime.events.create(
            EventType.STANDBY_BUCKET_CHANGED,
            AndroidSources.DEVICE,
            at,
            StandbyBucketPayload(bucket),
            "standby|${at.toEpochMilliseconds()}",
        )
    }

    /** STORAGE_SAMPLE keyed `storage|sample|<5-min bucket>`. */
    public fun storage(snapshot: StorageSnapshot, at: Instant): PersonalEvent {
        val bucket = Buckets.floor(at.toEpochMilliseconds())
        return runtime.events.create(
            EventType.STORAGE_SAMPLE,
            AndroidSources.DEVICE,
            Instant.fromEpochMilliseconds(bucket),
            StoragePayload(snapshot.freeBytes, snapshot.totalBytes),
            "storage|sample|$bucket",
        )
    }

    public fun forget() {
        dnd.forget()
        alarm.forget()
        standby.forget()
    }

    public companion object {
        public const val STREAM_DND: String = "dnd"
        public const val STREAM_NEXT_ALARM: String = "next_alarm"
        public const val STREAM_STANDBY: String = "standby"
        private const val NONE = "none"
    }
}

/**
 * Time zone, locale and time-format changes (compared with the stored state, so a change made while Agentle was not
 * running is still found), wall-clock changes and boots. The zone is always the clock's zone (red team
 * testing-build-04), never the JVM default.
 */
public class SystemRecorder(private val runtime: CollectorRuntime) {
    private val zone = StateStream(runtime, STREAM_TIMEZONE, listOf(CapabilityIds.TIMEZONE_TIME_CHANGES))
    private val locale = StateStream(runtime, STREAM_LOCALE, listOf(CapabilityIds.LOCALE_TIME_FORMAT))

    /** TIMEZONE_CHANGED keyed `sys|tz|<eventMs>`; the first observation only stores the zone. */
    public suspend fun recordZone(at: Instant): WriteResult? {
        val current = runtime.clock.zone().id
        return zone.record(current, recordFirst = false) { previous ->
            runtime.events.create(
                EventType.TIMEZONE_CHANGED,
                AndroidSources.SYSTEM,
                at,
                SystemEventPayload(previous, current),
                "sys|tz|${at.toEpochMilliseconds()}",
            )
        }
    }

    /** LOCALE_CHANGED keyed `sys|locale|<eventMs>`; the state is the language tag plus the 12/24-hour setting. */
    public suspend fun recordLocale(tag: String, is24Hour: Boolean?, at: Instant): WriteResult? {
        val state = "$tag|${when (is24Hour) {
            true -> "24h"
            false -> "12h"
            null -> "?"
        }}"
        return locale.record(state, recordFirst = false) { previous ->
            runtime.events.create(
                EventType.LOCALE_CHANGED,
                AndroidSources.SYSTEM,
                at,
                SystemEventPayload(previous, state),
                "sys|locale|${at.toEpochMilliseconds()}",
            )
        }
    }

    /** TIME_CHANGED keyed `sys|time_set|<eventMs>` (the user or the network set the wall clock). */
    public fun timeSet(at: Instant): PersonalEvent =
        runtime.events.create(EventType.TIME_CHANGED, AndroidSources.SYSTEM, at, NoPayload, "sys|time_set|${at.toEpochMilliseconds()}")

    /** BOOT_COMPLETED keyed `sys|boot|<bootCount>`, at the boot time (now minus the time since boot). */
    public fun boot(bootCount: Int, bootAt: Instant): PersonalEvent =
        runtime.events.create(EventType.BOOT_COMPLETED, AndroidSources.SYSTEM, bootAt, NoPayload, "sys|boot|$bootCount")

    public fun forget() {
        zone.forget()
        locale.forget()
    }

    public companion object {
        public const val STREAM_TIMEZONE: String = "timezone"
        public const val STREAM_LOCALE: String = "locale"
    }
}

/** Confidence of rows that live receivers could not confirm with a live read (red team lifecycle-battery-07). */
internal const val HINT_CONFIDENCE: Double = 0.5
