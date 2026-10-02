package dev.agentle.core.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
public enum class ConnectionStatus { NOT_CONNECTED, CONNECTING, CONNECTED, NEEDS_REAUTH, DISABLED, UNAVAILABLE, ERROR }

@Serializable
public enum class SyncStatus { IDLE, RUNNING, SUCCEEDED, PARTIAL, FAILED }

/** A sanitized error snapshot safe to persist and show in diagnostics. */
@Serializable
public data class ErrorInfo(val code: String, val at: Instant, val message: String? = null)

/** Normalized connector metadata (spec §5). */
@Serializable
public data class ConnectorMetadata(
    val connectorId: String,
    val name: String,
    val enabled: Boolean,
    val connection: ConnectionStatus,
    val permissionSummary: PermissionState,
    val lastSuccessfulCollection: Instant? = null,
    val lastAttemptedCollection: Instant? = null,
    val lastError: ErrorInfo? = null,
    val supportedEventTypes: Set<EventType> = emptySet(),
    val syncState: SyncStatus = SyncStatus.IDLE,
    /**
     * Permission per stream (e.g. `steps` -> ALLOWED, `sleep` -> DENIED) for connectors whose grant is partial
     * (Google Health scopes); [permissionSummary] is then [PermissionState.PARTIALLY_ALLOWED]. Empty when not applicable.
     */
    val streamPermissions: Map<String, PermissionState> = emptyMap(),
    /**
     * Per stream, the instant before which the source asserts it has delivered everything recorded
     * (docs/research/10 §5.3). A stream without an entry has no coverage claim: treat its data as not synced yet.
     */
    val coverageThrough: Map<String, Instant> = emptyMap(),
)
