package dev.agentle.fakes.synth

import dev.agentle.core.model.ConnectorIds
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventId
import dev.agentle.core.model.EventMetadata
import dev.agentle.core.model.EventPayload
import dev.agentle.core.model.EventType
import dev.agentle.core.model.ExercisePayload
import dev.agentle.core.model.HeartRatePayload
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.Provenance
import dev.agentle.core.model.RestingHeartRatePayload
import dev.agentle.core.model.SleepSessionPayload
import dev.agentle.core.model.SleepStage
import dev.agentle.core.model.SleepStageKind
import dev.agentle.core.model.StepsPayload
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import java.util.UUID
import kotlin.time.Instant

/**
 * Turns the synthetic user into [PersonalEvent]s, the way the connectors would hand them to the event store: phone
 * steps, wearable steps, heart rate, daily resting heart rate, sleep sessions with stages, and exercise sessions.
 * Wear-off periods have no event type and are skipped (they show up as the absence of wearable data).
 *
 * Event ids and dedup keys are derived from the record, so the same spec always gives identical events. Dedup keys use
 * the `synth|` prefix: they never collide with a real connector's keys.
 *
 * @param wearableConnectorId connector id of the wearable streams (`googlehealth.steps`, `googlehealth.heart_rate`,
 *   `googlehealth.resting_heart_rate`, `googlehealth.sleep`, `googlehealth.exercise`).
 * @param phoneSource source of the phone's own step counting.
 */
