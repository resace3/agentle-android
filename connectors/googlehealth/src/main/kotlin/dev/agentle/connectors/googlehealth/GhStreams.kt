package dev.agentle.connectors.googlehealth

import dev.agentle.core.model.ConnectorIds
import dev.agentle.core.model.DailyTotalMetric
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** The API method a stream is read with (docs/research/05 §5.3). */
internal enum class GhMethod { LIST, RECONCILE, ROLL_UP, DAILY_ROLL_UP, PAIRED_DEVICES }

/** How a stream's points are filtered and mapped. */
internal enum class GhKind {
    /** `{t}.interval.start_time` filters; one event per interval. */
    INTERVAL,

    /** `{t}.sample_time.physical_time` filters; one event per sample. */
    SAMPLE,

    /** `{t}.date` filters; civil-date values. */
    DAILY,

    /** `sleep.interval.end_time` filters; sessions keyed by `name`. */
    SLEEP,

    /** `exercise.interval.civil_start_time` filters (±14 h); sessions keyed by `name`. */
    EXERCISE,

    /** `:rollUp` windows (no filter, a body with a range). */
    ROLL_UP,

    /** `:dailyRollUp` days (a body with a civil date range). */
    DAILY_ROLL_UP,

    /** `pairedDevices.list`. */
    DEVICES,
}

/** Which paired device's `lastSyncTime` bounds a stream's coverage (docs/research/10 §5.3). */
internal enum class GhDeviceClass { TRACKER, SCALE, NONE }

/**
 * One synced stream: the Google Health data type behind it, how it is read, and its window rules (round-2
 * correction 4: windows of at most 24 h for intervals, 6 h for raw samples, 7 days for sessions).
 */
internal data class GhStream(
    val id: String,
    /** Path id of the data type (kebab case), for example `active-energy-burned`. */
    val dataType: String,
    /** The value-union key of the type (camel case), for example `activeEnergyBurned`. */
    val unionKey: String,
    val method: GhMethod,
    val kind: GhKind,
    val eventType: EventType,
    val scope: String,
    /** Longest window fetched and committed at once. */
    val chunk: Duration,
    /** How far an incremental run re-reads before the stream's cursor (docs/research/05 §5.6). */
    val overlap: Duration,
    /** Device whose last sync bounds coverage. */
    val device: GhDeviceClass,
    /** Whether the device's last sync may shorten the overlap re-read (round-2 correction 6). */
    val deviceBoundOverlap: Boolean = false,
    /** Points carry a `name` that identifies them across fetches. */
    val identifiable: Boolean = false,
    val dailyMetric: DailyTotalMetric? = null,
) {
    val source: DataSourceId get() = DataSourceId.of(ConnectorIds.GOOGLE_HEALTH, id)

    /** The type's name inside filters (snake case, hygiene H3). */
    val filterName: String get() = dataType.replace('-', '_')

    /** Whether windows are whole civil days. */
    val civilDays: Boolean get() = kind == GhKind.DAILY || kind == GhKind.DAILY_ROLL_UP

    val pageSizeCapped: Boolean get() = kind == GhKind.SLEEP || kind == GhKind.EXERCISE
}

internal object GhStreams {
    private val INTERVAL_CHUNK = 24.hours
    private val RAW_SAMPLE_CHUNK = 6.hours
    private val SESSION_CHUNK = 7.days
    private val SAMPLE_OVERLAP = 48.hours
    private val SESSION_OVERLAP = 7.days

