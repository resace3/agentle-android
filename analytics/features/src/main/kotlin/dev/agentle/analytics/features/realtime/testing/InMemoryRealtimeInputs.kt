package dev.agentle.analytics.features.realtime.testing

import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.analytics.features.realtime.ActivityTransition
import dev.agentle.analytics.features.realtime.CollectorIds
import dev.agentle.analytics.features.realtime.DailyMetric
import dev.agentle.analytics.features.realtime.HealthMetric
import dev.agentle.analytics.features.realtime.HeartRateSample
import dev.agentle.analytics.features.realtime.NotificationPost
import dev.agentle.analytics.features.realtime.RealtimeFeatureInputs
import dev.agentle.analytics.features.realtime.SleepSessionRecord
import dev.agentle.analytics.features.realtime.SleepStageSpan
import dev.agentle.analytics.features.realtime.StepInterval
import dev.agentle.analytics.features.realtime.UsageEvent
import dev.agentle.analytics.features.realtime.UsageEventKind
import dev.agentle.core.model.ActivityTransitionPayload
import dev.agentle.core.model.AppUsagePayload
import dev.agentle.core.model.ConnectorIds
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventPayload
import dev.agentle.core.model.EventType
import dev.agentle.core.model.HeartRatePayload
import dev.agentle.core.model.NotificationPayload
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.RestingHeartRatePayload
import dev.agentle.core.model.SleepSessionPayload
import dev.agentle.core.model.SleepStage
import dev.agentle.core.model.SleepStageKind
import dev.agentle.core.model.StepsPayload
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.offsetAt
import kotlin.math.roundToLong
import kotlin.time.Instant

/**
 * Test support, not for production code: every realtime input in memory, for tests of this module, the JITAI engine
 * and the Android layers. All ports share [log], which also bounds the snapshot of each `resolve()`. The live state
 * defaults to fixture F0 of R10 §12. Collectors have no coverage and health sources no `coverageThrough` until a test
 * declares them (coverage is mandatory, lifecycle-battery-01).
 */
public class InMemoryRealtimeInputs(public val log: ReadLog = ReadLog()) {
    public val usage: InMemoryUsageEvents = InMemoryUsageEvents(log)
    public val notifications: InMemoryNotifications = InMemoryNotifications(log)
    public val activity: InMemoryActivityTransitions = InMemoryActivityTransitions(log)
    public val sourceCoverage: InMemorySourceCoverage = InMemorySourceCoverage(log)
    public val steps: InMemoryStepSeries = InMemoryStepSeries(sourceCoverage, log)
    public val sleep: InMemorySleepSessions = InMemorySleepSessions(log = log)
    public val dailySummaries: InMemoryDailySummaries = InMemoryDailySummaries(log = log)
    public val heartRate: InMemoryHeartRate = InMemoryHeartRate(log = log)
    public val collectorCoverage: InMemoryCollectorCoverage = InMemoryCollectorCoverage(log)
    public val history: InMemoryInterventionHistory = InMemoryInterventionHistory(log)
    public val live: FakeLiveDeviceReads = FakeLiveDeviceReads(log)

    /** Every port, for example to make all of them fail at once. */
    public val ports: List<InMemoryPort>
        get() = listOf(
            usage, notifications, activity, sourceCoverage, steps, sleep, dailySummaries, heartRate, collectorCoverage, history, live,
        )

    /** The ports as engine inputs; every read of a `resolve()` runs inside one snapshot of [log]. */
    public fun toInputs(): RealtimeFeatureInputs = RealtimeFeatureInputs(
        usage = usage,
        notifications = notifications,
        activity = activity,
        steps = steps,
        sleep = sleep,
        dailySummaries = dailySummaries,
        heartRate = heartRate,
        sourceCoverage = sourceCoverage,
        collectorCoverage = collectorCoverage,
        history = history,
        live = live,
        snapshots = log,
    )

    /** Marks the usage, notification and activity-transition collectors healthy from [from] on. */
    public fun collectorsHealthySince(from: Instant) {
        listOf(CollectorIds.USAGE_EVENTS, CollectorIds.NOTIFICATION_LISTENER, CollectorIds.ACTIVITY_TRANSITIONS)
            .forEach { collectorCoverage.healthySince(it, from) }
    }

