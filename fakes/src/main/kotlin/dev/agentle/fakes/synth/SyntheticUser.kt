package dev.agentle.fakes.synth

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.security.MessageDigest
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Raw observation kinds of the synthetic user (docs/research/08 §6). The names are part of the golden fingerprint. */
public enum class SynthEventType { STEPS_MINUTE, HEART_RATE_SAMPLE, SLEEP_SESSION, EXERCISE_SESSION, WEAR_OFF, PHONE_STEPS_MINUTE }

/**
 * One raw observation as a device would record it: UTC instants plus the zone the user was in. [value] is steps for
 * the step minutes, beats per minute for heart rate, minutes for sleep, 1 for exercise and 0 for wear-off.
 */
public data class SynthEvent(
    val type: SynthEventType,
    val start: Instant,
    val end: Instant,
    val value: Double,
    val zone: TimeZone,
    val source: String,
) {
    /** Offset in force at [start], exactly what a device stamps on the record. */
    val utcOffsetSeconds: Int get() = zone.offsetAt(start).totalSeconds
}

/** Data volume profiles (docs/research/08 §6.3). */
public enum class SynthProfile {
    /** The default 90-day user. */
    TYPICAL,

    /** No events at all: a new user, or one who connects nothing. */
    EMPTY,

    /** Heart rate hourly; about 10% of days produce any data. */
    SPARSE,

    /** Heart rate every minute. */
    HIGH_VOLUME,
}

/** A trip: from [departure] on, the user lives in [zone] until [returnAt]. Instants avoid civil-time ambiguity. */
public data class Trip(val departure: Instant, val returnAt: Instant, val zone: TimeZone)

/**
 * What to generate. `SynthSpec(seed = 42)` is the default user of docs/research/08 §6.2: 2026-08-10 .. 2026-11-07 in
 * America/New_York, a trip to Berlin (days 71-80) that covers the EU DST end, the US DST end at home on 2026-11-01,
 * and wearable gap days 20-21.
 */
public data class SynthSpec(
    val seed: Long,
    val profile: SynthProfile = SynthProfile.TYPICAL,
    val homeZone: TimeZone = NEW_YORK,
    val firstDay: LocalDate = LocalDate(2026, 8, 10),
    val days: Int = 90,
    val trip: Trip? = DEFAULT_TRIP,
    /** Day indexes (from [firstDay]) with no wearable data at all: watch lost, broken or not synced. */
    val wearableGapDays: IntRange? = 20..21,
    val hrEveryMinutes: Int = 5,
    /** Fraction of days that produce any data (SPARSE users skip most days). */
    val dayKeep: Double = 1.0,
) {
    init {
        require(days >= 0) { "days must be >= 0" }
        require(hrEveryMinutes > 0) { "hrEveryMinutes must be > 0" }
        require(dayKeep in 0.0..1.0) { "dayKeep must be in 0..1" }
    }

    /** First instant of the window: local midnight of [firstDay] at home. */
    val windowStart: Instant get() = firstDay.atStartOfDayIn(homeZone)

    /** End of the window (exclusive): local midnight at home after the last day. */
    val windowEnd: Instant get() = firstDay.plus(DatePeriod(days = days)).atStartOfDayIn(homeZone)

    public companion object {
        public val NEW_YORK: TimeZone = TimeZone.of("America/New_York")
        public val BERLIN: TimeZone = TimeZone.of("Europe/Berlin")

        /** Fly out Tue 2026-10-20 18:00 New York, fly back Thu 2026-10-29 10:00 Berlin (days 71..80). */
        public val DEFAULT_TRIP: Trip = Trip(
            departure = LocalDateTime(2026, 10, 20, 18, 0).toInstant(NEW_YORK),
            returnAt = LocalDateTime(2026, 10, 29, 10, 0).toInstant(BERLIN),
            zone = BERLIN,
        )

        private const val TRIP_DEPARTURE_DAY = 71
        private const val TRIP_RETURN_DAY = 80
        private const val DEPARTURE_HOUR = 18
        private const val RETURN_HOUR = 10

        /**
         * A spec anchored to [now] (testing-build-19): its last day is the local date of [now] in [homeZone], so a fake
         * built from it holds the [days] days up to the device's real "now" and the app clock never has to move to match
         * the data. The generator still produces the whole last day; fakes serve only what was recorded before their
         * clock's now. The trip keeps its place (days 71 to 80, to Berlin) when the window is long enough.
         */
        public fun endingAt(
            now: Instant,
            seed: Long,
            days: Int = 90,
            homeZone: TimeZone = NEW_YORK,
            profile: SynthProfile = SynthProfile.TYPICAL,
        ): SynthSpec {
            require(days >= 1) { "days must be >= 1" }
            val first = now.toLocalDateTime(homeZone).date.minus(DatePeriod(days = days - 1))
            val trip = if (days > TRIP_RETURN_DAY) {
                Trip(
                    departure = LocalDateTime(first.plus(DatePeriod(days = TRIP_DEPARTURE_DAY)), LocalTime(DEPARTURE_HOUR, 0))
                        .toInstant(homeZone),
                    returnAt = LocalDateTime(first.plus(DatePeriod(days = TRIP_RETURN_DAY)), LocalTime(RETURN_HOUR, 0)).toInstant(BERLIN),
                    zone = BERLIN,
                )
            } else {
                null
            }
            return SynthSpec(seed = seed, profile = profile, homeZone = homeZone, firstDay = first, days = days, trip = trip)
        }
    }
}

