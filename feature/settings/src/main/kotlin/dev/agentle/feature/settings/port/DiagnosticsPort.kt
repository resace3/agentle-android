package dev.agentle.feature.settings.port

import android.net.Uri
import dev.agentle.ai.api.AiProviderState
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.Severity
import dev.agentle.core.model.CapabilityStatus
import dev.agentle.core.model.ConnectorMetadata
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/** Build identity shown at the top of diagnostics. */
public data class AppBuildInfo(val versionName: String, val versionCode: Long, val buildType: String, val flavor: String)

/** The states of a WorkManager `WorkInfo`, mirrored so this module does not depend on WorkManager. */
public enum class WorkerRunState { ENQUEUED, RUNNING, SUCCEEDED, FAILED, BLOCKED, CANCELLED }

/** One unique background work (docs/ARCHITECTURE.md §13 names, for example `sync-googlehealth`). */
public data class WorkerStatus(
    val uniqueName: String,
    val state: WorkerRunState,
    val runAttemptCount: Int,
    /** `WorkInfo.nextScheduleTimeMillis` as an instant; null when not scheduled. */
    val nextRunAt: Instant?,
)

/** Record counts for the diagnostics screen. */
public data class RecordCounts(val events: Long, val insights: Long, val jitais: Long)

/**
 * One recent error. Allow-listed structure only (red team privacy-ai-11): a code and closed-set fields, never an
 * exception message, a payload, notification text or a location.
 */
public data class DiagnosticError(
    val at: Instant,
    val severity: Severity,
    /** A component code such as `sync.googlehealth`. */
    val component: String,
    /** A stable error code such as `rate_limited` (an `AppError.code`). */
    val code: String,
    val httpStatus: Int? = null,
)

/**
 * Everything the diagnostics screen shows (spec §55). Sections that could not be read are null or empty, and the rest
 * still shows. Never contains secrets, tokens, account labels, payloads, notification text or precise location: the
 * screen shows only codes, states, counts and times from these fields (it ignores `CapabilityStatus.detail` and
 * `ErrorInfo.message`).
 */
public data class DiagnosticsSnapshot(
    val build: AppBuildInfo,
    /** Room schema version; null when the database could not be opened. */
    val databaseVersion: Int?,
    val androidApi: Int,
    val permissions: List<CapabilityStatus>,
    val connectors: List<ConnectorMetadata>,
    val lastSuccessfulSync: Instant?,
    val lastWearableSync: Instant?,
    /** The ChatGPT state; the screen shows only its kind. */
    val chatGpt: AiProviderState,
    val workers: List<WorkerStatus>?,
    val counts: RecordCounts?,
    /** Newest first. */
    val recentErrors: List<DiagnosticError>,
)

/** A sanitized diagnostic report written for sharing. */
public data class DiagnosticReport(
    /** A `content://` URI from the app's `FileProvider` (a copy under `cacheDir/share`), readable through a grant. */
    val uri: Uri,
    /** `application/json`. */
    val mimeType: String,
)

/** Diagnostics (`AppRoute.Diagnostics`, spec §55). Available in every build; never shows secrets. */
public interface DiagnosticsPort {
    /**
     * A snapshot, taken when collection starts and again after [refresh]. With nothing connected and no permissions it
     * still emits: connectors `NOT_CONNECTED`, permissions with their denied states, counts 0, no syncs. While a sync
     * runs, connectors show `SyncStatus.RUNNING`. Emits `Outcome.Failure` (`DatabaseError`, `UnsupportedFeature`) only
     * when nothing at all can be read.
     */
    public val snapshot: Flow<Outcome<DiagnosticsSnapshot>>

    /** Asks [snapshot] to emit a fresh snapshot. Main-safe. */
    public suspend fun refresh()

    /**
     * Writes the sanitized JSON report (allow-listed fields only: codes, states, counts, durations, HTTP statuses)
     * and returns its share URI. Failures: `DatabaseError`, `UnsupportedFeature`. Main-safe.
     */
    public suspend fun exportReport(): Outcome<DiagnosticReport>
}
