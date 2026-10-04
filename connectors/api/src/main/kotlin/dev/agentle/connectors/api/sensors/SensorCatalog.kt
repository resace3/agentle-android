package dev.agentle.connectors.api.sensors

import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/** A runtime permission a sensor needs before Android delivers its events; [minSdk] is the first API level that asks. */
public enum class SensorPermission(
    public val androidPermission: String?,
    public val minSdk: Int,
    public val alternatives: List<String> = emptyList(),
) {
    NONE(null, 0),
    ACTIVITY_RECOGNITION("android.permission.ACTIVITY_RECOGNITION", 29),

    /** Heart sensors; apps targeting API 36+ get them through `health.READ_HEART_RATE` on Android 16+. */
    BODY_SENSORS("android.permission.BODY_SENSORS", 20, listOf("android.permission.health.READ_HEART_RATE")),
}

/** `Sensor.getReportingMode()`: 0 continuous, 1 on-change, 2 one-shot, 3 special trigger. */
public enum class SensorReportingMode {
    CONTINUOUS,
    ON_CHANGE,
    ONE_SHOT,
    SPECIAL_TRIGGER,
    ;

    public companion object {
        public fun fromAndroid(mode: Int): SensorReportingMode = entries.getOrElse(mode) { CONTINUOUS }
    }
}

/** How sensors are grouped on the Phone sensors screen. */
public enum class SensorGroup(public val label: String) {
    MOTION("Motion"),
    POSITION("Position and orientation"),
    ENVIRONMENT("Environment"),
    ACTIVITY("Activity"),
    BODY("Body"),
    GESTURE("Gestures"),
    OTHER("Other and vendor sensors"),
}

/** How a sensor's latest values read as text. */
public enum class ValueStyle { AXES, SINGLE, PROXIMITY, STEPS, EVENT, ROTATION, DEVICE_ORIENTATION, ON_BODY }

/**
 * What Agentle knows about one Android sensor type: [key] is its `Sensor.getStringType()` ("android.sensor.*"),
 * [public] is false for the AOSP types that have no public `Sensor.TYPE_*` constant but still appear on phones.
 */
public data class SensorKind(
    val type: Int,
    val key: String,
    val label: String,
    val group: SensorGroup,
    val unit: String = "",
    val axes: List<String> = emptyList(),
    val permission: SensorPermission = SensorPermission.NONE,
    val public: Boolean = true,
    val style: ValueStyle = if (axes.isEmpty()) ValueStyle.SINGLE else ValueStyle.AXES,
)

/**
 * Every sensor type Android defines: the 34 public `Sensor.TYPE_*` types of API 37 and the 8 types without a public
 * constant that phones still list (tilt detector, wake gesture, device orientation, Android 17's moisture intrusion and
 * others). Any other type, such as a vendor's own (from `Sensor.TYPE_DEVICE_PRIVATE_BASE`), gets an OTHER kind named
 * after its string type.
 */
public object SensorCatalog {
    private val XYZ = listOf("x", "y", "z")
    private val XYZ_BIAS = listOf("x", "y", "z", "bias x", "bias y", "bias z")
    private val QUATERNION = listOf("x", "y", "z", "w")
    private const val NEAR_LIMIT_CM = 5f
    private const val STEPS_UNIT = "steps since the phone restarted"
    private val ACTIVITY = SensorPermission.ACTIVITY_RECOGNITION
    private val BODY = SensorPermission.BODY_SENSORS
    private val ROTATION = ValueStyle.ROTATION

    @Suppress("LongParameterList")
    private fun kind(
        type: Int,
        name: String,
        label: String,
        group: SensorGroup,
        unit: String = "",
        axes: List<String> = emptyList(),
        permission: SensorPermission = SensorPermission.NONE,
        style: ValueStyle = if (axes.isEmpty()) ValueStyle.SINGLE else ValueStyle.AXES,
    ) = SensorKind(type, "android.sensor.$name", label, group, unit, axes, permission, public = true, style = style)

    private fun hidden(type: Int, name: String, label: String, group: SensorGroup, style: ValueStyle = ValueStyle.EVENT) =
        SensorKind(type, "android.sensor.$name", label, group, public = false, style = style)

