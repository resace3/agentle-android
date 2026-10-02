package dev.agentle.connectors.android.collectors.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Build
import dev.agentle.connectors.android.core.AndroidConnector
import dev.agentle.connectors.android.core.AndroidConnectorIds
import dev.agentle.connectors.android.core.CollectOutcome
import dev.agentle.connectors.android.core.CollectorRuntime
import dev.agentle.connectors.android.core.CoverageIds
import dev.agentle.connectors.android.core.LiveWriter
import dev.agentle.connectors.android.core.RunTally
import dev.agentle.connectors.android.core.RunWrites
import dev.agentle.connectors.android.core.epochSafely
import dev.agentle.connectors.android.core.writeChunked
import dev.agentle.connectors.android.permissions.Permissions
import dev.agentle.connectors.android.permissions.PlatformState
import dev.agentle.connectors.api.CapabilityIds
import dev.agentle.connectors.api.CapabilityStatusProvider
import dev.agentle.connectors.api.SyncTrigger
import dev.agentle.core.common.AppError
import dev.agentle.core.model.CapabilityStatus
import dev.agentle.core.model.EventType
import dev.agentle.core.model.NetworkKind
import dev.agentle.core.model.PersonalEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/** True when [id] was evaluated and can collect. */
internal fun Map<String, CapabilityStatus>.collectable(id: String): Boolean = this[id]?.state?.canCollect == true

/**
 * True unless [id] was evaluated and cannot collect: for capabilities that need no permission, whose status may not be
 * evaluated yet in a process a broadcast just started.
 */
internal fun Map<String, CapabilityStatus>.notBlocked(id: String): Boolean = this[id]?.state?.canCollect ?: true

/** Writes [events] in one run of the serialized writer and counts them in [tally]; a lost write closes [coverageIds]. */
internal suspend fun CollectorRuntime.writeInto(tally: RunTally, coverageIds: List<String>, events: List<PersonalEvent>) {
    if (events.isEmpty()) return
    val epoch = writer.epochSafely()
    if (epoch == null) {
        tally.add(RunWrites(0, error = AppError.DatabaseError("writer_unavailable")))
        return
    }
    tally.add(writeChunked(coverageIds, epoch, events, cursor = null))
}

/**
 * Battery level, plug state, power save, device idle and thermal status (§6.4 "Battery, charging, power save, thermal"):
 * a BATTERY_SAMPLE only when the level enters another 5% bucket (red team lifecycle-battery-17) plus change-detected
 * transitions; [PowerRecorder] is shared with the live receivers, which write through the [LiveWriter].
 */
public class BatteryConnector(
    runtime: CollectorRuntime,
    permissions: CapabilityStatusProvider,
    private val device: DeviceState,
    public val recorder: PowerRecorder,
) : AndroidConnector(
    id = AndroidConnectorIds.BATTERY,
    name = "Battery and power",
    supportedEventTypes = setOf(
        EventType.BATTERY_SAMPLE,
        EventType.CHARGING_STARTED,
        EventType.CHARGING_STOPPED,
        EventType.POWER_STATE_CHANGED,
    ),
    capabilityIds = listOf(CapabilityIds.BATTERY_STATE, CapabilityIds.POWER_SAVE_IDLE_STATE, CapabilityIds.THERMAL_STATUS),
    runtime = runtime,
    permissions = permissions,
) {
    override val requiredCapabilityIds: List<String> = listOf(CapabilityIds.BATTERY_STATE)

    override val coverageIds: List<String> = CoverageIds.BATTERY

    override suspend fun collect(trigger: SyncTrigger, statuses: Map<String, CapabilityStatus>): CollectOutcome {
        val tally = RunTally()
        val now = runtime.clock.now()
        val power = device.power()?.let { snapshot ->
            snapshot.copy(thermalStatus = snapshot.thermalStatus.takeIf { statuses.collectable(CapabilityIds.THERMAL_STATUS) })
        }
        val battery = device.battery()
        if (battery == null) {
            tally.fail("battery_unreadable")
        } else {
            tally.fetched += 1
            tally.add(recorder.recordLevel(battery, power, now))
            tally.add(recorder.recordPlugged(battery, now, recordFirst = false))
        }
        if (power != null && statuses.collectable(CapabilityIds.POWER_SAVE_IDLE_STATE)) tally.add(recorder.recordPower(power, now))
        return tally.outcome()
    }
}

