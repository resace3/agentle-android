package dev.agentle.connectors.android.collectors.sensors

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import dev.agentle.connectors.api.sensors.DeviceSensor
import dev.agentle.connectors.api.sensors.SensorCatalog
import dev.agentle.connectors.api.sensors.SensorObservation
import dev.agentle.connectors.api.sensors.SensorPermission
import dev.agentle.connectors.api.sensors.SensorReportingMode
import dev.agentle.connectors.api.sensors.SensorSnapshot
import dev.agentle.core.time.AgentleClock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The phone's sensors behind a seam: what it lists to apps, and their live readings while the Phone sensors screen is open. */
public interface SensorGateway {
    /** Every sensor the phone lists to apps (`getSensorList(TYPE_ALL)` and dynamic sensors such as a connected controller). */
    public fun sensors(): List<DeviceSensor>

    /**
     * Listens to [sensors] while collected, at `SENSOR_DELAY_NORMAL`, and emits a [SensorSnapshot] right away and then
     * every [SNAPSHOT_EVERY]. Android delivers sensor events only to the app on screen (Android 9+), so this is a
     * foreground check, not background collection.
     */
    public fun live(sensors: List<DeviceSensor>): Flow<SensorSnapshot>

    public companion object {
        public val SNAPSHOT_EVERY: Duration = 250.milliseconds
    }
}

/**
 * [SensorGateway] over `SensorManager`. One-shot sensors (significant motion, wake gesture) are armed with
 * `requestTriggerSensor` and re-armed after each trigger; every other sensor gets a listener on its own handler thread.
 * A sensor whose permission is missing is not touched: it reports [SensorObservation.missingPermission] instead.
 */
public class AndroidSensorGateway(
    private val context: Context,
    private val clock: AgentleClock,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : SensorGateway {
    private val manager: SensorManager? = context.getSystemService(SensorManager::class.java)

    override fun sensors(): List<DeviceSensor> = platformSensors().map { it.second }

    private fun platformSensors(): List<Pair<Sensor, DeviceSensor>> {
        val manager = manager ?: return emptyList()
        // Dynamic sensors are not in getSensorList; Robolectric and some builds throw for them, which only means none.
        val dynamic = runCatching { manager.getDynamicSensorList(Sensor.TYPE_ALL).orEmpty() }.getOrDefault(emptyList())
        val all = (manager.getSensorList(Sensor.TYPE_ALL).orEmpty() + dynamic).distinct()
        val ids = DeviceSensor.uniqueIds(
            all.map { DeviceSensor.baseId(it.type, it.name.orEmpty(), it.vendor.orEmpty(), it.isWakeUpSensor) },
        )
        return all.zip(ids) { sensor, id ->
            sensor to DeviceSensor(
                id = id,
                kind = SensorCatalog.kind(sensor.type, sensor.stringType),
                name = sensor.name.orEmpty(),
                vendor = sensor.vendor.orEmpty(),
                mode = SensorReportingMode.fromAndroid(sensor.reportingMode),
                wakeUp = sensor.isWakeUpSensor,
                maxRange = sensor.maximumRange,
            )
        }
    }

    /** The [permission] when the app lacks it on this API level, else null. */
    public fun missing(permission: SensorPermission): SensorPermission? {
        val name = permission.androidPermission ?: return null
        if (sdkInt < permission.minSdk) return null
        val granted = (listOf(name) + permission.alternatives).any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        return if (granted) null else permission
    }

    override fun live(sensors: List<DeviceSensor>): Flow<SensorSnapshot> = callbackFlow {
        val manager = manager
        val wanted = sensors.mapTo(HashSet()) { it.id }
        val targets = platformSensors().filter { it.second.id in wanted }
        val ids = targets.associate { it.first to it.second.id }
        val observations = ConcurrentHashMap<String, SensorObservation>()
        fun record(sensor: Sensor?, values: FloatArray, accuracy: Int?) {
            val id = sensor?.let(ids::get) ?: return
            val copy = values.toList()
            observations.compute(id) { _, old ->
                val base = old ?: SensorObservation(registered = true)
                base.copy(events = base.events + 1, values = copy, accuracy = accuracy ?: base.accuracy)
            }
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) = record(event.sensor, event.values, event.accuracy)

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                val id = ids[sensor] ?: return
                observations.computeIfPresent(id) { _, old -> old.copy(accuracy = accuracy) }
            }
        }
        val trigger = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent) {
                record(event.sensor, event.values, null)
                // One-shot sensors switch themselves off after firing; arm again to keep watching.
                runCatching { manager?.requestTriggerSensor(this, event.sensor) }
            }
        }
        val thread = HandlerThread("agentle-sensors").apply { start() }
        val handler = Handler(thread.looper)
        targets.forEach { (sensor, device) ->
            val missing = missing(device.kind.permission)
            val registered = when {
                missing != null || manager == null -> null

                device.mode == SensorReportingMode.ONE_SHOT ->
                    runCatching { manager.requestTriggerSensor(trigger, sensor) }.getOrDefault(false)

                else -> runCatching {
                    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
                }.getOrDefault(false)
            }
            observations.compute(device.id) { _, old ->
                (old ?: SensorObservation()).copy(registered = registered ?: old?.registered, missingPermission = missing)
            }
        }
        val startedAt = clock.elapsed()
        val ticker = launch {
            while (isActive) {
                send(SensorSnapshot(clock.elapsed() - startedAt, HashMap(observations)))
                clock.sleeper.sleep(SensorGateway.SNAPSHOT_EVERY)
            }
        }
        awaitClose {
            ticker.cancel()
            manager?.unregisterListener(listener)
            targets.filter { it.second.mode == SensorReportingMode.ONE_SHOT }.forEach { (sensor, _) ->
                runCatching { manager?.cancelTriggerSensor(trigger, sensor) }
            }
            thread.quitSafely()
        }
    }
}
