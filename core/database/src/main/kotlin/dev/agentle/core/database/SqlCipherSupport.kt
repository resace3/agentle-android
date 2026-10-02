package dev.agentle.core.database

import android.database.sqlite.SQLiteException
import androidx.sqlite.SQLiteDriver
import dev.agentle.core.security.DatabaseKey
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.Logger
import net.zetetic.database.NoopTarget
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver

/**
 * SQLCipher for Android behind Room 3 (docs/ARCHITECTURE.md §5.4, R04 §3.2): every app variant opens the database
 * through [SQLCipherDriver] with the raw 32-byte key. Before any SQLCipher class logs, its logger is replaced by a no-op
 * target (no SQL statements in logcat), and every connection runs `PRAGMA cipher_log_level = NONE` before and after
 * keying and `PRAGMA secure_delete = ON` after keying, which is verified.
 */
object SqlCipherSupport {
    private const val BUSY_TIMEOUT_MS = 5_000

    @Volatile private var ready = false

    /** Silences SQLCipher's logger and loads its native library; idempotent. */
    @Synchronized
    fun ensureReady() {
        if (ready) return
        Logger.setTarget(NoopTarget())
        System.loadLibrary("sqlcipher")
        ready = true
    }

    /**
     * A driver keyed with [passphrase] (`x'<hex>'`, see [DatabaseKey.sqlCipherPassphrase]). SQLCipher keeps the array to
     * key every pooled connection, so the caller wipes it only after the database is closed.
     */
    fun driver(passphrase: ByteArray): SQLiteDriver {
        ensureReady()
        return SQLCipherDriver(passphrase, SecureConnectionHook, NonDeletingErrorHandler)
    }

    private object SecureConnectionHook : SQLiteDatabaseHook {
        override fun preKey(connection: SQLiteConnection) {
            connection.executeRaw("PRAGMA cipher_log_level = NONE", null, null)
        }

        override fun postKey(connection: SQLiteConnection) {
            connection.executeRaw("PRAGMA cipher_log_level = NONE", null, null)
            connection.executeRaw("PRAGMA secure_delete = ON", null, null)
            connection.executeRaw("PRAGMA busy_timeout = $BUSY_TIMEOUT_MS", null, null)
            if (connection.executeForLong("PRAGMA secure_delete", null, null) != 1L) {
                // Never run with deleted content left in free pages.
                throw SQLiteException("secure_delete unavailable")
            }
        }
    }

    /**
     * SQLCipher's default handler deletes the database file on corruption. This one never deletes anything: an
     * unreadable database goes through the visible, user-confirmed reset flow instead (red team lifecycle-battery-08).
     */
    private object NonDeletingErrorHandler : DatabaseErrorHandler {
        override fun onCorruption(database: SQLiteDatabase?, exception: SQLiteException?) {
            corruptionReported = true
        }
    }

    /** Set when SQLCipher reported corruption since the process started; read by the database provider. */
    @Volatile
    var corruptionReported: Boolean = false
        private set
}
