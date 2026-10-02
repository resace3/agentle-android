package dev.agentle.core.database

import android.content.Context
import android.database.SQLException
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import dev.agentle.core.security.SecurityPaths
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

/** Why [DatabaseMaintenance.vacuumIfAllowed] did or did not run. */
enum class VacuumOutcome { DONE, NOT_CHARGING_AND_IDLE, NOT_ENOUGH_SPACE, FAILED }

/**
 * WAL checkpoints after deletions and the rare VACUUM (round 2 correction 6). Never touches the files of an open
 * database: `-wal` and `-shm` are only ever truncated through SQLite itself.
 */
class DatabaseMaintenance(
    private val context: Context,
    private val provider: DatabaseProvider,
    private val paths: SecurityPaths,
    private val ioContext: CoroutineContext,
) {
    /**
     * `PRAGMA wal_checkpoint(TRUNCATE)`, retried with a short backoff while readers keep the WAL busy, so deleted pages
     * leave the WAL file too. Returns true once a checkpoint completed without being blocked.
     */
    suspend fun checkpoint(attempts: Int = CHECKPOINT_ATTEMPTS): Boolean {
        val transactions = provider.transactions()
        repeat(attempts) { attempt ->
            val busy = transactions.outsideTransaction {
                query("PRAGMA wal_checkpoint(TRUNCATE)") { row -> row.longOrNull(0) ?: 0L }.firstOrNull() ?: 0L
            }
            if (busy == 0L) return true
            delay((CHECKPOINT_BACKOFF_MS * (attempt + 1)).milliseconds)
        }
        return false
    }

    /**
     * VACUUM, only while the device is charging and idle and free space is at least [FREE_SPACE_FACTOR] times the
     * database size; temporary files go to the app's cache directory (`temp_store = FILE`, `SQLITE_TMPDIR`), never to
     * memory, so a large database cannot exhaust the heap.
     */
    suspend fun vacuumIfAllowed(): VacuumOutcome {
        if (!chargingAndIdle()) return VacuumOutcome.NOT_CHARGING_AND_IDLE
        val enoughSpace = withContext(ioContext) {
            val size = paths.databaseFiles.sumOf { if (it.exists()) it.length() else 0L }
            val free = paths.databaseFile.parentFile?.let { StatFs(it.path).availableBytes } ?: 0L
            free >= (size * FREE_SPACE_FACTOR).toLong()
        }
        if (!enoughSpace) return VacuumOutcome.NOT_ENOUGH_SPACE
        return try {
            Os.setenv("SQLITE_TMPDIR", tempDir().path, true)
            provider.transactions().outsideTransaction {
                execute("PRAGMA temp_store = FILE")
                execute("VACUUM")
                execute("PRAGMA temp_store = DEFAULT")
            }
            checkpoint()
            VacuumOutcome.DONE
        } catch (expected: ErrnoException) {
            VacuumOutcome.FAILED
        } catch (expected: SQLException) {
            // SQLite errors of every driver (androidx.sqlite.SQLiteException is this class on Android).
            VacuumOutcome.FAILED
        }
    }

    private fun tempDir(): File = File(context.cacheDir, "sqlite-tmp").apply { mkdirs() }

    private fun chargingAndIdle(): Boolean {
        val battery = context.getSystemService(BatteryManager::class.java) ?: return false
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return battery.isCharging && power.isDeviceIdleMode
    }

    companion object {
        const val FREE_SPACE_FACTOR: Double = 2.2
        const val CHECKPOINT_ATTEMPTS: Int = 5
        private const val CHECKPOINT_BACKOFF_MS = 200L
    }
}
