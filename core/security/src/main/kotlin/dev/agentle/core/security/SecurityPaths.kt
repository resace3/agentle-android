package dev.agentle.core.security

import android.content.Context
import java.io.File

/**
 * Where Agentle keeps key material and security state. Everything except the database lives under
 * `noBackupFilesDir`, which Android never backs up or restores, so a wrapped key never meets a device whose Keystore
 * cannot unwrap it (docs/ARCHITECTURE.md §5.4, docs/research/04 §3.2). The directories are resolved on first use, so
 * injecting this class does no disk I/O on the main thread.
 */
class SecurityPaths(noBackupDir: () -> File, databaseFile: () -> File) {
    constructor(noBackupDir: File, databaseFile: File) : this({ noBackupDir }, { databaseFile })

    val noBackupDir: File by lazy(noBackupDir)
    val databaseFile: File by lazy(databaseFile)

    val keysDir: File get() = File(noBackupDir, "keys")

    /** The SQLCipher data key, sealed by [KekAlias.DATABASE]: `0x01 || IV(12) || ct(32) || tag(16)`. */
    val databaseKeyFile: File get() = File(keysDir, "db-dek.v1.bin")

    val vaultDir: File get() = File(noBackupDir, "vault")

    /** Small security state: unlock-failure counter, install id and salt, delete-all marker. */
    val stateDir: File get() = File(noBackupDir, "state")

    /** Files moved aside by a user-confirmed local data reset; never read again by the app. */
    val quarantineDir: File get() = File(noBackupDir, "quarantine")

    /** The database and every SQLite side file (`-wal`, `-shm`, `-journal`). */
    val databaseFiles: List<File>
        get() = listOf(databaseFile) + DATABASE_SIDE_SUFFIXES.map { File(databaseFile.path + it) }

    fun vaultFile(entry: VaultEntry): File = File(vaultDir, "${entry.id}.v1.bin")

    companion object {
        const val DATABASE_NAME: String = "agentle.db"
        val DATABASE_SIDE_SUFFIXES: List<String> = listOf("-wal", "-shm", "-journal")

        fun from(context: Context): SecurityPaths {
            val app = context.applicationContext ?: context
            return SecurityPaths({ app.noBackupFilesDir }, { app.getDatabasePath(DATABASE_NAME) })
        }
    }
}
