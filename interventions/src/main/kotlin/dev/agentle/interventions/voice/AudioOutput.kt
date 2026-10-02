package dev.agentle.interventions.voice

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Attributes of every intervention playback: `USAGE_MEDIA` (not `USAGE_ASSISTANT`, which Android 17 routes to its own
 * volume stream) and `CONTENT_TYPE_SPEECH` (R09 §2.6).
 */
val SPEECH_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

/** Where media audio goes now (R09 §2.7). */
enum class OutputRoute {
    BUILT_IN_SPEAKER,
    WIRED,
    BLUETOOTH_CLASSIC,
    BLUETOOTH_LE,
    BLUETOOTH_SPEAKER,
    BROADCAST,
    HEARING_AID,
    USB,
    OTHER,
    UNKNOWN,
    ;

    /** Not heard by bystanders ("only on headphones or Bluetooth audio"); advisory: an A2DP car kit counts as private. */
    val isPrivate: Boolean get() = this in PRIVATE

    private companion object {
        val PRIVATE = setOf(WIRED, BLUETOOTH_CLASSIC, BLUETOOTH_LE, HEARING_AID, USB)
    }
}

/** The media stream right now: its route and whether it is muted (muted or volume 0). */
data class OutputState(val route: OutputRoute, val muted: Boolean) {
    val isPrivate: Boolean get() = route.isPrivate
}

/** Reads the media route and mute state. Reading needs no permission; Agentle never changes volume or routes. */
class AudioOutputInspector(private val audioManager: AudioManager) {
    fun current(attributes: AudioAttributes = SPEECH_ATTRIBUTES): OutputState {
        val stream = attributes.volumeControlStream
        val muted = audioManager.isStreamMute(stream) || audioManager.getStreamVolume(stream) <= 0
        return OutputState(route(attributes), muted)
    }

    private fun route(attributes: AudioAttributes): OutputRoute {
        val devices: List<AudioDeviceInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // "the devices anticipated to play sound from an AudioTrack created with the specified AudioAttributes"
            audioManager.getAudioDevicesForAttributes(attributes)
        } else {
            // Before 33 only connected outputs are known: any external sink is expected to take media (UNVERIFIED, U5).
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).sortedBy { priority(classify(it.type)) }.take(1)
        }
        return devices.firstOrNull()?.let { classify(it.type) } ?: OutputRoute.UNKNOWN
    }

    private fun priority(route: OutputRoute): Int = when (route) {
        OutputRoute.BUILT_IN_SPEAKER -> 1
        OutputRoute.OTHER, OutputRoute.UNKNOWN -> 2
        else -> 0
    }

    companion object {
        /**
         * Device type to route. `TYPE_BLE_HEARING_AID` (API 37; an int constant inlined at compile time) is a hearing aid:
         * private, like the classic one, so "headphones only" does not skip speech for hearing-aid users.
         */
        fun classify(type: Int): OutputRoute = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> OutputRoute.BUILT_IN_SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> OutputRoute.WIRED
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> OutputRoute.BLUETOOTH_CLASSIC
            AudioDeviceInfo.TYPE_BLE_HEADSET -> OutputRoute.BLUETOOTH_LE
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> OutputRoute.BLUETOOTH_SPEAKER
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> OutputRoute.BROADCAST
            AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_BLE_HEARING_AID -> OutputRoute.HEARING_AID
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> OutputRoute.USB
            else -> OutputRoute.OTHER
        }
    }
}
