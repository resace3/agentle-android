package dev.agentle.data.retention

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.datastore.AppSettings
import dev.agentle.core.datastore.RetentionPeriod
import dev.agentle.core.datastore.RetentionSettings
import dev.agentle.core.datastore.SettingsStore
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventId
import dev.agentle.core.model.EventMetadata
import dev.agentle.core.model.EventType
import dev.agentle.core.model.NotificationPayload
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.StepsPayload
import dev.agentle.core.testing.TestAgentleClock
import dev.agentle.data.TestDataAccess
import dev.agentle.data.records.AiTextPoolStore
import dev.agentle.data.records.PooledTextRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Retention per family (round 2 correction 4) and content text age-out (red team privacy-ai-16); idempotent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class RetentionServiceTest {
    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private val clock = TestAgentleClock(now, TimeZone.of("America/St_Johns"))
    private val data = TestDataAccess()

    @After
    fun tearDown() = data.close()

    private class Settings(value: AppSettings) : SettingsStore {
        override val settings: Flow<AppSettings> = MutableStateFlow(value)

        override suspend fun current(): AppSettings = (settings as MutableStateFlow).value

        override suspend fun update(transform: (AppSettings) -> AppSettings): Boolean = false
    }

    private object NoPool : AiTextPoolStore {
        override suspend fun add(item: PooledTextRecord) = Unit

        override suspend fun pooled(contentHash: String): List<PooledTextRecord> = emptyList()

        override suspend fun get(id: String): PooledTextRecord? = null

        override suspend fun markUsed(id: String, decisionKey: String): Boolean = false

        override suspend fun purge(jitaiId: String, keepContentHash: String?): Int = 0

        override suspend fun purgeExpired(createdBeforeMs: Long): Int = 0

        override suspend fun clear(): Int = 0
    }

    private fun service(retention: RetentionSettings) =
        RoomRetentionService(data.access, Settings(AppSettings(retention = retention)), clock, RoomContentTextPurger(data.access), NoPool) {
        }

    private fun event(key: String, connector: String, daysAgo: Int, type: EventType = EventType.STEP_SAMPLE) = PersonalEvent(
        id = EventId(key),
        type = type,
        source = DataSourceId.of(connector, "test"),
        startTime = now - daysAgo.days,
        zoneId = "America/St_Johns",
        payload = if (type == EventType.STEP_SAMPLE) StepsPayload(1) else NotificationPayload("com.chat", title = "Secret", text = "Body"),
        dedupKey = key,
        metadata = EventMetadata(ingestedAt = now),
    )

    private suspend fun keys(): List<String> = data.access.read { sql.queryTexts("SELECT id FROM event ORDER BY id") }

    @Test
    fun `each family keeps its own period and forever keeps everything`() = runTest {
        data.insert(
            listOf(event("a-old", "android", 40), event("a-new", "android", 10), event("w-old", "googlehealth", 400)),
            now.toEpochMilliseconds(),
        )

        val report = service(RetentionSettings(android = RetentionPeriod.DAYS_30)).run()

        assertThat(report.removed["event:ANDROID"]).isEqualTo(1)
        assertThat(keys()).containsExactly("a-new", "w-old")
        assertThat(service(RetentionSettings(android = RetentionPeriod.DAYS_30)).run().removed["event:ANDROID"]).isEqualTo(0)
    }

    @Test
    fun `captured text ages out after contentTextDays whatever the global retention`() = runTest {
        data.insert(
            listOf(
                event("n-old", "android", 8, EventType.NOTIFICATION_POSTED),
                event("n-new", "android", 2, EventType.NOTIFICATION_POSTED),
            ),
            now.toEpochMilliseconds(),
        )

        val report = service(RetentionSettings()).run()

        assertThat(report.removed["content_text"]).isEqualTo(1)
        assertThat(keys()).hasSize(2)
        val text = data.access.read { sql.queryTexts("SELECT payload_json FROM event WHERE id = 'n-old'") }.single()
        assertThat(text).doesNotContain("Secret")
    }
}
