package dev.agentle.analytics.features.realtime

import dev.agentle.core.common.Outcome
import dev.agentle.core.time.ClosedOpenRange
import kotlinx.datetime.LocalDate
import kotlin.time.Duration
import kotlin.time.Instant

/** Collector ids of `collector_coverage` rows; they are the capability ids of docs/research/capabilities.json. */
public object CollectorIds {
    public const val USAGE_EVENTS: String = "app_usage_events"
    public const val NOTIFICATION_LISTENER: String = "notification_events_metadata"
    public const val ACTIVITY_TRANSITIONS: String = "activity_recognition_transitions"
}

/**
 * One interval `[from, to)` in which an on-device collector was known to be healthy: permission granted, listener
 * connected, transitions subscribed (R10 §5.1). [to] is null while the interval is still open (healthy now).
 */
public data class CoverageInterval(val from: Instant, val to: Instant? = null) {
    init {
        require(to == null || to >= from) { "coverage interval ends before it starts" }
    }
}

/** `collector_coverage(collector, fromMs, toMs)` written by the on-device collectors. */
public interface CollectorCoveragePort {
    /** Coverage intervals of [collectorId] that overlap [range], in any order. */
    public suspend fun intervals(collectorId: String, range: ClosedOpenRange): Outcome<List<CoverageInterval>>
}

/** Responses recorded on a decision row (R10 §8.7); [NONE] until the user responds or the outcome window closes. */
public enum class InterventionResponse { NONE, OPENED, DISMISSED, SNOOZED, HELPFUL, NOT_HELPFUL, IGNORED }

/** Decision states that count as a delivery (R10 §5.4 I). */
public enum class DeliveryState { DELIVERED, DELIVERY_UNCERTAIN }

/**
 * A `jitai_decision` row that counts as a delivery.
 *
 * @property at `deliveredAt` for [DeliveryState.DELIVERED], `decisionPointAt` otherwise (the anchor of R10 §8.3).
 * @property elapsedRealtime the monotonic clock (`AgentleClock.elapsed()`) at [at]; null when not recorded.
 * @property bootCount `Settings.Global.BOOT_COUNT` at [at]; null when not recorded.
 * @property engineDay the engine day the decision was counted in, as stored on the row.
 * @property jitaiCategory the JITAI's category (`PHYSICAL_ACTIVITY`, `DIGITAL_WELLBEING`, ...).
 */
public data class DeliveryRecord(
    val decisionKey: String,
    val jitaiId: String,
    val jitaiCategory: String,
    val state: DeliveryState,
    val at: Instant,
    val engineDay: LocalDate,
    val elapsedRealtime: Duration? = null,
    val bootCount: Int? = null,
    val response: InterventionResponse = InterventionResponse.NONE,
)

/** Which JITAIs a history feature looks at: the parsed `jitai` arg (R10 §4.5, §5.4 I). */
public sealed interface JitaiSelector {
    /** One JITAI by id. `self` is bound to the evaluated rule's id by the caller (see [JitaiArgs.bindSelf]). */
    public data class Id(val jitaiId: String) : JitaiSelector

    /** Any INTERVENTION JITAI (SUPPRESSION rules never deliver). */
    public data object AnyJitai : JitaiSelector

    /** Every JITAI of one category. */
    public data class Category(val category: String) : JitaiSelector

    /** True if [record] belongs to this selection. */
    public fun matches(record: DeliveryRecord): Boolean = when (this) {
        is Id -> record.jitaiId == jitaiId
        AnyJitai -> true
        is Category -> record.jitaiCategory == category
    }
}

/** Intervention history from `jitai_decision`. Returns counted deliveries only (`DELIVERED`, `DELIVERY_UNCERTAIN`). */
public interface InterventionHistoryPort {
    /**
     * The newest [limit] deliveries matching [selector], newest first in the order they were recorded. Not filtered by
     * wall-clock time: after the user moves the clock back, the latest delivery must still count (R10 §8.6, §10.7).
     */
    public suspend fun latest(selector: JitaiSelector, limit: Int): Outcome<List<DeliveryRecord>>

    /** Deliveries matching [selector] whose stored engine day is in `first..last` (both inclusive). */
    public suspend fun inEngineDays(selector: JitaiSelector, first: LocalDate, last: LocalDate): Outcome<List<DeliveryRecord>>
}

/**
 * One consistent read (database-sync-05): every port read of one `resolve()` runs inside [read], so all of them see one
 * database snapshot. The Android repository runs the block in one read transaction; [Direct] just runs it.
 */
public interface SnapshotReads {
    public suspend fun <T> read(block: suspend () -> T): T

    public companion object {
        /** Runs the block as is: for in-memory ports, which are consistent by construction. */
        public val Direct: SnapshotReads = object : SnapshotReads {
            override suspend fun <T> read(block: suspend () -> T): T = block()
        }
    }
}

/**
 * Every input the realtime feature engine reads. One instance is bound in the app graph (`:data` repositories) or
 * built from in-memory ports in tests (`dev.agentle.analytics.features.realtime.testing`).
 */
public class RealtimeFeatureInputs(
    public val usage: UsageEventPort,
    public val notifications: NotificationEventPort,
    public val activity: ActivityTransitionPort,
    public val steps: StepSeriesPort,
    public val sleep: SleepSessionPort,
    public val dailySummaries: DailySummaryPort,
    public val heartRate: HeartRateSamplePort,
    public val sourceCoverage: SourceCoveragePort,
    public val collectorCoverage: CollectorCoveragePort,
    public val history: InterventionHistoryPort,
    public val live: LiveDeviceReads,
    public val snapshots: SnapshotReads = SnapshotReads.Direct,
) {
    /** Runs [block] against one consistent snapshot of the stored inputs (database-sync-05). */
    public suspend fun <T> readSnapshot(block: suspend () -> T): T = snapshots.read(block)
}
