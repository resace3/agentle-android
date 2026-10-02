package dev.agentle.analytics.insights

import dev.agentle.analytics.features.daily.CanonicalSourcePolicy
import dev.agentle.analytics.features.daily.DailyFeatureStore
import dev.agentle.analytics.features.daily.DailyInputs
import dev.agentle.analytics.features.daily.DailyRowStatus
import dev.agentle.analytics.features.daily.EventQuery
import dev.agentle.analytics.features.daily.ExactSum
import dev.agentle.analytics.features.daily.IntervalMath
import dev.agentle.analytics.features.daily.MetricFamily
import dev.agentle.analytics.features.daily.SleepSessions
import dev.agentle.core.model.AppUsagePayload
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.EventType
import dev.agentle.core.model.Lineage
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.ScreenPayload
import dev.agentle.core.model.SleepSessionPayload
import dev.agentle.core.model.SourceFamily
import dev.agentle.core.model.StepsPayload
import dev.agentle.core.model.family
import dev.agentle.core.time.ClosedOpenRange
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Role of an outcome metric. */
public enum class OutcomeRole { PROXIMAL, DISTAL }

/**
 * The outcome metric catalog of docs/research/10 §15.1.
 *
 * @property window allowed `windowMinutes`; null when the metric takes none.
 */
public enum class OutcomeMetric(public val role: OutcomeRole, public val window: IntRange?) {
    STEPS_AFTER(OutcomeRole.PROXIMAL, 10..120),
    SCREEN_MINUTES_AFTER(OutcomeRole.PROXIMAL, 10..120),
    APP_MINUTES_AFTER(OutcomeRole.PROXIMAL, 10..120),
    APP_CATEGORY_MINUTES_AFTER(OutcomeRole.PROXIMAL, 10..120),
    NOTIFICATION_OPENED(OutcomeRole.PROXIMAL, 5..240),
    SELF_REPORT_HELPFUL(OutcomeRole.PROXIMAL, null),
    BEDTIME_NEXT(OutcomeRole.DISTAL, null),
    SLEEP_MINUTES_NEXT(OutcomeRole.DISTAL, null),
    STEPS_DAY_TOTAL(OutcomeRole.DISTAL, null),
}

/** `jitai_outcome.state`. */
public enum class OutcomeState { PENDING, AVAILABLE, UNAVAILABLE }

/** Responses recorded on a delivered reminder (docs/research/10 §8.7). */
public enum class ResponseKind { OPENED, SNOOZED, DISMISSED, HELPFUL, NOT_HELPFUL }

public data class DecisionResponse(val kind: ResponseKind, val at: Instant)

/**
 * The decision point an outcome belongs to.
 *
 * @property at `t0`, the decision row's `decisionPointAt`.
 * @property zone the user's zone at `t0` (local days and next-day allowances are read in it).
 * @property deliveredAt when the reminder was shown; null for decisions without a delivery.
 * @property timeoutAt when the notification timed out (end of `SELF_REPORT_HELPFUL`).
 */
public data class OutcomeDecision(
    val decisionKey: String,
    val at: Instant,
    val zone: TimeZone,
    val deliveredAt: Instant? = null,
    val timeoutAt: Instant? = null,
    val responses: List<DecisionResponse> = emptyList(),
)

/**
 * One computed outcome. [number] holds INT and NIGHT_TIME values, [flag] BOOL values, [code] ENUM values; all are null
 * unless [state] is AVAILABLE (and a delivery-less decision has no value at all).
 */
public data class OutcomeValue(
    val decisionKey: String,
    val metric: OutcomeMetric,
    val state: OutcomeState,
    val number: Long? = null,
    val flag: Boolean? = null,
    val code: String? = null,
    val lineage: Lineage = Lineage.NONE,
)

/**
 * Computes the §15.1 outcome metrics from local data as pure functions of the inputs: a re-run gives the same value.
 * A value is PENDING until its latency allowance has passed and its data is complete (collector coverage, or the
 * source's asserted coverage for steps); without data 48 h after the window it is UNAVAILABLE. Steps never add sources
 * together: they come from the per-minute fused series. Sleep follows the daily sleep rules ([SleepSessions]).
 */
