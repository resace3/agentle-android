package dev.agentle.connectors.android.collectors.sensors

import android.Manifest
import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.connectors.api.sensors.SensorCatalog
import dev.agentle.connectors.api.sensors.SensorCheck
import dev.agentle.connectors.api.sensors.SensorSnapshot
import dev.agentle.connectors.api.sensors.SensorState
import dev.agentle.core.testing.TestAgentleClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorBuilder
import org.robolectric.shadows.SensorEventBuilder
import java.lang.reflect.Modifier

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(minSdk = 29)
class AndroidSensorGatewayTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val manager: SensorManager = app.getSystemService(SensorManager::class.java)

    private fun add(type: Int, name: String, wakeUp: Boolean = false): Sensor =
        SensorBuilder.newBuilder().setType(type).setName(name).setWakeUpFlag(wakeUp).setMaximumRange(5f).build()
            .also { shadowOf(manager).addSensor(it) }

    private fun TestScope.listen(gateway: AndroidSensorGateway): MutableList<SensorSnapshot> {
        val snapshots = ArrayList<SensorSnapshot>()
        backgroundScope.launch { gateway.live(gateway.sensors()).collect { snapshots += it } }
        runCurrent()
        return snapshots
    }

    private fun TestScope.nextSnapshot() {
        advanceTimeBy(SensorGateway.SNAPSHOT_EVERY)
        runCurrent()
    }

    private fun send(sensor: Sensor, vararg values: Float) =
        shadowOf(manager).sendSensorEventToListeners(SensorEventBuilder.newBuilder(sensor, values).build())

    @Test
    fun `every sensor type of this Android version, hidden ones included, has a catalog kind with its string type`() {
        val fields = Sensor::class.java.declaredFields.filter {
            Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && it.name.startsWith("TYPE_") &&
                it.name !in setOf("TYPE_ALL", "TYPE_DEVICE_PRIVATE_BASE")
        }
        assertThat(fields.size).isAtLeast(30)
        fields.forEach { field ->
            field.isAccessible = true
            val type = field.getInt(null)
            assertWithMessage(field.name).that(SensorCatalog.KINDS.map { it.type }).contains(type)
            // The tilt detector's constant is SENSOR_STRING_TYPE_TILT_DETECTOR; every other one is STRING_TYPE_*.
            val stringType = Sensor::class.java.declaredFields.firstOrNull { it.name.removePrefix("SENSOR_") == "STRING_${field.name}" }
                ?.apply { isAccessible = true }?.get(null)
            if (stringType != null) assertWithMessage(field.name).that(SensorCatalog.kind(type, null).key).isEqualTo(stringType)
        }
    }

    @Test
    fun `every listed sensor is read, wake-up twins and vendor sensors included`() = runTest {
        val accel = add(Sensor.TYPE_ACCELEROMETER, "Accelerometer")
        val light = add(Sensor.TYPE_LIGHT, "Light")
        val proximity = add(Sensor.TYPE_PROXIMITY, "Proximity", wakeUp = true)
        val tilt = add(22, "Tilt Detector", wakeUp = true)
        val vendor = add(65_570, "Hall sensor")
        val gateway = AndroidSensorGateway(app, TestAgentleClock(scheduler = testScheduler))
        val sensors = gateway.sensors()
        assertThat(sensors.map { it.kind.label })
            .containsExactly("Accelerometer", "Ambient light", "Proximity", "Tilt detector", "Vendor sensor 65570")

        val snapshots = listen(gateway)
        send(accel, 0f, 9.81f, 0f)
        send(light, 312f)
        send(proximity, 0f)
        send(tilt, 1f)
        send(vendor, 1f)
        nextSnapshot()

        val statuses = SensorCheck.statuses(sensors, snapshots.last())
        assertThat(statuses.map { it.state }.toSet()).containsExactly(SensorState.READING)
        assertThat(statuses.map { it.value })
            .containsExactly("x 0, y 9.81, z 0 m/s²", "312 lx", "Near (0 cm)", "Detected once", "1")
        assertThat(SensorCheck.summarize(statuses).line()).isEqualTo("5 sensors: 5 sending data")
    }

    @Test
    fun `a continuous sensor that stays silent past the grace period has no data`() = runTest {
        add(Sensor.TYPE_GYROSCOPE, "Gyroscope")
        val gateway = AndroidSensorGateway(app, TestAgentleClock(scheduler = testScheduler))
        val snapshots = listen(gateway)
        assertThat(SensorCheck.statuses(gateway.sensors(), snapshots.last()).single().state).isEqualTo(SensorState.CHECKING)

        advanceTimeBy(SensorCheck.GRACE)
        runCurrent()

        assertThat(SensorCheck.statuses(gateway.sensors(), snapshots.last()).single().state).isEqualTo(SensorState.NO_DATA)
    }

    @Test
    fun `step sensors wait for the physical activity permission and are read once it is granted`() = runTest {
        val counter = add(Sensor.TYPE_STEP_COUNTER, "Step Counter")
        val gateway = AndroidSensorGateway(app, TestAgentleClock(scheduler = testScheduler))
        val sensors = gateway.sensors()

        val denied = gateway.live(sensors).first()
        assertThat(SensorCheck.statuses(sensors, denied).single().state).isEqualTo(SensorState.NEEDS_PERMISSION)
        assertThat(shadowOf(manager).listeners).isEmpty()

        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        val snapshots = listen(gateway)
        assertThat(shadowOf(manager).listeners).hasSize(1)
        send(counter, 12_345f)
        nextSnapshot()

        val status = SensorCheck.statuses(sensors, snapshots.last()).single()
        assertThat(status.state).isEqualTo(SensorState.READING)
        assertThat(status.value).isEqualTo("12345 steps since the phone restarted")
    }

    @Test
    fun `listeners are removed when the screen stops listening`() = runTest {
        add(Sensor.TYPE_ACCELEROMETER, "Accelerometer")
        val gateway = AndroidSensorGateway(app, TestAgentleClock(scheduler = testScheduler))

        gateway.live(gateway.sensors()).first()

        assertThat(shadowOf(manager).listeners).isEmpty()
    }
}