/**
 * The plan of one local date: everything decided once per date from an RNG forked by the date label, so plans are
 * independent of each other and of the order in which they are computed.
 */
public data class SynthDayPlan(
    val date: LocalDate,
    /** Zone the routine is planned in (home, or the trip zone when the user is there at local noon). */
    val zone: TimeZone,
    val dayIndex: Int,
    /** False on days a SPARSE user produces no data at all. */
    val keep: Boolean,
    val weekend: Boolean,
    val exerciseDay: Boolean,
    val sedentaryDay: Boolean,
    val wearableMissing: Boolean,
    /** Sleep that ends on this date. */
    val sleepStart: Instant,
    val sleepEnd: Instant,
    /** The nightly off-wrist period (charging). */
    val offStart: Instant,
    val offEnd: Instant,
    val exerciseStart: Instant,
    val exerciseEnd: Instant,
    /** The day's resting heart rate (bpm, unrounded). */
    val restingHeartRate: Double,
    internal val minuteRng: SplitMix64,
)

/**
 * Deterministic synthetic user (docs/research/08 §6). Time is walked in absolute minutes and the zone is a function
 * of the instant (home, or the trip zone), so a trip never duplicates or drops instants while DST days naturally have
 * 23 or 25 local hours. Every per-day decision comes from an RNG forked by the local date label, so adding a stream
 * or changing one day never shifts the values of other days. Same spec, same output, on every JVM and device.
 */
public object SyntheticUser {
    public const val WEARABLE: String = "wearable"
    public const val PHONE: String = "phone"

