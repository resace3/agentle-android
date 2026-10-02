package dev.agentle.core.common

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class RedactorTest {
    @Test
    fun `jwt access tokens are removed`() {
        val jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJlLXZhbHVl"
        val out = Redactor.redact("token issued: $jwt for user")
        assertThat(out).doesNotContain("eyJ")
        assertThat(out).contains("[REDACTED]")
    }

    @Test
    fun `oauth parameters in urls and json are removed`() {
        val out = Redactor.redact("""GET /auth/callback?code=abc123def&state=xyz789 {"refresh_token":"rt-secret-value"}""")
        assertThat(out).doesNotContain("abc123def")
        assertThat(out).doesNotContain("xyz789")
        assertThat(out).doesNotContain("rt-secret-value")
    }

    @Test
    fun `authorization headers and bearer tokens are removed`() {
        assertThat(Redactor.redact("Authorization: Bearer ya29.a0AfH6SMBx")).doesNotContain("ya29")
        assertThat(Redactor.redact("using bearer abcdefghijklmnop")).doesNotContain("abcdefghijklmnop")
    }

    @Test
    fun `emails coordinates and long numbers are masked`() {
        val out = Redactor.redact("nick@example.com at 52.520008,13.404954 called +4915112345678")
        assertThat(out).doesNotContain("nick@example.com")
        assertThat(out).contains("52.52")
        assertThat(out).doesNotContain("520008")
        assertThat(out).doesNotContain("15112345678")
    }

    @Test
    fun `plain diagnostic text is unchanged`() {
        val text = "sync finished: 42 events, 3 pages, status=SUCCESS"
        assertThat(Redactor.redact(text)).isEqualTo(text)
        assertThat(Redactor.containsSensitive(text)).isFalse()
    }

    @Test
    fun `logger redacts message and fields before any sink`() {
        val records = mutableListOf<LogRecord>()
        val logger = Logger(listOf(LogSink { records += it }), { 1L }, Severity.DEBUG)
        logger.i("auth", "refreshed refresh_token=rt-abc123456789", fields = mapOf("hdr" to "Bearer abcdefghijkl123"))
        assertThat(records).hasSize(1)
        assertThat(records[0].message).doesNotContain("rt-abc123456789")
        assertThat(records[0].fields.getValue("hdr")).doesNotContain("abcdefghijkl123")
    }
}

class SingleFlightTest {
    @Test
    fun `concurrent callers share one execution`() = runTest {
        val flight = SingleFlight<Int>()
        val calls = AtomicInteger()
        val results = (1..10).map {
            async {
                flight.run {
                    calls.incrementAndGet()
                    delay(100)
                    7
                }
            }
        }.awaitAll()
        assertThat(results.toSet()).containsExactly(7)
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `a new call after completion runs again`() = runTest {
        val flight = SingleFlight<Int>()
        val calls = AtomicInteger()
        flight.run { calls.incrementAndGet() }
        flight.run { calls.incrementAndGet() }
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    fun `failure propagates to all waiters and the next call retries`() = runTest {
        val flight = SingleFlight<Int>()
        val calls = AtomicInteger()
        val failures = (1..3).map {
            async {
                runCatching {
                    flight.run {
                        calls.incrementAndGet()
                        delay(50)
                        error("boom")
                    }
                }
            }
        }.awaitAll()
        assertThat(failures.all { it.isFailure }).isTrue()
        assertThat(calls.get()).isEqualTo(1)
        assertThat(flight.run { 1 }).isEqualTo(1)
    }

    @Test
    fun `outcomeOf maps exceptions and keeps app errors`() {
        val failure = outcomeOf { throw AppException(AppError.NetworkUnavailable("offline")) }
        assertThat(failure.errorOrNull()).isEqualTo(AppError.NetworkUnavailable("offline"))
        val unexpected = outcomeOf { error("x") }
        assertThat(unexpected.errorOrNull()).isInstanceOf(AppError.Unexpected::class.java)
        assertThat(outcomeOf { 5 }.getOrNull()).isEqualTo(5)
    }
}
