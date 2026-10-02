package dev.agentle.analytics.features.daily

import dev.agentle.core.model.ActivityKind
import dev.agentle.core.model.ActivityTransitionPayload
import dev.agentle.core.model.AppUsagePayload
import dev.agentle.core.model.DailyTotalMetric
import dev.agentle.core.model.DailyTotalPayload
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventId
import dev.agentle.core.model.EventMetadata
import dev.agentle.core.model.EventPayload
import dev.agentle.core.model.EventType
import dev.agentle.core.model.ExercisePayload
import dev.agentle.core.model.HeartRatePayload
import dev.agentle.core.model.JitaiEventPayload
import dev.agentle.core.model.LocationVisitPayload
import dev.agentle.core.model.NoPayload
import dev.agentle.core.model.NotificationPayload
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.PlaceClass
import dev.agentle.core.model.RestingHeartRatePayload
import dev.agentle.core.model.ScreenPayload
import dev.agentle.core.model.SleepSessionPayload
import dev.agentle.core.model.SleepStage
import dev.agentle.core.model.StepsPayload
import dev.agentle.core.model.TransitionKind
import dev.agentle.core.time.ClosedOpenRange
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Source ids used by the daily tests (the connectors' naming: `<connector>.<stream>`). */
internal object Src {
    val USAGE = DataSourceId("android.usage")
    val UNLOCK = DataSourceId("android.unlock")
    val NOTIFICATIONS = DataSourceId("android.notifications")
    val BATTERY = DataSourceId("android.battery")
    val ACTIVITY = DataSourceId("android.activity")
    val LOCATION = DataSourceId("android.location")
    val PHONE_STEPS = DataSourceId("android.steps")
    val GH_STEPS = DataSourceId("googlehealth.steps")
    val GH_DAILY = DataSourceId("googlehealth.daily_totals")
    val GH_HR = DataSourceId("googlehealth.heart_rate")
    val GH_RHR = DataSourceId("googlehealth.resting_heart_rate")
    val GH_SLEEP = DataSourceId("googlehealth.sleep")
    val GH_EXERCISE = DataSourceId("googlehealth.exercise")
    val HC_STEPS = DataSourceId("healthconnect.steps")
    val HC_HR = DataSourceId("healthconnect.heart_rate")
    val HC_RHR = DataSourceId("healthconnect.resting_heart_rate")
    val HC_SLEEP = DataSourceId("healthconnect.sleep")
    val HC_EXERCISE = DataSourceId("healthconnect.exercise")
    val JITAI = DataSourceId("agentle.jitai")
    val UNLISTED = DataSourceId("otherapp.steps")
}

/** Collector (capability) ids of the on-device streams. */
internal object Col {
    const val USAGE = "app_usage_events"
    const val UNLOCK = "unlock_keyguard_events"
    const val NOTIFICATIONS = "notification_events_metadata"
    const val BATTERY = "battery_state"
    const val ACTIVITY = "activity_recognition_transitions"
    const val LOCATION = "location_background"
    val ALL = listOf(USAGE, UNLOCK, NOTIFICATIONS, BATTERY, ACTIVITY, LOCATION)
}

internal val UTC: TimeZone = TimeZone.UTC
internal val NEW_YORK: TimeZone = TimeZone.of("America/New_York")
internal val BERLIN: TimeZone = TimeZone.of("Europe/Berlin")
internal val TOKYO: TimeZone = TimeZone.of("Asia/Tokyo")

internal fun date(text: String): LocalDate = LocalDate.parse(text)

internal fun LocalDate.plusDays(n: Int): LocalDate = if (n >= 0) plus(DatePeriod(days = n)) else minus(DatePeriod(days = -n))

/** The instant of local [hour]:[minute]:[second] on [day] in [zone]. */
internal fun at(day: LocalDate, hour: Int, minute: Int = 0, zone: TimeZone = UTC, second: Int = 0): Instant =
    if (hour == 24) day.plusDays(1).atStartOfDayIn(zone) else LocalDateTime(day, LocalTime(hour, minute, second)).toInstant(zone)

internal fun range(start: Instant, end: Instant): ClosedOpenRange = ClosedOpenRange(start, end)

/** A range from 3 days before [first] to 4 days after [last] (UTC days): full coverage around a test. */
internal fun around(first: LocalDate, last: LocalDate = first): ClosedOpenRange =
    ClosedOpenRange(first.atStartOfDayIn(UTC) - 3.days, last.atStartOfDayIn(UTC) + 4.days)

internal fun List<DailySummaryRow>.row(metric: String): DailySummaryRow =
    singleOrNull { it.metric == metric } ?: error("no row $metric in ${map { it.metric }}")

internal fun List<DailySummaryRow>.rowOrNull(metric: String): DailySummaryRow? = singleOrNull { it.metric == metric }

/** Builders of normalized events as the collectors and connectors write them. */
internal object Ev {
    private val META = EventMetadata(ingestedAt = Instant.parse("2026-01-01T00:00:00Z"))

    fun of(
        type: EventType,
        source: DataSourceId,
        start: Instant,
        end: Instant?,
        payload: EventPayload,
        zone: TimeZone = UTC,
        key: String? = null,
    ): PersonalEvent {
        val dedup = key ?: listOf(source.value, type.name, start.toEpochMilliseconds(), end?.toEpochMilliseconds(), payload.hashCode())
            .joinToString("|")
        return PersonalEvent(EventId(dedup), type, source, start, end, zone.id, payload, dedupKey = dedup, metadata = META)
    }

    fun screen(start: Instant, end: Instant, zone: TimeZone = UTC): PersonalEvent =
        of(EventType.SCREEN_SESSION, Src.USAGE, start, end, ScreenPayload(true, (end - start).inWholeMilliseconds), zone)

    fun app(pkg: String, start: Instant, end: Instant, category: String? = null, zone: TimeZone = UTC): PersonalEvent =
        of(EventType.APP_SESSION, Src.USAGE, start, end, AppUsagePayload(pkg, (end - start).inWholeMilliseconds, category), zone)

    fun unlock(at: Instant, zone: TimeZone = UTC): PersonalEvent = of(EventType.DEVICE_UNLOCK, Src.UNLOCK, at, null, NoPayload, zone)

    fun notification(
        pkg: String,
        at: Instant,
        keyHash: String?,
        ongoing: Boolean = false,
        groupSummary: Boolean = false,
        foregroundService: Boolean = false,
        key: String? = null,
    ): PersonalEvent = of(
        EventType.NOTIFICATION_POSTED,
        Src.NOTIFICATIONS,
        at,
        null,
        NotificationPayload(pkg, ongoing = ongoing, groupSummary = groupSummary, keyHash = keyHash, foregroundService = foregroundService),
        key = key,
    )

    fun charging(started: Boolean, at: Instant, zone: TimeZone = UTC): PersonalEvent =
        of(if (started) EventType.CHARGING_STARTED else EventType.CHARGING_STOPPED, Src.BATTERY, at, null, NoPayload, zone)

    fun activity(kind: ActivityKind, transition: TransitionKind, at: Instant): PersonalEvent =
        of(EventType.ACTIVITY, Src.ACTIVITY, at, null, ActivityTransitionPayload(kind, transition))

    fun visit(place: PlaceClass, start: Instant, end: Instant): PersonalEvent = of(
        EventType.LOCATION_VISIT,
        Src.LOCATION,
        start,
        null,
        LocationVisitPayload("p-${place.name}", place, (end - start).inWholeMilliseconds),
    )

    fun steps(source: DataSourceId, start: Instant, end: Instant, count: Long, zone: TimeZone = UTC): PersonalEvent =
        of(EventType.STEP_SAMPLE, source, start, end, StepsPayload(count), zone)

    /** One step record per minute from [start] for [minutes] minutes, [perMinute] steps each. */
    fun stepMinutes(source: DataSourceId, start: Instant, minutes: Int, perMinute: Long, zone: TimeZone = UTC): List<PersonalEvent> =
        (0 until minutes).map { i ->
            val s = start + i.minutes
            steps(source, s, s + 1.minutes, perMinute, zone)
        }

    fun heartRate(source: DataSourceId, at: Instant, bpm: Double, end: Instant? = null, maxBpm: Double? = null): PersonalEvent =
        of(EventType.HEART_RATE, source, at, end, HeartRatePayload(bpm, maxBpm = maxBpm))

    /** A daily resting heart rate: dated by its payload; the event spans that date in [zone]. */
    fun restingHr(source: DataSourceId, day: LocalDate, bpm: Double, zone: TimeZone = UTC): PersonalEvent = of(
        EventType.RESTING_HEART_RATE,
        source,
        day.atStartOfDayIn(zone),
        day.plusDays(1).atStartOfDayIn(zone),
        RestingHeartRatePayload(bpm, day),
        zone,
    )

    fun dailySteps(source: DataSourceId, day: LocalDate, value: Double, zone: TimeZone = UTC): PersonalEvent = of(
        EventType.DAILY_TOTAL,
        source,
        day.atStartOfDayIn(zone),
        day.plusDays(1).atStartOfDayIn(zone),
        DailyTotalPayload(day, DailyTotalMetric.STEPS, value),
        zone,
    )

    fun sleep(
        source: DataSourceId,
        start: Instant,
        end: Instant,
        zone: TimeZone = UTC,
        stages: List<SleepStage> = emptyList(),
        minutesAsleep: Long? = null,
        isNap: Boolean = false,
        isMainSleep: Boolean = true,
        processed: Boolean? = true,
        startOffsetSeconds: Int? = null,
        endOffsetSeconds: Int? = null,
        outOfBed: List<SleepStage> = emptyList(),
    ): PersonalEvent = of(
        EventType.SLEEP_SESSION,
        source,
        start,
        end,
        SleepSessionPayload(
            stages = stages,
            minutesAsleep = minutesAsleep,
            isMainSleep = isMainSleep,
            isNap = isNap,
            processed = processed,
            startUtcOffsetSeconds = startOffsetSeconds,
            endUtcOffsetSeconds = endOffsetSeconds,
            outOfBedSegments = outOfBed,
        ),
        zone,
    )

    fun exercise(source: DataSourceId, start: Instant, end: Instant): PersonalEvent =
        of(EventType.EXERCISE_SESSION, source, start, end, ExercisePayload("RUNNING", (end - start).inWholeMilliseconds))

    fun jitai(type: EventType, at: Instant, id: String = "j1"): PersonalEvent =
        of(type, Src.JITAI, at, null, JitaiEventPayload(id, "$id-${at.toEpochMilliseconds()}"))
}
