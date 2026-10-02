package dev.agentle.core.database

import android.app.Activity
import android.content.Context
import dev.agentle.core.security.DatabaseKey
import dev.agentle.core.security.DatabaseKeyManager
import dev.agentle.core.security.DatabaseKeyResult
import dev.agentle.core.security.FailureKind
import dev.agentle.core.security.Quarantine
import dev.agentle.core.security.SecurityPaths
import dev.agentle.core.security.UnlockFailureReason
import dev.agentle.core.security.UnlockFailureStatus
import dev.agentle.core.security.UnlockFailureTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The database cannot be opened right now (round 1 correction 8): the key could not be obtained or SQLCipher rejected
 * it. [reason] says whether it can heal by itself; there is never a plaintext fallback. The message carries the reason
 * code only.
 */
class LocalDataUnavailableException(val reason: UnlockFailureReason, val errorClass: String? = null) :
    Exception("local data unavailable: ${reason.name}") {
    val permanent: Boolean get() = reason.kind == FailureKind.PERMANENT
}

/** What the app knows about the local database. */
sealed interface DatabaseState {
    data object NotOpened : DatabaseState

    data object Open : DatabaseState

    /** The last attempt failed; [status] tells the UI whether a user-confirmed reset is allowed. */
    data class Unavailable(val reason: UnlockFailureReason, val status: UnlockFailureStatus) : DatabaseState

    /** Closed by delete-all or a reset; nothing may open it again in this process until [DatabaseProvider.reopen]. */
    data object Closed : DatabaseState
}

/** A built database plus the passphrase its driver holds (wiped after close). */
class BuiltDatabase(val database: AgentleDatabase, private val passphrase: ByteArray?) {
    fun close() {
        database.close()
        passphrase?.fill(0)
    }
}

/** Builds the database for a key. Production: SQLCipher; tests: an unencrypted driver. */
interface DatabaseFactory {
    fun create(key: DatabaseKey, queryContext: CoroutineContext): BuiltDatabase
}

/** The production factory: [SqlCipherSupport.driver] on `databases/agentle.db`. */
class SqlCipherDatabaseFactory(private val context: Context, private val paths: SecurityPaths) : DatabaseFactory {
    override fun create(key: DatabaseKey, queryContext: CoroutineContext): BuiltDatabase {
        val passphrase = key.sqlCipherPassphrase()
        val database = AgentleDatabases.build(context, paths.databaseFile, SqlCipherSupport.driver(passphrase), queryContext)
        return BuiltDatabase(database, passphrase)
    }
}

/**
 * Opens the database lazily, off the main thread, on first use (round 1 correction 8). A failure is classified, counted
 * by the [UnlockFailureTracker] (in `noBackupFilesDir`) and reported as [LocalDataUnavailableException]; nothing is ever
 * deleted here. Every caller gets the same open database and its [DatabaseTransactions].
 */
interface DatabaseProvider {
    val state: StateFlow<DatabaseState>

    /** The open database. Throws [LocalDataUnavailableException]. */
    suspend fun database(): AgentleDatabase

    /** The serialized writer and the read-transaction runner of the open database. */
    suspend fun transactions(): DatabaseTransactions

    /** Closes the database for delete-all or a reset; afterwards [database] fails until [reopen]. */
    suspend fun close()

    /** Allows opening again (after a reset created fresh key material). */
    suspend fun reopen()
}

