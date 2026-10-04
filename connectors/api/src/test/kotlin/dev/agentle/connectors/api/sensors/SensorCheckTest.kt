package dev.agentle.connectors.api.sensors

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class SensorCheckTest {
    private fun sensor(type: Int, mode: SensorReportingMode, name: String = "s$type") = DeviceSensor(
        id = DeviceSensor.baseId(type, name, "v", wakeUp = false),
        kind = SensorCatalog.kind(type, null),
        name = name,
        vendor = "v",
        mode = mode,
        wakeUp = false,
        maxRange = 5f,
    )

    private val accel = sensor(1, SensorReportingMode.CONTINUOUS)
    private val light = sensor(5, SensorReportingMode.ON_CHANGE)
    private val significantMotion = sensor(17, SensorReportingMode.ONE_SHOT)
    private val stepDetector = sensor(18, SensorReportingMode.SPECIAL_TRIGGER)
    private val stepCounter = sensor(19, SensorReportingMode.ON_CHANGE)

    private val registered = SensorObservation(registered = true)
    private val late = 6.seconds
    private val early = 1.seconds

    @Test
    fun `a sensor that sent events is reading, whatever its mode`() {
        val sending = registered.copy(events = 3, values = listOf(1f, 2f, 3f))
        listOf(accel, light, significantMotion, stepDetector).forEach {
            assertThat(SensorCheck.state(it, sending, early)).isEqualTo(SensorState.READING)
        }
    }

    @Test
    fun `a missing permission or a refused listener is reported before anything else`() {
        val noPermission = SensorObservation(missingPermission = SensorPermission.ACTIVITY_RECOGNITION)
        assertThat(SensorCheck.state(stepCounter, noPermission, late)).isEqualTo(SensorState.NEEDS_PERMISSION)
        assertThat(SensorCheck.state(accel, SensorObservation(registered = false), late)).isEqualTo(SensorState.REFUSED)
        assertThat(SensorCheck.state(accel, SensorObservation(registered = false, events = 2), late)).isEqualTo(SensorState.REFUSED)
    }

    @Test
    fun `silence is a fault only for continuous sensors and only after the grace period`() {
        assertThat(SensorCheck.state(accel, null, late)).isEqualTo(SensorState.CHECKING)
        assertThat(SensorCheck.state(accel, registered, early)).isEqualTo(SensorState.CHECKING)
        assertThat(SensorCheck.state(accel, registered, late)).isEqualTo(SensorState.NO_DATA)
        assertThat(SensorCheck.state(light, registered, early)).isEqualTo(SensorState.CHECKING)
        assertThat(SensorCheck.state(light, registered, late)).isEqualTo(SensorState.WAITING_FOR_EVENT)
        assertThat(SensorCheck.state(stepDetector, registered, early)).isEqualTo(SensorState.WAITING_FOR_EVENT)
        assertThat(SensorCheck.state(significantMotion, registered, early)).isEqualTo(SensorState.ARMED)
    }

    @Test
    fun `statuses carry the value text and the summary counts every state`() {
        val snapshot = SensorSnapshot(
            elapsed = late,
            observations = mapOf(
                accel.id to registered.copy(events = 10, values = listOf(0f, 9.81f, 0f)),
                light.id to registered,
                stepDetector.id to registered.copy(events = 1, values = listOf(1f)),
                stepCounter.id to SensorObservation(missingPermission = SensorPermission.ACTIVITY_RECOGNITION),
            ),
        )
        val statuses = SensorCheck.statuses(listOf(accel, light, significantMotion, stepDetector, stepCounter), snapshot)

        assertThat(statuses.map { it.state }).containsExactly(
            SensorState.READING,
            SensorState.WAITING_FOR_EVENT,
            SensorState.CHECKING,
            SensorState.READING,
            SensorState.NEEDS_PERMISSION,
        ).inOrder()
        assertThat(statuses[0].value).isEqualTo("x 0, y 9.81, z 0 m/s²")
        assertThat(statuses[3].value).isEqualTo("Detected once")
        assertThat(statuses[1].value).isNull()
        val summary = SensorCheck.summarize(statuses)
        assertThat(summary.readable).isEqualTo(4)
        assertThat(summary.line()).isEqualTo("5 sensors: 2 sending data, 1 ready, 1 checking, 1 need permission")
    }

    @Test
    fun `duplicate sensors get distinct ids`() {
        val base = DeviceSensor.baseId(18, "Step Detector", "MTK", wakeUp = false)
        val ids = DeviceSensor.uniqueIds(listOf(base, DeviceSensor.baseId(18, "Step Detector", "MTK", wakeUp = true), base))
        assertThat(ids).containsExactly(base, "18|Step Detector|MTK|true", "$base|2").inOrder()
    }
}
