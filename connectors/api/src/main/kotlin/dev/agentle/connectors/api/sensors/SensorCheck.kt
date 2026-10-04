package dev.agentle.connectors.api.sensors

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One sensor the phone lists to apps. [id] is stable for a phone: type, name, vendor and wake-up flag. */
public data class DeviceSensor(
    val id: String,
    val kind: SensorKind,
    val name: String,
    val vendor: String,
    val mode: SensorReportingMode,
    val wakeUp: Boolean,
    val maxRange: Float,
) {
    public companion object {
        public fun baseId(type: Int, name: String, vendor: String, wakeUp: Boolean): String = "$type|$name|$vendor|$wakeUp"

        /** [baseIds] made unique: the second and later copies of an id get "|2", "|3" and so on, in list order. */
        public fun uniqueIds(baseIds: List<String>): List<String> {
            val seen = HashMap<String, Int>()
            return baseIds.map { base ->
                val count = (seen[base] ?: 0) + 1
                seen[base] = count
                if (count == 1) base else "$base|$count"
            }
        }
    }
}

/**
 * What listening to one sensor has shown so far. [registered] is null until Agentle tried to listen (or when a missing
 * permission kept it from trying), false when Android refused the listener.
 */
public data class SensorObservation(
    val registered: Boolean? = null,
    val missingPermission: SensorPermission? = null,
    val events: Long = 0,
    val values: List<Float>? = null,
    val accuracy: Int? = null,
)

/** Every observation keyed by [DeviceSensor.id], [elapsed] after listening started. */
public data class SensorSnapshot(val elapsed: Duration, val observations: Map<String, SensorObservation>)

/** A sensor's state on the Phone sensors screen; [ok] is false when the app cannot read it. */
public enum class SensorState(public val label: String, public val ok: Boolean) {
    CHECKING("Checking", true),
    READING("Sending data", true),
    WAITING_FOR_EVENT("Ready, reports only when something happens", true),
    ARMED("Ready, fires once when triggered", true),
    NO_DATA("No data", false),
    NEEDS_PERMISSION("Needs permission", false),
    REFUSED("Android refused access", false),
}

public data class SensorStatus(val sensor: DeviceSensor, val state: SensorState, val value: String?, val events: Long)

public data class SensorCheckSummary(val total: Int, val counts: Map<SensorState, Int>) {
    public val readable: Int get() = counts.filterKeys { it.ok }.values.sum()

    /** "28 sensors: 20 sending data, 6 ready, 1 needs permission, 1 no data" (only the states that occur). */
    public fun line(): String {
        val ready = (counts[SensorState.WAITING_FOR_EVENT] ?: 0) + (counts[SensorState.ARMED] ?: 0)
        val parts = listOfNotNull(
            counts[SensorState.READING]?.let { "$it sending data" },
            ready.takeIf { it > 0 }?.let { "$it ready" },
            counts[SensorState.CHECKING]?.let { "$it checking" },
            counts[SensorState.NEEDS_PERMISSION]?.let { "$it need permission" },
            counts[SensorState.NO_DATA]?.let { "$it no data" },
            counts[SensorState.REFUSED]?.let { "$it refused" },
        )
        val sensors = if (total == 1) "1 sensor" else "$total sensors"
        return sensors + if (parts.isEmpty()) "" else ": " + parts.joinToString(", ")
    }
}

/**
 * Turns what listening showed into one state per sensor. A continuous sensor that sent nothing within [GRACE] has no
 * data; on-change and trigger sensors only report when their value changes or they detect something, so silence from
 * them is not a fault.
 */
public object SensorCheck {
    public val GRACE: Duration = 5.seconds

    public fun state(sensor: DeviceSensor, observation: SensorObservation?, elapsed: Duration): SensorState = when {
        observation?.missingPermission != null -> SensorState.NEEDS_PERMISSION
        observation?.registered == false -> SensorState.REFUSED
        (observation?.events ?: 0) > 0 -> SensorState.READING
        observation?.registered == null -> SensorState.CHECKING
        sensor.mode == SensorReportingMode.ONE_SHOT -> SensorState.ARMED
        sensor.mode == SensorReportingMode.SPECIAL_TRIGGER -> SensorState.WAITING_FOR_EVENT
        elapsed < GRACE -> SensorState.CHECKING
        sensor.mode == SensorReportingMode.ON_CHANGE -> SensorState.WAITING_FOR_EVENT
        else -> SensorState.NO_DATA
    }

    public fun statuses(sensors: List<DeviceSensor>, snapshot: SensorSnapshot): List<SensorStatus> = sensors.map { sensor ->
        val observation = snapshot.observations[sensor.id]
        val events = observation?.events ?: 0
        val values = observation?.values
        val value = when {
            values == null -> null
            sensor.kind.style == ValueStyle.EVENT -> if (events == 1L) "Detected once" else "Detected $events times"
            else -> SensorCatalog.format(sensor.kind, values, sensor.maxRange)
        }
        SensorStatus(sensor, state(sensor, observation, snapshot.elapsed), value, events)
    }

    public fun summarize(statuses: List<SensorStatus>): SensorCheckSummary =
        SensorCheckSummary(statuses.size, statuses.groupingBy { it.state }.eachCount())
}
