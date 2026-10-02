package dev.agentle.fakes.synth

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Sleep stage kinds of the synthetic user (Google Health `STAGES` sleep). */
public enum class SynthSleepStageKind { AWAKE, LIGHT, DEEP, REM }

/** One contiguous sleep stage; the stages of a session cover it exactly, without gaps or overlaps. */
public data class SynthSleepStage(val kind: SynthSleepStageKind, val start: Instant, val end: Instant) {
    val minutes: Long get() = (end - start).inWholeMinutes
}

/** Exercise kinds of the synthetic user: runs on Monday and Wednesday, a walk on Saturday. */
public enum class SynthExerciseKind { RUNNING, WALKING }

/** Totals of one exercise session, computed from the wearable minutes and samples inside it. */
public data class SynthExerciseMetrics(
    val steps: Long,
    val distanceMillimeters: Long,
    val activeKcal: Double,
    /** Average of the heart-rate samples inside the session, rounded; null when there are none. */
    val averageHeartRate: Long?,
)

/** The daily resting heart rate the wearable reports for a civil [date] (in the zone the user is in that day). */
public data class SynthRestingHeartRate(val date: LocalDate, val zone: TimeZone, val bpm: Int)

/** A smart-scale reading (weight and body fat) taken shortly after waking up on Mondays and Thursdays. */
public data class SynthBodyMeasurement(val time: Instant, val zone: TimeZone, val weightGrams: Long, val bodyFatPercent: Double)

/**
 * Health values derived from the synthetic user for the data types beyond docs/research/08 §6 (docs/research/08 §6.6
 * "more types"). They come from separate RNG forks or from fixed formulas over the golden events, so the golden event
 * stream and its fingerprint are unchanged. Consumers (the Google Health fake, expected values in tests) share these
 * definitions, so the expected totals are exact.
 */
public object SynthHealth {
    /** Distance per wearable step. */
    public const val STRIDE_MILLIMETERS: Long = 762

    /** Active energy per wearable step (kcal). */
    public const val ACTIVE_KCAL_PER_STEP: Double = 0.04

    /** A wearable minute with at least this many steps also climbs one floor. */
    public const val FLOOR_STEPS_THRESHOLD: Long = 120

    /** Basal energy per minute of a day the wearable recorded (kcal); part of `total-calories`. */
    public const val BASAL_KCAL_PER_MINUTE: Double = 1.1

    private const val WEIGHT_GRAMS_STEP = 50L

    public fun distanceMillimeters(steps: Long): Long = steps * STRIDE_MILLIMETERS

    public fun activeKcal(steps: Long): Double = steps * ACTIVE_KCAL_PER_STEP

    public fun floors(steps: Long): Long = if (steps >= FLOOR_STEPS_THRESHOLD) 1 else 0

    /** Saturday sessions are walks, the others runs (by the local weekday of the session start). */
    public fun exerciseKind(session: SynthEvent): SynthExerciseKind {
        require(session.type == SynthEventType.EXERCISE_SESSION) { "not an exercise session: ${session.type}" }
        val weekday = session.start.toLocalDateTime(session.zone).dayOfWeek
        return if (weekday == DayOfWeek.SATURDAY) SynthExerciseKind.WALKING else SynthExerciseKind.RUNNING
    }

    /** Totals of [session] over the wearable step minutes and heart-rate samples of [events] that start inside it. */
    public fun exerciseMetrics(session: SynthEvent, events: List<SynthEvent>): SynthExerciseMetrics {
        require(session.type == SynthEventType.EXERCISE_SESSION) { "not an exercise session: ${session.type}" }
        var steps = 0L
        var hrSum = 0.0
        var hrCount = 0
        for (e in events) {
            if (e.start < session.start || e.start >= session.end) continue
            when (e.type) {
                SynthEventType.STEPS_MINUTE -> steps += e.value.toLong()

                SynthEventType.HEART_RATE_SAMPLE -> {
                    hrSum += e.value
                    hrCount++
                }

                else -> Unit
            }
        }
        return SynthExerciseMetrics(
            steps = steps,
            distanceMillimeters = distanceMillimeters(steps),
            activeKcal = activeKcal(steps),
            averageHeartRate = if (hrCount == 0) null else (hrSum / hrCount).roundToLong(),
        )
    }

    /**
     * The contiguous stages of a sleep session: falling asleep (AWAKE), then 90-minute-like cycles of LIGHT, DEEP,
     * LIGHT and REM (more DEEP early, more REM late) with occasional short awakenings, truncated at the session end.
     * Deterministic per session start.
     */
    public fun sleepStages(spec: SynthSpec, session: SynthEvent): List<SynthSleepStage> {
        require(session.type == SynthEventType.SLEEP_SESSION) { "not a sleep session: ${session.type}" }
        val rng = SplitMix64(spec.seed).fork("sleep-stages").fork(session.start.toEpochMilliseconds().toString())
        val stages = ArrayList<SynthSleepStage>()
        var t = session.start
        fun add(kind: SynthSleepStageKind, minutes: Int) {
            val end = minOf(t + minutes.minutes, session.end)
            if (end > t) {
                stages += SynthSleepStage(kind, t, end)
                t = end
            }
        }
        add(SynthSleepStageKind.AWAKE, rng.nextInt(2, 9))
        var cycle = 0
        while (t < session.end) {
            val early = cycle < 2
            add(SynthSleepStageKind.LIGHT, rng.nextInt(20, 41))
            add(SynthSleepStageKind.DEEP, if (early) rng.nextInt(25, 46) else rng.nextInt(5, 16))
            add(SynthSleepStageKind.LIGHT, rng.nextInt(10, 21))
            add(SynthSleepStageKind.REM, if (early) rng.nextInt(8, 16) else rng.nextInt(20, 36))
            if (rng.chance(0.4)) add(SynthSleepStageKind.AWAKE, rng.nextInt(1, 6))
            cycle++
        }
        return stages
    }

    /** Resting heart rate for every date the wearable recorded (kept, not a gap day), in walk order. */
    public fun restingHeartRates(spec: SynthSpec): List<SynthRestingHeartRate> = SyntheticUser.days(spec).mapNotNull { date ->
        val plan = SyntheticUser.dayPlan(spec, date)
        if (!plan.keep || plan.wearableMissing) return@mapNotNull null
        SynthRestingHeartRate(date, plan.zone, plan.restingHeartRate.roundToInt())
    }

    /** Scale readings 15 minutes after waking on Mondays and Thursdays of kept days (the scale is not the wearable). */
    public fun bodyMeasurements(spec: SynthSpec): List<SynthBodyMeasurement> {
        val root = SplitMix64(spec.seed).fork("body")
        return SyntheticUser.days(spec).mapNotNull { date ->
            val plan = SyntheticUser.dayPlan(spec, date)
            if (!plan.keep || (date.dayOfWeek != DayOfWeek.MONDAY && date.dayOfWeek != DayOfWeek.THURSDAY)) return@mapNotNull null
            val rng = root.fork("body-$date")
            val kilograms = 78.0 - 0.02 * plan.dayIndex + rng.gaussian(0.0, 0.3)
            val bodyFat = 22.0 - 0.01 * plan.dayIndex + rng.gaussian(0.0, 0.4)
            val time = plan.sleepEnd + 15.minutes
            SynthBodyMeasurement(
                time = time,
                zone = SyntheticUser.zoneAt(spec, time),
                weightGrams = (kilograms * 1000 / WEIGHT_GRAMS_STEP).roundToLong() * WEIGHT_GRAMS_STEP,
                bodyFatPercent = (bodyFat * 10).roundToLong() / 10.0,
            )
        }
    }
}
