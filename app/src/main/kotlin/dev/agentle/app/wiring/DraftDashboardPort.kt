package dev.agentle.app.wiring

import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.Quality
import dev.agentle.connectors.android.AndroidCollectorsGraph
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.EventType
import dev.agentle.core.model.ScreenPayload
import dev.agentle.core.model.StepsPayload
import dev.agentle.core.time.AgentleClock
import dev.agentle.data.events.EventRepository
import dev.agentle.feature.hub.port.DashboardData
import dev.agentle.feature.hub.port.DashboardPort
import dev.agentle.feature.hub.port.TodayMetricKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject
import kotlin.time.Instant

/**
 * The draft Dashboard: today's steps, unlocks and screen time are summed from stored events (provisional, and Missing
 * when nothing was stored today, never a zero), plus the collectors' status. Insights, JITAIs, the wearable and
 * pending interventions are not wired yet, so those sections stay empty and their actions are unsupported.
 */
internal class DraftDashboardPort @Inject constructor(
    private val events: EventRepository,
    graph: AndroidCollectorsGraph,
    private val clock: AgentleClock,
) : DashboardPort {
    override val dashboard: Flow<DashboardData> =
        combine(events.changes(), combine(graph.connectors.map { it.metadata }) { it.toList() }) { _, collectors ->
            DashboardData(today = today(), collectors = collectors)
        }

    private suspend fun today(): Map<TodayMetricKind, FeatureValue> {
        val now = clock.now()
        val zone = clock.zone()
        val start = now.toLocalDateTime(zone).date.atStartOfDayIn(zone)
        val steps = events.range(EventType.STEP_SAMPLE, start, now).map { it.event.payload }.filterIsInstance<StepsPayload>()
        val unlocks = events.range(EventType.DEVICE_UNLOCK, start, now)
        val screen = events.range(EventType.SCREEN_SESSION, start, now).map { it.event.payload }.filterIsInstance<ScreenPayload>()
        return mapOf(
            TodayMetricKind.STEPS to known(steps.isNotEmpty(), steps.sumOf { it.count }, now),
            TodayMetricKind.UNLOCKS to known(unlocks.isNotEmpty(), unlocks.size.toLong(), now),
            TodayMetricKind.SCREEN_TIME_MINUTES to
                known(screen.isNotEmpty(), screen.sumOf { it.durationMs ?: 0L } / MS_PER_MINUTE, now),
        )
    }

    private fun known(present: Boolean, value: Long, asOf: Instant): FeatureValue = if (present) {
        FeatureValue.Known(FeatureScalar.IntValue(value), asOf, Quality.PROVISIONAL)
    } else {
        FeatureValue.Missing(MissingReason.NO_DATA)
    }

    override suspend fun snooze(decisionKey: String): Outcome<Unit> = Outcome.failure(AppError.UnsupportedFeature("dashboard"))

    override suspend fun notNow(decisionKey: String): Outcome<Unit> = Outcome.failure(AppError.UnsupportedFeature("dashboard"))

    override suspend fun stopJitai(jitaiId: String): Outcome<Unit> = Outcome.failure(AppError.UnsupportedFeature("dashboard"))

    private companion object {
        const val MS_PER_MINUTE = 60_000L
    }
}