    /** The catalog for [config]: heart rate is a 60-second rollup unless raw samples are opted in. */
    fun catalog(config: GoogleHealthConfig): List<GhStream> {
        val heartRate = if (config.rawHeartRate) {
            GhStream(
                GoogleHealthStreams.HEART_RATE, "heart-rate", "heartRate", GhMethod.LIST, GhKind.SAMPLE, EventType.HEART_RATE,
                GoogleHealthScopes.METRICS, RAW_SAMPLE_CHUNK, SAMPLE_OVERLAP, GhDeviceClass.TRACKER, deviceBoundOverlap = true,
            )
        } else {
            GhStream(
                GoogleHealthStreams.HEART_RATE, "heart-rate", "heartRate", GhMethod.ROLL_UP, GhKind.ROLL_UP, EventType.HEART_RATE,
                GoogleHealthScopes.METRICS, INTERVAL_CHUNK, SAMPLE_OVERLAP, GhDeviceClass.TRACKER, deviceBoundOverlap = true,
            )
        }
        val all = listOf(
            GhStream(
                GoogleHealthStreams.DEVICES, "pairedDevices", "", GhMethod.PAIRED_DEVICES, GhKind.DEVICES, EventType.WEARABLE_DEVICE,
                GoogleHealthScopes.SETTINGS, Duration.ZERO, Duration.ZERO, GhDeviceClass.NONE,
            ),
            GhStream(
                GoogleHealthStreams.SLEEP, "sleep", "sleep", GhMethod.LIST, GhKind.SLEEP, EventType.SLEEP_SESSION,
                GoogleHealthScopes.SLEEP, SESSION_CHUNK, SESSION_OVERLAP, GhDeviceClass.TRACKER, identifiable = true,
            ),
            interval(GoogleHealthStreams.STEPS, "steps", "steps", EventType.STEP_SAMPLE),
            interval(GoogleHealthStreams.DISTANCE, "distance", "distance", EventType.DISTANCE_SAMPLE),
            interval(GoogleHealthStreams.ACTIVE_ENERGY, "active-energy-burned", "activeEnergyBurned", EventType.CALORIES_SAMPLE),
            interval(GoogleHealthStreams.FLOORS, "floors", "floors", EventType.FLOORS_SAMPLE, GhMethod.RECONCILE),
            heartRate,
            GhStream(
                GoogleHealthStreams.EXERCISE, "exercise", "exercise", GhMethod.LIST, GhKind.EXERCISE, EventType.EXERCISE_SESSION,
                GoogleHealthScopes.ACTIVITY, SESSION_CHUNK, SESSION_OVERLAP, GhDeviceClass.TRACKER, identifiable = true,
            ),
            GhStream(
                GoogleHealthStreams.RESTING_HEART_RATE, "daily-resting-heart-rate", "dailyRestingHeartRate", GhMethod.LIST,
                GhKind.DAILY, EventType.RESTING_HEART_RATE, GoogleHealthScopes.METRICS, SESSION_CHUNK, SESSION_OVERLAP,
                GhDeviceClass.TRACKER,
            ),
            daily(GoogleHealthStreams.DAILY_STEPS, "steps", "steps", DailyTotalMetric.STEPS),
            daily(GoogleHealthStreams.DAILY_DISTANCE, "distance", "distance", DailyTotalMetric.DISTANCE_METERS),
            daily(GoogleHealthStreams.DAILY_FLOORS, "floors", "floors", DailyTotalMetric.FLOORS),
            daily(GoogleHealthStreams.DAILY_TOTAL_CALORIES, "total-calories", "totalCalories", DailyTotalMetric.TOTAL_CALORIES_KCAL),
            daily(
                GoogleHealthStreams.DAILY_ACTIVE_ENERGY,
                "active-energy-burned",
                "activeEnergyBurned",
                DailyTotalMetric.ACTIVE_CALORIES_KCAL,
            ),
            // Body measurements are sparse (about one a day): 7-day windows instead of the 6-hour raw-sample cap.
            GhStream(
                GoogleHealthStreams.WEIGHT, "weight", "weight", GhMethod.LIST, GhKind.SAMPLE, EventType.WEIGHT,
                GoogleHealthScopes.METRICS, SESSION_CHUNK, SAMPLE_OVERLAP, GhDeviceClass.SCALE, identifiable = true,
            ),
            GhStream(
                GoogleHealthStreams.BODY_FAT, "body-fat", "bodyFat", GhMethod.LIST, GhKind.SAMPLE, EventType.BODY_FAT,
                GoogleHealthScopes.METRICS, SESSION_CHUNK, SAMPLE_OVERLAP, GhDeviceClass.SCALE, identifiable = true,
            ),
        )
        check(all.map { it.id } == GoogleHealthStreams.ALL) { "catalog out of sync with GoogleHealthStreams.ALL" }
        return all
    }

    private fun interval(id: String, dataType: String, unionKey: String, type: EventType, method: GhMethod = GhMethod.LIST) = GhStream(
        id, dataType, unionKey, method, GhKind.INTERVAL, type, GoogleHealthScopes.ACTIVITY, INTERVAL_CHUNK, SAMPLE_OVERLAP,
        GhDeviceClass.TRACKER, deviceBoundOverlap = true,
    )

    private fun daily(id: String, dataType: String, unionKey: String, metric: DailyTotalMetric) = GhStream(
        id, dataType, unionKey, GhMethod.DAILY_ROLL_UP, GhKind.DAILY_ROLL_UP, EventType.DAILY_TOTAL, GoogleHealthScopes.ACTIVITY,
        SESSION_CHUNK, SESSION_OVERLAP, GhDeviceClass.TRACKER, dailyMetric = metric,
    )
}
