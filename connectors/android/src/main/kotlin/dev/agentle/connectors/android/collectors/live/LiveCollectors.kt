package dev.agentle.connectors.android.collectors.live

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import dev.agentle.connectors.android.collectors.device.AudioRecorder
import dev.agentle.connectors.android.collectors.device.BluetoothRecorder
import dev.agentle.connectors.android.collectors.device.DeviceRecorder
import dev.agentle.connectors.android.collectors.device.DeviceState
import dev.agentle.connectors.android.collectors.device.NetworkRecorder
import dev.agentle.connectors.android.collectors.device.PowerRecorder
import dev.agentle.connectors.android.core.AndroidConnectorIds
import dev.agentle.connectors.android.core.AndroidSources
import dev.agentle.connectors.android.core.CollectorRuntime
import dev.agentle.connectors.android.core.CoverageIds
import dev.agentle.connectors.android.core.LiveWriter
import dev.agentle.connectors.android.core.RateLimit
import dev.agentle.connectors.android.core.LiveSource
import dev.agentle.connectors.android.core.RuntimeReceiver
import dev.agentle.connectors.android.permissions.Permissions
import dev.agentle.connectors.android.permissions.PlatformState
import dev.agentle.connectors.api.CapabilityIds
import dev.agentle.core.model.CallEventPayload
import dev.agentle.core.model.CallState
import dev.agentle.core.model.EventType
import dev.agentle.core.model.NetworkKind
import dev.agentle.core.model.NoPayload
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.PlugType
import dev.agentle.core.model.ScreenPayload
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Confidence of live rows a live read could not confirm (red team lifecycle-battery-07). */
private const val HINT_CONFIDENCE = 0.5

/*
 * Live sources record no coverage of their own except call state: the sweep connectors own the coverage of the other
 * capabilities ([CoverageIds]), and these rows are best effort while the process lives (red team lifecycle-battery-07).
 */

/**
 * Screen on/off and user present (docs/research/02 §6.11): runtime receivers registered with RECEIVER_EXPORTED (USER_PRESENT
 * comes from SystemUI; all three are protected broadcasts). Each broadcast is confirmed with a live read
 * (`isInteractive`, `isKeyguardLocked`) and written to `android.screen_live`; an unconfirmed one becomes a hint row in
 * `android.screen_hint` with confidence 0.5. The receipt time is the event time. Usage events stay the truth
 * (`android.screen`); these rows are best effort while the process lives.
 */
public class ScreenLiveSource(private val runtime: CollectorRuntime, private val device: DeviceState, live: LiveWriter) : LiveSource {
    override val sourceId: String = SOURCE_ID
    override val connectorId: String = AndroidConnectorIds.SCREEN
    override val requiredCapabilityIds: List<String> = listOf(CapabilityIds.SCREEN_INTERACTIVE_EVENTS)

    private val buffer = live.channel(SOURCE_ID, RateLimit(burst = 30, perHour = 120))
    public val receiver: RuntimeReceiver =
        RuntimeReceiver(runtime.context, ACTIONS, exported = true, clock = runtime.clock) { action, _, at ->
            onAction(action, at)
        }

    override fun start(): Boolean = receiver.register()

    override fun stop(): Unit = receiver.unregister()

    override suspend fun flush() {
        buffer.flush()
    }

    override suspend fun discard(): Unit = buffer.discard()

    public fun onAction(action: String, at: Instant) {
        val event = eventFor(action, at) ?: return
        runtime.scope.launch { buffer.submit(listOf(event)) }
    }

    /** The row for [action] received at [at], confirmed or a hint; null for actions outside the allow-list. */
    public fun eventFor(action: String, at: Instant): PersonalEvent? {
        val type: EventType
        val name: String
        val confirmed: Boolean
        when (action) {
            Intent.ACTION_SCREEN_ON -> {
                type = EventType.SCREEN_ON
                name = "on"
                confirmed = device.isInteractive() == true
            }

            Intent.ACTION_SCREEN_OFF -> {
                type = EventType.SCREEN_OFF
                name = "off"
                confirmed = device.isInteractive() == false
            }

            Intent.ACTION_USER_PRESENT -> {
                type = EventType.DEVICE_UNLOCK
                name = "present"
                confirmed = device.isKeyguardLocked() == false
            }

            else -> return null
        }
        val payload = when (type) {
            EventType.SCREEN_ON -> ScreenPayload(interactive = true)
            EventType.SCREEN_OFF -> ScreenPayload(interactive = false)
            else -> NoPayload
        }
        val prefix = if (confirmed) "screen_live" else "screen_hint"
        return runtime.events.create(
            type = type,
            source = if (confirmed) AndroidSources.SCREEN_LIVE else AndroidSources.SCREEN_HINT,
            start = at,
            payload = payload,
            dedupKey = "$prefix|$name|${at.toEpochMilliseconds()}",
            confidence = if (confirmed) null else HINT_CONFIDENCE,
        )
    }

