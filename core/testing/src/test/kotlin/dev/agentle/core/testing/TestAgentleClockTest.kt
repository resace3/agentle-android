package dev.agentle.core.testing

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.offsetAt
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TestAgentleClockTest {
    private val start = Instant.parse("2026-10-01T12:00:00Z")
    private val adelaide = TimeZone.of("Australia/Adelaide")

    @Nested
    inner class Defaults {
        @Test
        fun `a new clock reads the default start with nothing elapsed`() {
            val clock = TestAgentleClock()
            assertThat(clock.now()).isEqualTo(start)
            assertThat(clock.wall.now()).isEqualTo(start)
            assertThat(clock.elapsed()).isEqualTo(Duration.ZERO)
            assertThat(TestAgentleClock.DEFAULT_START).isEqualTo(start)
        }

        @Test
        fun `the default zone is Adelaide, a half-hour DST zone that is not the test JVM zone`() {
            val clock = TestAgentleClock()
            assertThat(clock.zone()).isEqualTo(adelaide)
            assertThat(TestAgentleClock.DEFAULT_ZONE).isEqualTo(adelaide)
            assertThat(clock.zone().offsetAt(start)).isEqualTo(UtcOffset(hours = 9, minutes = 30))
            // Daylight saving time starts on Sunday 2026-10-04 at 02:00 local time.
            assertThat(clock.zone().offsetAt(Instant.parse("2026-10-04T12:00:00Z"))).isEqualTo(UtcOffset(hours = 10, minutes = 30))
            assertThat(clock.zone().id).isNotEqualTo(System.getProperty("user.timezone"))
        }

        @Test
        fun `today turns at local midnight, 14-30 UTC, not at UTC midnight`() {
            val clock = TestAgentleClock()
            assertThat(clock.today()).isEqualTo(LocalDate(2026, 10, 1))
            clock.advanceBy(2.hours + 29.minutes + 59.seconds)
            assertThat(clock.today()).isEqualTo(LocalDate(2026, 10, 1))
            clock.advanceBy(1.seconds)
            assertThat(clock.now()).isEqualTo(Instant.parse("2026-10-01T14:30:00Z"))
            assertThat(clock.today()).isEqualTo(LocalDate(2026, 10, 2))
        }

        @Test
        fun `start and zone can still be given positionally`() {
            val clock = TestAgentleClock(Instant.parse("2027-01-01T00:00:00Z"), TimeZone.UTC)
            assertThat(clock.now()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"))
            assertThat(clock.zone()).isEqualTo(TimeZone.UTC)
        }
    }

    @Nested
    inner class Manual {
        @Test
        fun `advanceBy moves wall and elapsed time together`() {
            val clock = TestAgentleClock()
            clock.advanceBy(90.seconds)
            clock.advanceBy(Duration.ZERO)
            assertThat(clock.now()).isEqualTo(start + 90.seconds)
            assertThat(clock.elapsed()).isEqualTo(90.seconds)
        }

        @Test
        fun `advanceBy keeps sub-millisecond precision`() {
            val clock = TestAgentleClock()
            clock.advanceBy(1500.microseconds)
            assertThat(clock.elapsed()).isEqualTo(1500.microseconds)
        }

        @Test
        fun `advanceBy refuses to go backwards`() {
            val clock = TestAgentleClock()
            assertThrows<IllegalArgumentException> { clock.advanceBy((-1).seconds) }
            assertThat(clock.elapsed()).isEqualTo(Duration.ZERO)
        }

        @Test
        fun `setWallClock moves only the wall clock, both ways`() {
            val clock = TestAgentleClock()
            clock.advanceBy(10.seconds)
            clock.setWallClock(start + 3.hours)
            assertThat(clock.now()).isEqualTo(start + 3.hours)
            clock.setWallClock(start - 1.hours)
            assertThat(clock.now()).isEqualTo(start - 1.hours)
            assertThat(clock.elapsed()).isEqualTo(10.seconds)
            clock.advanceBy(5.seconds)
            assertThat(clock.now()).isEqualTo(start - 1.hours + 5.seconds)
            assertThat(clock.elapsed()).isEqualTo(15.seconds)
        }

        @Test
        fun `setZone changes the zone and the local day, not the instant`() {
            val clock = TestAgentleClock()
            clock.setZone(TimeZone.of("America/Los_Angeles"))
            assertThat(clock.zone()).isEqualTo(TimeZone.of("America/Los_Angeles"))
            assertThat(clock.now()).isEqualTo(start)
            clock.setWallClock(Instant.parse("2026-10-02T03:00:00Z"))
            assertThat(clock.today()).isEqualTo(LocalDate(2026, 10, 1))
            clock.setZone(adelaide)
            assertThat(clock.today()).isEqualTo(LocalDate(2026, 10, 2))
        }

        @Test
        fun `the sleeper advances the clock by the slept duration`() = runTest {
            val clock = TestAgentleClock()
            clock.sleeper.sleep(90.seconds)
            assertThat(clock.now()).isEqualTo(start + 90.seconds)
            assertThat(clock.elapsed()).isEqualTo(90.seconds)
            assertThat(currentTime).isEqualTo(90_000)
        }

        @Test
        fun `the sleeper does not move the clock for zero or negative durations`() = runTest {
            val clock = TestAgentleClock()
            clock.sleeper.sleep(Duration.ZERO)
            clock.sleeper.sleep((-1).seconds)
            assertThat(clock.elapsed()).isEqualTo(Duration.ZERO)
            assertThat(clock.now()).isEqualTo(start)
        }

        @Test
        fun `a loop that sleeps until elapsed time has passed ends (testing-build-19)`() = runTest {
            val clock = TestAgentleClock()
            val deadline = clock.elapsed() + 1.seconds
            var sleeps = 0
            while (clock.elapsed() < deadline) {
                clock.sleeper.sleep(300.milliseconds)
                sleeps++
            }
            assertThat(sleeps).isEqualTo(4)
            assertThat(clock.elapsed()).isEqualTo(1200.milliseconds)
        }
    }

    @Nested
    inner class VirtualTime {
        @Test
        fun `the scheduler's virtual time drives elapsed and wall time`() = runTest {
            val clock = TestAgentleClock(scheduler = testScheduler)
            assertThat(clock.elapsed()).isEqualTo(Duration.ZERO)
            delay(1500.milliseconds)
            assertThat(clock.elapsed()).isEqualTo(1500.milliseconds)
            assertThat(clock.now()).isEqualTo(start + 1500.milliseconds)
        }

        @Test
        fun `start is the wall time at virtual time zero`() = runTest {
            delay(1.seconds)
            val clock = TestAgentleClock(scheduler = testScheduler)
            assertThat(clock.elapsed()).isEqualTo(1.seconds)
            assertThat(clock.now()).isEqualTo(start + 1.seconds)
        }

        @Test
        fun `advanceBy advances the scheduler and runs what is due by then`() = runTest {
            val clock = TestAgentleClock(scheduler = testScheduler)
            var wokeAt: Duration? = null
            launch {
                delay(5.seconds)
                wokeAt = clock.elapsed()
            }
            runCurrent()
            clock.advanceBy(5.seconds)
            assertThat(wokeAt).isEqualTo(5.seconds)
            assertThat(currentTime).isEqualTo(5_000)
            assertThat(clock.now()).isEqualTo(start + 5.seconds)
        }

        @Test
        fun `advanceBy works on a scheduler outside runTest`() {
            val scheduler = TestCoroutineScheduler()
            val clock = TestAgentleClock(scheduler = scheduler)
            clock.advanceBy(10.minutes)
            assertThat(scheduler.currentTime).isEqualTo(600_000)
            assertThat(clock.elapsed()).isEqualTo(10.minutes)
            assertThat(clock.now()).isEqualTo(start + 10.minutes)
        }

        @Test
        fun `advanceBy refuses negative and sub-millisecond durations`() = runTest {
            val clock = TestAgentleClock(scheduler = testScheduler)
            assertThrows<IllegalArgumentException> { clock.advanceBy((-1).milliseconds) }
            assertThrows<IllegalArgumentException> { clock.advanceBy(1500.microseconds) }
            assertThat(currentTime).isEqualTo(0)
        }

        @Test
        fun `setWallClock adds an offset that virtual time keeps moving`() = runTest {
            val clock = TestAgentleClock(scheduler = testScheduler)
            delay(2.seconds)
            val changed = Instant.parse("2026-12-24T18:00:00Z")
            clock.setWallClock(changed)
            assertThat(clock.now()).isEqualTo(changed)
            assertThat(clock.elapsed()).isEqualTo(2.seconds)
            delay(3.seconds)
            assertThat(clock.now()).isEqualTo(changed + 3.seconds)
            assertThat(clock.elapsed()).isEqualTo(5.seconds)
            clock.setWallClock(start - 1.hours)
            assertThat(clock.now()).isEqualTo(start - 1.hours)
            assertThat(clock.elapsed()).isEqualTo(5.seconds)
        }

        @Test
        fun `setZone works the same with a scheduler`() = runTest {
            val clock = TestAgentleClock(scheduler = testScheduler)
            clock.setZone(TimeZone.UTC)
            assertThat(clock.zone()).isEqualTo(TimeZone.UTC)
        }

        @Test
        fun `concurrent sleepers share virtual time`() = runTest {
            val clock = TestAgentleClock(scheduler = testScheduler)
            val first = launch { clock.sleeper.sleep(1.seconds) }
            val second = launch { clock.sleeper.sleep(1.seconds) }
            joinAll(first, second)
            assertThat(clock.elapsed()).isEqualTo(1.seconds)
            assertThat(clock.now()).isEqualTo(start + 1.seconds)
        }

        @Test
        fun `a loop on clock elapsed that waits with delay ends (testing-build-19 scenario 1)`() = runTest {
            val clock = TestAgentleClock(scheduler = testScheduler)
            val deadline = clock.elapsed() + 1.seconds
            while (clock.elapsed() < deadline) delay(250.milliseconds)
            assertThat(clock.elapsed()).isEqualTo(1.seconds)
        }
    }
}