    /** Every health source of the ports asserts completeness through [through]. */
    public fun healthSyncedThrough(through: Instant) {
        steps.sources.forEach { sourceCoverage.set(it.id, HealthMetric.STEPS, through) }
        sourceCoverage.set(sleep.source, HealthMetric.SLEEP, through)
        sourceCoverage.set(dailySummaries.source, HealthMetric.RESTING_HEART_RATE, through)
        sourceCoverage.set(heartRate.source, HealthMetric.HEART_RATE, through)
    }

    /**
     * Stores [events] the way the collectors and connectors would. Sleep, resting heart rate and heart rate are kept
     * only for the ports' canonical sources; steps of a source the step port does not know are appended to its
     * sources (last priority). Event types the realtime features do not read are ignored.
     */
    public fun addEvents(events: List<PersonalEvent>) {
        for (event in events) add(event)
    }

    private fun add(event: PersonalEvent) {
        val kind = USAGE_KINDS[event.type]
        val payload = event.payload
        when {
            kind != null -> usage.rows += UsageEvent(event.startTime, kind)

            event.type == EventType.SCREEN_SESSION -> session(
                event,
                UsageEventKind.SCREEN_INTERACTIVE,
                UsageEventKind.SCREEN_NON_INTERACTIVE,
            )

            payload is AppUsagePayload -> appEvent(event, payload)

            payload is NotificationPayload && event.type == EventType.NOTIFICATION_POSTED ->
                notifications.rows +=
                    notification(event, payload)

            payload is ActivityTransitionPayload ->
                activity.rows +=
                    ActivityTransition(event.startTime, payload.activity, payload.transition)

            payload is StepsPayload && event.type == EventType.STEP_SAMPLE -> stepEvent(event, payload)

            else -> healthEvent(event, payload)
        }
    }

    private fun healthEvent(event: PersonalEvent, payload: EventPayload) {
        val source = event.source.value
        when {
            payload is SleepSessionPayload && source == sleep.source -> sleep.rows += sleepRecord(event, payload)

            payload is RestingHeartRatePayload && source == dailySummaries.source ->
                dailySummaries.put(DailyMetric.RESTING_HEART_RATE, payload.date, payload.bpm.roundToLong())

            payload is HeartRatePayload && source == heartRate.source ->
                heartRate.rows += HeartRateSample(event.startTime, payload.bpm.roundToLong())

            else -> Unit
        }
    }

    private fun appEvent(event: PersonalEvent, payload: AppUsagePayload) {
        val packageName = payload.packageName
        payload.appCategory?.uppercase()?.takeIf { it in RealtimeFeatureCatalog.APP_CATEGORIES }?.let { usage.categories[packageName] = it }
        when (event.type) {
            EventType.APP_FOREGROUND -> usage.rows += UsageEvent(event.startTime, UsageEventKind.ACTIVITY_RESUMED, packageName)
            EventType.APP_BACKGROUND -> usage.rows += UsageEvent(event.startTime, UsageEventKind.ACTIVITY_PAUSED, packageName)
            EventType.APP_SESSION -> session(event, UsageEventKind.ACTIVITY_RESUMED, UsageEventKind.ACTIVITY_PAUSED, packageName)
            else -> Unit
        }
    }

    private fun session(event: PersonalEvent, on: UsageEventKind, off: UsageEventKind, packageName: String? = null) {
        usage.rows += UsageEvent(event.startTime, on, packageName)
        event.endTime?.let { usage.rows += UsageEvent(it, off, packageName) }
    }

    private fun notification(event: PersonalEvent, payload: NotificationPayload) = NotificationPost(
        at = event.startTime,
        packageName = payload.packageName,
        keyHash = payload.keyHash ?: event.dedupKey,
        ongoing = payload.ongoing,
        groupSummary = payload.groupSummary,
    )

    private fun stepEvent(event: PersonalEvent, payload: StepsPayload) {
        if (payload.count < 0) return
        val source = event.source.value
        if (steps.sources.none { it.id == source }) steps.sources += stepSource(event.source)
        steps.add(source, StepInterval(event.startTime, event.endTime ?: event.startTime, payload.count))
    }

