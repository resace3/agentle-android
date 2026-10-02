package dev.agentle.core.common

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
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
    fun `diagnostic fields that merely contain a secret key name stay readable`() {
        val text = "siwcState=CONNECTED last_error_code=TOKEN_EXPIRED stateCount=3"
        assertThat(Redactor.redact(text)).isEqualTo(text)
    }

    @Test
    fun `compound secret keys and provider identifiers are removed`() {
        val out = Redactor.redact(
            "oauth_token=abcd1234efgh x-api-key: k-998877 client=oaiapp_Zx81kq2 host=urn:uuid:1b4e28ba-2fa1-11d2-883f-0016d3cca427 " +
                "rt_Q2hhdEdQVHJlZnJlc2gx at_YWNjZXNzdG9rZW5BQkNE",
        )
        assertThat(out).doesNotContain("abcd1234efgh")
        assertThat(out).doesNotContain("k-998877")
        assertThat(out).doesNotContain("oaiapp_Zx81kq2")
        assertThat(out).doesNotContain("1b4e28ba")
        assertThat(out).doesNotContain("Q2hhdEdQVHJlZnJlc2gx")
        assertThat(out).doesNotContain("YWNjZXNzdG9rZW5BQkNE")
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
    fun `a cancelled owner without a scope hands the run to the next waiter`() = runTest {
        val flight = SingleFlight<Int>()
        val calls = AtomicInteger()
        val owner = launch {
            flight.run {
                calls.incrementAndGet()
                delay(1_000)
                1
            }
        }
        runCurrent()
        val waiter = async {
            flight.run {
                calls.incrementAndGet()
                delay(10)
                2
            }
        }
        runCurrent()
        owner.cancel()
        assertThat(waiter.await()).isEqualTo(2)
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    fun `with a scope the run finishes even when every caller is cancelled`() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val flight = SingleFlight<Int>(appScope)
        val calls = AtomicInteger()
        val finished = CompletableDeferred<Int>()
        val block: suspend () -> Int = {
            calls.incrementAndGet()
            delay(100)
            finished.complete(42)
            42
        }
        val first = launch { flight.run(block) }
        val second = launch { flight.run(block) }
        runCurrent()
        first.cancel()
        second.cancel()
        assertThat(finished.await()).isEqualTo(42)
        assertThat(calls.get()).isEqualTo(1)
        // The finished run is not reused: the next call starts a new one.
        assertThat(flight.run { 7 }).isEqualTo(7)
        appScope.cancel()
    }

    @Test
    fun `with a scope a cancelled waiter does not disturb the others`() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val flight = SingleFlight<Int>(appScope)
        val calls = AtomicInteger()
        val block: suspend () -> Int = {
            calls.incrementAndGet()
            delay(100)
            5
        }
        val cancelled = launch { flight.run(block) }
        val survivor = async { flight.run(block) }
        runCurrent()
        cancelled.cancel()
        assertThat(survivor.await()).isEqualTo(5)
        assertThat(calls.get()).isEqualTo(1)
        appScope.cancel()
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
