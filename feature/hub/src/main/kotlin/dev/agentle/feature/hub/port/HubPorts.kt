package dev.agentle.feature.hub.port

import android.content.Intent
import androidx.paging.PagingData
import dev.agentle.ai.api.AiProviderState
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.CapabilityStatus
import dev.agentle.core.model.ConnectorMetadata
import dev.agentle.core.model.DataCapability
import dev.agentle.core.model.EventType
import dev.agentle.core.model.Insight
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.time.AgentleClock
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

// All suspend functions of these ports are main-safe. Read flows may throw dev.agentle.core.common.AppException
// (the screens catch it and show ErrorState with a retry); they never emit invented values.

/** The user's clock and zone: every date, day grouping and relative time on the hub screens uses it. */
public interface HubClockPort {
    public val clock: AgentleClock
}

/** Today summary metrics shown on the Dashboard. Minutes for the *_MINUTES kinds, counts otherwise. */
public enum class TodayMetricKind { STEPS, ACTIVE_MINUTES, SLEEP_MINUTES, SCREEN_TIME_MINUTES, UNLOCKS }

/** An active JITAI and the earliest time it could deliver next (null: no delivery possible now, see [blockedReason]). */
public data class ActiveJitai(val id: String, val name: String, val nextPossibleDelivery: Instant?, val blockedReason: String? = null)

/** An intervention that could not be shown as a notification and waits in the app (decision key from the engine). */
public data class PendingIntervention(val decisionKey: String, val jitaiId: String, val title: String, val text: String, val createdAt: Instant)

/**
 * Everything the Dashboard shows.
 *
 * @property today one value per [TodayMetricKind] for today in the user's zone. `FeatureValue.Missing` when there is no
 *   data or no coverage (never a zero), `Stale` when only an older value exists.
 * @property wearable the Google Health connector, null when the build has none.
 * @property collectors one entry per Android collector and connector, for "data collection health".
 */
public data class DashboardData(
    val today: Map<TodayMetricKind, FeatureValue> = emptyMap(),
    val recentInsights: List<Insight> = emptyList(),
    val activeJitais: List<ActiveJitai> = emptyList(),
    val wearable: ConnectorMetadata? = null,
    val chatGpt: AiProviderState = AiProviderState.Disconnected,
    val collectors: List<ConnectorMetadata> = emptyList(),
    val pendingInterventions: List<PendingIntervention> = emptyList(),
)

/** Dashboard read model and the in-app intervention actions. */
public interface DashboardPort {
    /** Emits [DashboardData] with empty lists and Missing metrics on a fresh install; updates as data arrives. */
    public val dashboard: Flow<DashboardData>

    /** Records "snooze" for [decisionKey] (engine snooze rules apply). Errors: `database_error`, `validation_error`. */
    public suspend fun snooze(decisionKey: String): Outcome<Unit>

    /** Records "not now" (a dismissal) for [decisionKey]. Errors: `database_error`. */
    public suspend fun notNow(decisionKey: String): Outcome<Unit>

    /** Pauses the JITAI ("stop this JITAI"); its pending cards disappear. Errors: `database_error`. */
    public suspend fun stopJitai(jitaiId: String): Outcome<Unit>
}

/** What a data source is. */
public enum class DataSourceKind { ANDROID_COLLECTOR, WEARABLE, OTHER }

/**
 * One connector or collector on the Data Sources screen.
 *
 * @property coverageThrough the latest time up to which data is complete (null: no coverage yet).
 * @property gapDays local days in the last 7 days without coverage.
 */
public data class DataSourceItem(
    val metadata: ConnectorMetadata,
    val kind: DataSourceKind,
    val capabilityIds: List<String>,
    val coverageThrough: Instant? = null,
    val gapDays: List<LocalDate> = emptyList(),
)

/** Data Sources read model and actions. */
public interface DataSourcesPort {
    /** Every connector and collector of this build, also the disabled and unavailable ones; empty only while loading. */
    public val sources: Flow<List<DataSourceItem>>

    /** Enables or disables collection; disabling never deletes data. Errors: `database_error`, `unsupported_feature`. */
    public suspend fun setEnabled(connectorId: String, enabled: Boolean): Outcome<Unit>

    /**
     * Starts a sync now (the wearable). Returns when it is queued; progress shows in [sources]. Errors:
     * `authentication_required`, `network_unavailable`, `rate_limited`, `unsupported_feature`.
     */
    public suspend fun syncNow(connectorId: String): Outcome<Unit>
}

/**
 * One capability in the Permission Center: registry entry plus live state.
 *
 * @property missingPermissions runtime permissions of [capability] not held now (requestable from the screen).
 * @property collectionEnabled the user's on/off switch for collecting this capability.
 * @property lastUsedAt the last time a collector used it, null if never.
 */
public data class CapabilityItem(
    val capability: DataCapability,
    val status: CapabilityStatus,
    val missingPermissions: List<String> = emptyList(),
    val collectionEnabled: Boolean = false,
    val lastUsedAt: Instant? = null,
)

/** Permission Center read model (ARCHITECTURE §6.3) and actions. */
public interface PermissionCenterPort {
    /**
     * Every capability of the registry with its resolved state, also DEFER and DOCUMENT_UNAVAILABLE ones (shown as
     * unsupported). Re-emits after [refresh] and after any reported result.
     */
    public val capabilities: Flow<List<CapabilityItem>>

    /**
     * Re-evaluates every resolver (on resume). [rationale] holds `shouldShowRequestPermissionRationale` for each
     * runtime permission, read by the screen: the UI-only refinement of DENIED vs DENIED_PERMANENTLY.
     */
    public suspend fun refresh(rationale: Map<String, Boolean>): Outcome<Unit>

    /**
     * Reports one permission dialog for [capabilityId]: [granted] per permission and the rationale flags after denial.
     * Sets the "requested once" flags. Errors: `database_error`.
     */
    public suspend fun reportPermissionResult(
        capabilityId: String,
        granted: Map<String, Boolean>,
        showRationale: Map<String, Boolean>,
    ): Outcome<Unit>

    /** Turns collection of [capabilityId] on or off. Errors: `database_error`, `permission_denied`. */
    public suspend fun setCollectionEnabled(capabilityId: String, enabled: Boolean): Outcome<Unit>

    /**
     * The exact Settings screen that grants or revokes [capabilityId] (special access, Health Connect, app details,
     * location or Bluetooth switch, R01 §6), or null when none applies. Must not start anything itself.
     */
    public fun settingsIntent(capabilityId: String): Intent?
}

/** Timeline filters. Null means "all". [date] is a local day in the user's zone. */
public data class TimelineFilter(val source: String? = null, val eventType: EventType? = null, val date: LocalDate? = null)

/** How complete one local day is. */
public enum class DayCoverage { COMPLETE, PARTIAL, NONE }

/** Timeline read model. */
public interface TimelinePort {
    /**
     * Events newest first, paged by keyset (`start_ms`, `seq`) with an upper bound of [upperBound] (captured when the
     * Timeline opens, so rows ingested while scrolling do not shift pages). Empty paging data when nothing is stored.
     */
    public fun events(filter: TimelineFilter, upperBound: Instant): Flow<PagingData<PersonalEvent>>

    /** Coverage of each local day in [from]..[to] (inclusive); days absent from the map have no coverage record. */
    public fun dayCoverage(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, DayCoverage>>

    /** Source ids that have stored events, for the source filter; empty when nothing is stored. */
    public val sources: Flow<List<String>>
}
