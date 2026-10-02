package dev.agentle.data.jitai

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.testing.TestAgentleClock
import dev.agentle.data.TestDataAccess
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The contracts the JITAI engine's ports rely on (round 3 correction 1 and the engine team's relay): a definition change
 * deletes its timers but keeps OUTCOME timers, in the same transaction; pauseInvalid pauses with a notice; timers keep
 * offsetSeconds; commit compares the database generation; the ledger reports its retained range.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class JitaiStoreContractTest {
    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private val clock = TestAgentleClock(now, TimeZone.of("America/St_Johns"))
    private val data = TestDataAccess()
    private val definitions = RoomJitaiDefinitionStore(data.access, clock)
    private val ledger = RoomJitaiLedger(data.access, clock)

    @After
    fun tearDown() = data.close()

    private fun draft(hash: String = "h1") = DefinitionDraft(
        id = "j1", json = "{}", contentHash = hash, enabled = true, state = DefinitionStates.ACTIVE, kind = "INTERVENTION",
        category = "SLEEP", origin = "USER", expiresMs = null,
    )

    private suspend fun seedTimers() {
        val generation = ledger.engineState().dbGeneration
        val result = ledger.commit(generation) { writer ->
            writer.replaceTimers(
                listOf(
                    TimerRecord("slot", "SLOT", 1_000, jitaiId = "j1", offsetSeconds = -9_000),
                    TimerRecord("outcome", "OUTCOME", 2_000, jitaiId = "j1", decisionKey = "d1"),
                    TimerRecord("other", "SLOT", 3_000, jitaiId = "j2"),
                ),
            )
        }
        assertThat(result).isInstanceOf(LedgerCommit.Committed::class.java)
    }

    @Test
    fun `a definition change deletes its timers but keeps OUTCOME timers`() = runTest {
        definitions.save(draft())
        seedTimers()

        definitions.setEnabled("j1", false)

        assertThat(ledger.timers().map { it.key }).containsExactly("outcome", "other")
    }

    @Test
    fun `a new version deletes timers and a same-content save keeps them`() = runTest {
        definitions.save(draft())
        seedTimers()
        definitions.save(draft())
        assertThat(ledger.timers()).hasSize(3)

        definitions.save(draft(hash = "h2"))

        assertThat(definitions.definition("j1")?.version).isEqualTo(2)
        assertThat(ledger.timers().map { it.key }).containsExactly("outcome", "other")
    }

    @Test
    fun `pauseInvalid pauses with closed codes and deletes timers`() = runTest {
        definitions.save(draft())
        seedTimers()

        assertThat(definitions.pauseInvalid("j1", listOf("UNKNOWN_FEATURE", "free text: secret"), now.toEpochMilliseconds())).isTrue()

        val summary = definitions.definition("j1")!!
        assertThat(summary.state).isEqualTo(DefinitionStates.PAUSED)
        assertThat(ledger.timers().map { it.key }).containsExactly("outcome", "other")
        assertThat(definitions.pauseInvalid("missing", listOf("X"), 0)).isFalse()
        definitions.setState("j1", DefinitionStates.ACTIVE)
        assertThat(definitions.definition("j1")?.state).isEqualTo(DefinitionStates.ACTIVE)
    }

    @Test
    fun `timers keep offsetSeconds`() = runTest {
        seedTimers()

        assertThat(ledger.timers().single { it.key == "slot" }.offsetSeconds).isEqualTo(-9_000)
    }

    @Test
    fun `a commit for another database generation writes nothing`() = runTest {
        val result = ledger.commit("not-this-db") { writer -> writer.putTimer(TimerRecord("t", "SLOT", 1, jitaiId = "j1")) }

        assertThat(result).isEqualTo(LedgerCommit.GenerationMismatch)
        assertThat(ledger.timers()).isEmpty()
    }

    @Test
    fun `the ledger reports its retained range from the first commit, at most 400 days`() = runTest {
        assertThat(ledger.retainedRange(now.toEpochMilliseconds())).isNull()
        seedTimers()

        val later = (now + 500.days).toEpochMilliseconds()
        val range = ledger.retainedRange(later)!!

        assertThat(range.first).isEqualTo(later - 400.days.inWholeMilliseconds)
        assertThat(ledger.retainedRange(now.toEpochMilliseconds() + 1)!!.first).isEqualTo(now.toEpochMilliseconds())
    }
}
