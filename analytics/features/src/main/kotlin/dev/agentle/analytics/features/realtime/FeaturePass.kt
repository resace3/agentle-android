package dev.agentle.analytics.features.realtime

import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.Quality
import dev.agentle.core.time.ClosedOpenRange
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * One evaluation pass at [at] (R10 §5.2): the zone and the monotonic clock are read once, and every port read and every
 * derived window is memoized, so each input is read at most once per pass and every ref sees the same data.
 */
internal class FeaturePass(
    val at: Instant,
    val zone: TimeZone,
    /** `AgentleClock.elapsed()` at the start of the pass, for the elapsed-time rule of R10 §8.6. */
    val elapsedNow: Duration,
    val inputs: RealtimeFeatureInputs,
    val config: RealtimeFeatureConfig,
) {
    private val memo = HashMap<Any, Any?>()

    /** Today's local calendar date in the pass zone (R10 §10.5); daily values are keyed by it. */
    val localDate: LocalDate = at.toLocalDateTime(zone).date

    /** The engine day of [at] (R10 §10.2). */
    val engineDay: LocalDate = LocalTimeRules.engineDay(at, zone, config.engineDayRollover)

    /** Computes [compute] once per [key] in this pass. */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> memo(key: Any, compute: suspend () -> T): T {
        if (memo.containsKey(key)) return memo[key] as T
        val value = compute()
        memo[key] = value
        return value
    }

    fun known(value: FeatureScalar, asOf: Instant = at, quality: Quality = Quality.FINAL): FeatureValue =
        FeatureValue.Known(value, asOf, quality)

    // ------------------------------------------------------------------ live reads (once per pass)

    suspend fun charging(): Read<Boolean> = memo(Key.LIVE_CHARGING) { inputs.live.charging().toRead() }

    suspend fun batteryPercent(): Read<Int> = memo(Key.LIVE_BATTERY) { inputs.live.batteryPercent().toRead() }

    suspend fun interactive(): Read<Boolean> = memo(Key.LIVE_INTERACTIVE) { inputs.live.interactive().toRead() }

    suspend fun interruptionFilter(): Read<InterruptionFilter> = memo(Key.LIVE_FILTER) { inputs.live.interruptionFilter().toRead() }

    suspend fun audioMode(): Read<AudioMode> = memo(Key.LIVE_AUDIO_MODE) { inputs.live.audioMode().toRead() }

    suspend fun audioOutputs(): Read<Set<AudioOutputType>> = memo(Key.LIVE_AUDIO_OUTPUTS) { inputs.live.audioOutputs().toRead() }

    suspend fun foregroundApp(): Read<String?> = memo(Key.LIVE_FOREGROUND) { inputs.live.foregroundApp().toRead() }

    suspend fun bootCount(): Read<Int> = memo(Key.LIVE_BOOT_COUNT) { inputs.live.bootCount().toRead() }

    // ------------------------------------------------------------------ stored inputs (once per window)

    suspend fun usageEvents(window: ClosedOpenRange): Read<List<UsageEvent>> =
        memo(WindowKey("usage", window)) { inputs.usage.events(window).toRead() }

    suspend fun appCategories(packages: Set<String>): Read<Map<String, String>> =
        memo(WindowKey("categories", packages)) { inputs.usage.appCategories(packages).toRead() }

    suspend fun notificationPosts(window: ClosedOpenRange): Read<List<NotificationPost>> =
        memo(WindowKey("notifications", window)) { inputs.notifications.posted(window).toRead() }

    suspend fun activityTransitions(window: ClosedOpenRange): Read<List<ActivityTransition>> =
        memo(WindowKey("activity", window)) { inputs.activity.transitions(window).toRead() }

    suspend fun fusedSteps(window: ClosedOpenRange): Read<FusedStepSeries> =
        memo(WindowKey("steps", window)) { inputs.steps.fusedMinuteSeries(window).toRead() }

    suspend fun sleepEnding(endRange: ClosedOpenRange): Read<SleepSeries> =
        memo(WindowKey("sleep", endRange)) { inputs.sleep.sessionsEnding(endRange).toRead() }

    suspend fun dailyValues(metric: DailyMetric, first: LocalDate, last: LocalDate): Read<DailySeries> =
        memo(WindowKey("daily-$metric", first to last)) { inputs.dailySummaries.values(metric, first, last).toRead() }

    suspend fun coverageThrough(source: String, metric: HealthMetric): Read<Instant?> =
        memo(WindowKey("coverage-$metric", source)) { inputs.sourceCoverage.coverageThrough(source, metric).toPlainRead() }

    suspend fun latestDeliveries(selector: JitaiSelector, limit: Int): Read<List<DeliveryRecord>> =
        memo(WindowKey("latest-$limit", selector)) { inputs.history.latest(selector, limit).toPlainRead() }

    suspend fun deliveriesInEngineDays(selector: JitaiSelector, first: LocalDate, last: LocalDate): Read<List<DeliveryRecord>> =
        memo(WindowKey("days-$first-$last", selector)) { inputs.history.inEngineDays(selector, first, last).toPlainRead() }

    /**
     * Why [collectorId] does not cover [window], or null when it covers all of it: `COLLECTOR_INACTIVE` when it is not
     * healthy at the window end (the evaluation instant), `COVERAGE_GAP` for a gap inside (R10 §5.3, lifecycle-battery-01).
     */
    suspend fun collectorGap(collectorId: String, window: ClosedOpenRange): MissingReason? =
        memo(WindowKey("collector-$collectorId", window)) {
            when (val read = inputs.collectorCoverage.intervals(collectorId, window).toPlainRead()) {
                is Read.Fail -> read.reason
                is Read.Ok -> CoverageCheck.gap(read.value, window)
            }
        }

    /**
     * For a feature "at t" (REALTIME-FEATURES-R1-5): the start of the stretch of [window] that [collectorId] covers
     * without a gap up to its end, or `Missing(COLLECTOR_INACTIVE)` when it is not healthy at the end.
     */
    suspend fun coveredSince(collectorId: String, window: ClosedOpenRange): Read<Instant> =
        memo(WindowKey("covered-since-$collectorId", window)) {
            when (val read = inputs.collectorCoverage.intervals(collectorId, window).toPlainRead()) {
                is Read.Fail -> read

                is Read.Ok -> CoverageCheck.coveredSince(read.value, window)?.let { Read.Ok(it) }
                    ?: Read.Fail(MissingReason.COLLECTOR_INACTIVE)
            }
        }

    /**
     * The reason a daily value of [source] is absent: `NO_DATA` when the source asserted coverage within
     * [RealtimeFeatureConfig.dailyValueSyncLag], else `NOT_SYNCED` (absence is never zero, R10 §5.3).
     */
    suspend fun absentDailyValue(source: String, metric: HealthMetric): FeatureValue = when (val read = coverageThrough(source, metric)) {
        is Read.Fail -> FeatureValue.Missing(read.reason)

        is Read.Ok -> {
            val through = read.value
            val recent = through != null && through >= at - config.dailyValueSyncLag
            FeatureValue.Missing(if (recent) MissingReason.NO_DATA else MissingReason.NOT_SYNCED)
        }
    }

    private enum class Key {
        LIVE_CHARGING,
        LIVE_BATTERY,
        LIVE_INTERACTIVE,
        LIVE_FILTER,
        LIVE_AUDIO_MODE,
        LIVE_AUDIO_OUTPUTS,
        LIVE_FOREGROUND,
        LIVE_BOOT_COUNT,
    }

    private data class WindowKey(val kind: String, val window: Any)
}

