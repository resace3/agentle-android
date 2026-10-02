package dev.agentle.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class HealthPayloadsTest {
    @Test
    fun `health payloads round trip with every new field`() {
        val payloads = listOf(
            CaloriesPayload(3.68, EnergyBasis.ACTIVE),
            HeartRatePayload(73.0, motionContext = "SEDENTARY", sensorLocation = "WRIST"),
            HeartRatePayload(71.4, minBpm = 68.0, maxBpm = 76.0),
            RestingHeartRatePayload(54.0, LocalDate(2026, 9, 30), calculationMethod = "WITH_SLEEP"),
            DailyTotalPayload(LocalDate(2026, 9, 29), DailyTotalMetric.STEPS, 10412.0),
            ExercisePayload(
                exerciseType = "RUNNING",
                durationMs = 2_170_000,
                distanceMeters = 5012.0,
                activeDurationMs = 2_050_000,
                hasGps = true,
                heartRateZoneSeconds = mapOf("light" to 300L, "peak" to 70L),
                events = listOf(ExerciseEventEntry("START", 1_000L, -14_400)),
                startUtcOffsetSeconds = -14_400,
            ),
            SleepSessionPayload(
                stages = listOf(SleepStage(SleepStageKind.LIGHT, 1, 2, -14_400, -18_000)),
                isNap = true,
                sleepType = "CLASSIC",
                stagesStatus = "REJECTED_NAP",
                stageSummaries = listOf(SleepStageSummary(SleepStageKind.AWAKE, minutes = 17, count = 2)),
                shortAwakenings = listOf(SleepStage(SleepStageKind.AWAKE, 5, 6)),
            ),
            WeightPayload(72.8, notes = "after dinner"),
            WearableDevicePayload("hash", model = "Charge 6", batteryPercent = 82, deviceType = "TRACKER", batteryStatus = "High"),
        )
        payloads.forEach { payload -> assertThat(EventCodec.decode(EventCodec.encode(payload))).isEqualTo(payload) }
    }

    @Test
    fun `rows written before the new fields existed still decode`() {
        assertThat(EventCodec.decode("""{"kind":"heart_rate","bpm":70.0}""")).isEqualTo(HeartRatePayload(70.0))
        assertThat(EventCodec.decode("""{"kind":"calories","kilocalories":5.0}""")).isEqualTo(CaloriesPayload(5.0))
        assertThat(EventCodec.decode("""{"kind":"weight","kilograms":70.0}""")).isEqualTo(WeightPayload(70.0))
    }

    @Test
    fun `metadata and connector additions default to empty`() {
        val metadata = EventMetadata(ingestedAt = Instant.parse("2026-10-01T12:00:00Z"))
        assertThat(metadata.provenance).isNull()
        assertThat(metadata.upstreamId).isNull()
        assertThat(metadata.upstreamUpdatedAt).isNull()
        assertThat(metadata.payloadHash).isNull()
        val connector = ConnectorMetadata("googlehealth", "Google Health", true, ConnectionStatus.CONNECTED, PermissionState.ALLOWED)
        assertThat(connector.streamPermissions).isEmpty()
        assertThat(connector.coverageThrough).isEmpty()
        // Appended right after the v1 types; later appends (device-state types) follow it, never precede it.
        assertThat(EventType.entries.indexOf(EventType.DAILY_TOTAL)).isEqualTo(EventType.entries.indexOf(EventType.VIDEO_GENERATED) + 1)
        assertThat(EventType.DAILY_TOTAL.category).isEqualTo(DataCategory.ACTIVITY)
    }
}
