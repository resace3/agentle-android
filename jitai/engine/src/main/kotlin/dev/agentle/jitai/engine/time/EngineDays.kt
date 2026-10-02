package dev.agentle.jitai.engine.time

import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Engine days with a configurable rollover (R10 §10.2; DataStore `EngineSettings`, default 04:00, range 00:00-06:00):
 * `engineDay(t) = localDate(t) - 1` when the local time is before the rollover, else `localDate(t)`. Daily and weekly caps
 * count by engine day, so a night from 22:00 to 02:00 is one engine day.
 */
public object EngineDays {
    public const val DEFAULT_ROLLOVER_MINUTE: Int = 240
    public const val MAX_ROLLOVER_MINUTE: Int = 360

    /** The engine day containing [instant] in [zone]. */
    public fun of(instant: Instant, zone: TimeZone, rolloverMinute: Int = DEFAULT_ROLLOVER_MINUTE): LocalDate {
        val local = instant.toLocalDateTime(zone)
        val minute = local.hour * MINUTES_PER_HOUR + local.minute
        return if (minute < clampRollover(rolloverMinute)) local.date.minus(1, DateTimeUnit.DAY) else local.date
    }

    /** The next engine-day rollover after [instant]: the start of the following engine day (snooze "until tomorrow"). */
    public fun nextRollover(instant: Instant, zone: TimeZone, rolloverMinute: Int = DEFAULT_ROLLOVER_MINUTE): Instant {
        val day = of(instant, zone, rolloverMinute)
        return LocalWindow.atMinute(day.plus(1, DateTimeUnit.DAY), clampRollover(rolloverMinute), zone)
    }

    /** The engine days `today - (days - 1) .. today` (the current and previous six for a 7-day window). */
    public fun window(today: LocalDate, days: Int): ClosedRange<LocalDate> = today.minus(days - 1, DateTimeUnit.DAY)..today

    /** Clamps a rollover setting to the allowed range 00:00-06:00. */
    public fun clampRollover(rolloverMinute: Int): Int = rolloverMinute.coerceIn(0, MAX_ROLLOVER_MINUTE)

    private const val MINUTES_PER_HOUR = 60
}

/**
 * Reference computation of the time and calendar features of R10 §5.4 A (`local_time`, `day_of_week`, `day_type`,
 * `engine_day_of_week`): read from the evaluation instant and the current zone, never UNKNOWN. The realtime feature
 * resolver owns these values in a pass; this is the engine-side definition used by the test fakes and by tests of
 * R10 §12.F.
 */
public object CalendarFeatures {
    public const val LOCAL_TIME: String = "local_time"
    public const val DAY_OF_WEEK: String = "day_of_week"
    public const val DAY_TYPE: String = "day_type"
    public const val ENGINE_DAY_OF_WEEK: String = "engine_day_of_week"

    /** Default weekend when no CLDR week data is available (R10 §3.5). */
    public val DEFAULT_WEEKEND: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    public val IDS: Set<String> = setOf(LOCAL_TIME, DAY_OF_WEEK, DAY_TYPE, ENGINE_DAY_OF_WEEK)

    /** The value of calendar feature [featureId] at [at], or null when [featureId] is not a calendar feature. */
    public fun value(
        featureId: String,
        at: Instant,
        zone: TimeZone,
        rolloverMinute: Int = EngineDays.DEFAULT_ROLLOVER_MINUTE,
        weekend: Set<DayOfWeek> = DEFAULT_WEEKEND,
    ): FeatureValue.Known? {
        val local = at.toLocalDateTime(zone)
        val scalar: FeatureScalar = when (featureId) {
            LOCAL_TIME -> FeatureScalar.LocalTimeValue(local.hour * MINUTES_PER_HOUR + local.minute)
            DAY_OF_WEEK -> FeatureScalar.DayOfWeekValue(local.date.dayOfWeek)
            DAY_TYPE -> FeatureScalar.EnumValue(if (local.date.dayOfWeek in weekend) "WEEKEND" else "WEEKDAY")
            ENGINE_DAY_OF_WEEK -> FeatureScalar.DayOfWeekValue(EngineDays.of(at, zone, rolloverMinute).dayOfWeek)
            else -> return null
        }
        return FeatureValue.Known(scalar, at)
    }

    private const val MINUTES_PER_HOUR = 60
}