/** Default network, metered/validated flags and airplane mode (§6.4 "Connectivity, network type, Wi-Fi metadata"). */
public class NetworkConnector(
    runtime: CollectorRuntime,
    permissions: CapabilityStatusProvider,
    private val device: DeviceState,
    public val recorder: NetworkRecorder,
) : AndroidConnector(
    id = AndroidConnectorIds.NETWORK,
    name = "Connectivity",
    supportedEventTypes = setOf(EventType.CONNECTIVITY_CHANGED, EventType.AIRPLANE_MODE_CHANGED),
    capabilityIds = listOf(CapabilityIds.NETWORK_CONNECTIVITY, CapabilityIds.WIFI_CONNECTION_METADATA, CapabilityIds.AIRPLANE_MODE),
    runtime = runtime,
    permissions = permissions,
) {
    override val requiredCapabilityIds: List<String> = listOf(CapabilityIds.NETWORK_CONNECTIVITY)

    override val coverageIds: List<String> = CoverageIds.NETWORK

    override suspend fun collect(trigger: SyncTrigger, statuses: Map<String, CapabilityStatus>): CollectOutcome {
        val tally = RunTally()
        val now = runtime.clock.now()
        val network = device.network()?.let { snapshot ->
            snapshot.copy(downstreamKbps = snapshot.downstreamKbps.takeIf { statuses.collectable(CapabilityIds.WIFI_CONNECTION_METADATA) })
        }
        val airplane = device.airplaneMode()
        if (network == null) tally.fail("network_unreadable") else tally.add(recorder.recordNetwork(network, airplane, now))
        if (airplane != null && statuses.collectable(CapabilityIds.AIRPLANE_MODE)) {
            tally.add(recorder.recordAirplane(airplane, network?.kind ?: NetworkKind.OTHER, now))
        }
        tally.fetched = listOfNotNull(network, airplane).size
        return tally.outcome()
    }
}

/**
 * Bluetooth adapter state (sweeps and an exported runtime receiver) and ACL connections (an exported manifest receiver;
 * device addresses only as salted hashes, BLUETOOTH_CONNECT on API 31+).
 */
