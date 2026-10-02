package dev.agentle.connectors.googlehealth

import dev.agentle.connectors.googlehealth.GhJson.array
import dev.agentle.connectors.googlehealth.GhJson.boolean
import dev.agentle.connectors.googlehealth.GhJson.double
import dev.agentle.connectors.googlehealth.GhJson.instant
import dev.agentle.connectors.googlehealth.GhJson.long
import dev.agentle.connectors.googlehealth.GhJson.obj
import dev.agentle.connectors.googlehealth.GhJson.string
import dev.agentle.core.model.ExerciseEventEntry
import dev.agentle.core.model.ExercisePayload
import dev.agentle.core.model.Provenance
import dev.agentle.core.model.Sensitivity
import dev.agentle.core.model.SleepSessionPayload
import dev.agentle.core.model.SleepStage
import dev.agentle.core.model.SleepStageKind
import dev.agentle.core.model.SleepStageSummary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.TreeMap

/** Sleep and exercise sessions (docs/research/05 §4.4, §8.4 F-SLEEP and F-EXERCISE, §8.6 R4, R5a, R5e, R9a). */
internal class GhSessionMapper(private val base: GhMapper) {
    /**
     * A sleep session, keyed by `name`. Stages, short awakenings and the upstream summary are stored as given: short
     * awakenings stay separate (stages are never split) and the summary is never recomputed. Absent booleans are false
     * (R5a: a session still processing has `processed = false` and no stages).
     */
    fun sleep(stream: GhStream, value: JsonObject, provenance: Provenance?, upstreamId: String?): Mapped {
        val time = base.intervalOf(value.obj("interval")) ?: return Mapped.Skip("interval")
        val metadata = value.obj("metadata")
        val summary = value.obj("summary")
        val payload = SleepSessionPayload(
            stages = stages(value.array("stages")),
            minutesAsleep = summary?.long("minutesAsleep"),
            minutesAwake = summary?.long("minutesAwake"),
            isMainSleep = metadata?.boolean("mainSleep") ?: false,
            isNap = metadata?.boolean("nap") ?: false,
            processed = metadata?.boolean("processed") ?: false,
            sleepType = value.string("type"),
            stagesStatus = metadata?.string("stagesStatus"),
            minutesInSleepPeriod = summary?.long("minutesInSleepPeriod"),
            minutesToFallAsleep = summary?.long("minutesToFallAsleep"),
            minutesAfterWakeUp = summary?.long("minutesAfterWakeUp"),
            stageSummaries = summaries(summary?.array("stagesSummary")),
            shortAwakenings = stages(value.array("shortAwakenings")),
            outOfBedSegments = stages(value.array("outOfBedSegments"), SleepStageKind.OUT_OF_BED),
            manuallyEdited = metadata?.boolean("manuallyEdited"),
            startUtcOffsetSeconds = time.startOffset,
            endUtcOffsetSeconds = time.endOffset,
        )
        val key = sessionKey(stream, upstreamId, time)
        val updated = value.instant("updateTime") ?: value.instant("createTime")
        return Mapped.Event(base.event(stream, time.start, time.end, time.startOffset, payload, key, provenance, upstreamId, updated))
    }

    /**
     * An exercise session, keyed by `name`. An unknown `exerciseType` is `UNKNOWN` with the raw value kept (R4); the
     * codelab spelling `distanceMillimiters` is accepted when the discovery spelling is absent (§4.8). Missing metrics
     * stay null; the duration comes from the interval (R5e).
     */
    fun exercise(stream: GhStream, value: JsonObject, provenance: Provenance?, upstreamId: String?): Mapped {
        val time = base.intervalOf(value.obj("interval")) ?: return Mapped.Skip("interval")
        val metrics = value.obj("metricsSummary")
        val raw = value.string("exerciseType")
        val known = raw != null && raw in KNOWN_EXERCISE_TYPES
        val notes = value.string("notes")
        val payload = ExercisePayload(
            exerciseType = if (known) requireNotNull(raw) else UNKNOWN,
            durationMs = (time.end - time.start).inWholeMilliseconds,
            distanceMeters = (metrics?.double("distanceMillimeters") ?: metrics?.double("distanceMillimiters"))?.div(MM_PER_M),
            kilocalories = metrics?.double("caloriesKcal"),
            averageHeartRate = metrics?.double("averageHeartRateBeatsPerMinute"),
            steps = metrics?.long("steps"),
            rawExerciseType = if (known) null else raw,
            activeDurationMs = GhJson.durationMs(value.string("activeDuration")),
            displayName = value.string("displayName"),
            notes = notes,
            hasGps = value.obj("exerciseMetadata")?.boolean("hasGps"),
            elevationGainMeters = metrics?.double("elevationGainMillimeters")?.div(MM_PER_M),
            activeZoneMinutes = metrics?.long("activeZoneMinutes"),
            averagePaceSecondsPerMeter = metrics?.double("averagePaceSecondsPerMeter"),
            heartRateZoneSeconds = zones(metrics?.obj("heartRateZoneDurations")),
            events = events(value.array("exerciseEvents")),
            startUtcOffsetSeconds = time.startOffset,
            endUtcOffsetSeconds = time.endOffset,
        )
        val key = sessionKey(stream, upstreamId, time)
        val updated = value.instant("updateTime") ?: value.instant("createTime")
        val sensitivity = if (notes != null) Sensitivity.PERSONAL else Sensitivity.NORMAL
        return Mapped.Event(
            base.event(stream, time.start, time.end, time.startOffset, payload, key, provenance, upstreamId, updated, sensitivity),
        )
    }

