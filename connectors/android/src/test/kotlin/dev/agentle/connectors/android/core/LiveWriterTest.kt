package dev.agentle.connectors.android.core

import com.google.common.truth.Truth.assertThat
import dev.agentle.connectors.android.TestRuntime
import dev.agentle.connectors.android.collectors.device.BatterySnapshot
import dev.agentle.connectors.android.collectors.device.NetworkRecorder
import dev.agentle.connectors.android.collectors.device.NetworkSnapshot
import dev.agentle.connectors.android.collectors.device.PowerRecorder
import dev.agentle.connectors.android.collectors.device.PowerSnapshot
import dev.agentle.core.model.EventType
import dev.agentle.core.model.NetworkKind
import dev.agentle.core.model.PlugType
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(minSdk = 29)
class LiveWriterTest {
    private val power = PowerSnapshot(powerSaveMode = false, deviceIdle = false, thermalStatus = 0)

    private fun network(i: Int) = NetworkSnapshot(
        kind = if (i % 2 == 0) NetworkKind.WIFI else NetworkKind.CELLULAR,
        metered = i % 2 != 0,
        validated = true,
        downstreamKbps = 1000 + i,
    )

    /** Battery broadcasts every 10 s and a connectivity flap every minute for 8 hours; returns the rows written. */
    private suspend fun overnight(t: TestRuntime, live: Boolean, notificationAccess: Boolean) {
        val battery = PowerRecorder(t.runtime)
        val net = NetworkRecorder(t.runtime)
        val writer = LiveWriter(t.runtime)
        val batteryOut = writer.channel("battery")
        val networkOut = writer.channel("network")
        val notificationsOut = writer.channel("notifications")
        val steps = (8.hours / 10.seconds).toInt()
        for (i in 0 until steps) {
            val level = 20 + i * 80 / steps
            val snapshot = BatterySnapshot(level, PlugType.AC, charging = true, temperatureCelsius = 30f)
            val at = t.clock.now()
            val sweep = i % (15.minutes / 10.seconds).toInt() == 0
            if (live) {
                battery.recordLevel(snapshot, power, at, via = batteryOut)
                if (i % 6 == 0) net.recordNetwork(network(i / 6), airplaneMode = false, at = at, via = networkOut)
                if (notificationAccess && i % 30 == 0) notificationsOut.submit(emptyList())
            } else if (sweep) {
                battery.recordLevel(snapshot, power, at)
            }
            t.clock.advanceBy(10.seconds)
            if (i % 30 == 0) writer.flush()
        }
        battery.recordLevel(BatterySnapshot(100, PlugType.AC, false, 30f), power, t.clock.now(), via = if (live) batteryOut else null)
        writer.flush()
    }

    @Test
    fun `an overnight charge with flapping connectivity writes a small bounded number of rows`() = runTest {
        val t = TestRuntime(backgroundScope)
        overnight(t, live = true, notificationAccess = true)

        val battery = t.writer.rows.values.filter { it.type == EventType.BATTERY_SAMPLE }
        val network = t.writer.rows.values.filter { it.type == EventType.CONNECTIVITY_CHANGED }
        // 20% to 100% is 17 five-percent buckets, whatever the broadcast rate.
        assertThat(battery.size).isAtMost(17)
        // 480 flaps, capped by the default budget: 6 at once + 12 per hour.
        assertThat(network.size).isAtMost(LiveWriter.DEFAULT_LIMIT.burst + 8 * LiveWriter.DEFAULT_LIMIT.perHour)
        assertThat(t.writer.rows.size).isLessThan(130)
    }

    @Test
    fun `the battery series is the same with and without notification access`() = runTest {
        val withAccess = TestRuntime(backgroundScope)
        overnight(withAccess, live = true, notificationAccess = true)
        val withoutAccess = TestRuntime(backgroundScope)
        overnight(withoutAccess, live = false, notificationAccess = false)

        fun levels(t: TestRuntime) = t.writer.rows.values
            .filter { it.type == EventType.BATTERY_SAMPLE }
            .map { (it.payload as dev.agentle.core.model.BatteryPayload).levelPercent / 5 }
            .toSet()
        assertThat(levels(withAccess)).isEqualTo(levels(withoutAccess))
    }

    @Test
    fun `rows over the rate limit are dropped and counted`() = runTest {
        val t = TestRuntime(backgroundScope)
        val writer = LiveWriter(t.runtime)
        val out = writer.channel("test", RateLimit(burst = 3, perHour = 1))
        val net = NetworkRecorder(t.runtime)
        repeat(10) { i ->
            net.recordNetwork(network(i), airplaneMode = false, at = t.clock.now(), via = out)
            writer.flush()
            t.clock.advanceBy(1.seconds)
        }
        // Three tokens: WIFI, CELLULAR, WIFI. Every later change to CELLULAR is dropped; WIFI is then the last state.
        assertThat(t.writer.rows).hasSize(3)
        assertThat(out.dropped).isEqualTo(4)
    }

    @Test
    fun `an unavailable database closes the channel's coverage once and the next commit reopens it`() = runTest {
        val t = TestRuntime(backgroundScope)
        val writer = LiveWriter(t.runtime)
        val out = writer.channel("calls", coverageIds = listOf("cap"))
        val net = NetworkRecorder(t.runtime)
        val events = (0 until 2).map { i ->
            t.runtime.events.create(
                type = EventType.CONNECTIVITY_CHANGED,
                source = AndroidSources.NETWORK,
                start = t.clock.now(),
                payload = dev.agentle.core.model.ConnectivityPayload(NetworkKind.WIFI, false, true, false, i),
                dedupKey = "k$i",
            )
        }
        t.writer.available = false
        out.submit(events.take(1))
        writer.flush()
        out.submit(events.take(1))
        writer.flush()
        t.writer.available = true
        out.submit(events.drop(1))
        writer.flush()
        assertThat(t.coverage.calls.filter { it.startsWith("close") }).hasSize(1)
        assertThat(t.coverage.calls.last()).isEqualTo("open:cap")
        assertThat(net).isNotNull()
    }

    @Test
    fun `updates of one key coalesce into one row`() = runTest {
        val t = TestRuntime(backgroundScope)
        val writer = LiveWriter(t.runtime)
        val out = writer.channel("test", RateLimit(burst = 1, perHour = 1))
        repeat(5_000) { i ->
            out.submit(
                listOf(
                    t.runtime.events.create(
                        type = EventType.CONNECTIVITY_CHANGED,
                        source = AndroidSources.NETWORK,
                        start = t.clock.now(),
                        payload = dev.agentle.core.model.ConnectivityPayload(NetworkKind.WIFI, false, true, false, i),
                        dedupKey = "same",
                    ),
                ),
            )
        }
        writer.flush()
        assertThat(t.writer.rows).hasSize(1)
        assertThat(t.writer.transactions).isEqualTo(1)
        assertThat(out.dropped).isEqualTo(0)
    }
}