public class SynthAdapter(
    private val spec: SynthSpec,
    private val wearableConnectorId: String = ConnectorIds.GOOGLE_HEALTH,
    private val phoneSource: DataSourceId = DataSourceId("android.steps"),
) {
    private val wearableSteps = DataSourceId.of(wearableConnectorId, "steps")
    private val heartRate = DataSourceId.of(wearableConnectorId, "heart_rate")
    private val restingHeartRate = DataSourceId.of(wearableConnectorId, "resting_heart_rate")
    private val sleep = DataSourceId.of(wearableConnectorId, "sleep")
    private val exercise = DataSourceId.of(wearableConnectorId, "exercise")

    /** Every event of the spec (raw events, then resting heart rates), ordered by start time. */
    public fun all(): List<PersonalEvent> {
        val raw = SyntheticUser.generate(spec)
        return (toPersonalEvents(raw) + restingHeartRateEvents()).sortedWith(compareBy({ it.startTime }, { it.dedupKey }))
    }

    /** Maps [events] in order; wear-off periods are skipped. Exercise totals are computed from [events]. */
    public fun toPersonalEvents(events: List<SynthEvent>): List<PersonalEvent> = events.mapNotNull { toPersonalEvent(it, events) }

    /** One event; [context] provides the minutes and samples inside an exercise session. Null for wear-off. */
    public fun toPersonalEvent(event: SynthEvent, context: List<SynthEvent> = emptyList()): PersonalEvent? = when (event.type) {
        SynthEventType.PHONE_STEPS_MINUTE -> stepEvent(event, phoneSource)
        SynthEventType.STEPS_MINUTE -> stepEvent(event, wearableSteps)
        SynthEventType.HEART_RATE_SAMPLE -> build(heartRate, EventType.HEART_RATE, event, null, HeartRatePayload(event.value))
        SynthEventType.SLEEP_SESSION -> sleepEvent(event)
        SynthEventType.EXERCISE_SESSION -> exerciseEvent(event, context)
        SynthEventType.WEAR_OFF -> null
    }

    /** Daily resting heart rates, keyed by their civil date (the event spans that date in the zone of the day). */
    public fun restingHeartRateEvents(): List<PersonalEvent> = SynthHealth.restingHeartRates(spec).map { rhr ->
        val start = rhr.date.atStartOfDayIn(rhr.zone)
        val end = rhr.date.plus(DatePeriod(days = 1)).atStartOfDayIn(rhr.zone)
        val key = "synth|${restingHeartRate.value}|${rhr.date}"
        PersonalEvent(
            id = idFor(key),
            type = EventType.RESTING_HEART_RATE,
            source = restingHeartRate,
            startTime = start,
            endTime = end,
            zoneId = rhr.zone.id,
            payload = RestingHeartRatePayload(bpm = rhr.bpm.toDouble(), date = rhr.date),
            dedupKey = key,
            metadata = metadata(end),
        )
    }

    private fun stepEvent(event: SynthEvent, source: DataSourceId): PersonalEvent =
        build(source, EventType.STEP_SAMPLE, event, event.end, StepsPayload(event.value.toLong()))

    private fun sleepEvent(event: SynthEvent): PersonalEvent {
        val stages = SynthHealth.sleepStages(spec, event)
        val awake = stages.filter { it.kind == SynthSleepStageKind.AWAKE }.sumOf { it.minutes }
        val total = (event.end - event.start).inWholeMinutes
        val payload = SleepSessionPayload(
            stages = stages.map { stage ->
                SleepStage(
                    stage = stage.kind.toModel(),
                    startEpochMs = stage.start.toEpochMilliseconds(),
                    endEpochMs = stage.end.toEpochMilliseconds(),
                    startUtcOffsetSeconds = SyntheticUser.zoneAt(spec, stage.start).offsetAt(stage.start).totalSeconds,
                    endUtcOffsetSeconds = SyntheticUser.zoneAt(spec, stage.end).offsetAt(stage.end).totalSeconds,
                )
            },
            minutesAsleep = total - awake,
            minutesAwake = awake,
            isMainSleep = true,
            isNap = false,
            processed = true,
            sleepType = "STAGES",
            minutesInSleepPeriod = total,
            startUtcOffsetSeconds = event.utcOffsetSeconds,
            endUtcOffsetSeconds = SyntheticUser.zoneAt(spec, event.end).offsetAt(event.end).totalSeconds,
        )
        return build(sleep, EventType.SLEEP_SESSION, event, event.end, payload)
    }

    private fun exerciseEvent(event: SynthEvent, context: List<SynthEvent>): PersonalEvent {
        val metrics = SynthHealth.exerciseMetrics(event, context)
        val payload = ExercisePayload(
            exerciseType = SynthHealth.exerciseKind(event).name,
            durationMs = (event.end - event.start).inWholeMilliseconds,
            distanceMeters = if (context.isEmpty()) null else metrics.distanceMillimeters / MILLIMETERS_PER_METER,
            kilocalories = if (context.isEmpty()) null else metrics.activeKcal,
            averageHeartRate = metrics.averageHeartRate?.toDouble(),
            steps = if (context.isEmpty()) null else metrics.steps,
            startUtcOffsetSeconds = event.utcOffsetSeconds,
            endUtcOffsetSeconds = SyntheticUser.zoneAt(spec, event.end).offsetAt(event.end).totalSeconds,
        )
        return build(exercise, EventType.EXERCISE_SESSION, event, event.end, payload)
    }

    private fun build(source: DataSourceId, type: EventType, event: SynthEvent, end: Instant?, payload: EventPayload): PersonalEvent {
        val key = "synth|${source.value}|${event.start.toEpochMilliseconds()}|${event.end.toEpochMilliseconds()}"
        return PersonalEvent(
            id = idFor(key),
            type = type,
            source = source,
            startTime = event.start,
            endTime = end,
            zoneId = event.zone.id,
            payload = payload,
            dedupKey = key,
            metadata = metadata(event.end, provenance(source)),
        )
    }

    private fun provenance(source: DataSourceId): Provenance = if (source == phoneSource) {
        Provenance(formFactor = "PHONE", recordingMethod = "PASSIVELY_MEASURED")
    } else {
        Provenance(platform = "FITBIT", deviceName = DEVICE_NAME, formFactor = "FITNESS_BAND", recordingMethod = "PASSIVELY_MEASURED")
    }

    private fun metadata(ingestedAt: Instant, provenance: Provenance? = null) =
        EventMetadata(ingestedAt = ingestedAt, origin = provenance?.deviceName, provenance = provenance)

    private fun idFor(key: String): EventId = EventId(UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString())

    private fun SynthSleepStageKind.toModel(): SleepStageKind = when (this) {
        SynthSleepStageKind.AWAKE -> SleepStageKind.AWAKE
        SynthSleepStageKind.LIGHT -> SleepStageKind.LIGHT
        SynthSleepStageKind.DEEP -> SleepStageKind.DEEP
        SynthSleepStageKind.REM -> SleepStageKind.REM
    }

    public companion object {
        /** The wearable the synthetic user wears (the Google Health fake reports the same device). */
        public const val DEVICE_NAME: String = "Charge 6"
        private const val MILLIMETERS_PER_METER = 1000.0
    }
}
