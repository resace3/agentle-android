package dev.agentle.connectors.api.sensors

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SensorCatalogTest {
    /** Every public `Sensor.TYPE_*` of API 37 and its `STRING_TYPE_*`, copied from the SDK's android.jar (javap -constants). */
    private val publicTypes = mapOf(
        1 to "accelerometer", 2 to "magnetic_field", 3 to "orientation", 4 to "gyroscope", 5 to "light", 6 to "pressure",
        7 to "temperature", 8 to "proximity", 9 to "gravity", 10 to "linear_acceleration", 11 to "rotation_vector",
        12 to "relative_humidity", 13 to "ambient_temperature", 14 to "magnetic_field_uncalibrated", 15 to "game_rotation_vector",
        16 to "gyroscope_uncalibrated", 17 to "significant_motion", 18 to "step_detector", 19 to "step_counter",
        20 to "geomagnetic_rotation_vector", 21 to "heart_rate", 28 to "pose_6dof", 29 to "stationary_detect", 30 to "motion_detect",
        31 to "heart_beat", 34 to "low_latency_offbody_detect", 35 to "accelerometer_uncalibrated", 36 to "hinge_angle",
        37 to "head_tracker", 38 to "accelerometer_limited_axes", 39 to "gyroscope_limited_axes",
        40 to "accelerometer_limited_axes_uncalibrated", 41 to "gyroscope_limited_axes_uncalibrated", 42 to "heading",
    )

    /** AOSP types phones list without a public constant (a Unihertz Jelly Star lists tilt_detector, wake_gesture, device_orientation). */
    private val hiddenTypes = mapOf(
        22 to "tilt_detector",
        23 to "wake_gesture",
        24 to "glance_gesture",
        25 to "pick_up_gesture",
        26 to "wrist_tilt_gesture",
        27 to "device_orientation",
        32 to "dynamic_sensor_meta",
        43 to "moisture_intrusion",
    )

    @Test
    fun `every public Android sensor type has a named kind with its string type`() {
        assertThat(publicTypes).hasSize(34)
        publicTypes.forEach { (type, name) ->
            val kind = SensorCatalog.kind(type, null)
            assertThat(kind.key).isEqualTo("android.sensor.$name")
            assertThat(kind.public).isTrue()
            assertThat(kind.group).isNotEqualTo(SensorGroup.OTHER)
            assertThat(kind.label).doesNotContain("_")
        }
    }

    @Test
    fun `the hidden AOSP types are named too and the catalog holds nothing else`() {
        hiddenTypes.forEach { (type, name) ->
            val kind = SensorCatalog.kind(type, null)
            assertThat(kind.key).isEqualTo("android.sensor.$name")
            assertThat(kind.public).isFalse()
            assertThat(kind.label).doesNotContain("Vendor")
        }
        assertThat(SensorCatalog.KINDS.map { it.type }).containsExactlyElementsIn(publicTypes.keys + hiddenTypes.keys)
        assertThat(SensorCatalog.KINDS.map { it.key }.toSet()).hasSize(SensorCatalog.KINDS.size)
    }

    @Test
    fun `vendor and unknown types fall back to their string type`() {
        val vendor = SensorCatalog.kind(65_537, "com.example.sensor.hall_sensor")
        assertThat(vendor.key).isEqualTo("com.example.sensor.hall_sensor")
        assertThat(vendor.label).isEqualTo("Hall sensor")
        assertThat(vendor.group).isEqualTo(SensorGroup.OTHER)
        assertThat(SensorCatalog.kind(65_538, null).key).isEqualTo("vendor.65538")
        assertThat(SensorCatalog.kind(65_538, " ").label).isEqualTo("Vendor sensor 65538")
    }

    @Test
    fun `step and heart sensors name the permission they need`() {
        assertThat(SensorCatalog.kind(19, null).permission).isEqualTo(SensorPermission.ACTIVITY_RECOGNITION)
        assertThat(SensorCatalog.kind(18, null).permission).isEqualTo(SensorPermission.ACTIVITY_RECOGNITION)
        assertThat(SensorCatalog.kind(21, null).permission).isEqualTo(SensorPermission.BODY_SENSORS)
        assertThat(SensorCatalog.kind(31, null).permission).isEqualTo(SensorPermission.BODY_SENSORS)
        val needing = SensorCatalog.KINDS.filter { it.permission != SensorPermission.NONE }.map { it.type }
        assertThat(needing).containsExactly(18, 19, 21, 31)
    }

    @Test
    fun `values read as text`() {
        val kind = { type: Int -> SensorCatalog.kind(type, null) }
        assertThat(SensorCatalog.format(kind(1), listOf(0.1234f, 9.81f, -0.001f))).isEqualTo("x 0.12, y 9.81, z 0.00 m/s²")
        assertThat(SensorCatalog.format(kind(5), listOf(312f))).isEqualTo("312 lx")
        assertThat(SensorCatalog.format(kind(8), listOf(0f), maxRange = 5f)).isEqualTo("Near (0 cm)")
        assertThat(SensorCatalog.format(kind(8), listOf(5f), maxRange = 5f)).isEqualTo("Far (5 cm)")
        assertThat(SensorCatalog.format(kind(8), listOf(1f), maxRange = 1f)).isEqualTo("Far (1 cm)")
        assertThat(SensorCatalog.format(kind(19), listOf(12_345f))).isEqualTo("12345 steps since the phone restarted")
        assertThat(SensorCatalog.format(kind(27), listOf(1f))).isEqualTo("Turned left")
        assertThat(SensorCatalog.format(kind(34), listOf(0f))).isEqualTo("Off body")
        assertThat(SensorCatalog.format(kind(11), listOf(0f, 0f, 0.7071f, 0.7071f, 0f))).isEqualTo("x 0, y 0, z 0.71, w 0.71")
        assertThat(SensorCatalog.format(kind(14), emptyList())).isEqualTo("No value")
    }

    @Test
    fun `numbers ignore the locale and never read minus zero`() {
        assertThat(SensorCatalog.number(-0.004f)).isEqualTo("0.00")
        assertThat(SensorCatalog.number(-0f)).isEqualTo("0")
        assertThat(SensorCatalog.number(1013.25f)).isEqualTo("1013")
        assertThat(SensorCatalog.number(-12.34f)).isEqualTo("-12.3")
        assertThat(SensorCatalog.number(Float.NaN)).isEqualTo("NaN")
    }

    @Test
    fun `reporting modes map from the Android ints`() {
        assertThat((0..3).map(SensorReportingMode::fromAndroid)).containsExactlyElementsIn(SensorReportingMode.entries).inOrder()
        assertThat(SensorReportingMode.fromAndroid(9)).isEqualTo(SensorReportingMode.CONTINUOUS)
    }
}
