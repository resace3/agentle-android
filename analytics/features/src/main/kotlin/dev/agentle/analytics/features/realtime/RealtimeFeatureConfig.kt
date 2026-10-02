package dev.agentle.analytics.features.realtime

import dev.agentle.core.time.EngineDay
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Device facts and user settings the realtime features depend on.
 *
 * @property apiLevel the device's `Build.VERSION.SDK_INT`; features whose `minApi` is higher are `Missing(API_LEVEL)`.
 * @property weekendDays the user's weekend (default from CLDR week data, fallback SAT+SUN; R10 §3.5).
 * @property engineDayRollover start of the engine day (default 04:00, user range 00:00-06:00; R10 §10.2).
 * @property ownPackages Agentle's own packages; their notifications are not counted (R10 §5.4 D).
 * @property activityLookback how far back `activity_state` looks for the latest ENTER (design).
 * @property dailyValueSyncLag when a daily value (sleep, resting heart rate) is absent, the reason is `NO_DATA` if its
 *   source asserted coverage within this lag and `NOT_SYNCED` otherwise (design, so the `daily_at` retry of R10 §8.2
 *   asks for a sync instead of giving up).
 */
public data class RealtimeFeatureConfig(
    val apiLevel: Int = 37,
    val weekendDays: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
    val engineDayRollover: LocalTime = EngineDay.ROLLOVER,
    val ownPackages: Set<String> = setOf("dev.agentle.app", "dev.agentle.app.fake"),
    val activityLookback: Duration = 24.hours,
    val dailyValueSyncLag: Duration = 30.minutes,
) {
    init {
        require(engineDayRollover <= LocalTime(6, 0)) { "engine-day rollover must be within 00:00-06:00" }
        require(activityLookback.isPositive()) { "activity lookback must be positive" }
        require(!dailyValueSyncLag.isNegative()) { "daily value sync lag must not be negative" }
    }
}
