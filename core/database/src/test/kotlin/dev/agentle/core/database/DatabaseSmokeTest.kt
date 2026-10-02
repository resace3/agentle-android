package dev.agentle.core.database

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.database.entity.EventEntity
import dev.agentle.core.database.entity.TermEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The schema opens on the test driver, seeds its bookkeeping rows and round-trips an event. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseSmokeTest {
    private lateinit var database: AgentleDatabase
    private lateinit var transactions: DatabaseTransactions

    @Before
    fun setUp() {
        database = TestDatabases.inMemory()
        transactions = TestDatabases.transactions(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `creation seeds the engine state with a random generation and zero counters`() = runTest {
        val generation = transactions.read {
            queryText("SELECT text_value FROM engine_state WHERE name = ?", EngineStateKeys.DB_GENERATION)
        }
        assertThat(generation).isNotNull()
        assertThat(generation!!.length).isEqualTo(36)
        val counters = transactions.read {
            query("SELECT name, int_value FROM engine_state WHERE int_value IS NOT NULL ORDER BY name") { it.text(0) to it.long(1) }
        }
        assertThat(counters.toMap()).containsExactly(
            EngineStateKeys.CHANGE_SEQ,
            0L,
            EngineStateKeys.DATA_EPOCH,
            0L,
            EngineStateKeys.JITAI_DIRTY,
            0L,
            EngineStateKeys.JITAI_WATERMARK,
            0L,
        )
    }

    @Test
    fun `secure delete is on for the connection`() = runTest {
        assertThat(transactions.read { queryLong("PRAGMA secure_delete") }).isEqualTo(1L)
    }

    @Test
    fun `an event round-trips through the writer and the DAO`() = runTest {
        val seq = transactions.write {
            val type = database.termDao().insert(TermEntity(kind = TermKind.TYPE, value = "STEP_SAMPLE"))
            val source = database.termDao().insert(TermEntity(kind = TermKind.SOURCE, value = "googlehealth.steps"))
            database.eventDao().insert(event(type, source, dedupHash = 42L))
        }
        val stored = database.eventDao().bySeq(seq)
        assertThat(stored).isNotNull()
        assertThat(stored!!.dedupKey).isEqualTo("k1")
        assertThat(database.eventDao().byDedupHashes(listOf(42L, 43L)).map { it.seq }).containsExactly(seq)
        assertThat(database.eventDao().count()).isEqualTo(1L)
    }

    @Test
    fun `a failing write rolls back and notifies rollback listeners`() = runTest {
        var rolledBack = 0
        transactions.addRollbackListener { rolledBack++ }
        val failure = runCatching {
            transactions.write {
                database.termDao().insert(TermEntity(kind = TermKind.TYPE, value = "HEART_RATE"))
                error("boom")
            }
        }
        assertThat(failure.isFailure).isTrue()
        assertThat(rolledBack).isEqualTo(1)
        assertThat(database.termDao().find(TermKind.TYPE, "HEART_RATE")).isNull()
    }

    @Test
    fun `a write inside a write joins the outer transaction`() = runTest {
        transactions.write {
            transactions.write { database.termDao().insert(TermEntity(kind = TermKind.TYPE, value = "SLEEP_SESSION")) }
            assertThat(database.termDao().find(TermKind.TYPE, "SLEEP_SESSION")).isNotNull()
        }
    }

    private fun event(type: Long, source: Long, dedupHash: Long) = EventEntity(
        id = "e1",
        type = type,
        source = source,
        account = TermKind.NO_ACCOUNT,
        startMs = 1_000L,
        endMs = 61_000L,
        startOffsetS = 0,
        endOffsetS = 0,
        localDate = null,
        zoneId = "UTC",
        dedupHash = dedupHash,
        dedupKey = "k1",
        upstreamId = null,
        upstreamUpdateMs = null,
        payloadHash = 7L,
        subject = null,
        valueNum = 12.0,
        payloadJson = """{"kind":"steps","count":12}""",
        payloadVersion = 1,
        confidence = null,
        ingestedMs = 2_000L,
        sensitivity = 0,
        origin = null,
        provenanceJson = null,
        changeSeq = 1L,
        changeKind = 0,
    )
}