    public companion object {
        public const val SOURCE_ID: String = "android.screen_live"
        public val ACTIONS: Set<String> = setOf(Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT)
    }
}

/**
 * Power connected/disconnected, power save and device idle (protected broadcasts from system_server, so the runtime
 * receiver is not exported) and the thermal status listener. A plug broadcast is confirmed with the sticky battery
 * intent and recorded as a transition (`battery|charging_started|<eventMs>`); an unconfirmed one is a hint row.
 */
public class PowerLiveSource(
    private val runtime: CollectorRuntime,
    private val device: DeviceState,
    private val recorder: PowerRecorder,
    live: LiveWriter,
) : LiveSource {
    override val sourceId: String = SOURCE_ID
    override val connectorId: String = AndroidConnectorIds.BATTERY
    override val requiredCapabilityIds: List<String> = listOf(CapabilityIds.BATTERY_STATE)

    private val buffer = live.channel(SOURCE_ID)
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    public val receiver: RuntimeReceiver =
        RuntimeReceiver(runtime.context, ACTIONS, exported = false, clock = runtime.clock) { action, _, at ->
            onAction(action, at)
        }

    override fun start(): Boolean {
        if (!receiver.register()) return false
        registerThermal()
        return true
    }

    @Suppress("TooGenericExceptionCaught")
    override fun stop() {
        receiver.unregister()
        val listener = thermalListener ?: return
        thermalListener = null
        try {
            runtime.context.getSystemService(PowerManager::class.java)?.removeThermalStatusListener(listener)
        } catch (ignored: RuntimeException) {
            // Already removed.
        }
    }

    override suspend fun flush() {
        buffer.flush()
    }

    override suspend fun discard(): Unit = buffer.discard()

    public fun onAction(action: String, at: Instant) {
        when (action) {
            Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> {
                val connected = action == Intent.ACTION_POWER_CONNECTED
                val snapshot = device.battery()
                val confirmed = snapshot != null && (snapshot.plugType != PlugType.NONE) == connected
                runtime.scope.launch {
                    if (confirmed && snapshot != null) {
                        recorder.recordPlugged(snapshot, at, recordFirst = true, via = buffer)
                    } else {
                        buffer.submit(listOf(recorder.hint(connected, snapshot, at)))
                    }
                }
            }

            Intent.ACTION_BATTERY_LOW, Intent.ACTION_BATTERY_OKAY -> {
                val snapshot = device.battery() ?: return
                runtime.scope.launch { recorder.recordLevel(snapshot, device.power(), at, via = buffer) }
            }

            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED, PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> recordPower(at)

            else -> Unit
        }
    }

    private fun recordPower(at: Instant) {
        val snapshot = device.power() ?: return
        runtime.scope.launch { recorder.recordPower(snapshot, at, via = buffer) }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun registerThermal() {
        val manager = runtime.context.getSystemService(PowerManager::class.java) ?: return
        val listener = PowerManager.OnThermalStatusChangedListener { recordPower(runtime.clock.now()) }
        try {
            manager.addThermalStatusListener(runtime.context.mainExecutor, listener)
            thermalListener = listener
        } catch (ignored: RuntimeException) {
            // No thermal service: power save and idle are still recorded.
        }
    }

    public companion object {
        public const val SOURCE_ID: String = "android.battery_live"
        public val ACTIONS: Set<String> = setOf(
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            Intent.ACTION_BATTERY_LOW,
            Intent.ACTION_BATTERY_OKAY,
            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED,
            PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED,
        )
    }
}

/**
 * The default network through `ConnectivityManager.NetworkCallback` while the process lives (§6.4), plus airplane mode
 * (a protected system broadcast, so not exported). Callbacks come in bursts; one live read follows a short quiet period.
 */
public class NetworkLiveSource(
    private val runtime: CollectorRuntime,
    private val device: DeviceState,
    private val recorder: NetworkRecorder,
    live: LiveWriter,
) : LiveSource {
    private val out = live.channel(SOURCE_ID)
    override val sourceId: String = SOURCE_ID
    override val connectorId: String = AndroidConnectorIds.NETWORK
    override val requiredCapabilityIds: List<String> = listOf(CapabilityIds.NETWORK_CONNECTIVITY)

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var pending: Job? = null
    public val airplaneReceiver: RuntimeReceiver =
        RuntimeReceiver(runtime.context, AIRPLANE_ACTIONS, exported = false, clock = runtime.clock) { _, _, at -> onAirplane(at) }

    @Suppress("TooGenericExceptionCaught")
    override fun start(): Boolean {
        val manager = runtime.context.getSystemService(ConnectivityManager::class.java) ?: return false
        val created = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange(runtime.clock.now())

            override fun onLost(network: Network) = onChange(runtime.clock.now())

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = onChange(runtime.clock.now())
        }
        try {
            manager.registerDefaultNetworkCallback(created)
        } catch (ignored: RuntimeException) {
            return false
        }
        callback = created
        airplaneReceiver.register()
        return true
    }

    @Suppress("TooGenericExceptionCaught")
    override fun stop() {
        airplaneReceiver.unregister()
        synchronized(this) {
            pending?.cancel()
            pending = null
        }
        val registered = callback ?: return
        callback = null
        try {
            runtime.context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(registered)
        } catch (ignored: RuntimeException) {
            // Already unregistered.
        }
    }

    /** A callback fired at [at]; the state is read once the burst settled. */
    public fun onChange(at: Instant) {
        synchronized(this) {
            pending?.cancel()
            pending = runtime.scope.launch {
                delay(SETTLE)
                record(at)
            }
        }
    }

    public suspend fun record(at: Instant) {
        val snapshot = device.network() ?: return
        recorder.recordNetwork(snapshot, device.airplaneMode(), at, via = out)
    }

    private fun onAirplane(at: Instant) {
        val on = device.airplaneMode() ?: return
        runtime.scope.launch { recorder.recordAirplane(on, device.network()?.kind ?: NetworkKind.OTHER, at, via = out) }
    }

    public companion object {
        public const val SOURCE_ID: String = "android.network_live"
        public val AIRPLANE_ACTIONS: Set<String> = setOf(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        /** Connectivity transitions are recorded once the network settled for 30 s (red team lifecycle-battery-17). */
        public val SETTLE: Duration = 30.seconds
    }
}

/**
 * Bluetooth adapter on/off (docs/research/02 §6.11: sent by the Bluetooth UID, so registered with RECEIVER_EXPORTED;
 * protected, so it cannot be spoofed). The extras are hints: the state is read from the adapter.
 */
public class BluetoothLiveSource(
    private val runtime: CollectorRuntime,
    private val device: DeviceState,
    private val recorder: BluetoothRecorder,
    live: LiveWriter,
) : LiveSource {
    private val out = live.channel(SOURCE_ID)
    override val sourceId: String = SOURCE_ID
    override val connectorId: String = AndroidConnectorIds.BLUETOOTH
    override val requiredCapabilityIds: List<String> = listOf(CapabilityIds.BLUETOOTH_ADAPTER_STATE)

    public val receiver: RuntimeReceiver = RuntimeReceiver(runtime.context, ACTIONS, exported = true, clock = runtime.clock) { _, _, at ->
        onStateChanged(at)
    }

    override fun start(): Boolean = receiver.register()

    override fun stop(): Unit = receiver.unregister()

    public fun onStateChanged(at: Instant) {
        val on = device.bluetoothEnabled() ?: return
        runtime.scope.launch { recorder.recordAdapter(on, at, via = out) }
    }

    public companion object {
        public const val SOURCE_ID: String = "android.bluetooth_live"
        public val ACTIONS: Set<String> = setOf(BluetoothAdapter.ACTION_STATE_CHANGED)
    }
}

/**
 * Output devices through `AudioDeviceCallback` (headset arrivals and departures; devices present at registration are the
 * baseline, not arrivals) and ringer mode changes (a protected system broadcast, so not exported).
 */
public class AudioLiveSource(
    private val runtime: CollectorRuntime,
    private val device: DeviceState,
    private val recorder: AudioRecorder,
    live: LiveWriter,
) : LiveSource {
    override val sourceId: String = SOURCE_ID
    override val connectorId: String = AndroidConnectorIds.AUDIO
    override val requiredCapabilityIds: List<String> = listOf(CapabilityIds.AUDIO_VOLUME_RINGER)

    private val buffer = live.channel(SOURCE_ID)
    private val known = HashMap<Int, Int>()
    private var callback: AudioDeviceCallback? = null
    public val ringerReceiver: RuntimeReceiver =
        RuntimeReceiver(runtime.context, ACTIONS, exported = false, clock = runtime.clock) { _, _, at ->
            recordState(at)
        }

    @Suppress("TooGenericExceptionCaught")
    override fun start(): Boolean {
        val manager = runtime.context.getSystemService(AudioManager::class.java) ?: return false
        val baseline = try {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.id to it.type }
        } catch (ignored: RuntimeException) {
            emptyList()
        }
        synchronized(known) {
            known.clear()
            baseline.forEach { (id, type) -> known[id] = type }
        }
        val created = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) =
                onDevices(addedDevices.orEmpty().filter { it.isSink }.map { it.id to it.type }, connected = true, at = runtime.clock.now())

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onDevices(
                removedDevices.orEmpty().filter {
                    it.isSink
                }.map { it.id to it.type },
                connected = false,
                at = runtime.clock.now(),
            )
        }
        try {
            manager.registerAudioDeviceCallback(created, null)
        } catch (ignored: RuntimeException) {
            return false
        }
        callback = created
        ringerReceiver.register()
        return true
    }

    @Suppress("TooGenericExceptionCaught")
    override fun stop() {
        ringerReceiver.unregister()
        val registered = callback ?: return
        callback = null
        try {
            runtime.context.getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(registered)
        } catch (ignored: RuntimeException) {
            // Already unregistered.
        }
    }

    override suspend fun flush() {
        buffer.flush()
    }

    override suspend fun discard(): Unit = buffer.discard()

    /** Output devices (id to type) that were added or removed at [at]; ids already known are not arrivals. */
    public fun onDevices(devices: List<Pair<Int, Int>>, connected: Boolean, at: Instant) {
        val changed = synchronized(known) {
            devices.filter { (id, type) -> if (connected) known.put(id, type) == null else known.remove(id) != null }
        }
        if (changed.isEmpty()) return
        val events = changed.filter {
            DeviceState.isHeadset(it.second)
        }.map { recorder.headset(connected, DeviceState.routeName(it.second), at) }
        runtime.scope.launch {
            if (events.isNotEmpty()) buffer.submit(events)
            device.audio()?.let { recorder.recordState(it, at, via = buffer) }
        }
    }

    private fun recordState(at: Instant) {
        val snapshot = device.audio() ?: return
        runtime.scope.launch { recorder.recordState(snapshot, at, via = buffer) }
    }

    public companion object {
        public const val SOURCE_ID: String = "android.audio_live"
        public val ACTIONS: Set<String> = setOf(AudioManager.RINGER_MODE_CHANGED_ACTION)
    }
}

