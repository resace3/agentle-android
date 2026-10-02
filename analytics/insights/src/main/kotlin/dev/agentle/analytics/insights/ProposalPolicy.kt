package dev.agentle.analytics.insights

import dev.agentle.core.model.Lineage
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Lifecycle of a discovered proposal (docs/research/10 §14.8). */
public enum class ProposalStatus {
    /** Shown as one quiet in-app card, waiting for the user. */
    PROPOSED,

    /** "Try for 4 weeks": the JITAI layer creates the rule from the draft after validation and approval. */
    APPROVED,

    /** "Not now" or "Never suggest this". */
    DECLINED,
}

/** The user's answer on a proposal card. */
public enum class ProposalDecision {
    TRY_FOR_FOUR_WEEKS,
    NOT_NOW,
    NEVER_SUGGEST,
}

/** One stored proposal; [proposal] is the §14.7 object. */
public data class ProposalRecord(
    val proposalId: String,
    val patternId: String,
    val hypothesisId: String,
    val tier: PatternTier,
    val createdAt: Instant,
    val status: ProposalStatus,
    val proposal: JsonObject,
    val lineage: Lineage,
    val decidedAt: Instant? = null,
)

/** A muted pattern: "Not now" mutes it until [until]; "Never suggest this" ([until] = null) until suggestions are reset. */
public data class PatternMute(val patternId: String, val until: Instant?) {
    public fun isActive(now: Instant): Boolean = until == null || until > now
}

/**
 * Storage of the discovery pipeline (a Room-backed repository in the app; [InMemoryDiscoveryStore] for tests). Runs are
 * kept per week so persistence can be checked against the previous weekly run.
 */
public interface DiscoveryStore {
    /** The stored runs, any order. Implementations keep at least the last few weeks. */
    public suspend fun runs(): List<PatternRun>

    public suspend fun saveRun(run: PatternRun)

    public suspend fun proposals(): List<ProposalRecord>

    public suspend fun upsertProposal(record: ProposalRecord)

    public suspend fun mutes(): List<PatternMute>

    public suspend fun upsertMute(mute: PatternMute)

    /** "Reset suggestions": removes every mute. */
    public suspend fun clearMutes()
}

/** In-memory [DiscoveryStore]; keeps the last [keepRuns] runs. */
public class InMemoryDiscoveryStore(private val keepRuns: Int = 12) : DiscoveryStore {
    private val runs = ArrayList<PatternRun>()
    private val proposals = LinkedHashMap<String, ProposalRecord>()
    private val mutes = LinkedHashMap<String, PatternMute>()

    override suspend fun runs(): List<PatternRun> = runs.toList()

    override suspend fun saveRun(run: PatternRun) {
        runs += run
        runs.sortBy { it.runAt }
        while (runs.size > keepRuns) runs.removeAt(0)
    }

    override suspend fun proposals(): List<ProposalRecord> = proposals.values.toList()

    override suspend fun upsertProposal(record: ProposalRecord) {
        proposals[record.proposalId] = record
    }

    override suspend fun mutes(): List<PatternMute> = mutes.values.toList()

    override suspend fun upsertMute(mute: PatternMute) {
        mutes[mute.patternId] = mute
    }

    override suspend fun clearMutes() {
        mutes.clear()
    }
}

/** Hypotheses that already have a rule in any status except ARCHIVED (read from the JITAI repository). */
public fun interface DiscoveredRuleLookup {
    public suspend fun hypothesesWithRules(): Set<String>
}

/**
 * The proposal policy of docs/research/10 §14.5: a finding becomes a proposal only when it is MODERATE or STRONG with
 * the same sign in two consecutive weekly runs; only positive associations are proposed; at most one new proposal per
 * week and at most [InsightConfig.maxOpenProposals] open ones; muted patterns and hypotheses with a rule are skipped;
 * ranking is STRONG first, then lower q, then larger `abs(RD_MH)`, then hypothesis id.
 */
public object ProposalPolicy {
    /**
     * The previous weekly run of [current] among [runs]: the latest run whose last night is at least a week and at most
     * [InsightConfig.maxRunGapDays] days before the current one, with the same family version.
     */
    public fun previousRun(current: PatternRun, runs: List<PatternRun>, config: InsightConfig = InsightConfig()): PatternRun? {
        val newest = current.lastNight.minus(DatePeriod(days = InsightConfig.WEEK_DAYS))
        val oldest = current.lastNight.minus(DatePeriod(days = config.maxRunGapDays))
        return runs.filter { it.familyVersion == current.familyVersion && it.lastNight in oldest..newest }.maxByOrNull { it.lastNight }
    }

    /** The finding to propose now, if any. */
    public fun select(
        current: PatternRun,
        previous: PatternRun?,
        proposals: List<ProposalRecord>,
        mutes: List<PatternMute>,
        ruleHypotheses: Set<String>,
        now: Instant,
        config: InsightConfig = InsightConfig(),
    ): HypothesisResult? {
        if (previous == null) return null
        if (proposals.count { it.status == ProposalStatus.PROPOSED } >= config.maxOpenProposals) return null
        if (proposals.any { it.createdAt > now - InsightConfig.WEEK_DAYS.days }) return null
        val muted = mutes.filter { it.isActive(now) }.mapTo(HashSet()) { it.patternId }
        val open = proposals.filter { it.status == ProposalStatus.PROPOSED }.mapTo(HashSet()) { it.patternId }
        val persistent = previous.claims
        return current.claims.values
            .filter { (it.riskDifferenceMh ?: 0.0) > 0 && it.patternId in persistent }
            .filter { it.patternId !in muted && it.patternId !in open && it.hypothesis.id !in ruleHypotheses }
            .minWithOrNull(RANKING)
    }

    /** STRONG before MODERATE, then lower q, then larger `abs(RD_MH)`, then hypothesis id. */
    public val RANKING: Comparator<HypothesisResult> = compareBy<HypothesisResult>(
        { if (it.tier == PatternTier.STRONG) 0 else 1 },
        { it.qValue },
        { -abs(it.riskDifferenceMh ?: 0.0) },
        { it.hypothesis.id },
    )
}
