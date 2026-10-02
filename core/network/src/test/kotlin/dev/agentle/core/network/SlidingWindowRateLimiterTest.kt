package dev.agentle.core.network

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.testing.TestAgentleClock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SlidingWindowRateLimiterTest {
    private var now = Duration.ZERO
    private val googleHealthLimits = listOf(RateLimit(4, 1.seconds), RateLimit(200, 1.minutes))

    private fun limiter() = SlidingWindowRateLimiter(googleHealthLimits, elapsed = { now }, sleep = { now += it })

    @Test
    fun `a burst of four goes out at once and the fifth waits for the window`() = runTest {
        val limiter = limiter()
        repeat(4) { limiter.acquire() }
        assertThat(now).isEqualTo(Duration.ZERO)
        limiter.acquire()
        assertThat(now).isEqualTo(1.seconds)
    }

    @Test
    fun `no more than 200 requests in any minute`() = runTest {
        val limiter = limiter()
        val sentAt = mutableListOf<Duration>()
        repeat(450) {
            limiter.acquire()
            sentAt += now
        }
        for (i in 200 until sentAt.size) {
            assertThat(sentAt[i] - sentAt[i - 200]).isAtLeast(1.minutes - 1.milliseconds)
        }
    }

    @Test
    fun `no more than 4 requests in any second`() = runTest {
        val limiter = limiter()
        val sentAt = mutableListOf<Duration>()
        repeat(100) {
            limiter.acquire()
            sentAt += now
        }
        for (i in 4 until sentAt.size) {
            assertThat(sentAt[i] - sentAt[i - 4]).isAtLeast(1.seconds - 1.milliseconds)
        }
    }

    @Test
    fun `wall clock jumps cannot open the gate because time is monotonic`() = runTest {
        val clock = TestAgentleClock()
        val limiter = SlidingWindowRateLimiter(googleHealthLimits, elapsed = clock::elapsed)
        repeat(4) { limiter.acquire() }
        clock.setWallClock(clock.now() + 1.hours)
        assertThat(limiter.tryAcquire()).isFalse()
        clock.advanceBy(1.seconds)
        assertThat(limiter.tryAcquire()).isTrue()
    }

    @Test
    fun `tryAcquire never waits`() = runTest {
        val limiter = limiter()
        repeat(4) { assertThat(limiter.tryAcquire()).isTrue() }
        assertThat(limiter.tryAcquire()).isFalse()
        now += 999.milliseconds
        assertThat(limiter.tryAcquire()).isFalse()
        now += 1.milliseconds
        assertThat(limiter.tryAcquire()).isTrue()
    }
}
