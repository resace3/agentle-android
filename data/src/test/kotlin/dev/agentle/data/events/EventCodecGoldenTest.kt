package dev.agentle.data.events

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.model.AppUsagePayload
import dev.agentle.core.model.CalendarEventPayload
import dev.agentle.core.model.EventCodec
import dev.agentle.core.model.NoPayload
import dev.agentle.core.model.NotificationPayload
import dev.agentle.core.model.SleepSessionPayload
import dev.agentle.core.model.StepsPayload
import dev.agentle.core.model.UnknownPayload
import dev.agentle.core.model.UserLogKind
import dev.agentle.core.model.UserLogPayload
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Golden corpus of persisted event payloads (round 4 correction 4, testing-build-09): `payload_json` written by
 * version 1 must keep decoding with the current code. A change that breaks a line needs a migration, not a new golden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class EventCodecGoldenTest {
    private val lines: List<String> =
        checkNotNull(javaClass.classLoader?.getResource("golden/event-payloads.v1.jsonl")).readText().lines().filter { it.isNotBlank() }

    @Test
    fun `every v1 payload decodes, and only the unknown kind is unknown`() {
        val decoded = lines.map(EventCodec::decode)

        assertThat(decoded.filterIsInstance<UnknownPayload>()).hasSize(1)
        assertThat(decoded[0]).isEqualTo(NoPayload)
        assertThat(decoded[1]).isEqualTo(AppUsagePayload("com.example.social", durationMs = 90_000, appCategory = "social"))
        assertThat(decoded[2]).isEqualTo(StepsPayload(1234))
        assertThat((decoded[6] as SleepSessionPayload).minutesAsleep).isEqualTo(412)
        val notification = decoded[7] as NotificationPayload
        assertThat(notification.keyHash).isEqualTo("k1")
        assertThat(notification.title).isNull()
        assertThat((decoded[8] as CalendarEventPayload).attendeeCount).isEqualTo(3)
        assertThat(decoded[9]).isEqualTo(UserLogPayload(UserLogKind.MOOD, value = 4.0))
    }

    @Test
    fun `the current encoder's output decodes to the same payloads`() {
        lines.map(EventCodec::decode).filterNot { it is UnknownPayload }.forEach { payload ->
            assertThat(EventCodec.decode(EventCodec.encode(payload))).isEqualTo(payload)
        }
    }
}
