package dev.agentle.analytics.features.realtime

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.realtime.testing.LiveRead
import dev.agentle.core.common.AppError
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ClockAndDeviceFeaturesTest {
    @ParameterizedTest(name = "{0} at {1}")
    @CsvSource(
        "F1, 2026-10-01T12:00, SATURDAY;SUNDAY, THURSDAY, WEEKDAY, THURSDAY",
        "F2, 2026-10-02T23:30, SATURDAY;SUNDAY, FRIDAY, WEEKDAY, FRIDAY",
        "F3, 2026-10-03T01:00, SATURDAY;SUNDAY, SATURDAY, WEEKEND, FRIDAY",
        "F4, 2026-10-03T04:00, SATURDAY;SUNDAY, SATURDAY, WEEKEND, SATURDAY",
        "F5, 2026-10-02T12:00, FRIDAY;SATURDAY, FRIDAY, WEEKEND, FRIDAY",
        "F6, 2026-10-04T12:00, FRIDAY;SATURDAY, SUNDAY, WEEKDAY, SUNDAY",
    )
    fun `R10 12F day of week, day type and engine day of week`(
        id: String,
        local: String,
        weekend: String,
        dayOfWeek: DayOfWeek,
        dayType: String,
        engineDayOfWeek: DayOfWeek,
    ) = runTest {
        val weekendDays = weekend.split(';').map { DayOfWeek.valueOf(it) }.toSet()
        val f = RealtimeFixture(start = local, config = RealtimeFeatureConfig(weekendDays = weekendDays))

        val snapshot = f.snapshot(FeatureRef("day_of_week"), FeatureRef("day_type"), FeatureRef("engine_day_of_week"))

        assertWithMessage(id).that(snapshot[FeatureRef("day_of_week")])
            .isEqualTo(FeatureValue.Known(FeatureScalar.DayOfWeekValue(dayOfWeek), f.now))
        assertThat(snapshot[FeatureRef("day_type")]).isEqualTo(FeatureValue.Known(FeatureScalar.EnumValue(dayType), f.now))
        assertThat(snapshot[FeatureRef("engine_day_of_week")])
            .isEqualTo(FeatureValue.Known(FeatureScalar.DayOfWeekValue(engineDayOfWeek), f.now))
    }

    @ParameterizedTest(name = "{0} at {1}")
    @CsvSource(
        "O11, 2026-10-02T01:30, THURSDAY",
        "O11, 2026-10-02T03:59, THURSDAY",
        "O11, 2026-10-02T04:00, FRIDAY",
    )
    fun `R10 12O O11 the engine day rolls over at 04 00`(id: String, local: String, expected: DayOfWeek) = runTest {
        val f = RealtimeFixture(start = local)

        assertWithMessage(id).that(f.value("engine_day_of_week").knownScalar).isEqualTo(FeatureScalar.DayOfWeekValue(expected))
    }

    @Test
    fun `the engine day follows a configured rollover`() = runTest {
        val f =
            RealtimeFixture(
                start = "2026-10-02T01:30",
                config = RealtimeFeatureConfig(engineDayRollover = kotlinx.datetime.LocalTime(1, 0)),
            )

        assertThat(f.value("engine_day_of_week").knownScalar).isEqualTo(FeatureScalar.DayOfWeekValue(DayOfWeek.FRIDAY))
    }

    @ParameterizedTest(name = "{0} local_time at {1} is {2}")
    @CsvSource(
        "12B, 2026-10-01T21:59, 1319",
        "12B, 2026-10-01T22:00, 1320",
        "12B, 2026-10-01T23:59, 1439",
        "12B, 2026-10-02T00:00, 0",
        "12B, 2026-10-01T08:59, 539",
        "12B, 2026-10-01T09:00, 540",
        "12B, 2026-10-01T17:00, 1020",
        "12B, 2026-10-01T17:01, 1021",
        "E1, 2026-10-02T01:59, 119",
        "E1, 2026-10-02T02:00, 120",
        "E2, 2026-10-01T16:59, 1019",
        "A5, 2026-10-01T21:59, 1319",
        "A6, 2026-10-01T22:00, 1320",
    )
    fun `R10 local_time is the minute of day on the local wall clock`(id: String, local: String, minuteOfDay: Int) = runTest {
        val f = RealtimeFixture(start = local)

        assertWithMessage(id).that(f.value("local_time")).isEqualTo(FeatureValue.Known(FeatureScalar.LocalTimeValue(minuteOfDay), f.now))
    }

    @Test
    fun `local_time truncates to the minute`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T21:59")
        f.clock.advanceBy(59.seconds + 999.milliseconds)

        assertThat(f.value("local_time").knownScalar).isEqualTo(FeatureScalar.LocalTimeValue(1319))
    }

    @Test
    fun `local_time reads the zone at every pass`() = runTest {
        val f = RealtimeFixture(start = "2026-10-01T22:07:30")
        assertThat(f.value("local_time").knownScalar).isEqualTo(FeatureScalar.LocalTimeValue(22 * 60 + 7))

        f.clock.setZone(Zones.KOLKATA)

        // R10 12O O5: the same instant read in Asia/Kolkata is 01:37:30.
        assertThat(f.value("local_time").knownScalar).isEqualTo(FeatureScalar.LocalTimeValue(97))
        assertThat(f.snapshot(FeatureRef("local_time")).zoneId).isEqualTo("Asia/Kolkata")
    }

    @Test
    fun `R10 12B charging is true, false or unknown when the battery service fails`() = runTest {
        val f = RealtimeFixture()
        assertThat(f.value("charging").knownScalar).isEqualTo(FeatureScalar.BoolValue(false))

        f.inputs.live.state = f.inputs.live.state.copy(charging = true)
        assertThat(f.value("charging").knownScalar).isEqualTo(FeatureScalar.BoolValue(true))

        f.inputs.live.failures[LiveRead.CHARGING] = AppError.UnsupportedFeature("battery_state")
        assertThat(f.value("charging")).isEqualTo(missing(MissingReason.API_UNAVAILABLE))
    }

    @Test
    fun `battery_pct is the live capacity and an impossible reading is invalid`() = runTest {
        val f = RealtimeFixture()
        assertThat(f.value("battery_pct")).isEqualTo(knownInt(80, f.now))

        f.inputs.live.state = f.inputs.live.state.copy(batteryPercent = 101)
        assertThat(f.value("battery_pct")).isEqualTo(missing(MissingReason.INVALID_VALUE))

        f.inputs.live.unavailableReads[LiveRead.BATTERY] = MissingReason.API_UNAVAILABLE
        assertThat(f.value("battery_pct")).isEqualTo(missing(MissingReason.API_UNAVAILABLE))
    }

    @ParameterizedTest(name = "M7 interactive {0}")
    @CsvSource("true", "false")
    fun `R10 12M M7 device_interactive is the live power state`(interactive: Boolean) = runTest {
        val f = RealtimeFixture()
        f.inputs.live.state = f.inputs.live.state.copy(interactive = interactive)

        assertThat(f.value("device_interactive").knownScalar).isEqualTo(FeatureScalar.BoolValue(interactive))
    }

    @ParameterizedTest(name = "{0}: filter {1}")
    @CsvSource(
        "M8, PRIORITY, true",
        "M8, ALL, false",
        "B, NONE, true",
        "B, ALARMS, true",
    )
    fun `R10 dnd_active maps the interruption filter`(id: String, filter: InterruptionFilter, expected: Boolean) = runTest {
        val f = RealtimeFixture()
        f.inputs.live.state = f.inputs.live.state.copy(interruptionFilter = filter)

        assertWithMessage(id).that(f.value("dnd_active").knownScalar).isEqualTo(FeatureScalar.BoolValue(expected))
    }

    @Test
    fun `R10 12M M8 an UNKNOWN interruption filter is unknown`() = runTest {
        val f = RealtimeFixture()
        f.inputs.live.state = f.inputs.live.state.copy(interruptionFilter = InterruptionFilter.UNKNOWN)

        assertThat(f.value("dnd_active")).isEqualTo(missing(MissingReason.API_UNAVAILABLE))
    }

    @ParameterizedTest(name = "audio mode {0}")
    @EnumSource(AudioMode::class)
    fun `in_call is true only in a call or a communication session`(mode: AudioMode) = runTest {
        val f = RealtimeFixture()
        f.inputs.live.state = f.inputs.live.state.copy(audioMode = mode)

        val expected = mode == AudioMode.IN_CALL || mode == AudioMode.IN_COMMUNICATION
        assertThat(f.value("in_call").knownScalar).isEqualTo(FeatureScalar.BoolValue(expected))
    }

    @ParameterizedTest(name = "output {0}")
    @EnumSource(AudioOutputType::class)
    fun `headphones_connected is true for wired, A2DP, BLE and USB headsets`(type: AudioOutputType) = runTest {
        val f = RealtimeFixture()
        f.inputs.live.state = f.inputs.live.state.copy(audioOutputs = setOf(AudioOutputType.BUILTIN_SPEAKER, type))

        val headphones = setOf(
            AudioOutputType.WIRED_HEADPHONES,
            AudioOutputType.WIRED_HEADSET,
            AudioOutputType.BLUETOOTH_A2DP,
            AudioOutputType.BLE_HEADSET,
            AudioOutputType.USB_HEADSET,
        )
        assertThat(f.value("headphones_connected").knownScalar).isEqualTo(FeatureScalar.BoolValue(type in headphones))
    }

    @Test
    fun `a failing audio service makes in_call and headphones_connected unknown`() = runTest {
        val f = RealtimeFixture()
        f.inputs.live.failures[LiveRead.AUDIO_MODE] = AppError.Unexpected("IllegalStateException")
        f.inputs.live.failures[LiveRead.AUDIO_OUTPUTS] = AppError.Unexpected("IllegalStateException")

        assertThat(f.value("in_call")).isEqualTo(missing(MissingReason.API_UNAVAILABLE))
        assertThat(f.value("headphones_connected")).isEqualTo(missing(MissingReason.API_UNAVAILABLE))
    }

    @Test
    fun `a live read failing for a missing permission is NO_PERMISSION`() = runTest {
        val f = RealtimeFixture()
        f.inputs.live.failures[LiveRead.INTERRUPTION_FILTER] = AppError.PermissionDenied("notification_policy_access")

        assertThat(f.value("dnd_active")).isEqualTo(missing(MissingReason.NO_PERMISSION))
    }

    @Test
    fun `clock features are known without any port`() = runTest {
        val f = RealtimeFixture()
        f.inputs.ports.forEach { it.failure = AppError.DatabaseError("SQLiteException") }

        val snapshot = f.snapshot(
            FeatureRef("local_time"),
            FeatureRef("day_of_week"),
            FeatureRef("day_type"),
            FeatureRef("engine_day_of_week"),
        )

        assertThat(snapshot.values.values.all { it is FeatureValue.Known }).isTrue()
        assertThat(f.inputs.log.reads).isEmpty()
    }
}