public class BluetoothConnector(
    runtime: CollectorRuntime,
    permissions: CapabilityStatusProvider,
    private val device: DeviceState,
    private val platform: PlatformState,
    public val recorder: BluetoothRecorder,
) : AndroidConnector(
    id = AndroidConnectorIds.BLUETOOTH,
    name = "Bluetooth",
    supportedEventTypes = setOf(EventType.BLUETOOTH_STATE_CHANGED, EventType.BLUETOOTH_CONNECTED, EventType.BLUETOOTH_DISCONNECTED),
    capabilityIds = listOf(CapabilityIds.BLUETOOTH_ADAPTER_STATE, CapabilityIds.BLUETOOTH_CONNECTED_DEVICES),
    runtime = runtime,
    permissions = permissions,
) {
    override val requiredCapabilityIds: List<String> = listOf(CapabilityIds.BLUETOOTH_ADAPTER_STATE)

    /** The batched live writer for ACL broadcasts (lifecycle-battery-17); null writes directly (tests). */
    public var live: LiveWriter.Channel? = null

    override val coverageIds: List<String> = CoverageIds.BLUETOOTH

    override suspend fun collect(trigger: SyncTrigger, statuses: Map<String, CapabilityStatus>): CollectOutcome {
        val tally = RunTally()
        val on = device.bluetoothEnabled()
        if (on == null) tally.fail("bluetooth_unreadable") else tally.add(recorder.recordAdapter(on, runtime.clock.now()))
        return tally.outcome()
    }

    /**
     * An ACL broadcast (a protected broadcast from the Bluetooth stack; its extras are still only hints). Written only
     * while the connector is enabled and `bluetooth_connected_devices` can collect (re-evaluated now: the process may
     * have been started for this broadcast); a "connected" is dropped when the adapter is off.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    public suspend fun onAcl(connected: Boolean, bluetoothDevice: BluetoothDevice?, at: Instant): Int {
        if (!isEnabled(runtime.settings.current())) return 0
        val statuses = try {
            permissions.refresh(listOf(CapabilityIds.BLUETOOTH_CONNECTED_DEVICES))
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            return 0
        }
        if (!statuses.collectable(CapabilityIds.BLUETOOTH_CONNECTED_DEVICES)) return 0
        if (connected && device.bluetoothEnabled() == false) return 0
        val address = readAddress(bluetoothDevice)
        val event = recorder.acl(connected, address?.let(runtime.hasher::shortHash), readDeviceClass(bluetoothDevice), at)
        val out = live ?: return recorder.writeAcl(event).written
        val accepted = out.submit(listOf(event))
        out.flush()
        return accepted
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked first; a SecurityException is caught.
    @Suppress("TooGenericExceptionCaught")
    private fun readAddress(bluetoothDevice: BluetoothDevice?): String? {
        if (bluetoothDevice == null || !connectGranted()) return null
        return try {
            bluetoothDevice.address
        } catch (ignored: RuntimeException) {
            null
        }
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked first; a SecurityException is caught.
    @Suppress("TooGenericExceptionCaught")
    private fun readDeviceClass(bluetoothDevice: BluetoothDevice?): Int? {
        if (bluetoothDevice == null || !connectGranted()) return null
        return try {
            bluetoothDevice.bluetoothClass?.majorDeviceClass
        } catch (ignored: RuntimeException) {
            null
        }
    }

    private fun connectGranted(): Boolean = platform.sdkInt < Build.VERSION_CODES.S || platform.isGranted(Permissions.BLUETOOTH_CONNECT)
}

/** Ringer mode, music volume and the output route (§6.4 "Audio"); headset changes come from the live source. */
public class AudioConnector(
    runtime: CollectorRuntime,
    permissions: CapabilityStatusProvider,
    private val device: DeviceState,
    public val recorder: AudioRecorder,
) : AndroidConnector(
    id = AndroidConnectorIds.AUDIO,
    name = "Audio",
    supportedEventTypes = setOf(EventType.AUDIO_STATE, EventType.HEADSET_CONNECTED, EventType.HEADSET_DISCONNECTED),
    capabilityIds = listOf(CapabilityIds.AUDIO_VOLUME_RINGER, CapabilityIds.AUDIO_OUTPUT_DEVICES),
    runtime = runtime,
    permissions = permissions,
) {
    override val requiredCapabilityIds: List<String> = listOf(CapabilityIds.AUDIO_VOLUME_RINGER)

    override val coverageIds: List<String> = CoverageIds.AUDIO

    override suspend fun collect(trigger: SyncTrigger, statuses: Map<String, CapabilityStatus>): CollectOutcome {
        val tally = RunTally()
        val audio = device.audio()?.let { snapshot ->
            snapshot.copy(outputRoute = snapshot.outputRoute.takeIf { statuses.collectable(CapabilityIds.AUDIO_OUTPUT_DEVICES) })
        }
        if (audio == null) tally.fail("audio_unreadable") else tally.add(recorder.recordState(audio, runtime.clock.now()))
        return tally.outcome()
    }
}

/** DND state, next alarm clock, Agentle's standby bucket and storage (§6.4 "DND state, next alarm, standby, storage"). */
public class DeviceStateConnector(
    runtime: CollectorRuntime,
    permissions: CapabilityStatusProvider,
    private val device: DeviceState,
    public val recorder: DeviceRecorder,
) : AndroidConnector(
    id = AndroidConnectorIds.DEVICE,
    name = "Device state",
    supportedEventTypes = setOf(
        EventType.DND_CHANGED,
        EventType.NEXT_ALARM_CHANGED,
        EventType.STANDBY_BUCKET_CHANGED,
        EventType.STORAGE_SAMPLE,
    ),
    capabilityIds = listOf(
        CapabilityIds.DND_STATE,
        CapabilityIds.NEXT_ALARM_CLOCK,
        CapabilityIds.APP_STANDBY_BUCKET,
        CapabilityIds.STORAGE_STATS,
    ),
    runtime = runtime,
    permissions = permissions,
) {
    /** Each part checks its own capability; none blocks the others. */
    override val requiredCapabilityIds: List<String> = emptyList()

    override val coverageIds: List<String> = CoverageIds.DEVICE

    override suspend fun collect(trigger: SyncTrigger, statuses: Map<String, CapabilityStatus>): CollectOutcome {
        val tally = RunTally()
        val now = runtime.clock.now()
        if (statuses.collectable(CapabilityIds.DND_STATE)) device.interruptionFilter()?.let { tally.add(recorder.recordDnd(it, now)) }
        if (statuses.collectable(CapabilityIds.NEXT_ALARM_CLOCK)) device.nextAlarm()?.let { tally.add(recorder.recordNextAlarm(it, now)) }
        if (statuses.collectable(CapabilityIds.APP_STANDBY_BUCKET)) {
            device.standbyBucket()?.let {
                tally.add(recorder.recordStandby(it, now))
            }
        }
        if (statuses.collectable(CapabilityIds.STORAGE_STATS)) {
            device.storage()?.let { runtime.writeInto(tally, listOf(CapabilityIds.STORAGE_STATS), listOf(recorder.storage(it, now))) }
        }
        return tally.outcome()
    }

    /** NEXT_ALARM_CLOCK_CHANGED (manifest receiver). */
    public suspend fun onNextAlarmChanged(at: Instant) {
        if (!allowed(CapabilityIds.NEXT_ALARM_CLOCK)) return
        device.nextAlarm()?.let { recorder.recordNextAlarm(it, at) }
    }

    /** The listener's interruption filter callback (and the DND live receiver). */
    public suspend fun onInterruptionFilter(filter: Int, at: Instant) {
        if (!allowed(CapabilityIds.DND_STATE)) return
        recorder.recordDnd(filter, at, via = live)
    }

    /** The batched live writer for listener callbacks (lifecycle-battery-17); null writes directly (tests). */
    public var live: LiveWriter.Channel? = null

    private suspend fun allowed(capabilityId: String): Boolean =
        isEnabled(runtime.settings.current()) && permissions.statuses.value.notBlocked(capabilityId)
}

/**
 * Time zone, locale and 12/24-hour changes, wall-clock changes and boots (§6.4 "Time zone / time / locale changes,
 * boot"). Sweeps compare with the stored state, so changes made while Agentle was not running are found too.
 */
public class SystemConnector(
    runtime: CollectorRuntime,
    permissions: CapabilityStatusProvider,
    private val device: DeviceState,
    private val platform: PlatformState,
    public val recorder: SystemRecorder,
) : AndroidConnector(
    id = AndroidConnectorIds.SYSTEM,
    name = "Time zone, locale and boot",
    supportedEventTypes = setOf(EventType.TIMEZONE_CHANGED, EventType.TIME_CHANGED, EventType.LOCALE_CHANGED, EventType.BOOT_COMPLETED),
    capabilityIds = listOf(CapabilityIds.TIMEZONE_TIME_CHANGES, CapabilityIds.LOCALE_TIME_FORMAT, CapabilityIds.BOOT_SHUTDOWN_EVENTS),
    runtime = runtime,
    permissions = permissions,
) {
    override val requiredCapabilityIds: List<String> = emptyList()

    /** Boot rows here are only a fallback without usage access; boot coverage belongs to the usage collector. */
    override val coverageIds: List<String> = CoverageIds.SYSTEM

    override suspend fun collect(trigger: SyncTrigger, statuses: Map<String, CapabilityStatus>): CollectOutcome {
        val tally = RunTally()
        val now = runtime.clock.now()
        if (statuses.collectable(CapabilityIds.TIMEZONE_TIME_CHANGES)) tally.add(recorder.recordZone(now))
        if (statuses.collectable(CapabilityIds.LOCALE_TIME_FORMAT)) {
            val tag = device.localeTag()
            if (tag == null) tally.fail("locale_unreadable") else tally.add(recorder.recordLocale(tag, device.is24HourFormat(), now))
        }
        return tally.outcome()
    }

    public suspend fun onZoneChanged(at: Instant) {
        if (allowed(CapabilityIds.TIMEZONE_TIME_CHANGES)) recorder.recordZone(at, via = live)
    }

    /** The batched live writer for broadcasts (lifecycle-battery-17); null writes directly (tests). */
    public var live: LiveWriter.Channel? = null

    public suspend fun onLocaleChanged(at: Instant) {
        if (!allowed(CapabilityIds.LOCALE_TIME_FORMAT)) return
        device.localeTag()?.let { recorder.recordLocale(it, device.is24HourFormat(), at, via = live) }
    }

    public suspend fun onTimeSet(at: Instant) {
        if (!allowed(CapabilityIds.TIMEZONE_TIME_CHANGES)) return
        val out = live
        if (out != null) {
            out.submit(listOf(recorder.timeSet(at)))
            return
        }
        runtime.writeInto(RunTally(), listOf(CapabilityIds.TIMEZONE_TIME_CHANGES), listOf(recorder.timeSet(at)))
    }

    /**
     * BOOT_COMPLETED: written here only without usage access; with it, usage events (DEVICE_STARTUP) are the truth and
     * the usage collector writes the boot.
     */
    public suspend fun onBoot(at: Instant) {
        if (!allowed(CapabilityIds.BOOT_SHUTDOWN_EVENTS) || platform.usageAccessGranted()) return
        val bootCount = device.bootCount() ?: return
        runtime.writeInto(
            RunTally(),
            listOf(CapabilityIds.BOOT_SHUTDOWN_EVENTS),
            listOf(
                recorder.boot(
                    bootCount,
                    at - runtime.clock.elapsed(),
                ),
            ),
        )
    }

    private suspend fun allowed(capabilityId: String): Boolean =
        isEnabled(runtime.settings.current()) && permissions.statuses.value.notBlocked(capabilityId)
}

/**
 * Live screen on/off and user-present rows (best effort while the process lives; usage events stay the truth). The
 * connector gates the live source; [sync] only writes what is batched.
 */
public class ScreenConnector(runtime: CollectorRuntime, permissions: CapabilityStatusProvider, private val flushLive: suspend () -> Unit) :
    AndroidConnector(
        id = AndroidConnectorIds.SCREEN,
        name = "Screen and unlock (live)",
        supportedEventTypes = setOf(EventType.SCREEN_ON, EventType.SCREEN_OFF, EventType.DEVICE_UNLOCK),
        capabilityIds = listOf(CapabilityIds.SCREEN_INTERACTIVE_EVENTS, CapabilityIds.UNLOCK_KEYGUARD_EVENTS),
        runtime = runtime,
        permissions = permissions,
    ) {
    /** Screen and keyguard coverage belongs to the usage collector (usage events are the truth); live rows are best effort. */
    override val requiredCapabilityIds: List<String> = listOf(CapabilityIds.SCREEN_INTERACTIVE_EVENTS)

    override suspend fun collect(trigger: SyncTrigger, statuses: Map<String, CapabilityStatus>): CollectOutcome {
        flushLive()
        return CollectOutcome.EMPTY
    }
}

/**
 * Call state (opt-in, off until the user enables it; READ_PHONE_STATE is never requested before that, red team
 * lifecycle-battery-19). Real time while the process lives; [sync] only writes what is batched.
 */
public class CallConnector(runtime: CollectorRuntime, permissions: CapabilityStatusProvider, private val flushLive: suspend () -> Unit) :
    AndroidConnector(
        id = AndroidConnectorIds.CALL,
        name = "Call state",
        supportedEventTypes = setOf(EventType.CALL_EVENT),
        capabilityIds = listOf(CapabilityIds.CALL_STATE),
        runtime = runtime,
        permissions = permissions,
    ) {
    override suspend fun collect(trigger: SyncTrigger, statuses: Map<String, CapabilityStatus>): CollectOutcome {
        flushLive()
        return CollectOutcome.EMPTY
    }
}
