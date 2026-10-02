package dev.agentle.data.records

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.model.DataCategory
import dev.agentle.data.TestDataAccess
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R04 SEC-AI-06 and red team privacy-ai-13: the audit row carries metadata only, never the account id or a payload. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AiAuditRepositoryTest {
    private val data = TestDataAccess()
    private val repository = RoomAiAuditRepository(data.access) { sub -> "h:" + sub.length }

    @After
    fun tearDown() = data.close()

    private fun start(sha: String = "a".repeat(64)) = AiRequestStart(
        id = "r1", purpose = "SLEEP_INSIGHT", categories = setOf(DataCategory.SLEEP), rangeStartMs = 1, rangeEndMs = 2,
        rawEventsSent = false, aggregatesSent = true, model = "gpt-x", createdMs = 10, bytesSent = 512, consentVersion = 3,
        payloadSha256 = sha, initiator = AiInitiator.USER, accountSub = "user-sub-123", promptVersion = "p7",
    )

    @Test
    fun `stores the audit columns and a salted sub hash only`() = runTest {
        repository.recordRequest(start())
        repository.finishRequest("r1", "OK", null, "gpt-x", providerRequestId = "req_abc")

        val row = repository.recent(10).single()
        assertThat(row.payloadSha256).isEqualTo("a".repeat(64))
        assertThat(row.initiator).isEqualTo(AiInitiator.USER)
        assertThat(row.accountSubHash).isEqualTo("h:12")
        assertThat(row.promptVersion).isEqualTo("p7")
        assertThat(row.consentVersion).isEqualTo(3)
        assertThat(row.providerRequestId).isEqualTo("req_abc")
        assertThat(row.status).isEqualTo("OK")
        val dump = data.access.read { sql.queryTexts("SELECT quote(account_sub_hash) || quote(purpose) FROM ai_request") }
        assertThat(dump.joinToString()).doesNotContain("user-sub-123")
    }

    @Test
    fun `free text never lands in the row`() = runTest {
        repository.recordRequest(start(sha = "not a hash"))
        repository.finishRequest("r1", "FAILED", "boom: secret text", null, providerRequestId = "id with spaces")

        val row = repository.recent(10).single()
        assertThat(row.payloadSha256).isNull()
        assertThat(row.errorCode).isEqualTo("REDACTED")
        assertThat(row.providerRequestId).isEqualTo("REDACTED")
    }
}
