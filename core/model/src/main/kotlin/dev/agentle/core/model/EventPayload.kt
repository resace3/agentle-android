package dev.agentle.core.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

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
    /**
     * SHA-256 of `StatusBarNotification.key`. One POSTED row per key: updates of a posted notification are folded into
     * [updateCount]/[lastUpdateEpochMs] of that row instead of becoming new posts (red team lifecycle-battery-02).
     */
    val keyHash: String? = null,
    val foregroundService: Boolean = false,
    val localOnly: Boolean = false,
    val updateCount: Int = 0,
    val lastUpdateEpochMs: Long? = null,
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

/** What a calorie value covers: [ACTIVE] excludes the basal burn (Google Health `active-energy-burned`). */
@Serializable
public enum class EnergyBasis { ACTIVE, TOTAL, BASAL }

@Serializable
@SerialName("calories")
public data class CaloriesPayload(
    val kilocalories: Double,
    /** Null when the source does not say (older rows); consumers must not sum different bases. */
    val basis: EnergyBasis? = null,
) : EventPayload

@Serializable
public enum class ActivityKind { STILL, WALKING, RUNNING, ON_BICYCLE, IN_VEHICLE, ON_FOOT, TILTING, UNKNOWN }

@Serializable
public enum class TransitionKind { ENTER, EXIT }

@Serializable
@SerialName("activity_transition")
public data class ActivityTransitionPayload(val activity: ActivityKind, val transition: TransitionKind) : EventPayload

/** One exercise event (start, stop, pause, resume, ...), kept as the upstream type name. */
@Serializable
public data class ExerciseEventEntry(val eventType: String, val epochMs: Long, val utcOffsetSeconds: Int? = null)

@Serializable
@SerialName("exercise")
public data class ExercisePayload(
    /** Upstream exercise type (e.g. `RUNNING`); `UNKNOWN` when the value is not in the known set (see [rawExerciseType]). */
    val exerciseType: String,
    val durationMs: Long,
    val distanceMeters: Double? = null,
    val kilocalories: Double? = null,
    val averageHeartRate: Double? = null,
    val steps: Long? = null,
    /** The upstream value when [exerciseType] is `UNKNOWN`, so nothing is lost. */
    val rawExerciseType: String? = null,
    /** Duration excluding pauses. */
    val activeDurationMs: Long? = null,
    val displayName: String? = null,
    /** Personal text: free-form notes the user typed when logging manually. */
    val notes: String? = null,
    val hasGps: Boolean? = null,
    val elevationGainMeters: Double? = null,
    val activeZoneMinutes: Long? = null,
    val averagePaceSecondsPerMeter: Double? = null,
    /** Seconds per heart-rate zone (keys as upstream, e.g. `light`, `moderate`, `vigorous`, `peak`). */
    val heartRateZoneSeconds: Map<String, Long> = emptyMap(),
    val events: List<ExerciseEventEntry> = emptyList(),
    val startUtcOffsetSeconds: Int? = null,
    val endUtcOffsetSeconds: Int? = null,
    /** Upstream last-modified time, when the source reports one. */
    val upstreamUpdatedAt: Instant? = null,
) : EventPayload

@Serializable
@SerialName("heart_rate")
public data class HeartRatePayload(
    val bpm: Double,
    /** Upstream motion context (e.g. `SEDENTARY`, `ACTIVE`), when reported. */
    val motionContext: String? = null,
    /** Upstream sensor location (e.g. `WRIST`), when reported. */
    val sensorLocation: String? = null,
) : EventPayload

/**
 * A daily resting heart rate. [date] is the civil date in the user's time zone as the source reports it; it is the
 * authoritative key and is never derived from the event's instants.
 */
@Serializable
@SerialName("resting_heart_rate")
public data class RestingHeartRatePayload(val bpm: Double, val date: LocalDate, val calculationMethod: String? = null) : EventPayload

/** Metrics a source reports as per-civil-day totals. */
@Serializable
public enum class DailyTotalMetric { STEPS, DISTANCE_METERS, FLOORS, TOTAL_CALORIES_KCAL, ACTIVE_CALORIES_KCAL }

/**
 * A per-civil-day total computed by the source (Google Health `:dailyRollUp`). [date] is authoritative; a day without
 * data has no event at all (absent is not zero). Never sum these with the interval samples of the same metric.
 */
@Serializable
@SerialName("daily_total")
public data class DailyTotalPayload(val date: LocalDate, val metric: DailyTotalMetric, val value: Double) : EventPayload

@Serializable
public enum class SleepStageKind { AWAKE, LIGHT, DEEP, REM, ASLEEP_UNSPECIFIED, OUT_OF_BED, RESTLESS, UNKNOWN }

@Serializable
public data class SleepStage(
    val stage: SleepStageKind,
    val startEpochMs: Long,
    val endEpochMs: Long,
    /** Offsets of the user's local time at the stage boundaries; they differ when a stage spans a DST change. */
    val startUtcOffsetSeconds: Int? = null,
    val endUtcOffsetSeconds: Int? = null,
    /** The upstream stage name when [stage] is [SleepStageKind.UNKNOWN]. */
    val rawStage: String? = null,
)

/** Upstream per-stage totals, stored as given (never recomputed). */
@Serializable
public data class SleepStageSummary(
    val stage: SleepStageKind,
    val minutes: Long? = null,
    val count: Long? = null,
    val rawStage: String? = null,
)

@Serializable
@SerialName("sleep_session")
public data class SleepSessionPayload(
    val stages: List<SleepStage> = emptyList(),
    /** Minutes asleep as reported upstream, when available (otherwise derived from stages). */
    val minutesAsleep: Long? = null,
    val minutesAwake: Long? = null,
    val isMainSleep: Boolean = true,
    val isNap: Boolean = false,
    /** False while the source is still computing stages; the session is re-read later. Null if unknown. */
    val processed: Boolean? = null,
    /** Upstream sleep type, e.g. `STAGES` or `CLASSIC`. */
    val sleepType: String? = null,
    /** Upstream stage-processing status, e.g. `SUCCEEDED`, `REJECTED_NAP`. */
    val stagesStatus: String? = null,
    val minutesInSleepPeriod: Long? = null,
    val minutesToFallAsleep: Long? = null,
    val minutesAfterWakeUp: Long? = null,
    val stageSummaries: List<SleepStageSummary> = emptyList(),
    /** Short awake segments; they may overlap [stages] and are kept separately (stages are never split). */
    val shortAwakenings: List<SleepStage> = emptyList(),
    val outOfBedSegments: List<SleepStage> = emptyList(),
    val manuallyEdited: Boolean? = null,
    val startUtcOffsetSeconds: Int? = null,
    val endUtcOffsetSeconds: Int? = null,
    /** Upstream last-modified time, when the source reports one. */
    val upstreamUpdatedAt: Instant? = null,
) : EventPayload

@Serializable
@SerialName("weight")
public data class WeightPayload(
    val kilograms: Double,
    /** Personal text: notes typed when the weight was logged manually. */
    val notes: String? = null,
) : EventPayload

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
    /** Upstream device type, e.g. `TRACKER` or `SCALE`. */
    val deviceType: String? = null,
    /** Upstream battery bucket, e.g. `High`, `Low`. */
    val batteryStatus: String? = null,
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
