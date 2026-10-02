package dev.agentle.data.deletion

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.database.ContentScrubber
import dev.agentle.core.database.EngineStateKeys
import dev.agentle.core.datastore.AiConsentSnapshot
import dev.agentle.core.datastore.AiConsentStore
import dev.agentle.core.datastore.ConsentTicket
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventId
import dev.agentle.core.model.EventMetadata
import dev.agentle.core.model.EventPayload
import dev.agentle.core.model.EventType
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.SleepSessionPayload
import dev.agentle.core.model.StepsPayload
import dev.agentle.core.testing.TestAgentleClock
import dev.agentle.data.TestDataAccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Category and family deletions (R04 SEC-DEL-01, SEC-DEL-05; round 4 correction 3, testing-build-20): rows go in
 * chunks, the import floor and AI consent revocation happen, and a crash at any stage resumes to the same result.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class DeletionServiceTest {
    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private val clock = TestAgentleClock(now, TimeZone.of("Australia/Adelaide"))
    private var data = TestDataAccess()
    private val consent = RecordingConsent()
    private val mediaDeleted = mutableListOf<String>()

    @After
    fun tearDown() = data.close()

    private fun service(checkpoint: suspend (String) -> Unit = {}) = RoomDeletionService(
        access = data.access,
        consent = consent,
        clock = clock,
        scrubber = ContentScrubber { json, _ -> json },
        media = MediaFileDeleter { mediaDeleted += it },
        chunkRows = 2,
        checkpoint = checkpoint,
    )

    private fun event(key: String, type: EventType, payload: EventPayload, connector: String, minutesAgo: Int) = PersonalEvent(
        id = EventId(key),
        type = type,
        source = DataSourceId.of(connector, "test"),
        startTime = now - minutesAgo.minutes,
        zoneId = "Australia/Adelaide",
        payload = payload,
        dedupKey = key,
        metadata = EventMetadata(ingestedAt = now),
    )

    private suspend fun seed() {
        val sleep = (1..5).map { event("sleep$it", EventType.SLEEP_SESSION, SleepSessionPayload(), "googlehealth", it) }
        val steps = (1..3).map { event("steps$it", EventType.STEP_SAMPLE, StepsPayload(it.toLong()), "android", it) }
        data.insert(sleep + steps, now.toEpochMilliseconds())
    }

    private suspend fun countOf(type: EventType): Long = data.access.read {
        sql.queryLong("SELECT COUNT(*) FROM event e JOIN term t ON t.id = e.type WHERE t.value = ?", type.name) ?: 0L
    }

    private suspend fun floorOf(scope: String): Long? =
        data.access.read { sql.queryLong("SELECT floor_ms FROM ingest_floor WHERE scope = ?", scope) }

    private suspend fun markerPresent(): Boolean = data.access.read { db.stateDao().state(EngineStateKeys.DELETION_MARKER) } != null

    @Test
    fun `a category deletion removes its rows only, raises its floor, revokes consent and verifies`() = runTest {
        seed()

        val report = service().delete(DeletionTarget.Category(DataCategory.SLEEP))

        assertThat(report.verified).isTrue()
        assertThat(report.processed["event"]).isEqualTo(5L)
        assertThat(countOf(EventType.SLEEP_SESSION)).isEqualTo(0)
        assertThat(countOf(EventType.STEP_SAMPLE)).isEqualTo(3)
        assertThat(floorOf("C:SLEEP")).isEqualTo(now.toEpochMilliseconds())
        assertThat(consent.revoked).containsExactly(setOf("SLEEP"))
        assertThat(markerPresent()).isFalse()
    }

    @Test
    fun `a family deletion removes the family's events and revokes every grant`() = runTest {
        seed()

        val report = service().delete(DeletionTarget.AndroidCollected)

        assertThat(report.verified).isTrue()
        assertThat(countOf(EventType.STEP_SAMPLE)).isEqualTo(0)
        assertThat(countOf(EventType.SLEEP_SESSION)).isEqualTo(5)
        assertThat(floorOf("G:ANDROID")).isEqualTo(now.toEpochMilliseconds())
        assertThat(consent.revoked).containsExactly(null)
    }

    @Test
    fun `the data epoch is bumped so in-flight sync batches are rejected`() = runTest {
        seed()
        val before = data.access.read { db.stateDao().state(EngineStateKeys.DATA_EPOCH)?.intValue } ?: 0L

        service().delete(DeletionTarget.Category(DataCategory.SLEEP))

        assertThat(data.access.read { db.stateDao().state(EngineStateKeys.DATA_EPOCH)?.intValue }).isEqualTo(before + 1)
    }

    @Test
    fun `a crash at every stage resumes to the same verified result`() = runTest {
        val stages = mutableListOf<String>()
        seed()
        service { stages += it }.delete(DeletionTarget.Category(DataCategory.SLEEP))
        assertThat(stages.size).isAtLeast(4)

        for (stage in stages) {
            data.close()
            data = TestDataAccess()
            consent.revoked.clear()
            seed()
            val crashed = runCatching { service { if (it == stage) throw Crash() }.delete(DeletionTarget.Category(DataCategory.SLEEP)) }
            assertThat(crashed.exceptionOrNull()).isInstanceOf(Crash::class.java)

            val resumed = service().resumePending() ?: service().delete(DeletionTarget.Category(DataCategory.SLEEP))

            assertThat(resumed.verified).isTrue()
            assertThat(countOf(EventType.SLEEP_SESSION)).isEqualTo(0)
            assertThat(countOf(EventType.STEP_SAMPLE)).isEqualTo(3)
            assertThat(consent.revoked).contains(setOf("SLEEP"))
            assertThat(markerPresent()).isFalse()
        }
    }

    @Test
    fun `a crash between chunks keeps the progress in the marker`() = runTest {
        seed()
        var chunks = 0
        val crashing = RoomDeletionService(
            data.access,
            consent,
            clock,
            ContentScrubber { json, _ -> json },
            MediaFileDeleter { },
            chunkRows = 2,
            checkpoint = { stage -> if (stage.startsWith(RoomDeletionService.STAGE_STEP) && ++chunks > 1) throw Crash() },
        )
        runCatching { crashing.delete(DeletionTarget.Category(DataCategory.SLEEP)) }

        assertThat(markerPresent()).isTrue()
        assertThat(service().resumePending()?.verified).isTrue()
        assertThat(countOf(EventType.SLEEP_SESSION)).isEqualTo(0)
    }

    @Test
    fun `an unreadable marker is ignored`() {
        assertThat(RoomDeletionService.decode("{not json")).isNull()
    }

    private class Crash : RuntimeException()

    private class RecordingConsent : AiConsentStore {
        val revoked = mutableListOf<Set<String>?>()
        override val snapshots: Flow<AiConsentSnapshot> = emptyFlow()

        override suspend fun snapshot(): AiConsentSnapshot = error("unused")

        override suspend fun ticket(purpose: String, categories: Set<String>, accountSub: String?): ConsentTicket? = null

        override suspend fun revalidate(ticket: ConsentTicket): Boolean = false

        override suspend fun grant(category: String, purpose: String, accountSub: String?): Boolean = false

        override suspend fun revoke(category: String, purpose: String?): Boolean = false

        override suspend fun revokeCategories(categories: Set<String>?): Boolean {
            revoked += categories
            return true
        }
    }
}