    private fun sessionKey(stream: GhStream, upstreamId: String?, time: GhMapper.Interval): String =
        upstreamId?.let { "${base.prefix(stream)}|${it.substringAfterLast('/')}" }
            ?: "${base.prefix(stream)}|${time.start.toEpochMilliseconds()}|${time.end.toEpochMilliseconds()}"

    /** Stages with valid instants; unknown stage types are `UNKNOWN` with the raw name (R4). */
    private fun stages(array: JsonArray?, fixed: SleepStageKind? = null): List<SleepStage> = array.orEmpty().mapNotNull { element ->
        val stage = element as? JsonObject ?: return@mapNotNull null
        val start = stage.instant("startTime") ?: return@mapNotNull null
        val end = stage.instant("endTime") ?: return@mapNotNull null
        if (end <= start) return@mapNotNull null
        val raw = stage.string("type")
        val kind = fixed ?: stageKind(raw)
        val startOffset = GhJson.offsetSeconds(stage.string("startUtcOffset"))
        SleepStage(
            stage = kind,
            startEpochMs = start.toEpochMilliseconds(),
            endEpochMs = end.toEpochMilliseconds(),
            startUtcOffsetSeconds = startOffset,
            endUtcOffsetSeconds = GhJson.offsetSeconds(stage.string("endUtcOffset")) ?: startOffset,
            rawStage = if (kind == SleepStageKind.UNKNOWN) raw else null,
        )
    }

    private fun summaries(array: JsonArray?): List<SleepStageSummary> = array.orEmpty().mapNotNull { element ->
        val summary = element as? JsonObject ?: return@mapNotNull null
        val raw = summary.string("type")
        val kind = stageKind(raw)
        SleepStageSummary(kind, summary.long("minutes"), summary.long("count"), if (kind == SleepStageKind.UNKNOWN) raw else null)
    }

    private fun stageKind(raw: String?): SleepStageKind = when (raw) {
        "AWAKE" -> SleepStageKind.AWAKE
        "LIGHT" -> SleepStageKind.LIGHT
        "DEEP" -> SleepStageKind.DEEP
        "REM" -> SleepStageKind.REM
        "ASLEEP" -> SleepStageKind.ASLEEP_UNSPECIFIED
        "RESTLESS" -> SleepStageKind.RESTLESS
        else -> SleepStageKind.UNKNOWN
    }

    /** `{lightTime: "300s", ...}` as seconds per zone (`light`, `moderate`, ...), in a stable order. */
    private fun zones(durations: JsonObject?): Map<String, Long> {
        if (durations == null) return emptyMap()
        val result = TreeMap<String, Long>()
        durations.keys.forEach { key ->
            val seconds = GhJson.seconds(durations.string(key))?.takeIf { it >= 0 } ?: return@forEach
            result[key.removeSuffix("Time")] = seconds.toLong()
        }
        return result
    }

    private fun events(array: JsonArray?): List<ExerciseEventEntry> = array.orEmpty().mapNotNull { element ->
        val event = element as? JsonObject ?: return@mapNotNull null
        val type = event.string("exerciseEventType") ?: return@mapNotNull null
        val at = event.instant("eventTime") ?: return@mapNotNull null
        ExerciseEventEntry(type, at.toEpochMilliseconds(), GhJson.offsetSeconds(event.string("eventUtcOffset")))
    }

    companion object {
        const val UNKNOWN: String = "UNKNOWN"
        private const val MM_PER_M = 1000.0

        /**
         * Exercise types treated as known. The API enum has 182 values that were not available offline; this list is
         * a best-effort subset (UNVERIFIED). Any other value is stored as `UNKNOWN` with the raw value kept, so nothing
         * is lost when the list is incomplete.
         */
        val KNOWN_EXERCISE_TYPES: Set<String> = setOf(
            "RUNNING", "WALKING", "HIKING", "BIKING", "BIKING_STATIONARY", "MOUNTAIN_BIKING", "ROAD_BIKING", "SPINNING",
            "SWIMMING", "SWIMMING_POOL", "SWIMMING_OPEN_WATER", "YOGA", "PILATES", "WEIGHTLIFTING", "STRENGTH_TRAINING",
            "CIRCUIT_TRAINING", "CROSSFIT", "HIGH_INTENSITY_INTERVAL_TRAINING", "INTERVAL_TRAINING", "ELLIPTICAL", "ROWING",
            "ROWING_MACHINE", "STAIR_CLIMBING", "STAIR_CLIMBING_MACHINE", "TREADMILL", "RUNNING_TREADMILL", "WALKING_TREADMILL",
            "DANCING", "AEROBICS", "BOXING", "KICKBOXING", "MARTIAL_ARTS", "TENNIS", "TABLE_TENNIS", "BADMINTON", "SQUASH",
            "RACQUETBALL", "PICKLEBALL", "GOLF", "BASKETBALL", "SOCCER", "FOOTBALL_AMERICAN", "FOOTBALL_AUSTRALIAN",
            "BASEBALL", "SOFTBALL", "VOLLEYBALL", "ICE_HOCKEY", "ROLLER_HOCKEY", "ICE_SKATING", "SKATING", "SKIING",
            "CROSS_COUNTRY_SKIING", "SNOWBOARDING", "SNOWSHOEING", "SURFING", "PADDLING", "KAYAKING", "SAILING",
            "ROCK_CLIMBING", "STRETCHING", "MEDITATION", "GUIDED_BREATHING", "CALISTHENICS", "BOOT_CAMP", "BARRE",
            "CORE_TRAINING", "WORKOUT", "OUTDOOR_WORKOUT", "OTHER", "WHEELCHAIR", "CRICKET", "RUGBY", "HANDBALL", "FENCING",
            "GYMNASTICS", "WATER_POLO", "SCUBA_DIVING", "HORSEBACK_RIDING", "SPORT",
        )
    }
}
