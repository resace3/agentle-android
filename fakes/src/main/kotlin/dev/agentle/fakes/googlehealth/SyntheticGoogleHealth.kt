package dev.agentle.fakes.googlehealth

import dev.agentle.fakes.synth.SynthEvent
import dev.agentle.fakes.synth.SynthEventType
import dev.agentle.fakes.synth.SynthExerciseKind
import dev.agentle.fakes.synth.SynthHealth
import dev.agentle.fakes.synth.SynthSleepStageKind
import dev.agentle.fakes.synth.SynthSpec
import dev.agentle.fakes.synth.SyntheticUser
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.offsetAt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Model-mode data from the synthetic user (docs/research/08 §6): what the Google Health API would hold for that user.
 *
 * - steps, distance, active energy and floors per worn minute from the tracker (`FITBIT`), plus the phone's steps
 *   imported through Health Connect (`HEALTH_CONNECT`, every walking minute, worn or not);
 * - total calories per hour: basal 1.1 kcal/min plus the hour's active energy;
 * - heart-rate samples, daily resting heart rate, sleep sessions with stages and summary, exercise sessions with
 *   metrics, and scale weight and body fat;
 * - a Charge 6 tracker whose `lastSyncTime` is now minus the configured lag, and an Aria Air scale.
 *
 * Every value derives from the generator, so a test can compare synced totals with [SyntheticUser] exactly.
 */
public object SyntheticGoogleHealth {
    public fun dataset(spec: SynthSpec = SynthSpec(seed = DEFAULT_SEED), healthUserId: String = "1234567890"): FakeDataset {
        val events = SyntheticUser.generate(spec)
        val points = ArrayList<FakePoint>(events.size * 3)
        for (e in events) {
            when (e.type) {
                SynthEventType.STEPS_MINUTE -> points += minutePoints(e)

                SynthEventType.PHONE_STEPS_MINUTE -> points += interval(GhDataTypes.STEPS, e, e.value, FakeSource.PHONE_VIA_HEALTH_CONNECT)

                SynthEventType.HEART_RATE_SAMPLE ->
                    points += FakePoint(GhDataTypes.HEART_RATE, e.start, e.start, e.utcOffsetSeconds, amount = e.value)

                SynthEventType.SLEEP_SESSION -> points += sleep(spec, e, healthUserId)

                SynthEventType.EXERCISE_SESSION -> points += exercise(e, events, healthUserId)

                SynthEventType.WEAR_OFF -> Unit
            }
        }
        points += totalCalories(spec, events)
        SynthHealth.restingHeartRates(spec).forEach { r ->
            val start = r.date.atStartOfDayIn(r.zone)
            points += FakePoint(
                GhDataTypes.RESTING_HEART_RATE,
                start,
                start,
                null,
                date = r.date,
                amount = r.bpm.toDouble(),
                source = FakeSource.TRACKER_DERIVED,
                fields = buildJsonObject {
                    put("dailyRestingHeartRateMetadata", buildJsonObject { put("calculationMethod", "WITH_SLEEP") })
                },
            )
        }
        val body = SynthHealth.bodyMeasurements(spec)
        body.forEach { m ->
            val offset = m.zone.offsetAt(m.time).totalSeconds
            val id = m.time.epochSeconds
            points += FakePoint(
                GhDataTypes.WEIGHT,
                m.time,
                m.time,
                offset,
                amount = m.weightGrams.toDouble(),
                name = "users/$healthUserId/dataTypes/weight/dataPoints/${id}1",
                source = FakeSource.SCALE,
            )
            points += FakePoint(
                GhDataTypes.BODY_FAT,
                m.time,
                m.time,
                offset,
                amount = m.bodyFatPercent,
                name = "users/$healthUserId/dataTypes/body-fat/dataPoints/${id}2",
                source = FakeSource.SCALE,
            )
        }
        val devices = listOf(
            FakeDevice.CHARGE_6.copy(tracker = true, lastSyncTime = null),
            FakeDevice.ARIA_AIR.copy(lastSyncTime = body.lastOrNull()?.time),
        )
        return FakeDataset(points, devices)
    }

    private fun minutePoints(e: SynthEvent): List<FakePoint> {
        val steps = e.value.toLong()
        val out = ArrayList<FakePoint>(4)
        out += interval(GhDataTypes.STEPS, e, steps.toDouble(), FakeSource.TRACKER)
        out += interval(GhDataTypes.DISTANCE, e, SynthHealth.distanceMillimeters(steps).toDouble(), FakeSource.TRACKER)
        out += interval(GhDataTypes.ACTIVE_ENERGY, e, SynthHealth.activeKcal(steps), FakeSource.TRACKER_DERIVED)
        val floors = SynthHealth.floors(steps)
        if (floors > 0) out += interval(GhDataTypes.FLOORS, e, floors.toDouble(), FakeSource.TRACKER)
        return out
    }

    private fun interval(type: GhDataType, e: SynthEvent, amount: Double, source: FakeSource): FakePoint = FakePoint(
        type,
        e.start,
        e.end,
        e.zone.offsetAt(e.start).totalSeconds,
        e.zone.offsetAt(e.end).totalSeconds,
        amount = amount,
        source = source,
    )