/**
 * DND changes through `ACTION_INTERRUPTION_FILTER_CHANGED` (registered receivers only; a protected system broadcast, so
 * not exported). The notification listener reports the same changes while it is bound.
 */
@Suppress("UnusedPrivateProperty") // device is read in the receiver lambda (a detekt false positive).
public class DndLiveSource(
    private val runtime: CollectorRuntime,
    private val device: DeviceState,
    private val recorder: DeviceRecorder,
    live: LiveWriter,
) : LiveSource {
    private val out = live.channel(SOURCE_ID)
    override val sourceId: String = SOURCE_ID
    override val connectorId: String = AndroidConnectorIds.DEVICE
    override val requiredCapabilityIds: List<String> = listOf(CapabilityIds.DND_STATE)

    public val receiver: RuntimeReceiver = RuntimeReceiver(runtime.context, ACTIONS, exported = false, clock = runtime.clock) { _, _, at ->
        device.interruptionFilter()?.let { onFilter(it, at) }
    }

    override fun start(): Boolean = receiver.register()

    override fun stop(): Unit = receiver.unregister()

    public fun onFilter(filter: Int, at: Instant) {
        runtime.scope.launch { recorder.recordDnd(filter, at, via = out) }
    }

    public companion object {
        public const val SOURCE_ID: String = "android.dnd_live"
        public val ACTIONS: Set<String> = setOf(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
    }
}

/**
 * Call state while the process lives (opt-in `android.call`): `TelephonyCallback.CallStateListener` on API 31+ (needs
 * READ_PHONE_STATE, checked first), `PhoneStateListener(Executor)` with LISTEN_CALL_STATE on API 29-30 (no permission;
 * the phone number argument is never read). The first callback is the current state (baseline, not a row); every later
 * change is a CALL_EVENT keyed `call|<state>|<eventMs>`.
 */
public class CallStateLiveSource(private val runtime: CollectorRuntime, private val platform: PlatformState, live: LiveWriter) : LiveSource {
    override val sourceId: String = SOURCE_ID
    override val connectorId: String = AndroidConnectorIds.CALL
    override val requiredCapabilityIds: List<String> = listOf(CapabilityIds.CALL_STATE)

    /** The only source of call state, so it owns `call_state` coverage: open while the callback is registered. */
    override val coverageIds: List<String> = CoverageIds.CALL

    private val buffer = live.channel(SOURCE_ID, RateLimit(burst = 10, perHour = 30), coverageIds)
    private var unregister: (() -> Unit)? = null

    @Volatile private var last: Int? = null

    override fun start(): Boolean {
        val manager = runtime.context.getSystemService(TelephonyManager::class.java) ?: return false
        last = null
        return try {
            unregister = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!platform.isGranted(Permissions.READ_PHONE_STATE)) return false
                CallStateApi31.register(manager, runtime.context.mainExecutor) { onState(it, runtime.clock.now()) }
            } else {
                LegacyCallState.register(manager, runtime.context.mainExecutor) { onState(it, runtime.clock.now()) }
            }
            true
        } catch (ignored: SecurityException) {
            false
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun stop() {
        val registered = unregister ?: return
        unregister = null
        try {
            registered()
        } catch (ignored: RuntimeException) {
            // Already unregistered.
        }
    }

    override suspend fun flush() {
        buffer.flush()
    }

    override suspend fun discard(): Unit = buffer.discard()

    public fun onState(state: Int, at: Instant) {
        val previous = last
        last = state
        if (previous == null || previous == state) return
        val kind = when (state) {
            TelephonyManager.CALL_STATE_IDLE -> CallState.IDLE
            TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> CallState.OFFHOOK
            else -> return
        }
        val event = runtime.events.create(
            type = EventType.CALL_EVENT,
            source = AndroidSources.CALL,
            start = at,
            payload = CallEventPayload(kind),
            dedupKey = "call|${kind.name.lowercase()}|${at.toEpochMilliseconds()}",
        )
        runtime.scope.launch { buffer.submit(listOf(event)) }
    }

    public companion object {
        public const val SOURCE_ID: String = "android.call_live"
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private object CallStateApi31 {
    @SuppressLint("MissingPermission") // READ_PHONE_STATE is checked by the caller; a SecurityException is caught there.
    fun register(manager: TelephonyManager, executor: Executor, onState: (Int) -> Unit): () -> Unit {
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) = onState(state)
        }
        manager.registerTelephonyCallback(executor, callback)
        return { manager.unregisterTelephonyCallback(callback) }
    }
}

@Suppress("DEPRECATION")
private object LegacyCallState {
    @SuppressLint("MissingPermission") // LISTEN_CALL_STATE needs no permission below API 31.
    fun register(manager: TelephonyManager, executor: Executor, onState: (Int) -> Unit): () -> Unit {
        val listener = object : PhoneStateListener(executor) {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) = onState(state)
        }
        manager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        return { manager.listen(listener, PhoneStateListener.LISTEN_NONE) }
    }
}