    public val KINDS: List<SensorKind> = listOf(
        kind(1, "accelerometer", "Accelerometer", SensorGroup.MOTION, "m/s²", XYZ),
        kind(2, "magnetic_field", "Magnetometer", SensorGroup.POSITION, "µT", XYZ),
        kind(3, "orientation", "Orientation (legacy)", SensorGroup.POSITION, "°", listOf("azimuth", "pitch", "roll")),
        kind(4, "gyroscope", "Gyroscope", SensorGroup.MOTION, "rad/s", XYZ),
        kind(5, "light", "Ambient light", SensorGroup.ENVIRONMENT, "lx"),
        kind(6, "pressure", "Barometer", SensorGroup.ENVIRONMENT, "hPa"),
        kind(7, "temperature", "Temperature (legacy)", SensorGroup.ENVIRONMENT, "°C"),
        kind(8, "proximity", "Proximity", SensorGroup.POSITION, "cm", style = ValueStyle.PROXIMITY),
        kind(9, "gravity", "Gravity", SensorGroup.MOTION, "m/s²", XYZ),
        kind(10, "linear_acceleration", "Linear acceleration", SensorGroup.MOTION, "m/s²", XYZ),
        kind(11, "rotation_vector", "Rotation vector", SensorGroup.MOTION, axes = QUATERNION, style = ROTATION),
        kind(12, "relative_humidity", "Humidity", SensorGroup.ENVIRONMENT, "%"),
        kind(13, "ambient_temperature", "Air temperature", SensorGroup.ENVIRONMENT, "°C"),
        kind(14, "magnetic_field_uncalibrated", "Magnetometer (uncalibrated)", SensorGroup.POSITION, "µT", XYZ_BIAS),
        kind(15, "game_rotation_vector", "Game rotation vector", SensorGroup.MOTION, axes = QUATERNION, style = ROTATION),
        kind(16, "gyroscope_uncalibrated", "Gyroscope (uncalibrated)", SensorGroup.MOTION, "rad/s", XYZ_BIAS),
        kind(17, "significant_motion", "Significant motion", SensorGroup.ACTIVITY, style = ValueStyle.EVENT),
        kind(18, "step_detector", "Step detector", SensorGroup.ACTIVITY, permission = ACTIVITY, style = ValueStyle.EVENT),
        kind(19, "step_counter", "Step counter", SensorGroup.ACTIVITY, STEPS_UNIT, permission = ACTIVITY, style = ValueStyle.STEPS),
        kind(20, "geomagnetic_rotation_vector", "Geomagnetic rotation vector", SensorGroup.POSITION, axes = QUATERNION, style = ROTATION),
        kind(21, "heart_rate", "Heart rate", SensorGroup.BODY, "bpm", permission = BODY),
        hidden(22, "tilt_detector", "Tilt detector", SensorGroup.GESTURE),
        hidden(23, "wake_gesture", "Wake gesture", SensorGroup.GESTURE),
        hidden(24, "glance_gesture", "Glance gesture", SensorGroup.GESTURE),
        hidden(25, "pick_up_gesture", "Pick-up gesture", SensorGroup.GESTURE),
        hidden(26, "wrist_tilt_gesture", "Wrist tilt gesture", SensorGroup.GESTURE),
        hidden(27, "device_orientation", "Device orientation", SensorGroup.POSITION, ValueStyle.DEVICE_ORIENTATION),
        kind(28, "pose_6dof", "6DoF pose", SensorGroup.MOTION, axes = listOf("qx", "qy", "qz", "qw", "tx", "ty", "tz")),
        kind(29, "stationary_detect", "Stationary detect", SensorGroup.ACTIVITY, style = ValueStyle.EVENT),
        kind(30, "motion_detect", "Motion detect", SensorGroup.ACTIVITY, style = ValueStyle.EVENT),
        kind(31, "heart_beat", "Heart beat", SensorGroup.BODY, permission = BODY, style = ValueStyle.EVENT),
        hidden(32, "dynamic_sensor_meta", "Dynamic sensor meta", SensorGroup.OTHER),
        kind(34, "low_latency_offbody_detect", "Off-body detect", SensorGroup.BODY, style = ValueStyle.ON_BODY),
        kind(35, "accelerometer_uncalibrated", "Accelerometer (uncalibrated)", SensorGroup.MOTION, "m/s²", XYZ_BIAS),
        kind(36, "hinge_angle", "Hinge angle", SensorGroup.POSITION, "°"),
        kind(37, "head_tracker", "Head tracker", SensorGroup.MOTION, "rad", XYZ),
        kind(38, "accelerometer_limited_axes", "Accelerometer (limited axes)", SensorGroup.MOTION, "m/s²", XYZ),
        kind(39, "gyroscope_limited_axes", "Gyroscope (limited axes)", SensorGroup.MOTION, "rad/s", XYZ),
        kind(40, "accelerometer_limited_axes_uncalibrated", "Accelerometer (limited, uncalibrated)", SensorGroup.MOTION, "m/s²", XYZ_BIAS),
        kind(41, "gyroscope_limited_axes_uncalibrated", "Gyroscope (limited, uncalibrated)", SensorGroup.MOTION, "rad/s", XYZ_BIAS),
        kind(42, "heading", "Heading", SensorGroup.POSITION, "°", listOf("heading", "accuracy")),
        hidden(43, "moisture_intrusion", "Moisture intrusion", SensorGroup.ENVIRONMENT, ValueStyle.SINGLE),
    )

