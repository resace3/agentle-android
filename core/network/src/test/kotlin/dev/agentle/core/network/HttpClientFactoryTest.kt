package dev.agentle.core.network

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.LogRecord
import dev.agentle.core.common.LogSink
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Severity
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HttpClientFactoryTest {
    private val server = MockWebServer()
    private val records = mutableListOf<LogRecord>()
    private val logger = Logger(listOf(LogSink { records += it }), { 0L }, minSeverity = Severity.VERBOSE)

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `fake flavor reaches a loopback server over http and sends the user agent`() {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val client = HttpClientFactory.create(HttpClientConfig("Agentle/0.1 test", allowCleartextLoopback = true), logger)
        client.newCall(Request.Builder().url(server.url("/v4/x")).build()).execute().use { assertThat(it.code).isEqualTo(200) }
        assertThat(server.takeRequest().headers["User-Agent"]).isEqualTo("Agentle/0.1 test")
    }

    @Test
    fun `prod clients refuse cleartext even to loopback`() {
        val client = HttpClientFactory.create(HttpClientConfig("Agentle/0.1"), logger)
        assertThrows<CleartextNotPermittedException> {
            client.newCall(Request.Builder().url(server.url("/v4/x")).build()).execute()
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `cleartext to a non loopback host is refused before any connection`() {
        val client = HttpClientFactory.create(HttpClientConfig("Agentle/0.1", allowCleartextLoopback = true), logger)
        assertThrows<CleartextNotPermittedException> {
            client.newCall(Request.Builder().url("http://health.googleapis.com/v4/x").build()).execute()
        }
    }

    @Test
    fun `logs carry method host path and status but never query strings or headers`() {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val client = HttpClientFactory.create(HttpClientConfig("Agentle/0.1", allowCleartextLoopback = true), logger)
        val request = Request.Builder()
            .url(server.url("/auth/callback?code=secret-code-123&state=s1"))
            .header("Authorization", "Bearer secret-token-xyz")
            .build()
        client.newCall(request).execute().close()
        val text = records.joinToString("\n") { it.message + it.fields }
        assertThat(text).contains("GET")
        assertThat(text).contains("/auth/callback -> 200")
        assertThat(text).doesNotContain("secret-code-123")
        assertThat(text).doesNotContain("secret-token-xyz")
    }

    @Test
    fun `loopback detection never trusts names other than localhost`() {
        assertThat(TransportSecurityInterceptor.isLoopback("127.0.0.1")).isTrue()
        assertThat(TransportSecurityInterceptor.isLoopback("::1")).isTrue()
        assertThat(TransportSecurityInterceptor.isLoopback("localhost")).isTrue()
        assertThat(TransportSecurityInterceptor.isLoopback("10.0.2.2")).isFalse()
        assertThat(TransportSecurityInterceptor.isLoopback("127.0.0.1.nip.io")).isFalse()
        assertThat(TransportSecurityInterceptor.isLoopback("example.com")).isFalse()
    }
}
