package dev.agentle.core.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

/** At most [permits] requests in any window of length [per]. */
public data class RateLimit(val permits: Int, val per: Duration) {
    init {
        require(permits > 0) { "permits must be > 0" }
        require(per.isPositive()) { "per must be > 0" }
    }
}

/**
 * Strict client-side quota: a request goes out only if, for every [RateLimit], fewer than `permits` requests were
 * sent in the trailing `per` window (Google Health: 4 per second and 200 per minute per user,
 * docs/ARCHITECTURE.md §7). Unlike a token bucket, which allows a full burst on top of the refill rate, no sliding
 * window can ever exceed the cap. Time comes from a monotonic source ([elapsed], `AgentleClock.elapsed`) so wall-clock
 * changes cannot open the gate. Callers suspend; no thread is blocked.
 */
public class SlidingWindowRateLimiter(
    limits: List<RateLimit>,
    private val elapsed: () -> Duration,
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
) {
    private class Window(val limit: RateLimit) {
        val sent = ArrayDeque<Duration>()

        fun evict(now: Duration) {
            while (sent.isNotEmpty() && sent.first() <= now - limit.per) sent.removeFirst()
        }

        fun waitTime(now: Duration): Duration = if (sent.size < limit.permits) Duration.ZERO else sent.first() + limit.per - now
    }

    private val mutex = Mutex()
    private val windows: List<Window>

    init {
        require(limits.isNotEmpty()) { "at least one limit" }
        windows = limits.map(::Window)
    }

    /** Suspends until every window has room, then records the request. */
    public suspend fun acquire() {
        while (true) {
            val wait = mutex.withLock { tryTake() }
            if (wait <= Duration.ZERO) return
            sleep(wait)
        }
    }

    /** Records the request if every window has room; never waits. */
    public suspend fun tryAcquire(): Boolean = mutex.withLock { tryTake() <= Duration.ZERO }

    /** Zero (and the request recorded) if allowed now, otherwise how long until it would be. */
    private fun tryTake(): Duration {
        val now = elapsed()
        windows.forEach { it.evict(now) }
        val wait = windows.maxOf { it.waitTime(now) }
        if (wait <= Duration.ZERO) windows.forEach { it.sent.addLast(now) }
        return wait
    }
}
