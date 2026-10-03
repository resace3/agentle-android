package dev.agentle.app.shell

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.model.EventType
import dev.agentle.core.model.ScreenPayload
import dev.agentle.core.model.StepsPayload
import dev.agentle.core.time.AgentleClock
import dev.agentle.data.events.EventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject

/** One metric of a user dashboard: a total per day, oldest first; null for a day with no stored events. */
data class MetricSeries(val metric: DashboardMetric, val days: List<Pair<LocalDate, Long?>>)

/** Computes a user dashboard's metrics from the events stored on the device. */
@HiltViewModel
class UserDashboardViewModel @Inject constructor(
    private val events: EventRepository,
    private val clock: AgentleClock,
    val store: DashboardStore,
) : ViewModel() {
    fun series(spec: DashboardSpec): Flow<List<MetricSeries>> = events.changes().map { _ ->
        val zone = clock.zone()
        val today = clock.now().toLocalDateTime(zone).date
        val dates = (spec.days - 1 downTo 0).map { today.minus(DatePeriod(days = it)) }
        spec.metrics.map { metric ->
            MetricSeries(
                metric,
                dates.map { date ->
                    val start = date.atStartOfDayIn(zone)
                    val end = date.plus(DatePeriod(days = 1)).atStartOfDayIn(zone)
                    date to total(metric, start, end)
                },
            )
        }
    }

    private suspend fun total(metric: DashboardMetric, start: kotlin.time.Instant, end: kotlin.time.Instant): Long? = when (metric) {
        DashboardMetric.STEPS -> events.range(EventType.STEP_SAMPLE, start, end).map { it.event.payload }
            .filterIsInstance<StepsPayload>().takeIf { it.isNotEmpty() }?.sumOf { it.count }

        DashboardMetric.UNLOCKS -> events.range(EventType.DEVICE_UNLOCK, start, end)
            .takeIf { it.isNotEmpty() }?.size?.toLong()

        DashboardMetric.SCREEN_TIME_MINUTES -> events.range(EventType.SCREEN_SESSION, start, end).map { it.event.payload }
            .filterIsInstance<ScreenPayload>().takeIf { it.isNotEmpty() }?.sumOf { it.durationMs ?: 0L }?.div(MS_PER_MINUTE)
    }

    private companion object {
        const val MS_PER_MINUTE = 60_000L
    }
}