/** Unwraps a read: the reason as `Missing`, or the result of [onOk]. */
internal inline fun <T> Read<T>.orMissing(onOk: (T) -> FeatureValue): FeatureValue = when (this) {
    is Read.Fail -> FeatureValue.Missing(reason)
    is Read.Ok -> onOk(value)
}

/** Collector coverage arithmetic: is `[W0, t)` inside the union of the healthy intervals? */
internal object CoverageCheck {
    fun gap(intervals: List<CoverageInterval>, window: ClosedOpenRange): MissingReason? {
        if (window.start >= window.end) return null
        val healthyAtEnd = intervals.any { it.from < window.end && (it.to == null || it.to >= window.end) }
        var cursor = window.start
        for (interval in intervals.sortedBy { it.from }) {
            if (interval.from > cursor) break
            val to = interval.to ?: return null // open: healthy from `from` (<= cursor) until now
            if (to > cursor) cursor = to
            if (cursor >= window.end) return null
        }
        return if (healthyAtEnd) MissingReason.COVERAGE_GAP else MissingReason.COLLECTOR_INACTIVE
    }

    /** The start (clipped to the window) of the gapless covered stretch that reaches the window end; null if none. */
    fun coveredSince(intervals: List<CoverageInterval>, window: ClosedOpenRange): Instant? {
        var from: Instant? = null
        var to: Instant? = null
        var open = false
        var result: Instant? = null
        for (interval in intervals.sortedBy { it.from }) {
            if (interval.from >= window.end) break
            val joins = from != null && (open || interval.from <= checkNotNull(to))
            if (!joins) {
                from = interval.from
                to = interval.to
                open = interval.to == null
            } else if (!open) {
                if (interval.to == null) open = true else to = maxOf(checkNotNull(to), interval.to)
            }
            if (open || checkNotNull(to) >= window.end) result = from
        }
        return result?.let { maxOf(it, window.start) }
    }
}
