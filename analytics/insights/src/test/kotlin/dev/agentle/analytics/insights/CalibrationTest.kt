package dev.agentle.analytics.insights

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * Reproduces the seeded calibration of docs/research/10 §14.6 with the production analyzer: the generator of the
 * calibration script (`mine.py`, Python 3.11 `random.Random`) is ported exactly ([PythonRandom]), so seeds 1-1000 give
 * the same nights, and the table's counts must come out exactly. "Run k" is nights 1..N, "run k+1" nights 8..N+7; claims
 * are counted in run k+1 and a proposal is a pattern (hypothesis and sign) at MODERATE or above in both runs.
 */
class CalibrationTest {
    @Test
    fun `the port of Python's random gives Python's numbers`() {
        val r = PythonRandom(1)
        assertThat(listOf(r.random(), r.random(), r.random())).containsExactly(0.13436424411240122, 0.8474337369372327, 0.763774618976614)
            .inOrder()
        val s = PythonRandom(42)
        assertThat(s.normalvariate(0.0, 1.0)).isEqualTo(0.2453263417078634)
        assertThat(s.lognormvariate(StrictMath.log(28.0), 0.6)).isEqualTo(20.78222026330965)
        assertThat(s.uniform(0.2, 0.8)).isEqualTo(0.6418827284984074)
    }

