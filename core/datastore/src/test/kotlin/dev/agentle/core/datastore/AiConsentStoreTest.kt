package dev.agentle.core.datastore

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.testing.TestAgentleClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The AI consent allow-list denies on every fault (round 4 correction 6, testing-build-11): an IOException or a
 * corrupted file on read, a missing key, an unknown category, grants of another install or other terms, and a
 * toggle-off racing an in-flight request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AiConsentStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val diagnostics = RecordingDiagnostics()
    private val clock = TestAgentleClock(zone = TimeZone.of("America/St_Johns"))
    private val vocabulary = ConsentVocabulary(
        categories = setOf("SLEEP", "STEPS", "HEART"),
        purposes = setOf("SLEEP_INSIGHT", "ACTIVITY_INSIGHT"),
        termsVersion = 2,
    )

    private val file: File get() = File(temp.root, "ai-consent.json")

    private fun TestScope.store(install: String = INSTALL) =
        DataStoreAiConsentStore.create({ file }, backgroundScope, vocabulary, { install }, clock, diagnostics)

    private fun TestScope.faultyStore(): Pair<FaultyDataStore<AiConsentRecord>, DataStoreAiConsentStore> {
        val faulty = FaultyDataStore(
            jsonDataStore("ai-consent", { file }, AiConsentRecord.serializer(), AiConsentRecord(), backgroundScope, diagnostics) {
                AiConsentRecord(revision = DataStoreAiConsentStore.resetRevision())
            },
        )
        return faulty to DataStoreAiConsentStore.over(faulty, vocabulary, { INSTALL }, clock, diagnostics)
    }

    @Test
    fun `nothing is allowed by default`() = runTest {
        val store = store()

        assertThat(store.snapshot().grants).isEmpty()
        assertThat(store.snapshot().allows("SLEEP", "SLEEP_INSIGHT", SUB)).isFalse()
        assertThat(store.ticket("SLEEP_INSIGHT", setOf("SLEEP"), SUB)).isNull()
    }

    @Test
    fun `a grant allows exactly its category, purpose and account`() = runTest {
        val store = store()

        assertThat(store.grant("SLEEP", "SLEEP_INSIGHT", SUB)).isTrue()

        val snapshot = store.snapshot()
        assertThat(snapshot.allows("SLEEP", "SLEEP_INSIGHT", SUB)).isTrue()
        assertThat(snapshot.allows("SLEEP", "ACTIVITY_INSIGHT", SUB)).isFalse()
        assertThat(snapshot.allows("SLEEP", "SLEEP_INSIGHT", "another-account")).isFalse()
        assertThat(snapshot.allows("HEART", "SLEEP_INSIGHT", SUB)).isFalse()
        assertThat(store.ticket("SLEEP_INSIGHT", setOf("SLEEP", "HEART"), SUB)).isNull()
        assertThat(snapshot.grants.single().grantedAtMs).isEqualTo(clock.now().toEpochMilliseconds())
        assertThat(snapshot.grants.single().consentVersion).isEqualTo(2)
    }

    @Test
    fun `an IOException on read denies everything`() = runTest {
        val (faulty, store) = faultyStore()
        store.grant("SLEEP", "SLEEP_INSIGHT", SUB)
        val ticket = store.ticket("SLEEP_INSIGHT", setOf("SLEEP"), SUB)
        assertThat(ticket).isNotNull()

        faulty.failReads = true

        assertThat(store.snapshot().readable).isFalse()
        assertThat(store.snapshot().allows("SLEEP", "SLEEP_INSIGHT", SUB)).isFalse()
        assertThat(store.ticket("SLEEP_INSIGHT", setOf("SLEEP"), SUB)).isNull()
        assertThat(store.revalidate(ticket!!)).isFalse()
        assertThat(store.grant("STEPS", "ACTIVITY_INSIGHT", SUB)).isFalse()
        assertThat(diagnostics.ioFailures.map { it.second }).containsAtLeast("read", "write")
    }

    @Test
    fun `a corrupted file is reset to no grants at a fresh revision`() = runTest {
        file.writeText("""{"revision": 7, "installId": "$INSTALL", "grants": [{"category": "SLEEP", "purpose": """)

        val snapshot = store().snapshot()

        assertThat(snapshot.readable).isTrue()
        assertThat(snapshot.grants).isEmpty()
        assertThat(snapshot.allows("SLEEP", "SLEEP_INSIGHT", SUB)).isFalse()
        assertThat(snapshot.revision).isAtLeast(1 shl 30)
        assertThat(diagnostics.resets.single().first).isEqualTo("ai-consent")
    }

    @Test
    fun `a missing grants key denies`() = runTest {
        file.writeText("""{"revision": 3, "installId": "$INSTALL"}""")

        assertThat(store().ticket("SLEEP_INSIGHT", setOf("SLEEP"), SUB)).isNull()
    }

    @Test
    fun `a grant missing a required key resets the file, which denies`() = runTest {
        file.writeText(
            """{"revision": 3, "installId": "$INSTALL", "grants": [{"category": "SLEEP", "consentVersion": 2, "grantedAtMs": 1}]}""",
        )

        val store = store()

        assertThat(store.snapshot().grants).isEmpty()
        assertThat(store.ticket("SLEEP_INSIGHT", setOf("SLEEP"), null)).isNull()
        assertThat(diagnostics.resets).hasSize(1)
    }

    @Test
    fun `unknown categories and purposes, other terms and other installs are never in force`() = runTest {
        file.writeText(
            """{"revision": 4, "installId": "$INSTALL", "grants": [
                 {"category": "FUTURE_CATEGORY", "purpose": "SLEEP_INSIGHT", "consentVersion": 2, "grantedAtMs": 1, "accountSub": "$SUB"},
                 {"category": "SLEEP", "purpose": "FUTURE_PURPOSE", "consentVersion": 2, "grantedAtMs": 1, "accountSub": "$SUB"},
                 {"category": "STEPS", "purpose": "ACTIVITY_INSIGHT", "consentVersion": 1, "grantedAtMs": 1, "accountSub": "$SUB"}
               ]}""",
        )
        val store = store()

        val snapshot = store.snapshot()
        assertThat(snapshot.grants).isEmpty()
        assertThat(store.ticket("SLEEP_INSIGHT", setOf("FUTURE_CATEGORY"), SUB)).isNull()
        assertThat(store.grant("FUTURE_CATEGORY", "SLEEP_INSIGHT", SUB)).isFalse()
        assertThat(store.grant("SLEEP", "FUTURE_PURPOSE", SUB)).isFalse()
        assertThat(store.ticket("ACTIVITY_INSIGHT", setOf("STEPS"), SUB)).isNull()
    }

    @Test
    fun `grants restored from another install are void`() = runTest {
        file.writeText(
            """{"revision": 4, "installId": "a-previous-install", "grants": [
                 {"category": "SLEEP", "purpose": "SLEEP_INSIGHT", "consentVersion": 2, "grantedAtMs": 1, "accountSub": "$SUB"}]}""",
        )
        val store = store()

        assertThat(store.snapshot().allows("SLEEP", "SLEEP_INSIGHT", SUB)).isFalse()
        store.grant("STEPS", "ACTIVITY_INSIGHT", SUB)
        assertThat(store.snapshot().grants.map { it.category }).containsExactly("STEPS")
        assertThat(file.readText()).doesNotContain("a-previous-install")
    }

    @Test
    fun `a toggle-off racing an in-flight request denies the send`() = runTest {
        val store = store()
        store.grant("SLEEP", "SLEEP_INSIGHT", SUB)
        val ticket = store.ticket("SLEEP_INSIGHT", setOf("SLEEP"), SUB)!!
        val requestBuilt = CompletableDeferred<Unit>()

        val send = async {
            requestBuilt.await()
            store.revalidate(ticket)
        }
        launch { store.revoke("SLEEP") }.join()
        requestBuilt.complete(Unit)

        assertThat(send.await()).isFalse()
        assertThat(store.snapshot().allows("SLEEP", "SLEEP_INSIGHT", SUB)).isFalse()
    }

    @Test
    fun `any change after approval invalidates the ticket, and re-granting does not revive it`() = runTest {
        val store = store()
        store.grant("SLEEP", "SLEEP_INSIGHT", SUB)
        val ticket = store.ticket("SLEEP_INSIGHT", setOf("SLEEP"), SUB)!!
        assertThat(store.revalidate(ticket)).isTrue()

        store.grant("STEPS", "ACTIVITY_INSIGHT", SUB)
        assertThat(store.revalidate(ticket)).isFalse()

        store.revoke("SLEEP")
        store.grant("SLEEP", "SLEEP_INSIGHT", SUB)
        assertThat(store.revalidate(ticket)).isFalse()
        assertThat(store.revalidate(store.ticket("SLEEP_INSIGHT", setOf("SLEEP"), SUB)!!)).isTrue()
    }

    @Test
    fun `revoking categories or everything removes their grants and nothing else`() = runTest {
        val store = store()
        store.grant("SLEEP", "SLEEP_INSIGHT", SUB)
        store.grant("STEPS", "ACTIVITY_INSIGHT", SUB)
        store.grant("HEART", "ACTIVITY_INSIGHT", SUB)

        store.revokeCategories(setOf("SLEEP", "HEART"))
        assertThat(store.snapshot().grants.map { it.category }).containsExactly("STEPS")

        store.revokeCategories(null)
        assertThat(store.snapshot().grants).isEmpty()
    }

    private companion object {
        const val INSTALL = "0123456789abcdef0123456789abcdef"
        const val SUB = "account-sub-1"
    }
}
