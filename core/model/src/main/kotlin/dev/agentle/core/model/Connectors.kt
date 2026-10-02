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
)
