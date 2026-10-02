package dev.agentle.fakes.synth

/**
 * Fully specified PRNG (SplitMix64, Steele, Lea and Flood 2014). `kotlin.random.Random(seed)` is not used because its
 * sequence is only guaranteed stable "within the same version of Kotlin runtime"; the synthetic user's golden hash
 * must survive Kotlin, JDK and Android upgrades (docs/research/08 §6.1).
 */
public class SplitMix64(seed: Long) {
    private var state: Long = seed

    public fun nextLong(): Long {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return z xor (z ushr 31)
    }

    /** Uniform double in `[0, 1)`. */
    public fun nextDouble(): Double = (nextLong() ushr 11) * DOUBLE_UNIT

    /** Uniform int in `[fromInclusive, untilExclusive)`. */
    public fun nextInt(fromInclusive: Int, untilExclusive: Int): Int {
        require(untilExclusive > fromInclusive) { "empty range $fromInclusive until $untilExclusive" }
        val span = (untilExclusive - fromInclusive).toLong()
        return (fromInclusive + nextLong().mod(span)).toInt()
    }

    public fun chance(probability: Double): Boolean = nextDouble() < probability

    /** Approximately normal (Irwin-Hall: the sum of 12 uniforms); deterministic and cheap. */
    public fun gaussian(mean: Double, sd: Double): Double {
        var sum = 0.0
        repeat(IRWIN_HALL_TERMS) { sum += nextDouble() }
        return mean + (sum - IRWIN_HALL_TERMS / 2.0) * sd
    }

    /**
     * An independent child stream keyed by [label]. Forking does not advance this generator, so adding a stream or a
     * day never shifts the values of any other.
     */
    public fun fork(label: String): SplitMix64 {
        var h = state xor FORK_BASIS
        for (c in label) {
            h = (h xor c.code.toLong()) * FNV_PRIME
        }
        return SplitMix64(SplitMix64(h).nextLong())
    }

    private companion object {
        const val GOLDEN_GAMMA = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        const val MIX_1 = -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        const val MIX_2 = -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        const val FORK_BASIS = -0x340d631b7bdddcdbL // 0xCBF29CE484222325 (FNV-1a offset basis)
        const val FNV_PRIME = 0x100000001b3L
        const val DOUBLE_UNIT = 1.0 / (1L shl 53)
        const val IRWIN_HALL_TERMS = 12
    }
}
