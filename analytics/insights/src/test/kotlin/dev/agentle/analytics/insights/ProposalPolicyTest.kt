package dev.agentle.analytics.insights

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The proposal policy of docs/research/10 §14.5. */
class ProposalPolicyTest {
    private val h04 = hypothesis("H04")
    private val lastWeek = date("2026-09-27")
    private val thisWeek = date("2026-10-04")

    private fun select(
        current: PatternRun,
        previous: PatternRun?,
        proposals: List<ProposalRecord> = emptyList(),
        mutes: List<PatternMute> = emptyList(),
        rules: Set<String> = emptySet(),
    ): HypothesisResult? = ProposalPolicy.select(current, previous, proposals, mutes, rules, T0)

    private fun record(patternId: String, status: ProposalStatus, createdAt: Instant = T0 - 30.days) = ProposalRecord(
        proposalId = "p-$patternId-$status",
        patternId = patternId,
        hypothesisId = patternId.substringBefore(':'),
        tier = PatternTier.MODERATE,
        createdAt = createdAt,
        status = status,
        proposal = JsonObject(emptyMap()),
        lineage = SCREEN_LINEAGE,
    )

    @Test
    fun `a finding is proposed only when the previous weekly run had it with the same sign`() {
        val current = runOf(thisWeek, claim(h04))

        assertThat(select(current, previous = null)).isNull()
        assertThat(select(current, runOf(lastWeek))).isNull()
        assertThat(select(current, runOf(lastWeek, claim(h04, rdMh = -0.46)))).isNull()
        assertThat(select(current, runOf(lastWeek, claim(h04, tier = PatternTier.WEAK)))).isNull()
        assertThat(select(current, runOf(lastWeek, claim(h04, tier = PatternTier.STRONG)))?.hypothesis).isEqualTo(h04)
        assertThat(select(runOf(thisWeek, claim(h04, tier = PatternTier.WEAK)), runOf(lastWeek, claim(h04)))).isNull()
    }

