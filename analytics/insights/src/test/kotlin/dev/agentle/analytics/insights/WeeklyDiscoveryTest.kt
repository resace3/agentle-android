package dev.agentle.analytics.insights

import com.google.common.truth.Truth.assertThat
import dev.agentle.analytics.features.daily.DailyFeatureStore
import dev.agentle.analytics.features.daily.DailySummaryRow
import dev.agentle.analytics.features.daily.InMemoryDailyFeatureStore
import dev.agentle.core.common.AppError
import dev.agentle.core.common.LogRecord
import dev.agentle.core.common.LogSink
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.Severity
import dev.agentle.core.testing.TestAgentleClock
import dev.agentle.core.time.engineDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.days

/** The weekly run end to end (docs/research/10 §14.5, §14.8) over an in-memory daily store. */
class WeeklyDiscoveryTest {
    private val config = InsightConfig(windowNights = 60)
    private val clock = TestAgentleClock(T0)
    private val daily = InMemoryDailyFeatureStore()
    private val store = InMemoryDiscoveryStore()
    private val logs = mutableListOf<LogRecord>()
    private val logger = Logger(listOf(LogSink { logs += it }), { 0L })
    private var ruleHypotheses = emptySet<String>()
    private var ids = 0
    private val discovery = WeeklyDiscovery(daily, store, { ruleHypotheses }, clock, config, { "proposal-${++ids}" }, logger)

    /** Lays control P1 over the 60 nights ending [lastNight] as daily rows (exposed 50 vs 35 min; late 00:00 vs 23:00). */
    private suspend fun layP1(lastNight: LocalDate) {
        val rows = p1Rows(lastNight)
        daily.replaceDays(rows.map { it.date }.toSet(), rows)
    }

    private fun value(outcome: Outcome<WeeklyReport>): WeeklyReport = (outcome as Outcome.Success).value

    private suspend fun nextWeek(): WeeklyReport {
        clock.advanceBy(7.days)
        layP1(clock.engineDay().plusDays(-1))
        return value(discovery.run())
    }

    @Test
    fun `the first run reports the P1 finding and the second run a week later proposes it`() = runTest {
        layP1(date("2026-10-04"))

        val first = value(discovery.run())

        assertThat(first.run.lastNight).isEqualTo(date("2026-10-04"))
        assertThat(first.run.claims.keys).containsExactly("H04:+")
        assertThat(first.insights.single().finding).isEqualTo(P1_TEXT)
        assertThat(first.proposal).isNull()

        val second = nextWeek()

        val proposal = requireNotNull(second.proposal)
        assertThat(proposal.proposalId).isEqualTo("proposal-1")
        assertThat(proposal.patternId).isEqualTo("H04:+")
        assertThat(proposal.hypothesisId).isEqualTo("H04")
        assertThat(proposal.tier).isEqualTo(PatternTier.MODERATE)
        assertThat(proposal.status).isEqualTo(ProposalStatus.PROPOSED)
        assertThat(proposal.createdAt).isEqualTo(T0 + 7.days)
        assertThat(proposal.lineage).isEqualTo(SCREEN_LINEAGE + SLEEP_LINEAGE)
        val evidence = proposal.proposal.getValue("whyProposed").jsonObject.getValue("evidence").jsonObject
        assertThat(evidence.getValue("analysisWindow").jsonObject.getValue("lastNight").jsonPrimitive.content).isEqualTo("2026-10-11")
        assertThat(evidence.getValue("analysisWindow").jsonObject.getValue("firstNight").jsonPrimitive.content).isEqualTo("2026-08-13")
        assertThat(store.proposals()).containsExactly(proposal)
        assertThat(store.runs()).hasSize(2)

        // The pattern is open: it is not proposed again.
        assertThat(nextWeek().proposal).isNull()
    }

    @Test
    fun `not now declines and mutes for 60 days, reset allows it again`() = runTest {
        layP1(date("2026-10-04"))
        value(discovery.run())
        val proposal = requireNotNull(nextWeek().proposal)

        assertThat(discovery.decide("unknown", ProposalDecision.NOT_NOW))
            .isEqualTo(Outcome.Failure(AppError.ValidationError(listOf("unknown_proposal"))))
        val declined = (discovery.decide(proposal.proposalId, ProposalDecision.NOT_NOW) as Outcome.Success).value
        assertThat(declined.status).isEqualTo(ProposalStatus.DECLINED)
        assertThat(declined.decidedAt).isEqualTo(clock.now())
        assertThat(store.mutes()).containsExactly(PatternMute("H04:+", clock.now() + 60.days))
        assertThat(discovery.decide(proposal.proposalId, ProposalDecision.TRY_FOR_FOUR_WEEKS))
            .isEqualTo(Outcome.Failure(AppError.ValidationError(listOf("proposal_already_decided"))))

        assertThat(nextWeek().proposal).isNull()
        assertThat(discovery.resetSuggestions()).isEqualTo(Outcome.Success(Unit))
        val again = requireNotNull(nextWeek().proposal)

        assertThat(again.proposalId).isEqualTo("proposal-2")
        val never = (discovery.decide(again.proposalId, ProposalDecision.NEVER_SUGGEST) as Outcome.Success).value
        assertThat(never.status).isEqualTo(ProposalStatus.DECLINED)
        assertThat(store.mutes()).containsExactly(PatternMute("H04:+", null))
    }

