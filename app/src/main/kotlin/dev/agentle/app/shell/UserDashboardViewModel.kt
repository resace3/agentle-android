package dev.agentle.app.shell

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.model.CaloriesPayload
import dev.agentle.core.model.DailyTotalMetric
import dev.agentle.core.model.EnergyBasis
import dev.agentle.core.model.EventPayload
import dev.agentle.core.model.EventProjections
import dev.agentle.core.model.EventType
import dev.agentle.core.model.ExercisePayload
import dev.agentle.core.model.RestingHeartRatePayload
import dev.agentle.core.model.ScreenPayload
import dev.agentle.core.model.SleepSessionPayload
import dev.agentle.core.time.AgentleClock
import dev.agentle.data.events.EventRepository
import dev.agentle.data.events.MinuteAggregation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject
import kotlin.math.roundToLong
import kotlin.time.Instant

/** One metric of a user dashboard: a value per day, oldest first; null for a day with no stored data. */
data class MetricSeries(val metric: DashboardMetric, val days: List<Pair<LocalDate, Long?>>)

/** A dashboard's numbers, or [unreadable] when the data stored on the phone could not be read. */
data class DashboardData(val series: List<MetricSeries>, val unreadable: Boolean = false)

/** Computes a user dashboard's metrics on the device from stored events; nothing is sent anywhere. */
@HiltViewModel
class UserDashboardViewModel @Inject constructor(
    private val events: EventRepository,
    private val clock: AgentleClock,
    val store: DashboardStore,
) : ViewModel() {
    fun data(spec: DashboardSpec): Flow<DashboardData> = events.changes().conflate().map { _ ->
        val zone = clock.zone()
        val today = clock.now().toLocalDateTime(zone).date
        val window = Window(today.minus(DatePeriod(days = spec.days - 1)), today, zone)
        val dates = (0 until spec.days).map { window.first.plus(DatePeriod(days = it)) }
        val series = spec.metrics.map { metric ->
            val values = daily(metric, window)
            MetricSeries(metric, dates.map { date -> date to values[date]?.roundToLong() })
        }
        DashboardData(series)
    }.catch { emit(DashboardData(emptyList(), unreadable = true)) }

    private class Window(val first: LocalDate, val last: LocalDate, val zone: TimeZone) {
        val start: Instant = first.atStartOfDayIn(zone)
        val end: Instant = last.plus(DatePeriod(days = 1)).atStartOfDayIn(zone)

        /** A day earlier than [start], for nights that began before the first day. */
        val dayBefore: Instant = first.minus(DatePeriod(days = 1)).atStartOfDayIn(zone)
    }

    private suspend fun daily(metric: DashboardMetric, window: Window): Map<LocalDate, Double> {
        val zone = window.zone
        return when (metric) {
            DashboardMetric.STEPS -> withDailyTotals(
                DailyTotalMetric.STEPS,
                window,
                DashboardMath.total(fused(EventType.STEP_SAMPLE, "steps", window, MinuteAggregation.SUM), zone),
            )

            DashboardMetric.DISTANCE_METERS -> withDailyTotals(
                DailyTotalMetric.DISTANCE_METERS,
                window,
                DashboardMath.total(fused(EventType.DISTANCE_SAMPLE, "distance", window, MinuteAggregation.SUM), zone),
            )

            DashboardMetric.ACTIVE_CALORIES -> withDailyTotals(
                DailyTotalMetric.ACTIVE_CALORIES_KCAL,
                window,
                DashboardMath.largestSourceTotal(readings(EventType.CALORIES_SAMPLE, window, ::activeKilocalories), zone),
            )

            DashboardMetric.EXERCISE_MINUTES ->
                DashboardMath.largestSourceTotal(readings(EventType.EXERCISE_SESSION, window, ::exerciseMinutes), zone)

            // A night counts on the day the user woke up.
            DashboardMetric.SLEEP_MINUTES -> DashboardMath.largestSourceTotal(
                readings(EventType.SLEEP_SESSION, window, byEnd = true) { (it as? SleepSessionPayload)?.let(EventProjections::valueOf) },
                zone,
            )

            DashboardMetric.HEART_RATE_AVG ->
                DashboardMath.mean(fused(EventType.HEART_RATE, "heart_rate", window, MinuteAggregation.MEAN), zone)

            DashboardMetric.RESTING_HEART_RATE -> DashboardMath.byReportedDate(
                events.range(EventType.RESTING_HEART_RATE, window.dayBefore, window.end).mapNotNull { stored ->
                    (stored.event.payload as? RestingHeartRatePayload)?.let { it.date to it.bpm }
                },
            )

            DashboardMetric.SCREEN_TIME_MINUTES ->
                DashboardMath.largestSourceTotal(readings(EventType.SCREEN_SESSION, window, ::screenMinutes), zone)

            DashboardMetric.UNLOCKS -> DashboardMath.largestSourceTotal(readings(EventType.DEVICE_UNLOCK, window) { 1.0 }, zone)

            DashboardMetric.NOTIFICATIONS ->
                DashboardMath.largestSourceTotal(readings(EventType.NOTIFICATION_POSTED, window) { 1.0 }, zone)
        }
    }

    private suspend fun withDailyTotals(
        metric: DailyTotalMetric,
        window: Window,
        fromReadings: Map<LocalDate, Double>,
    ): Map<LocalDate, Double> {
        val totals = events.dailyTotals(metric, window.first, window.last).mapNotNull { total -> total.value?.let { total.date to it } }
        return DashboardMath.preferDailyTotals(totals, fromReadings)
    }

    /** Per-minute values of [type] merged across sources: each minute comes from one source only. */
    private suspend fun fused(type: EventType, policy: String, window: Window, aggregation: MinuteAggregation): List<Reading> =
        events.fusedMinutes(type, policy, window.start, window.end, aggregation).map { Reading(it.minuteStart, it.source, it.value) }

    /** Events of [type] in the window as readings at their start, or at their end when [byEnd]. */
    private suspend fun readings(type: EventType, window: Window, byEnd: Boolean = false, value: (EventPayload) -> Double?): List<Reading> =
        events.range(type, if (byEnd) window.dayBefore else window.start, window.end).mapNotNull { stored ->
            val event = stored.event
            value(event.payload)?.let { Reading(if (byEnd) event.endTime ?: event.startTime else event.startTime, event.source.value, it) }
        }

    private fun activeKilocalories(payload: EventPayload): Double? =
        (payload as? CaloriesPayload)?.takeIf { it.basis == EnergyBasis.ACTIVE }?.kilocalories

    private fun screenMinutes(payload: EventPayload): Double? = (payload as? ScreenPayload)?.durationMs?.let { it / MS_PER_MINUTE }

    private fun exerciseMinutes(payload: EventPayload): Double? =
        (payload as? ExercisePayload)?.let { (it.activeDurationMs ?: it.durationMs) / MS_PER_MINUTE }

    private companion object {
        const val MS_PER_MINUTE = 60_000.0
    }
}
