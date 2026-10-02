package dev.agentle.core.time

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SleeperTest {
    private class FixedClock : AgentleClock {
        override val wall: Clock = object : Clock {
            override fun now(): Instant = Instant.parse("2026-10-01T12:00:00Z")
        }

        override fun zone(): TimeZone = TimeZone.UTC

        override fun elapsed(): Duration = Duration.ZERO
    }

    @Test
    fun `Delay waits on the test dispatcher's virtual time`() = runTest {
        Sleeper.Delay.sleep(1.hours)
        assertThat(currentTime).isEqualTo(3_600_000)
    }

    @Test
    fun `Delay does not wait for zero or negative durations`() = runTest {
        Sleeper.Delay.sleep(Duration.ZERO)
        Sleeper.Delay.sleep((-1).seconds)
        assertThat(currentTime).isEqualTo(0)
    }

    @Test
    fun `a clock waits with Delay unless it overrides its sleeper`() {
        assertThat(FixedClock().sleeper).isSameInstanceAs(Sleeper.Delay)
    }

    @Test
    fun `the system clock waits with Delay, in real time like its elapsed time`() {
        assertThat(SystemAgentleClock().sleeper).isSameInstanceAs(Sleeper.Delay)
    }

    @Test
    fun `a lambda is a Sleeper`() = runTest {
        var slept = Duration.ZERO
        val sleeper = Sleeper { slept += it }
        sleeper.sleep(3.seconds)
        assertThat(slept).isEqualTo(3.seconds)
        assertThat(currentTime).isEqualTo(0)
    }
}
