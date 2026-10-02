package dev.agentle.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed event payloads. Serialized with a `kind` discriminator (see [EventCodec]); every class has a stable
 * `@SerialName`. Fields are only added (with defaults), never removed or retyped; payloads that a newer app version
 * wrote and this version does not know decode to [UnknownPayload] and are preserved.
 *
 * Personal text (notification text, calendar titles, notes) lives only in fields marked "personal text" below.
 * Such fields are never logged and never sent to AI unless the matching content category is enabled.
 */
@Serializable
public sealed interface EventPayload

@Serializable
@SerialName("none")
public data object NoPayload : EventPayload

@Serializable
@SerialName("app_usage")
public data class AppUsagePayload(
    val packageName: String,
    /** Duration of a foreground session, for [EventType.APP_SESSION]. */
    val durationMs: Long? = null,
    /** Play-store style app category if declared (ApplicationInfo.category), e.g. "social". */
    val appCategory: String? = null,
) : EventPayload

@Serializable
@SerialName("screen")
public data class ScreenPayload(val interactive: Boolean, val durationMs: Long? = null) : EventPayload

@Serializable
@SerialName("notification")
public data class NotificationPayload(
    val packageName: String,
    val category: String? = null,
    /** SHA-256 of the channel id; channel ids can embed personal names. */
    val channelHash: String? = null,
    val ongoing: Boolean = false,
    val groupSummary: Boolean = false,
    /** Whether the notification carried text at all (known even when content capture is off). */
    val hasText: Boolean = false,
    /** Personal text: only captured when notification content capture is enabled for this app. */
    val title: String? = null,
    /** Personal text: only captured when notification content capture is enabled for this app. */
    val text: String? = null,
    /** NotificationListenerService REASON_* for removals. */
    val removalReason: Int? = null,
) : EventPayload

@Serializable
public enum class PlaceClass { HOME, WORK, GYM, OTHER, UNKNOWN }

@Serializable
@SerialName("location_sample")
public data class LocationSamplePayload(
    /** Latitude/longitude rounded to the precision the user chose (default 3 decimals, about 100 m). */
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val placeClass: PlaceClass = PlaceClass.UNKNOWN,
) : EventPayload

@Serializable
@SerialName("location_visit")
public data class LocationVisitPayload(val placeId: String, val placeClass: PlaceClass, val durationMs: Long) : EventPayload

@Serializable
@SerialName("steps")
public data class StepsPayload(val count: Long) : EventPayload

@Serializable
@SerialName("distance")
public data class DistancePayload(val meters: Double) : EventPayload

@Serializable
@SerialName("floors")
public data class FloorsPayload(val floors: Double) : EventPayload

@Serializable
@SerialName("calories")
public data class CaloriesPayload(val kilocalories: Double) : EventPayload

@Serializable
public enum class ActivityKind { STILL, WALKING, RUNNING, ON_BICYCLE, IN_VEHICLE, ON_FOOT, TILTING, UNKNOWN }

@Serializable
public enum class TransitionKind { ENTER, EXIT }

@Serializable
@SerialName("activity_transition")
public data class ActivityTransitionPayload(val activity: ActivityKind, val transition: TransitionKind) : EventPayload

@Serializable
@SerialName("exercise")
public data class ExercisePayload(
    val exerciseType: String,
    val durationMs: Long,
    val distanceMeters: Double? = null,
    val kilocalories: Double? = null,
    val averageHeartRate: Double? = null,
    val steps: Long? = null,
) : EventPayload

@Serializable
@SerialName("heart_rate")
public data class HeartRatePayload(val bpm: Double) : EventPayload

@Serializable
public enum class SleepStageKind { AWAKE, LIGHT, DEEP, REM, ASLEEP_UNSPECIFIED, OUT_OF_BED, RESTLESS, UNKNOWN }

@Serializable
public data class SleepStage(val stage: SleepStageKind, val startEpochMs: Long, val endEpochMs: Long)

@Serializable
@SerialName("sleep_session")
public data class SleepSessionPayload(
    val stages: List<SleepStage> = emptyList(),
    /** Minutes asleep as reported upstream, when available (otherwise derived from stages). */
    val minutesAsleep: Long? = null,
    val minutesAwake: Long? = null,
    val isMainSleep: Boolean = true,
) : EventPayload

