package dev.agentle.fakes.googlehealth

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.testing.TestAgentleClock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** The fake over real loopback sockets: URLs, content types and the transport faults of docs/research/08 §5.5. */
class FakeGoogleHealthSocketTest {
    private val fake = FakeGoogleHealthServer(TestAgentleClock()).start()
    private val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()

    @AfterEach
    fun stop() {
        fake.close()
    }

    private fun get(url: String, http: OkHttpClient = client) = http.newCall(
        Request.Builder().url(url).header("Authorization", "Bearer ${FakeTokens.VALID}").header("Accept", "application/json").build(),
    ).execute()

    @Test
    fun `R08 5 2 production-shaped and scenario-prefixed base URLs reach the same fake`() {
        assertThat(fake.rootUrl()).startsWith("http://127.0.0.1:")
        get(fake.rootUrl() + "v4/users/me/identity").use { response ->
            assertThat(response.code).isEqualTo(200)
            assertThat(response.header("Content-Type")).isEqualTo(FakeResponse.JSON)
            assertThat(response.body.string()).isEqualTo(GoogleHealthFixtures.text("F-IDENTITY"))
        }
        val base = fake.scenarioUrl("rate-limited-retry-after")
        get(base + "v4/users/me/identity").use { assertThat(it.header("Retry-After")).isEqualTo("7") }
        get(base + "v4/users/me/identity").use { assertThat(it.code).isEqualTo(200) }
        get(fake.rootUrl() + "fake-googlehealth/scenarios").use { assertThat(it.body.string()).contains("\"timeout\"") }
        get(fake.rootUrl() + "v4/nonexistent").use { assertThat(it.header("Content-Type")).isEqualTo(FakeResponse.HTML) }
        val filter = "steps.interval.start_time >= \"2026-09-30T11:59:00Z\" AND steps.interval.start_time < \"2026-09-30T12:05:00Z\""
        val url = okhttp3.HttpUrl.Builder().scheme("http").host("127.0.0.1").port(java.net.URI(fake.rootUrl()).port)
            .addPathSegments(
                "v4/users/me/dataTypes/steps/dataPoints",
            ).addQueryParameter("filter", filter).addQueryParameter("pageSize", "3")
            .build()
        get(url.toString()).use { assertThat(it.body.string()).contains("\"count\":\"112\"") }
        assertThat(fake.journal.last().param("filter")).isEqualTo(filter)
        assertThat(fake.hygieneViolations()).isEmpty()
    }

    @Test
    fun `R08 5 5 disconnect mid body fails the first attempt only`() {
        val url = fake.scenarioUrl("disconnect-mid-body") + "v4/users/me/dataTypes/sleep/dataPoints"
        val failure = runCatching { get(url).use { it.body.string() } }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IOException::class.java)
        get(url).use { assertThat(it.body.string()).contains("7821966286120953001") }
    }

    @Test
    fun `R08 5 5 a stalled response times out and a throttled one still arrives`() {
        val fast = client.newBuilder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        val stalled = runCatching { get(fake.scenarioUrl("timeout") + "v4/users/me/identity", fast).use { it.body.string() } }
        assertThat(stalled.exceptionOrNull()).isInstanceOf(InterruptedIOException::class.java)
        get(fake.scenarioUrl("throttled") + "v4/users/me/identity").use {
            assertThat(it.body.string()).isEqualTo(GoogleHealthFixtures.text("F-IDENTITY"))
        }
    }

    @Test
    fun `R08 5 5 no content type and a captive portal`() {
        get(fake.scenarioUrl("no-content-type") + "v4/users/me/identity").use { assertThat(it.header("Content-Type")).isNull() }
        get(fake.scenarioUrl("captive-portal") + "v4/users/me/identity").use {
            assertThat(it.code).isEqualTo(200)
            assertThat(it.header("Content-Type")).isEqualTo(FakeResponse.HTML)
        }
        fake.inject(1, "E503", delay = kotlin.time.Duration.parse("10ms"))
        get(fake.rootUrl() + "v4/users/me/identity").use { assertThat(it.code).isEqualTo(503) }
    }
}