    @Test
    fun `try for four weeks approves without a mute`() = runTest {
        layP1(date("2026-10-04"))
        value(discovery.run())
        val proposal = requireNotNull(nextWeek().proposal)

        val approved = (discovery.decide(proposal.proposalId, ProposalDecision.TRY_FOR_FOUR_WEEKS) as Outcome.Success).value

        assertThat(approved.status).isEqualTo(ProposalStatus.APPROVED)
        assertThat(store.mutes()).isEmpty()
    }

    @Test
    fun `a hypothesis that already has a rule is not proposed`() = runTest {
        ruleHypotheses = setOf("H04")
        layP1(date("2026-10-04"))
        value(discovery.run())

        assertThat(nextWeek().proposal).isNull()
    }

    @Test
    fun `the run reads one bounded date range`() = runTest {
        val ranges = mutableListOf<Pair<LocalDate, LocalDate>>()
        val recording = object : DailyFeatureStore by daily {
            override suspend fun dailyRows(from: LocalDate, to: LocalDate): List<DailySummaryRow> {
                ranges += from to to
                return daily.dailyRows(from, to)
            }
        }
        layP1(date("2026-10-04"))

        value(WeeklyDiscovery(recording, store, { emptySet() }, clock, config).run())

        assertThat(ranges).containsExactly(date("2026-07-10") to date("2026-10-05"))
    }

    @Test
    fun `failures become Outcome failures with the error class only, and logs carry counts only`() = runTest {
        val failing = object : DailyFeatureStore by daily {
            override suspend fun dailyRows(from: LocalDate, to: LocalDate): List<DailySummaryRow> =
                throw IllegalStateException("bedtime 23:41 of com.example.secret")
        }
        layP1(date("2026-10-04"))
        value(discovery.run())

        val failed = WeeklyDiscovery(failing, store, { emptySet() }, clock, config, logger = logger).run()

        assertThat(failed).isEqualTo(Outcome.Failure(AppError.Unexpected("IllegalStateException")))
        assertThat(logs.map { it.severity }).containsExactly(Severity.INFO, Severity.WARN).inOrder()
        assertThat(logs[0].fields).containsExactly("findings", "1", "proposed", "false")
        assertThat(logs[1].errorCode).isEqualTo("unexpected")
        val text = logs.joinToString { it.toString() }
        listOf("23:41", "com.example", "2026-", "P1", "bedtime").forEach { assertThat(text).doesNotContain(it) }
    }

    @Test
    fun `cancellation is never turned into a failure`() = runTest {
        val cancelled = object : DailyFeatureStore by daily {
            override suspend fun dailyRows(from: LocalDate, to: LocalDate): List<DailySummaryRow> = throw CancellationException("stop")
        }

        val thrown = runCatching { WeeklyDiscovery(cancelled, store, { emptySet() }, clock, config).run() }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `without any data every hypothesis is INSUFFICIENT and nothing is shown`() = runTest {
        val report = value(discovery.run())

        assertThat(report.run.results.map { it.tier }.toSet()).containsExactly(PatternTier.INSUFFICIENT)
        assertThat(report.insights).isEmpty()
        assertThat(report.proposal).isNull()
    }
}

/** Daily rows of control P1 over the 60 nights ending [lastNight]: only `E_screen45` and `O_late` can be computed. */
internal fun p1Rows(lastNight: LocalDate): List<DailySummaryRow> {
    val table = Controls.table(lastNight.plusDays(-59), 60, Controls.P1)
    return table.nights.flatMap { night ->
        val exposed = night.exposures.getValue(Exposure.SCREEN_45)
        val late = night.outcomes.getValue(NightOutcome.LATE_BEDTIME)
        listOf(
            dailyRow(night.date, "screen_minutes_22_24", if (exposed) 50.0 else 35.0, lineage = SCREEN_LINEAGE),
            dailyRow(night.date, "bedtime", if (late) 720.0 else 660.0, lineage = SLEEP_LINEAGE),
        )
    }
}
