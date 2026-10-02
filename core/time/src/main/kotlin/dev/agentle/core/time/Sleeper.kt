package dev.agentle.core.time

import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * Waits on the time base of an [AgentleClock]. Code that measures time with [AgentleClock.elapsed] waits with that
 * clock's [AgentleClock.sleeper], never with a raw `delay` (red team testing-build-19): under a test clock a raw
 * `delay` can skip virtual time while the clock stands still, and a loop such as "sleep until the window has passed"
 * then never ends.
 */
public fun interface Sleeper {
    /** Suspends for [duration]. Zero and negative durations do not wait. */
    public suspend fun sleep(duration: Duration)

    public companion object {
        /**
         * `delay`: real time on production dispatchers, virtual time on a test dispatcher. The default of every
         * [AgentleClock] whose [AgentleClock.elapsed] advances with that time.
         */
        public val Delay: Sleeper = Sleeper { duration -> delay(duration) }
    }
}