    private fun totalCalories(spec: SynthSpec, events: List<SynthEvent>): List<FakePoint> {
        val active = HashMap<Long, Double>()
        for (e in events) {
            if (e.type != SynthEventType.STEPS_MINUTE) continue
            val hour = (e.start - spec.windowStart).inWholeHours
            active[hour] = (active[hour] ?: 0.0) + SynthHealth.activeKcal(e.value.toLong())
        }
        val out = ArrayList<FakePoint>()
        var t = spec.windowStart
        var hour = 0L
        while (t < spec.windowEnd) {
            val zone = SyntheticUser.zoneAt(spec, t)
            val end = t + 1.hours
            val kcal = SynthHealth.BASAL_KCAL_PER_MINUTE * MINUTES_PER_HOUR + (active[hour] ?: 0.0)
            out += FakePoint(
                GhDataTypes.TOTAL_CALORIES,
                t,
                end,
                zone.offsetAt(t).totalSeconds,
                zone.offsetAt(end).totalSeconds,
                amount = kcal,
                source = FakeSource.TRACKER_DERIVED,
            )
            t = end
            hour++
        }
        return out
    }

    private fun sleep(spec: SynthSpec, e: SynthEvent, userId: String): FakePoint {
        val stages = SynthHealth.sleepStages(spec, e)
        fun offset(t: Instant) = SyntheticUser.zoneAt(spec, t).offsetAt(t).totalSeconds
        val byKind = stages.groupBy { it.kind }
        val awake = byKind[SynthSleepStageKind.AWAKE].orEmpty().sumOf { it.minutes }
        val total = (e.end - e.start).inWholeMinutes
        val fields = buildJsonObject {
            put("type", "STAGES")
            put(
                "stages",
                JsonArray(
                    stages.map { s ->
                        buildJsonObject {
                            put("startTime", PointJson.timestamp(s.start))
                            put("startUtcOffset", PointJson.offset(offset(s.start)))
                            put("endTime", PointJson.timestamp(s.end))
                            put("endUtcOffset", PointJson.offset(offset(s.end)))
                            put("type", s.kind.name)
                        }
                    },
                ),
            )
            put(
                "metadata",
                buildJsonObject {
                    put("stagesStatus", "SUCCEEDED")
                    put("processed", true)
                    put("mainSleep", true)
                },
            )
            put(
                "summary",
                buildJsonObject {
                    put("minutesInSleepPeriod", total.toString())
                    put("minutesAfterWakeUp", "0")
                    put("minutesToFallAsleep", "0")
                    put("minutesAsleep", (total - awake).toString())
                    put("minutesAwake", awake.toString())
                    put(
                        "stagesSummary",
                        JsonArray(
                            SynthSleepStageKind.entries.filter { it in byKind }.map { kind ->
                                buildJsonObject {
                                    put("type", kind.name)
                                    put("minutes", byKind.getValue(kind).sumOf { it.minutes }.toString())
                                    put("count", byKind.getValue(kind).size.toString())
                                }
                            },
                        ),
                    )
                },
            )
            put("createTime", PointJson.timestamp(e.end + 10.minutes))
            put("updateTime", PointJson.timestamp(e.end + 40.minutes))
        }
        return FakePoint(
            GhDataTypes.SLEEP,
            e.start,
            e.end,
            offset(e.start),
            offset(e.end),
            name = "users/$userId/dataTypes/sleep/dataPoints/${e.start.epochSeconds}3",
            source = FakeSource.TRACKER_DERIVED,
            fields = fields,
        )
    }

    private fun exercise(e: SynthEvent, events: List<SynthEvent>, userId: String): FakePoint {
        val kind = SynthHealth.exerciseKind(e)
        val metrics = SynthHealth.exerciseMetrics(e, events)
        val offsetStart = e.zone.offsetAt(e.start).totalSeconds
        val offsetEnd = e.zone.offsetAt(e.end).totalSeconds
        val fields = buildJsonObject {
            put("exerciseType", kind.name)
            put(
                "metricsSummary",
                buildJsonObject {
                    put("caloriesKcal", PointJson.number(metrics.activeKcal))
                    put("distanceMillimeters", PointJson.number(metrics.distanceMillimeters.toDouble()))
                    put("steps", metrics.steps.toString())
                    metrics.averageHeartRate?.let { put("averageHeartRateBeatsPerMinute", it.toString()) }
                },
            )
            put("exerciseMetadata", buildJsonObject { if (kind == SynthExerciseKind.RUNNING) put("hasGps", true) })
            put("displayName", if (kind == SynthExerciseKind.RUNNING) "Run" else "Walk")
            put("activeDuration", "${(e.end - e.start).inWholeSeconds}s")
            put(
                "exerciseEvents",
                JsonArray(
                    listOf(e.start to "START", e.end to "STOP").map { (t, type) ->
                        buildJsonObject {
                            put("eventTime", PointJson.timestamp(t))
                            put("eventUtcOffset", PointJson.offset(e.zone.offsetAt(t).totalSeconds))
                            put("exerciseEventType", type)
                        }
                    },
                ),
            )
            put("updateTime", PointJson.timestamp(e.end + 5.minutes))
            put("createTime", PointJson.timestamp(e.end + 5.minutes))
        }
        return FakePoint(
            GhDataTypes.EXERCISE,
            e.start,
            e.end,
            offsetStart,
            offsetEnd,
            name = "users/$userId/dataTypes/exercise/dataPoints/${e.start.epochSeconds}4",
            source = FakeSource.TRACKER_WORKOUT,
            fields = fields,
        )
    }

    private const val DEFAULT_SEED = 42L
    private const val MINUTES_PER_HOUR = 60
}
