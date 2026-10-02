package dev.agentle.core.database

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/**
 * Databases for Robolectric tests (round 4 correction 4): Room over BundledSQLiteDriver, unencrypted (SQLCipher's native
 * library is built for Android only). AndroidSQLiteDriver is used only if the host cannot load the bundled native
 * library; [driverName] records which one ran, and the scale test writes it into its results.
 */
object TestDatabases {
    val queryContext: CoroutineContext by lazy {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "test-db").apply { isDaemon = true } }.asCoroutineDispatcher()
    }

    var driverName: String = "unknown"
        private set

    val driver: SQLiteDriver by lazy {
        try {
            BundledSQLiteDriver().also { it.open(":memory:").close() }.also { driverName = "BundledSQLiteDriver" }
        } catch (expected: LinkageError) {
            driverName = "AndroidSQLiteDriver (bundled native library not loadable on this host)"
            AndroidSQLiteDriver()
        }
    }

    fun inMemory(): AgentleDatabase = AgentleDatabases.inMemory(ApplicationProvider.getApplicationContext(), driver, queryContext)

    fun transactions(database: AgentleDatabase): DatabaseTransactions = DatabaseTransactions(database, queryContext)
}
