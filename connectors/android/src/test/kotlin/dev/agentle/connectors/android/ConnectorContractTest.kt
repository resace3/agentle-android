package dev.agentle.connectors.android

import com.google.common.truth.Truth.assertThat
import dev.agentle.connectors.android.collectors.calendar.CalendarConnector
import dev.agentle.connectors.android.collectors.calendar.CalendarInstance
import dev.agentle.connectors.android.collectors.calendar.CalendarSource
import dev.agentle.connectors.android.collectors.device.AudioConnector
import dev.agentle.connectors.android.collectors.device.AudioRecorder
import dev.agentle.connectors.android.collectors.device.BatteryConnector
import dev.agentle.connectors.android.collectors.device.DeviceRecorder
import dev.agentle.connectors.android.collectors.device.DeviceState
import dev.agentle.connectors.android.collectors.device.DeviceStateConnector
import dev.agentle.connectors.android.collectors.device.NetworkConnector
import dev.agentle.connectors.android.collectors.device.NetworkRecorder
import dev.agentle.connectors.android.collectors.device.PowerRecorder
import dev.agentle.connectors.android.collectors.device.SystemConnector
import dev.agentle.connectors.android.collectors.device.SystemRecorder
import dev.agentle.connectors.android.collectors.steps.StepBucket
import dev.agentle.connectors.android.collectors.steps.StepsConnector
import dev.agentle.connectors.android.collectors.steps.StepsRecordingGateway
import dev.agentle.connectors.android.collectors.usage.RawUsageEvent
import dev.agentle.connectors.android.collectors.usage.UsageConnector
import dev.agentle.connectors.android.collectors.usage.UsageEventSource
import dev.agentle.connectors.android.permissions.PlatformState
import dev.agentle.connectors.api.CapabilityIds
import dev.agentle.connectors.api.Connector
import dev.agentle.connectors.api.SyncResult
import dev.agentle.connectors.api.SyncTrigger
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The connector contract for every on-device connector with a seam: permission denied, permission revoked mid-run
 * (`SecurityException` from the seam), empty data, and process death (a new instance resumes from the stored cursor).
 */
@RunWith(RobolectricTestRunner::class)
@Config(minSdk = 29)
class ConnectorContractTest {
    private class Seams {
        var revoked = false
        var empty = false
        val usageBegins = ArrayList<Long>()
        val stepStarts = ArrayList<Instant>()

        val calendar = CalendarSource { begin, end, _ ->
            if (revoked) throw SecurityException()
            if (empty) {
                emptyList()
            } else {
                listOf(
                    CalendarInstance(1, begin + 2.hours.inWholeMilliseconds, begin + 3.hours.inWholeMilliseconds, false, true),
                )
            }
        }

        val usage = UsageEventSource { begin, end ->
            if (revoked) throw SecurityException()
            usageBegins += begin
            if (empty) emptyList() else listOf(RawUsageEvent(end - 60_000, 1, "com.example"), RawUsageEvent(end - 30_000, 2, "com.example"))
        }

        val steps = object : StepsRecordingGateway {
            override suspend fun subscribe(): Boolean = true

            override suspend fun readBuckets(start: Instant, end: Instant, bucket: Duration): List<StepBucket> {
                if (revoked) throw SecurityException()
                stepStarts += start
                return if (empty) emptyList() else listOf(StepBucket(start, start + bucket, 100))
            }
        }
    }

    private val seamConnectors = listOf("calendar", "usage", "steps")

    private fun TestScope.connector(name: String, t: TestRuntime, seams: Seams, denied: Set<String> = emptySet()): Connector {
        val perms = FakePermissions(t.clock, denied + CapabilityIds.HEALTH_CONNECT_ON_DEVICE_STEPS)
        val context = t.runtime.context
        return when (name) {
            "calendar" -> CalendarConnector(t.runtime, perms, seams.calendar)
            "usage" -> UsageConnector(t.runtime, perms, seams.usage, { 1 }, { null })
            "steps" -> StepsConnector(t.runtime, perms, seams.steps)
            "battery" -> BatteryConnector(t.runtime, perms, DeviceState(context), PowerRecorder(t.runtime))
            "network" -> NetworkConnector(t.runtime, perms, DeviceState(context), NetworkRecorder(t.runtime))
            "audio" -> AudioConnector(t.runtime, perms, DeviceState(context), AudioRecorder(t.runtime))
            "device" -> DeviceStateConnector(t.runtime, perms, DeviceState(context), DeviceRecorder(t.runtime))
            "system" -> SystemConnector(t.runtime, perms, DeviceState(context), PlatformState(context), SystemRecorder(t.runtime))
            else -> error(name)
        }
    }

    @Test
    fun `a denied connector writes nothing and reports no permission`() = runTest {
        for (name in seamConnectors + listOf("battery", "network", "audio", "device", "system")) {
            val t = TestRuntime(backgroundScope)
            val probe = connector(name, t, Seams())
            val result = connector(name, t, Seams(), denied = probe.capabilityIds.toSet()).sync(SyncTrigger.MANUAL)
            assertThat(t.writer.rows).isEmpty()
            assertThat(result.status).isAnyOf(SyncResult.Status.SKIPPED_NO_PERMISSION, SyncResult.Status.SUCCESS)
        }
    }

    @Test
    fun `a permission revoked mid-run is a permission state, not a crash`() = runTest {
        for (name in seamConnectors) {
            val t = TestRuntime(backgroundScope)
            val result = connector(name, t, Seams().apply { revoked = true }).sync(SyncTrigger.MANUAL)
            assertThat(result.status).isEqualTo(SyncResult.Status.SKIPPED_NO_PERMISSION)
            assertThat(t.writer.rows).isEmpty()
            assertThat(t.coverage.calls.any { it.startsWith("close:") && it.endsWith("PERMISSION_LOST") }).isTrue()
        }
    }

    @Test
    fun `empty data is a successful run with no rows`() = runTest {
        for (name in seamConnectors) {
            val t = TestRuntime(backgroundScope)
            val result = connector(name, t, Seams().apply { empty = true }).sync(SyncTrigger.MANUAL)
            assertThat(result.status).isEqualTo(SyncResult.Status.SUCCESS)
            assertThat(result.committed).isEqualTo(0)
            assertThat(t.writer.rows).isEmpty()
        }
    }

    @Test
    fun `after process death a new instance resumes from the stored cursor and converges`() = runTest {
        for (name in seamConnectors) {
            val t = TestRuntime(backgroundScope)
            val seams = Seams()
            assertThat(connector(name, t, seams).sync(SyncTrigger.MANUAL).status).isEqualTo(SyncResult.Status.SUCCESS)
            val rows = t.writer.rows.toMap()

            t.clock.advanceBy(30.minutes)
            val again = connector(name, t, seams).sync(SyncTrigger.MANUAL)
            assertThat(again.status).isEqualTo(SyncResult.Status.SUCCESS)
            assertThat(t.writer.rows.keys).containsAtLeastElementsIn(rows.keys)
        }
        val t = TestRuntime(backgroundScope)
        val seams = Seams()
        connector("usage", t, seams).sync(SyncTrigger.MANUAL)
        t.clock.advanceBy(30.minutes)
        connector("usage", t, seams).sync(SyncTrigger.MANUAL)
        // The second instance starts at (or overlapping just before) the first run's end, not at the first run's start.
        assertThat(seams.usageBegins[1]).isGreaterThan(seams.usageBegins[0])
    }
}