    private fun sleepRecord(event: PersonalEvent, payload: SleepSessionPayload): SleepSessionRecord {
        val zone = TimeZone.of(event.zoneId)
        val end = event.endTime ?: event.startTime
        // Health Connect has no main-sleep or nap flag: the 3-hour rule applies (R10 §5.4 G).
        val flagged = event.source.connectorId != ConnectorIds.HEALTH_CONNECT
        val stages = payload.stages.mapNotNull { span(it, it.stage) } +
            payload.outOfBedSegments.mapNotNull { span(it, SleepStageKind.OUT_OF_BED) }
        return SleepSessionRecord(
            start = event.startTime,
            end = end,
            startOffset = payload.startUtcOffsetSeconds?.let { UtcOffset(seconds = it) } ?: zone.offsetAt(event.startTime),
            endOffset = payload.endUtcOffsetSeconds?.let { UtcOffset(seconds = it) } ?: zone.offsetAt(end),
            stages = stages,
            minutesAsleep = payload.minutesAsleep,
            mainSleep = payload.isMainSleep.takeIf { flagged },
            nap = payload.isNap.takeIf { flagged },
            processed = payload.processed,
        )
    }

    private fun span(stage: SleepStage, kind: SleepStageKind): SleepStageSpan? = if (stage.endEpochMs >= stage.startEpochMs) {
        SleepStageSpan(kind, Instant.fromEpochMilliseconds(stage.startEpochMs), Instant.fromEpochMilliseconds(stage.endEpochMs))
    } else {
        null
    }

    public companion object {
        /**
         * Inputs holding [events]. The canonical sleep, resting-heart-rate and heart-rate sources are the first of the
         * events' sources by connector priority (Google Health API, Health Connect, then the rest by id), so one source
         * per metric is read (R05 §7.7). The step sources are the events' step sources in the same order.
         */
        public fun fromEvents(events: List<PersonalEvent>, log: ReadLog = ReadLog()): InMemoryRealtimeInputs =
            InMemoryRealtimeInputs(log).apply {
                sourcesOf(events, EventType.SLEEP_SESSION).firstOrNull()?.let { sleep.source = it.value }
                sourcesOf(events, EventType.RESTING_HEART_RATE).firstOrNull()?.let { dailySummaries.source = it.value }
                sourcesOf(events, EventType.HEART_RATE).firstOrNull()?.let { heartRate.source = it.value }
                steps.sources.clear()
                sourcesOf(events, EventType.STEP_SAMPLE).forEach { steps.sources += stepSource(it) }
                addEvents(events)
            }

        private val USAGE_KINDS = mapOf(
            EventType.SCREEN_ON to UsageEventKind.SCREEN_INTERACTIVE,
            EventType.SCREEN_OFF to UsageEventKind.SCREEN_NON_INTERACTIVE,
            EventType.DEVICE_UNLOCK to UsageEventKind.KEYGUARD_HIDDEN,
            EventType.DEVICE_LOCK to UsageEventKind.KEYGUARD_SHOWN,
            EventType.SHUTDOWN to UsageEventKind.DEVICE_SHUTDOWN,
            EventType.BOOT_COMPLETED to UsageEventKind.DEVICE_STARTUP,
        )

        private val CONNECTOR_PRIORITY = listOf(ConnectorIds.GOOGLE_HEALTH, ConnectorIds.HEALTH_CONNECT)

        private fun priority(source: DataSourceId): Int =
            CONNECTOR_PRIORITY.indexOf(source.connectorId).takeIf { it >= 0 } ?: CONNECTOR_PRIORITY.size

        private fun sourcesOf(events: List<PersonalEvent>, type: EventType): List<DataSourceId> = events.asSequence()
            .filter { it.type == type }
            .map { it.source }
            .distinct()
            .sortedWith(compareBy<DataSourceId>({ priority(it) }, { it.value }))
            .toList()

        /** The Google Health API writes true zeros (R05 §5.4); other step sources omit zero minutes. */
        private fun stepSource(source: DataSourceId) =
            StepSourceSpec(source.value, reportsTrueZeros = source.connectorId == ConnectorIds.GOOGLE_HEALTH)
    }
}
