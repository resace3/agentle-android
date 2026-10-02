package dev.agentle.core.network

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RetryPolicyTest {
    private val noJitter = RetryPolicy(jitterRatio = 0.0)

    @Test
    fun `transient failures back off exponentially and stop after maxRetries`() = runTest {
        val waits = mutableListOf<Duration>()
        var calls = 0
        val result = noJitter.execute<Int>(sleep = { waits += it }) {
            calls++
            Outcome.Failure(AppError.RemoteServerError(503))
        }
        assertThat(calls).isEqualTo(4)
        assertThat(waits).containsExactly(1.seconds, 2.seconds, 4.seconds).inOrder()
        assertThat(result).isEqualTo(Outcome.Failure(AppError.RemoteServerError(503)))
    }

    @Test
    fun `success after a transient failure returns the value`() = runTest {
        var calls = 0
        val result = noJitter.execute(sleep = {}) { attempt ->
            calls++
            if (attempt == 1) Outcome.Failure(AppError.NetworkUnavailable("reset")) else Outcome.Success("ok")
        }
        assertThat(result).isEqualTo(Outcome.Success("ok"))
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun `non transient failures are not retried`() = runTest {
        var calls = 0
        noJitter.execute<Unit>(sleep = {}) {
            calls++
            Outcome.Failure(AppError.RemoteServerError(404))
        }
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `Retry-After wins over backoff and a too long one ends the attempt`() = runTest {
        val waits = mutableListOf<Duration>()
        noJitter.execute<Unit>(sleep = { waits += it }) { attempt ->
            Outcome.Failure(AppError.RateLimited(if (attempt == 1) 7.seconds else 10.minutes))
        }
        assertThat(waits).containsExactly(7.seconds)
    }

    @Test
    fun `jitter stays within the configured ratio and delays are capped`() {
        val policy = RetryPolicy(maxRetries = 10, initialDelay = 1.seconds, maxDelay = 8.seconds, jitterRatio = 0.2)
        val random = Random(42)
        repeat(200) {
            val d = policy.delayFor(retry = 6, error = AppError.RemoteServerError(500), random = random)!!
            assertThat(d).isAtLeast(8.seconds * 0.8)
            assertThat(d).isAtMost(8.seconds * 1.2)
        }
    }

    @Test
    fun `token expiry is left to the token source, not the retry loop`() {
        assertThat(noJitter.delayFor(1, AppError.TokenExpired("p"), Random(1))).isNull()
    }
}
