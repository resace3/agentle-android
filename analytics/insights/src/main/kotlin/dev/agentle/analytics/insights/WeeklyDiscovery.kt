package dev.agentle.analytics.insights

import dev.agentle.analytics.features.daily.DailyFeatureStore
import dev.agentle.core.common.AppError
import dev.agentle.core.common.AppException
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.onFailure
import dev.agentle.core.common.outcomeOf
import dev.agentle.core.model.Insight
import dev.agentle.core.time.AgentleClock
import dev.agentle.core.time.engineDay
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import java.util.UUID
import kotlin.time.Duration.Companion.days

/** What one weekly run produced: the run, the insight cards of its findings and the new proposal, if any. */
public data class WeeklyReport(val run: PatternRun, val insights: List<Insight>, val proposal: ProposalRecord?)

/**
 * The weekly discovery run (docs/research/10 §14): reads the last [InsightConfig.windowNights] nights of
 * `daily_summary` (a bounded date range), runs the 18 hypotheses, stores the run, builds the insight cards of the
 * findings and applies the proposal policy. The background team schedules it (unique periodic work, 7 days, charging
 * constraint). Everything stays on the device. The last night is the engine day before the current one, in the clock's
 * zone (never the JVM default).
 */
public class WeeklyDiscovery(
    private val daily: DailyFeatureStore,
    private val store: DiscoveryStore,
    private val rules: DiscoveredRuleLookup,
    private val clock: AgentleClock,
    private val config: InsightConfig = InsightConfig(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val logger: Logger = Logger.NONE,
) {
    public suspend fun run(): Outcome<WeeklyReport> = guarded("run") {
        val now = clock.now()
        val zone = clock.zone()
        val lastNight = clock.engineDay().minus(DatePeriod(days = 1))
        val (from, to) = NightTableBuilder.rowRange(lastNight, config)
        val table = NightTableBuilder.build(lastNight, daily.dailyRows(from, to), config)
        val run = PatternAnalyzer.analyze(table, now)
        val previous = ProposalPolicy.previousRun(run, store.runs(), config)
        store.saveRun(run)
        val selected = ProposalPolicy.select(run, previous, store.proposals(), store.mutes(), rules.hypothesesWithRules(), now, config)
        val record = selected?.let { result ->
            val id = newId()
            ProposalRecord(
                proposalId = id,
                patternId = requireNotNull(result.patternId),
                hypothesisId = result.hypothesis.id,
                tier = result.tier,
                createdAt = now,
                status = ProposalStatus.PROPOSED,
                proposal = DiscoveredProposals.build(result, run, id, now, config),
                lineage = result.lineage,
            ).also { store.upsertProposal(it) }
        }
        val insights = PatternInsights.of(run, zone, now)
        logger.i(COMPONENT, "weekly discovery done", mapOf("findings" to run.claims.size, "proposed" to (record != null)))
        WeeklyReport(run, insights, record)
    }

    /** Records the user's answer on proposal [proposalId] (docs/research/10 §14.5, §14.8). */
    public suspend fun decide(proposalId: String, decision: ProposalDecision): Outcome<ProposalRecord> = guarded("decide") {
        val record = store.proposals().firstOrNull { it.proposalId == proposalId }
            ?: throw AppException(AppError.ValidationError(listOf("unknown_proposal")))
        if (record.status != ProposalStatus.PROPOSED) throw AppException(AppError.ValidationError(listOf("proposal_already_decided")))
        val now = clock.now()
        val updated = record.copy(
            status = if (decision == ProposalDecision.TRY_FOR_FOUR_WEEKS) ProposalStatus.APPROVED else ProposalStatus.DECLINED,
            decidedAt = now,
        )
        store.upsertProposal(updated)
        when (decision) {
            ProposalDecision.NOT_NOW -> store.upsertMute(PatternMute(record.patternId, now + config.muteDays.days))
            ProposalDecision.NEVER_SUGGEST -> store.upsertMute(PatternMute(record.patternId, null))
            ProposalDecision.TRY_FOR_FOUR_WEEKS -> Unit
        }
        updated
    }

    /** "Reset suggestions": muted patterns may be proposed again. */
    public suspend fun resetSuggestions(): Outcome<Unit> = guarded("reset") { store.clearMutes() }

    private suspend fun <T> guarded(operation: String, block: suspend () -> T): Outcome<T> =
        outcomeOf { block() }.onFailure { error -> logger.w(COMPONENT, "discovery $operation failed", error) }

    private companion object {
        const val COMPONENT = "WeeklyDiscovery"
    }
}