    @Test
    fun `the port of the generator gives the script's nights`() {
        // Seed 7, 10 nights, confounded: W/D = weekend or work night, then the six exposures and three outcomes (- = missing).
        val expected = listOf(
            "D-0--10010", "W11110100-", "W000001000", "D000010100", "D00-000-00",
            "D000010000", "D00001-000", "D10--101-0", "W11-01110-", "W111101100",
        )

        val nights = CalibrationGenerator.nights(7, 10, Scenario.CONFOUNDED)

        assertThat(nights.map { encode(it) }).containsExactlyElementsIn(expected).inOrder()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("table")
    fun `the calibration table of R10 14_6 is reproduced exactly`(row: String, key: SimulationKey, expected: List<Int>) {
        val counts = simulation(key)

        assertWithMessage(row).that(counts.asTableRow()).isEqualTo(expected)
    }

    @Test
    fun `R9 null data claim at most 5 percent, STRONG at most 1 percent and propose at most 3 percent`() {
        for (nights in listOf(56, 90)) {
            val counts = simulation(SimulationKey(Scenario.NULL, nights))
            assertWithMessage("null $nights").that(counts.anyClaim).isAtMost(SEEDS * 5 / 100)
            assertThat(counts.anyStrong).isAtMost(SEEDS / 100)
            assertThat(counts.anyProposal).isAtMost(SEEDS * 3 / 100)
            assertThat(counts.positiveProposal).isAtMost(counts.anyProposal)
        }
    }

    @Test
    fun `R10 weekend-confounded data propose the target pair in at most 1 percent`() {
        for (nights in listOf(56, 90)) {
            val counts = simulation(SimulationKey(Scenario.CONFOUNDED, nights))
            assertWithMessage("confounded $nights").that(counts.targetProposed).isAtMost(SEEDS / 100)
        }
    }

    enum class Scenario { NULL, CONFOUNDED, PLANTED }

    data class SimulationKey(val scenario: Scenario, val nights: Int, val p1: Double = 0.70, val p0: Double = 0.25)

    /** Numbers of seeds (of 1000) with each event. */
    data class Counts(
        val anyClaim: Int,
        val anyStrong: Int,
        val targetClaimed: Int,
        val anyProposal: Int,
        val targetProposed: Int,
        val positiveProposal: Int,
    ) {
        fun asTableRow(): List<Int> = listOf(anyClaim, anyStrong, targetClaimed, anyProposal, targetProposed)
    }

    companion object {
        private const val SEEDS = 1_000
        private val cache = HashMap<SimulationKey, Counts>()

        @Synchronized
        fun simulation(key: SimulationKey): Counts = cache.getOrPut(key) { simulate(key) }

        private fun simulate(key: SimulationKey): Counts {
            var anyClaim = 0
            var anyStrong = 0
            var target = 0
            var proposal = 0
            var targetProposed = 0
            var positive = 0
            for (seed in 1..SEEDS) {
                val nights = CalibrationGenerator.nights(seed, key.nights + 7, key.scenario, key.p1, key.p0)
                val first = analyze(nights.subList(0, key.nights))
                val second = analyze(nights.subList(7, key.nights + 7))
                val c1 = first.claims
                val c2 = second.claims
                if (c2.isNotEmpty()) anyClaim++
                if (c2.values.any { it.tier == PatternTier.STRONG }) anyStrong++
                if (c2.keys.any { it.startsWith("H04:") }) target++
                val persistent = c1.keys intersect c2.keys
                if (persistent.isNotEmpty()) proposal++
                if (persistent.any { it.startsWith("H04:") }) targetProposed++
                if (persistent.any { it.endsWith(":+") }) positive++
            }
            return Counts(anyClaim, anyStrong, target, proposal, targetProposed, positive)
        }

        private fun analyze(nights: List<Night>): PatternRun =
            PatternAnalyzer.analyze(NightTable(nights.first().date, nights.last().date, nights), T0)

        fun encode(night: Night): String = (if (night.type == NightType.WEEKEND_NIGHT) "W" else "D") +
            Exposure.entries.joinToString("") { e -> night.exposures[e]?.let { if (it) "1" else "0" } ?: "-" } +
            NightOutcome.entries.joinToString("") { o -> night.outcomes[o]?.let { if (it) "1" else "0" } ?: "-" }

        /** R10 §14.6 table, as counts of 1000 seeds: any claim, any STRONG, target claimed, any proposal, target proposed. */
        @JvmStatic
        fun table(): List<Arguments> = listOf(
            Arguments.of("K1 null, 28 nights", SimulationKey(Scenario.NULL, 28), listOf(0, 0, 0, 0, 0)),
            Arguments.of("K2 null, 56 nights", SimulationKey(Scenario.NULL, 56), listOf(33, 0, 1, 8, 0)),
            Arguments.of("K3 null, 90 nights", SimulationKey(Scenario.NULL, 90), listOf(45, 5, 4, 27, 3)),
            Arguments.of("K4 confounded, 56 nights", SimulationKey(Scenario.CONFOUNDED, 56), listOf(19, 0, 2, 6, 1)),
            Arguments.of("K5 confounded, 90 nights", SimulationKey(Scenario.CONFOUNDED, 90), listOf(38, 2, 2, 24, 1)),
            Arguments.of("K6 planted 0.70 vs 0.25, 28 nights", SimulationKey(Scenario.PLANTED, 28), listOf(1, 0, 1, 0, 0)),
            Arguments.of("K7 planted 0.70 vs 0.25, 56 nights", SimulationKey(Scenario.PLANTED, 56), listOf(362, 0, 328, 296, 269)),
            Arguments.of("K8 planted 0.70 vs 0.25, 90 nights", SimulationKey(Scenario.PLANTED, 90), listOf(679, 406, 654, 628, 608)),
            Arguments.of("K9 planted 0.55 vs 0.30, 56 nights", SimulationKey(Scenario.PLANTED, 56, 0.55, 0.30), listOf(107, 0, 71, 59, 46)),
            Arguments.of(
                "K10 planted 0.55 vs 0.30, 90 nights",
                SimulationKey(Scenario.PLANTED, 90, 0.55, 0.30),
                listOf(230, 62, 179, 175, 141),
            ),
        )
    }
}

/** The calibration generator of docs/research/10 §14.6 (`mine.py`, `gen`), ported draw for draw. */
internal object CalibrationGenerator {
    private val KEYS =
        listOf("E_screen30", "E_screen45", "E_screen60", "E_social20", "E_notif20", "E_steps5k", "O_late", "O_short", "O_rhr")

    fun nights(seed: Int, n: Int, scenario: CalibrationTest.Scenario, p1: Double = 0.70, p0: Double = 0.25): List<Night> {
        val r = PythonRandom(seed)
        val first = date("2026-10-01")
        return (0 until n).map { i ->
            val weekend = (THURSDAY + i) % 7 == FRIDAY || (THURSDAY + i) % 7 == SATURDAY
            val median = when {
                scenario != CalibrationTest.Scenario.CONFOUNDED -> 28.0
                weekend -> 40.0
                else -> 20.0
            }
            val sm = r.lognormvariate(StrictMath.log(median), 0.6)
            val v = LinkedHashMap<String, Boolean?>()
            v["E_screen30"] = sm >= 30
            v["E_screen45"] = sm >= 45
            v["E_screen60"] = sm >= 60
            v["E_social20"] = sm * r.uniform(0.2, 0.8) >= 20
            v["E_notif20"] = r.random() < 0.35
            v["E_steps5k"] = r.random() < 0.4
            v["O_late"] = r.random() < when (scenario) {
                CalibrationTest.Scenario.NULL -> 0.30
                CalibrationTest.Scenario.CONFOUNDED -> if (weekend) 0.55 else 0.15
                CalibrationTest.Scenario.PLANTED -> if (v["E_screen45"] == true) p1 else p0
            }
            v["O_short"] = r.random() < 0.25
            v["O_rhr"] = r.random() < 0.2
            for (k in KEYS) if (r.random() < 0.10) v[k] = null
            val d = first.plusDays(i)
            val type = NightType.of(d, WEEKEND)
            check((type == NightType.WEEKEND_NIGHT) == weekend) { "night type differs from the script" }
            Night(
                d,
                type,
                Exposure.entries.mapNotNull { e -> v[e.id]?.let { e to it } }.toMap(),
                NightOutcome.entries.mapNotNull { o -> v[o.id]?.let { o to it } }.toMap(),
            )
        }
    }

