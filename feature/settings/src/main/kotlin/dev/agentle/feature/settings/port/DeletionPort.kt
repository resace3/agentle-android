package dev.agentle.feature.settings.port

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.DataCategory
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/** What one "delete" action removes (spec §62, docs/ARCHITECTURE.md §5.5, R04 §3.7). */
public sealed interface DeletionTarget {
    /**
     * Everything synced from the user's wearable (`ConnectorIds.WEARABLE`: Google Health API and Health Connect) and
     * everything derived from it. Deleting never disconnects the wearable.
     */
    public data object WearableData : DeletionTarget

    /** Everything Agentle collected on this phone (connector `android`) and everything derived from it. */
    public data object PhoneData : DeletionTarget

    /** One data category from every source, everything derived from it, and its AI-sharing consent. */
    public data class Category(val category: DataCategory) : DeletionTarget

    /** Every insight (local and AI-made) and its supporting evidence. */
    public data object Insights : DeletionTarget

    /**
     * Intervention history: decision traces, feature snapshots, rendered content, AI-written texts (`ai_text_pool`)
     * and outcome metrics. The content-free delivery ledger (decision key, JITAI id, engine day, times, state, channel,
     * response) is kept for 400 days: caps and cooldowns depend on it. Only "delete everything" removes it.
     */
    public data object InterventionHistory : DeletionTarget

    /** Generated images, voice files and videos: `media_artifact` rows, their files and share copies. */
    public data object GeneratedMedia : DeletionTarget
}

/** Whether a target's data keeps arriving after a delete, i.e. whether "also stop collecting or syncing" applies. */
public enum class CollectionControl {
    /** Nothing collects this target (insights, history, media are produced by Agentle itself). */
    NOT_APPLICABLE,

    /** At least one collector or sync stream for this target is on. */
    ACTIVE,

    /** Every collector and stream for this target is already off. */
    STOPPED,
}

/** One deletable target with its current size. Counts are null when they could not be computed (partial state). */
public data class DeletionItem(
    val target: DeletionTarget,
    /** Rows of the target and of everything derived from it. */
    val records: Long?,
    /** Files (media, share copies) the target owns; null when the target owns no files. */
    val files: Int? = null,
    /** Size of [files] on disk. */
    val bytes: Long? = null,
    val collection: CollectionControl = CollectionControl.NOT_APPLICABLE,
)

/** Everything the "delete data" screen lists. */
public data class DeletionOverview(
    /** Targets in display order. Targets this build cannot delete are left out. */
    val items: List<DeletionItem>,
    /** Entries of the content-free delivery ledger that "delete intervention history" keeps; null if unknown. */
    val ledgerEntries: Long? = null,
)

/** Result of one per-target delete, verified by a count query after the delete (a UI message is not proof). */
public data class DeletionReport(
    val target: DeletionTarget,
    val deletedRecords: Long,
    val deletedFiles: Int,
    /** Rows of the target still present after the delete, counted independently. 0 when the delete is complete. */
    val remainingRecords: Long,
    /** Files of the target still present after the delete. 0 when complete. */
    val remainingFiles: Int,
    /** Later syncs and imports drop data that started before this instant (the import floor); null if not applicable. */
    val importFloor: Instant?,
    /** The target's AI-sharing consent grants were revoked (categories and sources). */
    val aiConsentRevoked: Boolean,
    /** The caller asked to stop collecting and every collector or stream of the target is now off. */
    val collectionStopped: Boolean,
    /** For [DeletionTarget.InterventionHistory]: ledger entries kept (see there). */
    val keptLedgerEntries: Long? = null,
) {
    /** True when the verification found nothing left. */
    val complete: Boolean get() = remainingRecords == 0L && remainingFiles == 0
}

/**
 * The steps of "delete everything", in order (red team corrections: deletion, round 2 item 6). The marker makes the
 * sequence resumable; the last step, `clearApplicationUserData()`, ends the app's process.
 */
public enum class DeleteAllStep {
    /** Write `noBackupFilesDir/deletion_in_progress` (with the step reached), so an app start resumes the sequence. */
    WRITE_MARKER,

    /** Cancel all work and alarms, disable the notification listener component, cancel posted notifications. */
    STOP_PRODUCERS,

    /** Revoke the ChatGPT refresh token and the Google grant while credentials still exist. */
    REVOKE_REMOTE,

    /** Wait for running writers through the epoch guard. */
    DRAIN_WRITERS,

    /** Close Room. */
    CLOSE_DATABASE,

    /** Delete the database file and its `-wal`, `-shm` and `-journal` files. */
    DELETE_DATABASE,

    /** Delete the wrapped database key file and both Keystore aliases (crypto-erasure). */
    DELETE_KEYS,

    /** Delete media, share copies, DataStore files and the token vault. */
    DELETE_FILES,

    /** Count what is left. */
    VERIFY,

    /** `ActivityManager.clearApplicationUserData()`: runs after the user saw the report; ends the process. */
    CLEAR_APP_DATA,
}

/** A remote service "delete everything" signs out of before it deletes local credentials. */
public enum class RemoteService { CHATGPT, GOOGLE }

