package dev.agentle.core.network

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import kotlinx.coroutines.delay
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Exponential backoff with jitter for transient failures ([HttpErrorMapper.isTransient]). Waits are coroutine
 * delays, never blocked threads. A server `Retry-After` wins over the computed delay; one longer than
 * [maxRetryAfter] ends the attempt so the caller can reschedule instead of holding a worker.
 */
public data class RetryPolicy(
    val maxRetries: Int = 3,
    val initialDelay: Duration = 1.seconds,
    val maxDelay: Duration = 30.seconds,
    val multiplier: Double = 2.0,
    val jitterRatio: Double = 0.2,
    val maxRetryAfter: Duration = 2.minutes,
) {
    init {
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
        require(multiplier >= 1.0) { "multiplier must be >= 1" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be in 0..1" }
    }

    /** Delay before retry number [retry] (1-based) after [error], or null if the error must not be retried. */
    public fun delayFor(retry: Int, error: AppError, random: Random): Duration? {
        if (retry > maxRetries || !HttpErrorMapper.isTransient(error)) return null
        val retryAfter = (error as? AppError.RateLimited)?.retryAfter
        if (retryAfter != null) return retryAfter.takeIf { it <= maxRetryAfter }
        val base = (initialDelay * multiplier.pow(retry - 1)).coerceAtMost(maxDelay)
        val jitter = if (jitterRatio == 0.0) 0.0 else random.nextDouble(-jitterRatio, jitterRatio)
        return (base * (1 + jitter)).coerceAtLeast(Duration.ZERO)
    }

    public companion object {
        public val NONE: RetryPolicy = RetryPolicy(maxRetries = 0)
    }
}

/**
 * Runs [block] until it succeeds, fails with a non-transient error, or retries are exhausted. [block] receives the
 * 1-based attempt number. [sleep] and [random] are injectable so tests run without real time.
 */
public suspend fun <T> RetryPolicy.execute(
    random: Random = Random.Default,
    sleep: suspend (Duration) -> Unit = { delay(it) },
    onRetry: (attempt: Int, error: AppError, wait: Duration) -> Unit = { _, _, _ -> },
    block: suspend (attempt: Int) -> Outcome<T>,
): Outcome<T> {
    var attempt = 1
    while (true) {
        val result = block(attempt)
        val error = (result as? Outcome.Failure)?.error ?: return result
        val wait = delayFor(attempt, error, random) ?: return result
        onRetry(attempt, error, wait)
        sleep(wait)
        attempt++
    }
}
