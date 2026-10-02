package dev.agentle.data.retention

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.datastore.RetentionSettings
import dev.agentle.core.model.CalendarEventPayload
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventId
import dev.agentle.core.model.EventMetadata
import dev.agentle.core.model.EventPayload
import dev.agentle.core.model.EventType
import dev.agentle.core.model.NotificationPayload
import dev.agentle.core.model.PersonalEvent
import dev.agentle.data.TestDataAccess
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Red team privacy-ai-16: content text retention (7-day default, 30-day cap), the opt-out and default-SMS purges. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ContentTextPurgerTest {
    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private val data = TestDataAccess()
    private val purger = RoomContentTextPurger(data.access)

    @After
    fun tearDown() = data.close()

    private fun event(key: String, type: EventType, payload: EventPayload, at: Instant) = PersonalEvent(
        id = EventId(key),
        type = type,
        source = DataSourceId.of("android", "test"),
        startTime = at,
        zoneId = "America/St_Johns",
        payload = payload,
        dedupKey = key,
        metadata = EventMetadata(ingestedAt = now),
    )

    private fun note(key: String, pkg: String, at: Instant) =
        event(key, EventType.NOTIFICATION_POSTED, NotificationPayload(pkg, hasText = true, title = "Secret $key", text = "Body $key"), at)

    private suspend fun payloads(): List<String> = data.access.read { sql.queryTexts("SELECT payload_json FROM event ORDER BY seq") }

    @Test
    fun `turning content capture off for an app clears its text only, once`() = runTest {
        data.insert(listOf(note("a", "com.chat", now), note("b", "com.other", now)), now.toEpochMilliseconds())

        assertThat(purger.purgeNotificationText("com.chat")).isEqualTo(TextPurgeCounts(1, 0))
        assertThat(purger.purgeNotificationText("com.chat")).isEqualTo(TextPurgeCounts(0, 0))
        val rows = payloads()
        assertThat(rows).hasSize(2)
        assertThat(rows.single { "com.chat" in it }).doesNotContain("Secret")
        assertThat(rows.single { "com.other" in it }).contains("Secret b")
    }

    @Test
    fun `a package becoming the default SMS app loses its stored text`() = runTest {
        data.insert(listOf(note("s", "com.sms", now)), now.toEpochMilliseconds())

        assertThat(purger.purgeTextOfPackage("com.sms").total).isEqualTo(1)
        assertThat(payloads().single()).doesNotContain("Body s")
    }

    @Test
    fun `text older than the 7-day default ages out and the event stays`() = runTest {
        val days = RetentionSettings().contentTextDays
        assertThat(days).isEqualTo(7)
        val old = now - 8.days
        data.insert(
            listOf(
                note("old", "com.chat", old),
                note("new", "com.chat", now - 1.days),
                event("cal", EventType.CALENDAR_EVENT, CalendarEventPayload("h", allDay = false, title = "Doctor"), old),
            ),
            now.toEpochMilliseconds(),
        )
        val cutoff = (now - days.days).toEpochMilliseconds()

        assertThat(purger.purgeTextBefore(cutoff)).isEqualTo(TextPurgeCounts(1, 1))
        assertThat(purger.purgeTextBefore(cutoff).total).isEqualTo(0)
        val all = payloads()
        assertThat(all).hasSize(3)
        assertThat(all.joinToString()).doesNotContain("Secret old")
        assertThat(all.joinToString()).doesNotContain("Doctor")
        assertThat(all.joinToString()).contains("Secret new")
    }

    @Test
    fun `content text retention is capped at 30 days`() {
        assertThat(RetentionSettings(contentTextDays = 365).sanitized().contentTextDays).isEqualTo(30)
        assertThat(RetentionSettings(contentTextDays = 0).sanitized().contentTextDays).isEqualTo(1)
    }
}