    @Test
    fun `negative associations are never proposed`() {
        val negative = claim(h04, rdMh = -0.46)

        assertThat(select(runOf(thisWeek, negative), runOf(lastWeek, negative))).isNull()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rankings")
    fun `ranking is STRONG first, then lower q, then larger RD_MH, then hypothesis id`(
        row: String,
        first: HypothesisResult,
        second: HypothesisResult,
        expected: String,
    ) {
        val current = runOf(thisWeek, first, second)
        val previous = runOf(lastWeek, first, second)

        assertWithMessage(row).that(select(current, previous)?.hypothesis?.id).isEqualTo(expected)
    }

    @Test
    fun `at most three open proposals`() {
        val current = runOf(thisWeek, claim(h04))
        val previous = runOf(lastWeek, claim(h04))
        val open = listOf("H01:+", "H02:+", "H03:+").map { record(it, ProposalStatus.PROPOSED) }
        val decided = listOf(record("H05:+", ProposalStatus.APPROVED), record("H06:+", ProposalStatus.DECLINED))

        assertThat(select(current, previous, open)).isNull()
        assertThat(select(current, previous, open.take(2) + decided)?.hypothesis).isEqualTo(h04)
    }

    @Test
    fun `at most one new proposal in seven days`() {
        val current = runOf(thisWeek, claim(h04))
        val previous = runOf(lastWeek, claim(h04))

        assertThat(select(current, previous, listOf(record("H01:+", ProposalStatus.DECLINED, T0 - 7.days + 1.seconds)))).isNull()
        assertThat(select(current, previous, listOf(record("H01:+", ProposalStatus.DECLINED, T0 - 7.days)))?.hypothesis).isEqualTo(h04)
    }

    @Test
    fun `muted and open patterns and hypotheses with a rule are skipped`() {
        val h05 = hypothesis("H05")
        val current = runOf(thisWeek, claim(h04, q = 0.01), claim(h05, q = 0.02))
        val previous = runOf(lastWeek, claim(h04, q = 0.01), claim(h05, q = 0.02))

        assertThat(select(current, previous)?.hypothesis).isEqualTo(h04)
        assertThat(select(current, previous, mutes = listOf(PatternMute("H04:+", T0 + 1.days)))?.hypothesis).isEqualTo(h05)
        assertThat(select(current, previous, mutes = listOf(PatternMute("H04:+", T0)))?.hypothesis).isEqualTo(h04)
        assertThat(select(current, previous, mutes = listOf(PatternMute("H04:+", null), PatternMute("H05:+", null)))).isNull()
        assertThat(select(current, previous, mutes = listOf(PatternMute("H04:-", null)))?.hypothesis).isEqualTo(h04)
        assertThat(select(current, previous, proposals = listOf(record("H04:+", ProposalStatus.PROPOSED)))?.hypothesis).isEqualTo(h05)
        assertThat(select(current, previous, rules = setOf("H04"))?.hypothesis).isEqualTo(h05)
        assertThat(select(current, previous, rules = setOf("H04", "H05"))).isNull()
    }

    @Test
    fun `a mute is active until its end, and forever without one`() {
        assertThat(PatternMute("H04:+", T0 + 1.seconds).isActive(T0)).isTrue()
        assertThat(PatternMute("H04:+", T0).isActive(T0)).isFalse()
        assertThat(PatternMute("H04:+", null).isActive(T0 + 3650.days)).isTrue()
    }

    @ParameterizedTest(name = "{0} previous run {1} days earlier")
    @CsvSource(
        "G1, 7, 1, true",
        "G2, 10, 1, true",
        "G3, 6, 1, false",
        "G4, 11, 1, false",
        "G5, 7, 2, false",
        "G6, 0, 1, false",
    )
    fun `the previous weekly run is 7 to 10 days older with the same family version`(
        row: String,
        daysEarlier: Int,
        familyVersion: Int,
        found: Boolean,
    ) {
        val current = runOf(thisWeek)
        val candidate = runOf(thisWeek.plusDays(-daysEarlier), familyVersion = familyVersion)

        assertWithMessage(row).that(ProposalPolicy.previousRun(current, listOf(candidate)) != null).isEqualTo(found)
    }

    @Test
    fun `the latest qualifying run is the previous run`() {
        val current = runOf(thisWeek)
        val older = runOf(thisWeek.plusDays(-9))
        val newer = runOf(thisWeek.plusDays(-7))

        assertThat(ProposalPolicy.previousRun(current, listOf(newer, older, current))).isSameInstanceAs(newer)
        assertThat(ProposalPolicy.previousRun(current, emptyList())).isNull()
    }

    @Test
    fun `the in-memory store keeps the latest runs and replaces proposals and mutes by id`() = runTest {
        val store = InMemoryDiscoveryStore(keepRuns = 2)
        listOf(0, 7, 14).forEach { store.saveRun(runOf(thisWeek.plusDays(it)).copy(runAt = T0 + it.days)) }
        store.upsertProposal(record("H04:+", ProposalStatus.PROPOSED))
        store.upsertProposal(record("H04:+", ProposalStatus.PROPOSED).copy(tier = PatternTier.STRONG))
        store.upsertMute(PatternMute("H04:+", null))
        store.upsertMute(PatternMute("H04:+", T0))

        assertThat(store.runs().map { it.lastNight }).containsExactly(thisWeek.plusDays(7), thisWeek.plusDays(14)).inOrder()
        assertThat(store.proposals().single().tier).isEqualTo(PatternTier.STRONG)
        assertThat(store.mutes()).containsExactly(PatternMute("H04:+", T0))
        store.clearMutes()
        assertThat(store.mutes()).isEmpty()
    }

    companion object {
        @JvmStatic
        fun rankings(): List<Arguments> {
            val h01 = hypothesis("H01")
            val h04 = hypothesis("H04")
            val h05 = hypothesis("H05")
            val h10 = hypothesis("H10")
            return listOf(
                Arguments.of("K1 STRONG before a lower q", claim(h01, q = 0.001), claim(h10, tier = PatternTier.STRONG, q = 0.005), "H10"),
                Arguments.of("K2 lower q", claim(h04, q = 0.05), claim(h05, q = 0.02), "H05"),
                Arguments.of("K3 larger abs(RD_MH)", claim(h04, q = 0.05, rdMh = 0.30), claim(h05, q = 0.05, rdMh = 0.40), "H05"),
                Arguments.of("K4 hypothesis id", claim(h05, q = 0.05), claim(h04, q = 0.05), "H04"),
            )
        }
    }
}
