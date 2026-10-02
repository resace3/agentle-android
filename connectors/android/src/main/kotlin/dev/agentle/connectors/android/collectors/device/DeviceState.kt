package dev.agentle.connectors.android.collectors.device

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.text.format.DateFormat
import androidx.core.content.ContextCompat
import dev.agentle.core.model.NetworkKind
import dev.agentle.core.model.PlugType

/** One `ACTION_BATTERY_CHANGED` reading. */
public data class BatterySnapshot(val levelPercent: Int, val plugType: PlugType, val charging: Boolean, val temperatureCelsius: Float?)

/** The default network as Agentle classifies it (no SSID, no BSSID: those need location, docs/research/01 §3.15). */
public data class NetworkSnapshot(val kind: NetworkKind, val metered: Boolean?, val validated: Boolean?, val downstreamKbps: Int?) {
    /** The coarse state that decides whether a CONNECTIVITY_CHANGED row is written (bandwidth changes are not changes). */
    val stateKey: String get() = "$kind|$metered|$validated"
}

public data class AudioSnapshot(val ringerMode: Int?, val musicVolumePercent: Int?, val outputRoute: String?) {
    val stateKey: String get() = "$ringerMode|$musicVolumePercent|$outputRoute"
}

/** The next alarm clock; [triggerAtMs] null means none is set. */
public data class NextAlarmSnapshot(val triggerAtMs: Long?)

public data class StorageSnapshot(val freeBytes: Long, val totalBytes: Long)

/** The power flags of POWER_STATE_CHANGED. */
public data class PowerSnapshot(val powerSaveMode: Boolean?, val deviceIdle: Boolean?, val thermalStatus: Int?) {
    val stateKey: String get() = "ps=$powerSaveMode;idle=$deviceIdle;thermal=$thermalStatus"
}

/**
 * Live device reads of the on-device collectors, with public APIs only and no permission beyond normal ones. Every read
 * is safe: one that throws (a missing service, a `SecurityException`, a Robolectric gap) returns null, which callers
 * treat as "unknown" (never as a value). Tests override single reads.
 */
@Suppress("TooManyFunctions")
public open class DeviceState(private val context: Context) {
    public open fun isInteractive(): Boolean? = read { context.getSystemService(PowerManager::class.java)?.isInteractive }

    public open fun isKeyguardLocked(): Boolean? = read { context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked }

    /** The sticky `ACTION_BATTERY_CHANGED` intent, parsed. */
    public open fun battery(): BatterySnapshot? = read {
        val intent = ContextCompat.registerReceiver(
            context,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        intent?.let(::parseBattery)
    }

    public open fun power(): PowerSnapshot? = read {
        val manager = context.getSystemService(PowerManager::class.java) ?: return@read null
        PowerSnapshot(manager.isPowerSaveMode, manager.isDeviceIdleMode, manager.currentThermalStatus)
    }

    public open fun network(): NetworkSnapshot? = read {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@read null
        val network = manager.activeNetwork ?: return@read NetworkSnapshot(NetworkKind.NONE, null, null, null)
        val capabilities = manager.getNetworkCapabilities(network) ?: return@read NetworkSnapshot(NetworkKind.NONE, null, null, null)
        snapshotOf(capabilities)
    }

    public open fun airplaneMode(): Boolean? = read {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) !=
            0
    }

    public open fun bluetoothEnabled(): Boolean? = read { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled }

    public open fun audio(): AudioSnapshot? = read {
        val manager = context.getSystemService(AudioManager::class.java) ?: return@read null
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volume = if (max > 0) manager.getStreamVolume(AudioManager.STREAM_MUSIC) * PERCENT / max else null
        AudioSnapshot(manager.ringerMode, volume, routeOf(manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }))
    }

    /** `NotificationManager.getCurrentInterruptionFilter()` (no permission needed to read it). */
    public open fun interruptionFilter(): Int? = read {
        context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
    }

    public open fun nextAlarm(): NextAlarmSnapshot? = read {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return@read null
        NextAlarmSnapshot(manager.nextAlarmClock?.triggerTime)
    }

    public open fun storage(): StorageSnapshot? = read {
        val stats = StatFs(Environment.getDataDirectory().path)
        StorageSnapshot(stats.availableBytes, stats.totalBytes)
    }

    /** Agentle's own standby bucket. */
    public open fun standbyBucket(): Int? = read { context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket }

    public open fun localeTag(): String? = read { context.resources.configuration.locales[0]?.toLanguageTag() }

    public open fun is24HourFormat(): Boolean? = read { DateFormat.is24HourFormat(context) }

    public open fun bootCount(): Int? = read {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf {
            it >=
                0
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> read(block: () -> T?): T? = try {
        block()
    } catch (ignored: RuntimeException) {
        null
    }

    public companion object {
        private const val PERCENT = 100
        private const val TENTHS = 10f

        /** `BatteryManager.BATTERY_PLUGGED_DOCK` (API 33). */
        private const val PLUGGED_DOCK = 8

        public fun parseBattery(intent: Intent): BatterySnapshot? {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return null
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            val plug = when (plugged) {
                0 -> PlugType.NONE
                BatteryManager.BATTERY_PLUGGED_AC -> PlugType.AC
                BatteryManager.BATTERY_PLUGGED_USB -> PlugType.USB
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> PlugType.WIRELESS
                PLUGGED_DOCK -> PlugType.DOCK
                else -> PlugType.UNKNOWN
            }
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || (status == BatteryManager.BATTERY_STATUS_FULL && plugged > 0)
            val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.let {
                it /
                    TENTHS
            }
            return BatterySnapshot(level * PERCENT / scale, plug, charging, temperature)
        }

        public fun snapshotOf(capabilities: NetworkCapabilities): NetworkSnapshot {
            val kind = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkKind.VPN
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.CELLULAR
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.ETHERNET
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> NetworkKind.BLUETOOTH
                else -> NetworkKind.OTHER
            }
            return NetworkSnapshot(
                kind = kind,
                metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                downstreamKbps = capabilities.linkDownstreamBandwidthKbps.takeIf { it > 0 },
            )
        }

        /** The most specific output route present: a headset wins over the built-in speaker. */
        public fun routeOf(types: List<Int>): String? = when {
            types.isEmpty() -> null
            types.any { it in WIRED } -> "wired"
            types.any { it in BLUETOOTH } -> "bluetooth"
            types.any { it == AudioDeviceInfo.TYPE_USB_HEADSET || it == AudioDeviceInfo.TYPE_USB_DEVICE } -> "usb"
            types.any { it == AudioDeviceInfo.TYPE_HEARING_AID } -> "hearing_aid"
            else -> "speaker"
        }

        /** Headset-like outputs whose arrival is HEADSET_CONNECTED. */
        public fun isHeadset(type: Int): Boolean =
            type in WIRED || type in BLUETOOTH || type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_HEARING_AID

        public fun routeName(type: Int): String = when {
            type in WIRED -> "wired"
            type in BLUETOOTH -> "bluetooth"
            type == AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
            type == AudioDeviceInfo.TYPE_HEARING_AID -> "hearing_aid"
            else -> "other"
        }

        private val WIRED = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)

        /** A2DP, SCO and (API 31) BLE headset/speaker, as literal values so minSdk 29 needs no inlined-API checks. */
        private val BLUETOOTH =
            setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER)
        private const val TYPE_BLE_HEADSET = 26
        private const val TYPE_BLE_SPEAKER = 27
    }
}