@Serializable
@SerialName("weight")
public data class WeightPayload(val kilograms: Double) : EventPayload

@Serializable
@SerialName("body_fat")
public data class BodyFatPayload(val percent: Double) : EventPayload

@Serializable
@SerialName("wearable_device")
public data class WearableDevicePayload(
    val deviceIdHash: String,
    val model: String? = null,
    val batteryPercent: Int? = null,
    val lastSyncEpochMs: Long? = null,
) : EventPayload

@Serializable
public enum class PlugType { NONE, AC, USB, WIRELESS, DOCK, UNKNOWN }

@Serializable
@SerialName("battery")
public data class BatteryPayload(
    val levelPercent: Int,
    val plugType: PlugType = PlugType.UNKNOWN,
    val charging: Boolean = false,
    val temperatureCelsius: Float? = null,
    val powerSaveMode: Boolean? = null,
) : EventPayload

@Serializable
@SerialName("power_state")
public data class PowerStatePayload(val powerSaveMode: Boolean? = null, val deviceIdle: Boolean? = null, val thermalStatus: Int? = null) :
    EventPayload

@Serializable
public enum class NetworkKind { NONE, WIFI, CELLULAR, ETHERNET, VPN, BLUETOOTH, OTHER }

@Serializable
@SerialName("connectivity")
public data class ConnectivityPayload(
    val network: NetworkKind,
    val metered: Boolean? = null,
    val validated: Boolean? = null,
    val airplaneMode: Boolean? = null,
    val downstreamKbps: Int? = null,
) : EventPayload

@Serializable
@SerialName("bluetooth")
public data class BluetoothPayload(
    val adapterOn: Boolean? = null,
    /** SHA-256 of the device address with a per-install salt; raw addresses are never stored. */
    val deviceHash: String? = null,
    val deviceClass: Int? = null,
) : EventPayload

@Serializable
@SerialName("audio")
public data class AudioStatePayload(val ringerMode: Int? = null, val musicVolumePercent: Int? = null, val outputRoute: String? = null) :
    EventPayload

@Serializable
@SerialName("dnd")
public data class DndPayload(val interruptionFilter: Int) : EventPayload

@Serializable
@SerialName("system")
public data class SystemEventPayload(val oldValue: String? = null, val newValue: String? = null) : EventPayload

@Serializable
@SerialName("calendar_event")
public data class CalendarEventPayload(
    val eventIdHash: String,
    val allDay: Boolean,
    val busy: Boolean = true,
    val attendeeCount: Int? = null,
    /** Personal text: only stored when calendar titles are enabled. */
    val title: String? = null,
) : EventPayload

@Serializable
public enum class CallState { IDLE, RINGING, OFFHOOK }

@Serializable
@SerialName("call")
public data class CallEventPayload(val state: CallState) : EventPayload

@Serializable
@SerialName("media_created")
public data class MediaCreatedPayload(
    val mediaType: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val durationMs: Long? = null,
) : EventPayload

@Serializable
public enum class UserLogKind { MOOD, ENERGY, STRESS, NOTE, CUSTOM }

@Serializable
@SerialName("user_log")
public data class UserLogPayload(
    val logKind: UserLogKind,
    /** Numeric value, e.g. mood 1-5. */
    val value: Double? = null,
    /** Personal text. */
    val note: String? = null,
    val label: String? = null,
) : EventPayload

@Serializable
@SerialName("jitai")
public data class JitaiEventPayload(
    val jitaiId: String,
    val decisionKey: String,
    val channel: String? = null,
    val action: String? = null,
) : EventPayload

@Serializable
@SerialName("insight")
public data class InsightEventPayload(val insightId: String, val kind: String) : EventPayload

@Serializable
@SerialName("generated_media")
public data class GeneratedMediaPayload(val artifactId: String, val mimeType: String, val sizeBytes: Long, val method: String) :
    EventPayload

/** A payload written by a newer schema that this version cannot decode. Preserved verbatim. */
@Serializable
@SerialName("unknown")
public data class UnknownPayload(val originalKind: String, val rawJson: String) : EventPayload