    private const val THURSDAY = 3
    private const val FRIDAY = 4
    private const val SATURDAY = 5
}

/**
 * CPython's `random.Random` for integer seeds below 2^32: MT19937 seeded with `init_by_array([seed])`, `random()` as
 * `genrand_res53`, and the 3.11 `normalvariate` (Kinderman-Monahan), `lognormvariate` and `uniform`. StrictMath keeps
 * the results identical on every JVM.
 */
internal class PythonRandom(seed: Int) {
    private val mt = IntArray(N)
    private var index = N + 1

    init {
        require(seed >= 0) { "non-negative seeds only" }
        initByArray(intArrayOf(seed))
    }

    fun random(): Double {
        val a = nextInt() ushr 5
        val b = nextInt() ushr 6
        return (a * 67_108_864.0 + b) * (1.0 / 9_007_199_254_740_992.0)
    }

    fun uniform(a: Double, b: Double): Double = a + (b - a) * random()

    fun normalvariate(mu: Double, sigma: Double): Double {
        while (true) {
            val u1 = random()
            val u2 = 1.0 - random()
            val z = NV_MAGICCONST * (u1 - 0.5) / u2
            val zz = z * z / 4.0
            if (zz <= -StrictMath.log(u2)) return mu + z * sigma
        }
    }

    fun lognormvariate(mu: Double, sigma: Double): Double = StrictMath.exp(normalvariate(mu, sigma))

    private fun initGenrand(s: Int) {
        mt[0] = s
        for (i in 1 until N) mt[i] = 1_812_433_253 * (mt[i - 1] xor (mt[i - 1] ushr 30)) + i
        index = N
    }

    private fun initByArray(key: IntArray) {
        initGenrand(19_650_218)
        var i = 1
        var j = 0
        var k = maxOf(N, key.size)
        while (k > 0) {
            mt[i] = (mt[i] xor ((mt[i - 1] xor (mt[i - 1] ushr 30)) * 1_664_525)) + key[j] + j
            i++
            j++
            if (i >= N) {
                mt[0] = mt[N - 1]
                i = 1
            }
            if (j >= key.size) j = 0
            k--
        }
        k = N - 1
        while (k > 0) {
            mt[i] = (mt[i] xor ((mt[i - 1] xor (mt[i - 1] ushr 30)) * 1_566_083_941)) - i
            i++
            if (i >= N) {
                mt[0] = mt[N - 1]
                i = 1
            }
            k--
        }
        mt[0] = UPPER
    }

    private fun nextInt(): Int {
        if (index >= N) twist()
        var y = mt[index++]
        y = y xor (y ushr 11)
        y = y xor ((y shl 7) and TEMPER_B)
        y = y xor ((y shl 15) and TEMPER_C)
        return y xor (y ushr 18)
    }

    private fun twist() {
        for (kk in 0 until N) {
            val y = (mt[kk] and UPPER) or (mt[(kk + 1) % N] and LOWER)
            mt[kk] = mt[(kk + M) % N] xor (y ushr 1) xor (if (y and 1 != 0) MATRIX_A else 0)
        }
        index = 0
    }

    private companion object {
        const val N = 624
        const val M = 397
        const val MATRIX_A = 0x9908b0df.toInt()
        const val UPPER = 0x80000000.toInt()
        const val LOWER = 0x7fffffff
        const val TEMPER_B = 0x9d2c5680.toInt()
        const val TEMPER_C = 0xefc60000.toInt()
        const val NV_MAGICCONST = 1.7155277699214135
    }
}
