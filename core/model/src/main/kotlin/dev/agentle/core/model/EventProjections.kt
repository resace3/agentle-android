package dev.agentle.core.model

import kotlinx.datetime.LocalDate

/**
 * Typed projections of a payload, stored next to its JSON in the `event` table (`subject`, `value_num`) so features can
 * aggregate in SQL without parsing JSON (docs/ARCHITECTURE.md §5.2).
 *
 * This mapping is part of the storage contract: changing what a payload projects to needs a database migration that
 * recomputes the columns, and a [VERSION] bump. Location coordinates and personal text are never projected.
 */
public object EventProjections {
    public const val VERSION: Int = 1

    /** The grouping key (package, kind, device hash, place class, ...), or null when the payload has none. */
    public fun subjectOf(payload: EventPayload): String? = when (payload) {
        is AppUsagePayload -> payload.packageName

        is NotificationPayload -> payload.packageName

        is LocationSamplePayload -> payload.placeClass.name

        is LocationVisitPayload -> payload.placeClass.name

        is ActivityTransitionPayload -> payload.activity.name

        is ExercisePayload -> payload.exerciseType

        is WearableDevicePayload -> payload.deviceIdHash

        is BatteryPayload -> payload.plugType.name

        is ConnectivityPayload -> payload.network.name

        is BluetoothPayload -> payload.deviceHash

        is AudioStatePayload -> payload.outputRoute

        is CalendarEventPayload -> payload.eventIdHash

        is CallEventPayload -> payload.state.name

        is MediaCreatedPayload -> payload.mediaType

        is UserLogPayload -> payload.logKind.name

        is JitaiEventPayload -> payload.jitaiId

        is InsightEventPayload -> payload.kind

        is GeneratedMediaPayload -> payload.method

        is DailyTotalPayload -> payload.metric.name

        NoPayload, is ScreenPayload, is StepsPayload, is DistancePayload, is FloorsPayload, is CaloriesPayload,
        is HeartRatePayload, is RestingHeartRatePayload, is SleepSessionPayload, is WeightPayload, is BodyFatPayload,
        is PowerStatePayload, is DndPayload, is SystemEventPayload, is UnknownPayload,
        -> null
    }

    /**
     * The main numeric value, or null. Units: durations in ms, steps/floors as counts, meters, kcal, bpm, kg, percent,
     * minutes asleep for sleep sessions, 1.0/0.0 for activity ENTER/EXIT transitions, bytes for media sizes.
     */
    public fun valueOf(payload: EventPayload): Double? = when (payload) {
        is AppUsagePayload -> payload.durationMs?.toDouble()

        is ScreenPayload -> payload.durationMs?.toDouble()

        is LocationVisitPayload -> payload.durationMs.toDouble()

        is StepsPayload -> payload.count.toDouble()

        is DistancePayload -> payload.meters

        is FloorsPayload -> payload.floors

        is CaloriesPayload -> payload.kilocalories

        is ActivityTransitionPayload -> if (payload.transition == TransitionKind.ENTER) 1.0 else 0.0

        is ExercisePayload -> payload.durationMs.toDouble()

        is HeartRatePayload -> payload.bpm

        is RestingHeartRatePayload -> payload.bpm

        is DailyTotalPayload -> payload.value

        is SleepSessionPayload -> (payload.minutesAsleep ?: minutesAsleep(payload.stages))?.toDouble()

        is WeightPayload -> payload.kilograms

        is BodyFatPayload -> payload.percent

        is WearableDevicePayload -> payload.batteryPercent?.toDouble()

        is BatteryPayload -> payload.levelPercent.toDouble()

        is PowerStatePayload -> payload.thermalStatus?.toDouble()

        is ConnectivityPayload -> payload.downstreamKbps?.toDouble()

        is AudioStatePayload -> payload.musicVolumePercent?.toDouble()

        is DndPayload -> payload.interruptionFilter.toDouble()

        is CalendarEventPayload -> payload.attendeeCount?.toDouble()

        is MediaCreatedPayload -> payload.sizeBytes?.toDouble()

        is UserLogPayload -> payload.value

        is GeneratedMediaPayload -> payload.sizeBytes.toDouble()

        NoPayload, is NotificationPayload, is LocationSamplePayload, is BluetoothPayload, is SystemEventPayload,
        is CallEventPayload, is JitaiEventPayload, is InsightEventPayload, is UnknownPayload,
        -> null
    }

    /**
     * The authoritative civil date of a day-keyed record (a source-computed daily total or resting heart rate), or
     * null for records keyed by their instants. Never derived from the instants.
     */
    public fun localDateOf(payload: EventPayload): LocalDate? = when (payload) {
        is DailyTotalPayload -> payload.date
        is RestingHeartRatePayload -> payload.date
        else -> null
    }

    private val NOT_ASLEEP = setOf(SleepStageKind.AWAKE, SleepStageKind.OUT_OF_BED, SleepStageKind.UNKNOWN)

    private fun minutesAsleep(stages: List<SleepStage>): Long? {
        if (stages.isEmpty()) return null
        val asleepMs = stages.filter { it.stage !in NOT_ASLEEP }.sumOf { (it.endEpochMs - it.startEpochMs).coerceAtLeast(0) }
        return asleepMs / MS_PER_MINUTE
    }

    private const val MS_PER_MINUTE = 60_000L
}