    private val byType: Map<Int, SensorKind> = KINDS.associateBy { it.type }

    /** The kind of a sensor with this `getType()` and `getStringType()`; unknown and vendor types get an OTHER kind. */
    public fun kind(type: Int, stringType: String?): SensorKind = byType[type] ?: SensorKind(
        type = type,
        key = stringType?.takeIf { it.isNotBlank() } ?: "vendor.$type",
        label = stringType?.substringAfterLast('.')?.takeIf { it.isNotBlank() }
            ?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }
            ?: "Vendor sensor $type",
        group = SensorGroup.OTHER,
        public = false,
        style = ValueStyle.SINGLE,
    )

    /** The latest [values] of a [kind] sensor as text; [maxRange] is `Sensor.getMaximumRange()` (proximity "far"). */
    public fun format(kind: SensorKind, values: List<Float>, maxRange: Float = 0f): String {
        val first = values.firstOrNull() ?: return "No value"
        return when (kind.style) {
            ValueStyle.AXES, ValueStyle.ROTATION -> {
                val shown = kind.axes.zip(values) { axis, value -> "$axis ${number(value)}" }.joinToString(", ")
                listOf(shown, kind.unit).filter { it.isNotEmpty() }.joinToString(" ")
            }

            ValueStyle.SINGLE -> listOf(number(first), kind.unit).filter { it.isNotEmpty() }.joinToString(" ")

            ValueStyle.PROXIMITY -> {
                val near = first < if (maxRange > 0f) min(maxRange, NEAR_LIMIT_CM) else NEAR_LIMIT_CM
                "${if (near) "Near" else "Far"} (${number(first)} cm)"
            }

            ValueStyle.STEPS -> "${first.toLong()} ${kind.unit}"

            ValueStyle.EVENT -> "Detected"

            ValueStyle.DEVICE_ORIENTATION -> when (first.toInt()) {
                0 -> "Upright"
                1 -> "Turned left"
                2 -> "Upside down"
                3 -> "Turned right"
                else -> "Unknown (${number(first)})"
            }

            ValueStyle.ON_BODY -> if (first >= 1f) "On body" else "Off body"
        }
    }

    /** Locale-independent: whole numbers without decimals, otherwise 0, 1 or 2 decimals by magnitude; never "-0". */
    public fun number(value: Float): String {
        if (value.isNaN() || value.isInfinite()) return value.toString()
        val size = abs(value)
        val text = when {
            size < 1e9f && value == value.toLong().toFloat() -> value.toLong().toString()
            size >= 100f -> String.format(Locale.ROOT, "%.0f", value)
            size >= 10f -> String.format(Locale.ROOT, "%.1f", value)
            else -> String.format(Locale.ROOT, "%.2f", value)
        }
        return if (text.startsWith('-') && text.drop(1).all { it == '0' || it == '.' }) text.drop(1) else text
    }
}
