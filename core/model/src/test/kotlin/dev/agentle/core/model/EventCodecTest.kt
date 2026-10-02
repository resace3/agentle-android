package dev.agentle.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EventCodecTest {
    @Test
    fun `typed payloads round trip with a kind discriminator`() {
        val payloads = listOf(
            AppUsagePayload("com.example.social", durationMs = 120_000),
            NotificationPayload("com.example.chat", category = "msg", hasText = true),
            StepsPayload(1234),
            SleepSessionPayload(listOf(SleepStage(SleepStageKind.DEEP, 1, 2)), minutesAsleep = 400),
            BatteryPayload(55, PlugType.USB, charging = true),
            UserLogPayload(UserLogKind.MOOD, value = 4.0),
            NoPayload,
        )
        payloads.forEach { payload ->
            val json = EventCodec.encode(payload)
            assertThat(json).contains("\"kind\"")
            assertThat(EventCodec.decode(json)).isEqualTo(payload)
        }
    }

    @Test
    fun `unknown kinds from newer versions are preserved`() {
        val json = """{"kind":"brain_waves","alpha":3}"""
        val decoded = EventCodec.decode(json)
        assertThat(decoded).isEqualTo(UnknownPayload("brain_waves", json))
    }

    @Test
    fun `unknown fields of known kinds are ignored`() {
        val decoded = EventCodec.decode("""{"kind":"steps","count":5,"futureField":true}""")
        assertThat(decoded).isEqualTo(StepsPayload(5))
    }

    @Test
    fun `malformed known kinds fail loudly`() {
        assertThrows<SerializationException> { EventCodec.decode("""{"kind":"steps","count":"many"}""") }
        assertThrows<SerializationException> { EventCodec.decode("""{"count":5}""") }
    }

    @Test
    fun `every event type has a category and sensitive categories are flagged`() {
        assertThat(EventType.entries.map { it.category }.toSet()).isNotEmpty()
        assertThat(DataCategory.NOTIFICATION_CONTENT.sensitiveByDefault).isTrue()
        assertThat(DataCategory.LOCATION.sensitiveByDefault).isTrue()
        assertThat(DataCategory.SLEEP.sensitiveByDefault).isFalse()
    }

    @Test
    fun `data source ids are validated`() {
        assertThat(DataSourceId("googlehealth.steps").connectorId).isEqualTo("googlehealth")
        assertThrows<IllegalArgumentException> { DataSourceId("Bad Id") }
    }
}