public class OutcomeCalculator(
    private val inputs: DailyInputs,
    private val daily: DailyFeatureStore,
    private val appCategoryOverrides: Map<String, String> = emptyMap(),
    private val healthConnectNapMax: Duration = 3.hours,
) {
    public suspend fun compute(
        metric: OutcomeMetric,
        decision: OutcomeDecision,
        now: Instant,
        windowMinutes: Int? = null,
        args: Map<String, String> = emptyMap(),
    ): OutcomeValue {
        val range = metric.window
        if (range != null) require(windowMinutes != null && windowMinutes in range) { "${metric.name} needs windowMinutes in $range" }
        val window = ClosedOpenRange(decision.at, decision.at + (windowMinutes ?: 0).minutes)
        return when (metric) {
            OutcomeMetric.STEPS_AFTER -> steps(decision, window, now, metric)

            OutcomeMetric.SCREEN_MINUTES_AFTER -> usage(decision, window, now, metric, null)

            OutcomeMetric.APP_MINUTES_AFTER -> {
                val pkg = requireNotNull(args[ARG_PACKAGE]) { "APP_MINUTES_AFTER needs the $ARG_PACKAGE argument" }
                usage(decision, window, now, metric) { it.packageName == pkg }
            }

            OutcomeMetric.APP_CATEGORY_MINUTES_AFTER -> {
                val category = requireNotNull(args[ARG_CATEGORY]) { "APP_CATEGORY_MINUTES_AFTER needs the $ARG_CATEGORY argument" }
                usage(decision, window, now, metric) { categoryOf(it) == category }
            }

            OutcomeMetric.NOTIFICATION_OPENED -> opened(decision, now, requireNotNull(windowMinutes).minutes)

            OutcomeMetric.SELF_REPORT_HELPFUL -> helpful(decision, now)

            OutcomeMetric.BEDTIME_NEXT, OutcomeMetric.SLEEP_MINUTES_NEXT -> sleep(decision, now, metric)

            OutcomeMetric.STEPS_DAY_TOTAL -> dayTotal(decision, now)
        }
    }

    private suspend fun steps(decision: OutcomeDecision, window: ClosedOpenRange, now: Instant, metric: OutcomeMetric): OutcomeValue {
        if (now < window.end + STEPS_LATENCY) return pending(decision, metric)
        val pieces = inputs.fusedSeries(MetricFamily.STEPS, window)
        val sum = ExactSum()
        val used = HashSet<SourceFamily>()
        for (piece in pieces.filter { it.isSelected }) {
            val count = (piece.event.payload as? StepsPayload)?.count ?: continue
            val interval =
                ClosedOpenRange(piece.event.startTime, maxOf(piece.event.endTime ?: piece.event.startTime, piece.event.startTime))
            val parts = IntervalMath.clip(piece.selected, listOf(window))
            if (interval.duration.inWholeMilliseconds == 0L) {
                if (piece.event.startTime in window) sum.add(count)
            } else {
                sum.addProrated(count, IntervalMath.totalMillis(parts), interval.duration.inWholeMilliseconds)
            }
            used += piece.event.source.family
        }
        val policy = inputs.sourcePolicy()
        val covered = inputs.sourceCoverage(MetricFamily.STEPS, window).any { (source, claims) ->
            policy.isEligible(MetricFamily.STEPS, source) &&
                IntervalMath.unionMillis(claims, listOf(window)) == window.duration.inWholeMilliseconds
        }
        val lineage = Lineage(setOf(DataCategory.ACTIVITY), used)
        return when {
            covered -> available(decision, metric, sum.floor(), lineage)
            now < window.end + UNAVAILABLE_AFTER -> pending(decision, metric)
            pieces.any { it.isSelected } -> available(decision, metric, sum.floor(), lineage)
            else -> OutcomeValue(decision.decisionKey, metric, OutcomeState.UNAVAILABLE)
        }
    }

    private suspend fun usage(
        decision: OutcomeDecision,
        window: ClosedOpenRange,
        now: Instant,
        metric: OutcomeMetric,
        app: ((AppUsagePayload) -> Boolean)?,
    ): OutcomeValue {
        if (now < window.end + USAGE_LATENCY) return pending(decision, metric)
        val coverage = inputs.collectorCoverage(USAGE_COLLECTOR, window)
        if (IntervalMath.unionMillis(coverage, listOf(window)) < window.duration.inWholeMilliseconds) {
            return if (now <
                window.end + UNAVAILABLE_AFTER
            ) {
                pending(decision, metric)
            } else {
                OutcomeValue(decision.decisionKey, metric, OutcomeState.UNAVAILABLE)
            }
        }
        val screen = inputs.events(EventQuery(setOf(EventType.SCREEN_SESSION), window)).map { interval(it) }
        val millis = if (app == null) {
            IntervalMath.unionMillis(screen, listOf(window))
        } else {
            val apps = inputs.events(EventQuery(setOf(EventType.APP_SESSION), window))
                .filter { (it.payload as? AppUsagePayload)?.let(app) == true }
                .groupBy { (it.payload as AppUsagePayload).packageName }
                .values.flatMap { list -> IntervalMath.mergeGaps(list.map { interval(it) }, APP_GAP_MILLIS) }
            IntervalMath.unionMillis(IntervalMath.clip(apps, IntervalMath.clip(screen, listOf(window))), listOf(window))
        }
        val category = if (app == null) DataCategory.SCREEN else DataCategory.APP_USAGE
        return available(decision, metric, millis / IntervalMath.MS_PER_MINUTE, Lineage(setOf(category), setOf(SourceFamily.ON_DEVICE)))
    }

    private fun opened(decision: OutcomeDecision, now: Instant, window: Duration): OutcomeValue {
        val metric = OutcomeMetric.NOTIFICATION_OPENED
        val delivered = decision.deliveredAt ?: return OutcomeValue(decision.decisionKey, metric, OutcomeState.AVAILABLE, lineage = HISTORY)
        val opened = decision.responses.any { it.kind == ResponseKind.OPENED && it.at >= delivered && it.at < delivered + window }
        return when {
            opened -> OutcomeValue(decision.decisionKey, metric, OutcomeState.AVAILABLE, flag = true, lineage = HISTORY)
            now < delivered + window -> pending(decision, metric)
            else -> OutcomeValue(decision.decisionKey, metric, OutcomeState.AVAILABLE, flag = false, lineage = HISTORY)
        }
    }

    private fun helpful(decision: OutcomeDecision, now: Instant): OutcomeValue {
        val metric = OutcomeMetric.SELF_REPORT_HELPFUL
        if (decision.deliveredAt == null) return OutcomeValue(decision.decisionKey, metric, OutcomeState.AVAILABLE, lineage = HISTORY)
        val end = decision.timeoutAt
        val answer = decision.responses.filter { end == null || it.at < end }
            .firstOrNull { it.kind == ResponseKind.HELPFUL || it.kind == ResponseKind.NOT_HELPFUL }
        return when {
            answer != null -> OutcomeValue(decision.decisionKey, metric, OutcomeState.AVAILABLE, code = answer.kind.name, lineage = HISTORY)
            end == null || now < end -> pending(decision, metric)
            else -> OutcomeValue(decision.decisionKey, metric, OutcomeState.AVAILABLE, code = NO_ANSWER, lineage = HISTORY)
        }
    }

    /** The first main sleep that starts in `[t0, t0 + 18 h)` from the canonical sleep source with such a session. */
    private suspend fun sleep(decision: OutcomeDecision, now: Instant, metric: OutcomeMetric): OutcomeValue {
        val ready = nextLocalDayAt(decision, SLEEP_READY)
        if (now < ready) return pending(decision, metric)
        val starts = ClosedOpenRange(decision.at, decision.at + SLEEP_SEARCH)
        val policy: CanonicalSourcePolicy = inputs.sourcePolicy()
        val candidates = inputs.events(EventQuery(setOf(EventType.SLEEP_SESSION), ClosedOpenRange(starts.start, starts.end + MAX_SESSION)))
            .filter {
                SleepSessions.isSession(it) && it.startTime in starts && policy.isEligible(MetricFamily.SLEEP, it.source) &&
                    !SleepSessions.isNap(it, healthConnectNapMax)
            }
        val source = policy.choose(MetricFamily.SLEEP, candidates.map { it.source })
        val session =
            candidates.filter {
                it.source == source
            }.sortedWith(compareBy({ !isMain(it) }, { it.startTime }, { it.dedupKey })).firstOrNull()
                ?: return if (now <
                    starts.end + UNAVAILABLE_AFTER
                ) {
                    pending(decision, metric)
                } else {
                    OutcomeValue(decision.decisionKey, metric, OutcomeState.UNAVAILABLE)
                }
        val reading = SleepSessions.read(session)
        if (reading.processed == false && now < ready + UNAVAILABLE_AFTER) return pending(decision, metric)
        val value = if (metric == OutcomeMetric.BEDTIME_NEXT) reading.bedtime.toLong() else reading.asleepMinutes
        return available(decision, metric, value, Lineage(setOf(DataCategory.SLEEP), setOf(session.source.family)))
    }

    /** Steps of t0's local calendar day: the `steps` daily row (canonical source rules, never a cross-source sum). */
    private suspend fun dayTotal(decision: OutcomeDecision, now: Instant): OutcomeValue {
        val metric = OutcomeMetric.STEPS_DAY_TOTAL
        val date = decision.at.toLocalDateTime(decision.zone).date
        val dayEnd = date.plus(DatePeriod(days = 1)).atStartOfDayIn(decision.zone)
        if (now < nextLocalDayAt(decision, LocalTime(4, 0)) + 1.hours) return pending(decision, metric)
        val row = daily.dailyRows(date, date).firstOrNull { it.metric == STEPS_FEATURE }
        val value = row?.value?.toLong()
        return when {
            row?.status == DailyRowStatus.FINAL && value != null -> available(decision, metric, value, row.lineage)
            now < dayEnd + UNAVAILABLE_AFTER -> pending(decision, metric)
            value != null && row.status != DailyRowStatus.MISSING -> available(decision, metric, value, row.lineage)
            else -> OutcomeValue(decision.decisionKey, metric, OutcomeState.UNAVAILABLE)
        }
    }

    private fun nextLocalDayAt(decision: OutcomeDecision, time: LocalTime): Instant {
        val next: LocalDate = decision.at.toLocalDateTime(decision.zone).date.plus(DatePeriod(days = 1))
        return LocalDateTime(next, time).toInstant(decision.zone)
    }

    private fun isMain(event: PersonalEvent): Boolean = (event.payload as SleepSessionPayload).isMainSleep

    private fun categoryOf(payload: AppUsagePayload): String {
        val raw = appCategoryOverrides[payload.packageName] ?: payload.appCategory ?: return UNDEFINED
        return raw.uppercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").ifEmpty { UNDEFINED }
    }

    private fun interval(event: PersonalEvent): ClosedOpenRange {
        val duration = when (val p = event.payload) {
            is AppUsagePayload -> p.durationMs
            is ScreenPayload -> p.durationMs
            else -> null
        }
        val end = event.endTime ?: duration?.let { event.startTime + it.milliseconds } ?: event.startTime
        return ClosedOpenRange(event.startTime, maxOf(end, event.startTime))
    }

    private fun pending(decision: OutcomeDecision, metric: OutcomeMetric) = OutcomeValue(decision.decisionKey, metric, OutcomeState.PENDING)

    private fun available(decision: OutcomeDecision, metric: OutcomeMetric, value: Long, lineage: Lineage) =
        OutcomeValue(decision.decisionKey, metric, OutcomeState.AVAILABLE, number = value, lineage = lineage)

    public companion object {
        public const val ARG_PACKAGE: String = "package"
        public const val ARG_CATEGORY: String = "category"
        public const val NO_ANSWER: String = "NONE"
        public const val USAGE_COLLECTOR: String = "app_usage_events"
        public const val STEPS_FEATURE: String = "steps"
        private const val UNDEFINED = "UNDEFINED"
        private const val APP_GAP_MILLIS = 2_000L
        private val STEPS_LATENCY = 60.minutes
        private val USAGE_LATENCY = 5.minutes
        private val UNAVAILABLE_AFTER = 48.hours
        private val SLEEP_SEARCH = 18.hours
        private val MAX_SESSION = 24.hours
        private val SLEEP_READY = LocalTime(14, 0)
        private val HISTORY = Lineage(setOf(DataCategory.INTERVENTIONS), setOf(SourceFamily.ON_DEVICE))
    }
}