    private val EXERCISE_DAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY)

    public fun generate(spec: SynthSpec): List<SynthEvent> = sequence(spec).toList()

    public fun sequence(spec: SynthSpec): Sequence<SynthEvent> = when (spec.profile) {
        SynthProfile.EMPTY -> emptySequence()
        SynthProfile.TYPICAL -> walk(spec)
        SynthProfile.SPARSE -> walk(effective(spec))
        SynthProfile.HIGH_VOLUME -> walk(effective(spec))
    }

    /** [spec] with the profile's volume knobs applied (what the walk actually uses). */
    public fun effective(spec: SynthSpec): SynthSpec = when (spec.profile) {
        SynthProfile.EMPTY, SynthProfile.TYPICAL -> spec
        SynthProfile.SPARSE -> spec.copy(hrEveryMinutes = 60, dayKeep = 0.1)
        SynthProfile.HIGH_VOLUME -> spec.copy(hrEveryMinutes = 1)
    }

    /** The zone the user is in at [t]. */
    public fun zoneAt(spec: SynthSpec, t: Instant): TimeZone {
        val trip = spec.trip ?: return spec.homeZone
        return if (t >= trip.departure && t < trip.returnAt) trip.zone else spec.homeZone
    }

    /**
     * Zone in which a local date's routine (bedtime, exercise, wear-off) is planned: the trip zone if the user is there
     * at local noon of that date, else home. Depends only on the date, so plans are order-independent.
     */
    public fun planZone(spec: SynthSpec, date: LocalDate): TimeZone {
        val trip = spec.trip ?: return spec.homeZone
        val noonThere = LocalDateTime(date, LocalTime(12, 0)).toInstant(trip.zone)
        return if (noonThere >= trip.departure && noonThere < trip.returnAt) trip.zone else spec.homeZone
    }

    /** The plan of [date] (a pure function of the spec and the date). Volume knobs of the profile apply. */
    public fun dayPlan(spec: SynthSpec, date: LocalDate): SynthDayPlan {
        val effective = effective(spec)
        return plan(effective, SplitMix64(effective.seed).fork("days"), date, planZone(effective, date))
    }

    /** Local dates the walk enters, in order (a date skipped by an eastbound flight is absent). */
    public fun days(spec: SynthSpec): List<LocalDate> {
        if (spec.profile == SynthProfile.EMPTY) return emptyList()
        val dates = LinkedHashSet<LocalDate>()
        var t = spec.windowStart
        val end = spec.windowEnd
        while (t < end) {
            dates += t.toLocalDateTime(zoneAt(spec, t)).date
            t += 1.minutes
        }
        return dates.toList()
    }

    @Suppress("LongMethod")
    private fun plan(spec: SynthSpec, root: SplitMix64, date: LocalDate, zone: TimeZone): SynthDayPlan {
        // The order of the draws below is part of the golden fingerprint: never reorder, only append new forks.
        val rng = root.fork("day-$date")
        val dayIndex = spec.firstDay.daysUntil(date)
        val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
        val exerciseDay = date.dayOfWeek in EXERCISE_DAYS
        val sedentary = !exerciseDay && rng.chance(0.35)
        // Sleep that ENDS on this date: weekday about 23:30 -> 06:45, weekend about 00:30 -> 08:30 (civil time in zone).
        val bed = if (weekend) {
            LocalDateTime(date, LocalTime(0, 30)).toInstant(zone)
        } else {
            LocalDateTime(date.minus(DatePeriod(days = 1)), LocalTime(23, 30)).toInstant(zone)
        }
        val wake = LocalDateTime(date, if (weekend) LocalTime(8, 30) else LocalTime(6, 45)).toInstant(zone)
        val sleepStart = bed + rng.nextInt(-25, 26).minutes
        val sleepEnd = wake + rng.nextInt(-20, 21).minutes
        val off = LocalDateTime(date, LocalTime(19, 0)).toInstant(zone) + rng.nextInt(0, 120).minutes
        val exercise = LocalDateTime(date, LocalTime(if (weekend) 10 else 18, 0)).toInstant(zone)
        val keep = spec.dayKeep >= 1.0 || rng.chance(spec.dayKeep)
        val offEnd = off + rng.nextInt(60, 91).minutes
        val exerciseEnd = exercise + rng.nextInt(30, 61).minutes
        val restingHeartRate = rng.gaussian(60.0, 3.0)
        return SynthDayPlan(
            date = date,
            zone = zone,
            dayIndex = dayIndex,
            keep = keep,
            weekend = weekend,
            exerciseDay = exerciseDay,
            sedentaryDay = sedentary,
            wearableMissing = spec.wearableGapDays?.contains(dayIndex) == true,
            sleepStart = sleepStart,
            sleepEnd = sleepEnd,
            offStart = off,
            offEnd = offEnd,
            exerciseStart = exercise,
            exerciseEnd = exerciseEnd,
            restingHeartRate = restingHeartRate,
            minuteRng = rng.fork("minutes"),
        )
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun walk(spec: SynthSpec): Sequence<SynthEvent> = sequence {
        val root = SplitMix64(spec.seed).fork("days")
        val end = spec.windowEnd
        val plans = HashMap<LocalDate, SynthDayPlan>()
        fun planFor(date: LocalDate) = plans.getOrPut(date) { plan(spec, root, date, planZone(spec, date)) }

        var t = spec.windowStart
        var minuteIndex = 0L
        val emitted = HashSet<LocalDate>() // a westbound flight can re-enter yesterday's date: never emit twice
        while (t < end) {
            val zone = zoneAt(spec, t)
            val local = t.toLocalDateTime(zone)
            val today = planFor(local.date)
            if (emitted.add(local.date) && today.keep && !today.wearableMissing) {
                // Session events are emitted once, when the walk enters the local date. Each is stamped with the zone
                // the user is in when the session STARTS (what a real device records).
                yield(
                    SynthEvent(
                        SynthEventType.SLEEP_SESSION,
                        today.sleepStart,
                        today.sleepEnd,
                        (today.sleepEnd - today.sleepStart).inWholeMinutes.toDouble(),
                        zoneAt(spec, today.sleepStart),
                        WEARABLE,
                    ),
                )
                yield(SynthEvent(SynthEventType.WEAR_OFF, today.offStart, today.offEnd, 0.0, zoneAt(spec, today.offStart), WEARABLE))
                if (today.exerciseDay) {
                    yield(
                        SynthEvent(
                            SynthEventType.EXERCISE_SESSION,
                            today.exerciseStart,
                            today.exerciseEnd,
                            1.0,
                            zoneAt(spec, today.exerciseStart),
                            WEARABLE,
                        ),
                    )
                }
            }
            if (today.keep) {
                // Tonight's sleep (ending tomorrow) may already have started before local midnight.
                val tomorrow = planFor(local.date.plus(DatePeriod(days = 1)))
                val asleep = inside(t, today.sleepStart, today.sleepEnd) || inside(t, tomorrow.sleepStart, tomorrow.sleepEnd)
                val notWorn = today.wearableMissing || inside(t, today.offStart, today.offEnd)
                val exercising = today.exerciseDay && inside(t, today.exerciseStart, today.exerciseEnd)
                val rng = today.minuteRng
                val base = when {
                    asleep -> 0.0
                    exercising -> rng.gaussian(150.0, 12.0)
                    today.sedentaryDay -> if (rng.chance(0.06)) rng.gaussian(25.0, 8.0) else 0.0
                    local.hour in 7..21 -> if (rng.chance(0.25)) rng.gaussian(40.0, 15.0) else 0.0
                    else -> 0.0
                }
                val steps = base.coerceAtLeast(0.0).toInt()
                if (steps > 0) {
                    // The phone also counts (about 90% of) steps, including while the watch is off-wrist or missing.
                    yield(SynthEvent(SynthEventType.PHONE_STEPS_MINUTE, t, t + 1.minutes, (steps * 0.9).toInt().toDouble(), zone, PHONE))
                    if (!notWorn) yield(SynthEvent(SynthEventType.STEPS_MINUTE, t, t + 1.minutes, steps.toDouble(), zone, WEARABLE))
                }
                if (!notWorn && minuteIndex % spec.hrEveryMinutes == 0L) {
                    val hr = when {
                        exercising -> rng.gaussian(140.0, 10.0)
                        asleep -> rng.gaussian(today.restingHeartRate - 6, 2.0)
                        else -> rng.gaussian(today.restingHeartRate + 15, 6.0)
                    }
                    yield(SynthEvent(SynthEventType.HEART_RATE_SAMPLE, t, t, hr.roundToLong().toDouble(), zone, WEARABLE))
                }
            }
            t += 1.minutes
            minuteIndex++
        }
    }

    private fun inside(t: Instant, from: Instant, until: Instant) = t >= from && t < until

    /** Stable fingerprint used as a golden value: same spec, same hash, on every JVM and device. */
    public fun fingerprint(events: Sequence<SynthEvent>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (e in events) {
            val line = "${e.type}|${e.start.toEpochMilliseconds()}|${e.end.toEpochMilliseconds()}|${e.value}|${e.zone.id}|${e.source}\n"
            md.update(line.toByteArray(Charsets.UTF_8))
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    public fun fingerprint(events: List<SynthEvent>): String = fingerprint(events.asSequence())
}
