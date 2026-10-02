package dev.agentle.fakes.synth

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SplitMix64Test {
    @Test
    fun `the sequence matches the SplitMix64 reference values`() {
        // Reference output of SplitMix64 for seed 0 (Steele, Lea and Flood; also java.util.SplittableRandom's mixer).
        val rng = SplitMix64(0)
        assertThat(rng.nextLong()).isEqualTo(-2152535657050944081L) // 0xE220A8397B1DCDAF
        assertThat(rng.nextLong()).isEqualTo(7960286522194355700L) // 0x6E789E6AA1B965F4
        assertThat(rng.nextLong()).isEqualTo(487617019471545679L) // 0x06C45D188009454F
    }

    @Test
    fun `doubles are in the unit interval and ints in their range`() {
        val rng = SplitMix64(42)
        repeat(10_000) {
            assertThat(rng.nextDouble()).isAtLeast(0.0)
        }
        val ints = (1..10_000).map { rng.nextInt(-3, 4) }
        assertThat(ints.toSet()).containsExactly(-3, -2, -1, 0, 1, 2, 3)
        assertThrows<IllegalArgumentException> { rng.nextInt(5, 5) }
    }

    @Test
    fun `chance and gaussian are calibrated`() {
        val rng = SplitMix64(7)
        val hits = (1..20_000).count { rng.chance(0.25) }
        assertThat(hits / 20_000.0).isWithin(0.02).of(0.25)
        val samples = (1..20_000).map { rng.gaussian(60.0, 3.0) }
        assertThat(samples.average()).isWithin(0.1).of(60.0)
        assertThat(samples.minOrNull()!!).isAtLeast(60.0 - 6 * 3.0)
        assertThat(samples.maxOrNull()!!).isAtMost(60.0 + 6 * 3.0)
    }

    @Test
    fun `fork is keyed by label and does not advance the parent`() {
        val a = SplitMix64(42)
        val b = SplitMix64(42)
        val forkA = a.fork("days")
        assertThat(a.nextLong()).isEqualTo(b.nextLong())
        assertThat(forkA.nextLong()).isEqualTo(SplitMix64(42).fork("days").nextLong())
        assertThat(SplitMix64(42).fork("days").nextLong()).isNotEqualTo(SplitMix64(42).fork("dayz").nextLong())
    }
}