/** How signing out of one remote service went. */
public enum class RemoteDisconnectionResult {
    /** The service confirmed the revocation (for example an empty HTTP 200 from the revocation endpoint). */
    DISCONNECTED,

    /** There was no session to revoke. */
    NOT_CONNECTED,

    /** Revocation failed or could not be confirmed: the user must remove access manually (the screen shows how). */
    UNCONFIRMED,
}

public data class RemoteDisconnection(val service: RemoteService, val result: RemoteDisconnectionResult)

/** What "delete everything" verifies before the app data is cleared. */
public enum class DeleteAllCheckItem {
    DATABASE_FILES,
    ENCRYPTION_KEYS,
    SIGN_IN_CREDENTIALS,
    MEDIA_FILES,
    SETTINGS_FILES,
    SCHEDULED_WORK,
}

/** One verification count: how many items of [item] are left (0 expected). */
public data class DeleteAllCheck(val item: DeleteAllCheckItem, val remaining: Long)

/** The verified result of "delete everything", shown before the app closes. */
public data class DeleteAllReport(val checks: List<DeleteAllCheck>, val remote: List<RemoteDisconnection>) {
    val complete: Boolean get() = checks.all { it.remaining == 0L }
    val remoteUnconfirmed: List<RemoteService>
        get() = remote.filter { it.result == RemoteDisconnectionResult.UNCONFIRMED }.map { it.service }
}

/** Progress of "delete everything". */
public sealed interface DeleteAllState {
    /** No deletion marker exists. */
    public data object Idle : DeleteAllState

    /**
     * The sequence is running. [resumed] is true when it was restarted from a marker found at app start (the screen
     * says "resuming deletion"). [remote] holds the results of [DeleteAllStep.REVOKE_REMOTE] once it ran.
     */
    public data class Running(
        val step: DeleteAllStep,
        val completed: Set<DeleteAllStep>,
        val resumed: Boolean,
        val remote: List<RemoteDisconnection> = emptyList(),
    ) : DeleteAllState

    /** Everything up to [DeleteAllStep.VERIFY] ran; waiting for [DeletionPort.finishDeleteEverything]. */
    public data class Verified(val report: DeleteAllReport, val resumed: Boolean) : DeleteAllState

    /** The sequence stopped at [step]; the marker stays, so [DeletionPort.deleteEverything] resumes from there. */
    public data class Failed(
        val step: DeleteAllStep,
        val error: AppError,
        val completed: Set<DeleteAllStep>,
        val resumed: Boolean,
        val remote: List<RemoteDisconnection> = emptyList(),
    ) : DeleteAllState
}

/**
 * Delete data (`AppRoute.DeleteData`, spec Journey 10). Every delete is verified by counting afterwards; the screen
 * shows those counts. Deleting never disconnects a source and disconnecting never deletes.
 */
public interface DeletionPort {
    /**
     * Deletable targets with their counts. Emits again after any delete, sync or retention run changes a count (a
     * running sync may raise counts while the screen is open). With no data every count is 0 (never an error); a
     * disconnected source or a missing permission does not hide its target (its data can still be deleted). A count
     * that cannot be computed is null (the rest still shows). Emits `Outcome.Failure` (`DatabaseError`,
     * `UnsupportedFeature`) when nothing can be read; deleting everything stays possible.
     */
    public val overview: Flow<Outcome<DeletionOverview>>

    /**
     * Progress of "delete everything". Emits [DeleteAllState.Idle] when no marker exists. When the app starts with a
     * marker, the implementation resumes the sequence by itself and this emits `Running(resumed = true)`; the app
     * opens this screen so the user sees it.
     */
    public val deleteAllState: Flow<DeleteAllState>

    /**
     * Deletes [target] and everything derived from it in bounded transactions under the epoch guard, writes the
     * import floor of its streams (so a later sync cannot bring the deleted period back), revokes its AI consent,
     * and, when [stopCollecting] is true, turns its collectors or sync streams off first. If collection continues,
     * only data from now on comes back. Returns the verified counts. Failures: `AppError.DatabaseError` (partly
     * deleted: retrying resumes from the stored progress), `UnsupportedFeature`. Main-safe; the delete runs to the
     * end even if the caller is cancelled (the caller only stops waiting).
     */
    public suspend fun delete(target: DeletionTarget, stopCollecting: Boolean): Outcome<DeletionReport>

    /**
     * Starts "delete everything", or resumes it from the marker after [DeleteAllState.Failed]. Returns once the
     * sequence is running in an application-scoped coroutine (it must outlive this screen); progress arrives on
     * [deleteAllState], which stops at [DeleteAllState.Verified]. Calling it while the sequence runs does nothing.
     * Failures (nothing started): `AppError.DatabaseError` when the marker cannot be written, `UnsupportedFeature`.
     */
    public suspend fun deleteEverything(): Outcome<Unit>

    /**
     * After [DeleteAllState.Verified]: calls `clearApplicationUserData()`, which revokes runtime permissions and ends
     * the process, so on a device this does not return on success. Returns `Outcome.Success` only where clearing does
     * not end the process (tests, fakes); the screen then restarts at onboarding. Failures: `AppError.Unexpected`
     * when the system refused to clear. Main-safe.
     */
    public suspend fun finishDeleteEverything(): Outcome<Unit>
}
