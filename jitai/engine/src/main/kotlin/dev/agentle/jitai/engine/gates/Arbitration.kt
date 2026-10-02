package dev.agentle.jitai.engine.gates

import dev.agentle.jitai.dsl.model.ExperimentMode
import dev.agentle.jitai.dsl.model.ExperimentSpec
import dev.agentle.jitai.dsl.model.JitaiDefinition
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.security.MessageDigest
import kotlin.time.Instant

/**
 * Arbitration (R10 §9.4, gate G16): at most one delivery per pass. Candidates that passed G01-G15 are ranked by
 * effective priority (descending), then the older last delivery first (never delivered ranks first), then `createdAt`,
 * then `id`. The order is total, so the winner is deterministic.
 */
public object Arbitration {
    /** One candidate; [lastDelivered] is the wall time of its latest counted delivery, null when never delivered. */
    public data class Contender(val definition: JitaiDefinition, val priority: Int, val lastDelivered: Instant?)

    private val ORDER: Comparator<Contender> = compareByDescending<Contender> { it.priority }
        .thenBy(nullsFirst()) { it.lastDelivered }
        .thenBy { it.definition.createdAt }
        .thenBy { it.definition.id }

    /** [contenders] best first. */
    public fun rank(contenders: List<Contender>): List<Contender> = contenders.sortedWith(ORDER)
}

/**
 * Optional consented micro-randomization (R10 §15.3): at an available decision point the delivery happens iff
 * `u < p`, where `u` is the first 8 bytes of SHA-256(installSalt + decisionKey) read as an unsigned 64-bit integer divided
 * by 2^64. The draw is reproducible per decision (retries and recovery agree) and independent across decisions.
 */
public object MicroRandomization {
    public const val DEFAULT_PROBABILITY: Double = 0.5
    public const val MIN_PROBABILITY: Double = 0.3
    public const val MAX_PROBABILITY: Double = 0.7
    private const val DRAW_BYTES = 8
    private val TWO_POW_64: BigInteger = BigInteger.ONE.shiftLeft(64)

    /** The delivery probability in force, or null when experiment mode is off. */
    public fun probability(spec: ExperimentSpec): Double? = if (spec.mode ==
        ExperimentMode.MICRO_RANDOMIZED
    ) {
        (spec.deliverProbability ?: DEFAULT_PROBABILITY).coerceIn(MIN_PROBABILITY, MAX_PROBABILITY)
    } else {
        null
    }

    /** The unsigned 64-bit draw for [decisionKey]. */
    public fun drawBits(salt: ByteArray, decisionKey: String): BigInteger {
        val digest = MessageDigest.getInstance("SHA-256").digest(salt + decisionKey.toByteArray(Charsets.UTF_8))
        return BigInteger(1, digest.copyOfRange(0, DRAW_BYTES))
    }

    /** `u` in [0, 1) as a double (stored as `randDraw`). */
    public fun draw(salt: ByteArray, decisionKey: String): Double =
        BigDecimal(drawBits(salt, decisionKey)).divide(BigDecimal(TWO_POW_64), MathContext.DECIMAL64).toDouble()

    /** Exact `u < p`. */
    public fun deliver(salt: ByteArray, decisionKey: String, probability: Double): Boolean =
        BigDecimal(drawBits(salt, decisionKey)) < BigDecimal(probability).multiply(BigDecimal(TWO_POW_64))
}
