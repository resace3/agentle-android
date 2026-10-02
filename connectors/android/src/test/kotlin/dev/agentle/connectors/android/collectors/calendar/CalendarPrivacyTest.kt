package dev.agentle.connectors.android.collectors.calendar

import com.google.common.truth.Truth.assertThat
import dev.agentle.connectors.android.FakePermissions
import dev.agentle.connectors.android.TestRuntime
import dev.agentle.connectors.api.SyncResult
import dev.agentle.connectors.api.SyncTrigger
import dev.agentle.core.model.CalendarEventPayload
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.hours

@RunWith(RobolectricTestRunner::class)
@Config(minSdk = 29)
class CalendarPrivacyTest {
    private class RecordingSource(private val start: Long) : CalendarSource {
        val titleRequests = ArrayList<Boolean>()

        override fun instances(beginMs: Long, endMs: Long, includeTitles: Boolean): List<CalendarInstance> {
            titleRequests += includeTitles
            return listOfNotNull(
                CalendarInstance(
                    eventId = 42,
                    beginMs = start,
                    endMs = start + 1.hours.inWholeMilliseconds,
                    allDay = false,
                    busy = true,
                    attendeeCount = 3,
                    title = if (includeTitles) "Dentist" else null,
                ).takeIf { it.endMs > beginMs && it.beginMs < endMs },
            )
        }
    }

    @Test
    fun `titles are off by default and only times, busy, all-day and attendee count are stored`() = runTest {
        val t = TestRuntime(backgroundScope)
        val source = RecordingSource(t.clock.now().toEpochMilliseconds())
        val result = CalendarConnector(t.runtime, FakePermissions(t.clock), source).sync(SyncTrigger.MANUAL)

        assertThat(result.status).isEqualTo(SyncResult.Status.SUCCESS)
        assertThat(source.titleRequests).containsExactly(false)
        val payload = t.writer.rows.values.single().payload as CalendarEventPayload
        assertThat(payload.title).isNull()
        assertThat(payload.attendeeCount).isEqualTo(3)
        assertThat(payload.busy).isTrue()
        assertThat(payload.eventIdHash).doesNotContain("42")
    }

    @Test
    fun `titles are stored only after the user opted in`() = runTest {
        val t = TestRuntime(backgroundScope)
        t.settings.update { it.copy(calendarTitles = true) }
        val source = RecordingSource(t.clock.now().toEpochMilliseconds())
        CalendarConnector(t.runtime, FakePermissions(t.clock), source).sync(SyncTrigger.MANUAL)

        assertThat(source.titleRequests).containsExactly(true)
        assertThat((t.writer.rows.values.single().payload as CalendarEventPayload).title).isEqualTo("Dentist")
    }
}
