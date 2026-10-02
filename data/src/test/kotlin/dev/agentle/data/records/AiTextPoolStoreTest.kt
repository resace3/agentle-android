package dev.agentle.data.records

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.datastore.AiConsentSnapshot
import dev.agentle.core.datastore.AiConsentStore
import dev.agentle.core.datastore.ConsentTicket
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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** The AI text pool (round 1 correction 3; engine relay): by content hash, used items readable until purged, consent-scoped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AiTextPoolStoreTest {
    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private val clock = TestAgentleClock(now, TimeZone.of("America/St_Johns"))
    private val data = TestDataAccess()
    private val consent = Consent(revision = 3)
    private val pool = RoomAiTextPoolStore(data.access, consent, clock)

    @After
    fun tearDown() = data.close()

    private class Consent(var revision: Int, var readable: Boolean = true) : AiConsentStore {
        override val snapshots: Flow<AiConsentSnapshot> = emptyFlow()

        override suspend fun snapshot() = AiConsentSnapshot(revision, emptySet(), readable)

        override suspend fun ticket(purpose: String, categories: Set<String>, accountSub: String?): ConsentTicket? = null

        override suspend fun revalidate(ticket: ConsentTicket): Boolean = false

        override suspend fun grant(category: String, purpose: String, accountSub: String?): Boolean = false

        override suspend fun revoke(category: String, purpose: String?): Boolean = false

        override suspend fun revokeCategories(categories: Set<String>?): Boolean = false
    }

    private fun item(id: String, hash: String = "c1", revision: Int = 3) = PooledTextRecord(
        id = id, jitaiId = "j1", contentHash = hash, title = "t", body = "b", consentRevision = revision, categories = emptySet(),
        snapshotHash = null, createdMs = now.toEpochMilliseconds(), expiresMs = (now + 48.hours).toEpochMilliseconds(),
    )

    @Test
    fun `pooled lists unused items of the content hash and get still returns a used item`() = runTest {
        pool.add(item("a"))
        pool.add(item("b"))
        pool.add(item("c", hash = "c2"))

        assertThat(pool.markUsed("a", "d1")).isTrue()
        assertThat(pool.markUsed("a", "d2")).isFalse()

        assertThat(pool.pooled("c1").map { it.id }).containsExactly("b")
        assertThat(pool.get("a")?.usedDecisionKey).isEqualTo("d1")
    }

    @Test
    fun `expiry is capped at 24 hours`() = runTest {
        pool.add(item("a"))

        assertThat(pool.get("a")?.expiresMs).isEqualTo((now + 24.hours).toEpochMilliseconds())
    }

    @Test
    fun `a consent change or an unreadable consent store voids the pool`() = runTest {
        pool.add(item("a"))
        consent.revision = 4
        assertThat(pool.pooled("c1")).isEmpty()

        pool.add(item("b", revision = 4))
        consent.readable = false
        assertThat(pool.get("b")).isNull()
    }

    @Test
    fun `purge keeps only the current content of the JITAI`() = runTest {
        pool.add(item("a"))
        pool.add(item("b", hash = "c2"))

        assertThat(pool.purge("j1", keepContentHash = "c2")).isEqualTo(1)
        assertThat(pool.pooled("c2").map { it.id }).containsExactly("b")
    }
}