class DefaultDatabaseProvider(
    private val factory: DatabaseFactory,
    private val keys: DatabaseKeyManager,
    private val tracker: UnlockFailureTracker?,
    private val queryContext: CoroutineContext,
    private val ioContext: CoroutineContext,
) : DatabaseProvider {
    private class Opened(val built: BuiltDatabase, val transactions: DatabaseTransactions)

    private val mutex = Mutex()

    @Volatile private var opened: Opened? = null

    @Volatile private var closed = false
    private val mutableState = MutableStateFlow<DatabaseState>(DatabaseState.NotOpened)

    override val state: StateFlow<DatabaseState> = mutableState.asStateFlow()

    override suspend fun database(): AgentleDatabase = (opened ?: open()).built.database

    override suspend fun transactions(): DatabaseTransactions = (opened ?: open()).transactions

    override suspend fun close() {
        mutex.withLock {
            closed = true
            opened?.let { current -> withContext(queryContext) { current.built.close() } }
            opened = null
            mutableState.value = DatabaseState.Closed
        }
    }

    override suspend fun reopen() {
        mutex.withLock {
            closed = false
            mutableState.value = DatabaseState.NotOpened
        }
    }

    private suspend fun open(): Opened = mutex.withLock {
        opened?.let { return it }
        if (closed) throw LocalDataUnavailableException(UnlockFailureReason.STORAGE_ERROR, "Closed")
        withContext(ioContext) { openLocked() }
    }

    private suspend fun openLocked(): Opened {
        val key = when (val result = keys.obtainKey()) {
            is DatabaseKeyResult.Unavailable -> fail(result.reason, result.errorClass)
            is DatabaseKeyResult.Available -> result.key
        }
        val built = try {
            factory.create(key, queryContext)
        } finally {
            key.close()
        }
        try {
            // The first statement opens and keys the connection: a wrong key fails here, not in a later query.
            DatabaseTransactions(built.database, queryContext).read { queryLong("SELECT COUNT(*) FROM sqlite_master") }
        } catch (e: CancellationException) {
            built.close()
            throw e
        } catch (e: Exception) {
            built.close()
            fail(OpenFailures.classify(e), e.javaClass.simpleName)
        }
        tracker?.clear()
        val result = Opened(built, DatabaseTransactions(built.database, queryContext))
        opened = result
        mutableState.value = DatabaseState.Open
        return result
    }

    private fun fail(reason: UnlockFailureReason, errorClass: String?): Nothing {
        val status = tracker?.recordFailure(reason) ?: UnlockFailureStatus(consecutiveFailures = 1, lastReason = reason)
        mutableState.value = DatabaseState.Unavailable(reason, status)
        throw LocalDataUnavailableException(reason, errorClass)
    }
}

/** Classification of a failed first open (round 1 correction 8). */
object OpenFailures {
    private const val NOT_A_DATABASE_CODE = "(code 26"
    private const val NOT_A_DATABASE_TEXT = "file is not a database"

    /**
     * "File is not a database" after a successful unwrap is permanent (the key does not fit the file); everything else
     * is transient. The exception message is inspected for the SQLite error only and is never stored or logged.
     */
    fun classify(error: Throwable): UnlockFailureReason {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (isNotADatabase(current)) return UnlockFailureReason.NOT_A_DATABASE
            current = current.cause
            depth++
        }
        return UnlockFailureReason.STORAGE_ERROR
    }

    private fun isNotADatabase(error: Throwable): Boolean {
        if (error.javaClass.simpleName == "SQLiteNotADatabaseException") return true
        val message = error.message ?: return false
        return message.contains(NOT_A_DATABASE_TEXT, ignoreCase = true) || message.contains(NOT_A_DATABASE_CODE)
    }

    private const val MAX_CAUSE_DEPTH = 8
}

/**
 * Proof that a visible activity asked for a local data reset after the user confirmed it (round 1 correction 8). Only
 * [fromVisibleActivity] creates one, and only while the activity has window focus, so no background code can reset.
 */
class ResetConfirmation private constructor(val activityName: String) {
    companion object {
        fun fromVisibleActivity(activity: Activity): ResetConfirmation? =
            if (activity.hasWindowFocus() && !activity.isFinishing) ResetConfirmation(activity.javaClass.simpleName) else null

        /** For Robolectric tests of the reset flow. */
        internal fun forTest(): ResetConfirmation = ResetConfirmation("Test")
    }
}

/** Result of [LocalDataReset.reset]. */
enum class ResetOutcome {
    /** The unreadable database and its wrapped key were moved into quarantine; a fresh database opens next. */
    RESET,

    /** Not allowed: the failure is transient and has not lasted across two boots (or there is no failure). */
    NOT_ALLOWED,

    /** The files could not be moved; nothing was deleted. */
    FAILED,
}

/**
 * The "local data unreadable" reset (round 1 correction 8): only from a visible activity after confirmation, and only
 * when the failure is permanent or has lasted across at least two boots. It renames the database files and the wrapped
 * key into the quarantine directory (never deletes them) and lets the next open create fresh ones.
 */
class LocalDataReset(
    private val provider: DatabaseProvider,
    private val keys: DatabaseKeyManager,
    private val tracker: UnlockFailureTracker,
    private val paths: SecurityPaths,
    private val ioContext: CoroutineContext,
) {
    /** [confirmation] can only come from a visible activity; [tag] names the quarantine folder. */
    suspend fun reset(confirmation: ResetConfirmation, tag: String): ResetOutcome {
        if (!tracker.status().resetAllowed) return ResetOutcome.NOT_ALLOWED
        provider.close()
        val folder = "$tag-${confirmation.activityName}"
        val moved = withContext(ioContext) {
            val files = paths.databaseFiles.all { Quarantine.move(it, paths.quarantineDir, folder) }
            files && keys.quarantineWrappedKey(folder)
        }
        if (moved) tracker.clear()
        provider.reopen()
        return if (moved) ResetOutcome.RESET else ResetOutcome.FAILED
    }
}
