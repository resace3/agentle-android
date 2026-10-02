package dev.agentle.data

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import dev.agentle.core.database.AgentleDatabase
import dev.agentle.core.database.AgentleDatabases
import dev.agentle.core.database.DatabaseProvider
import dev.agentle.core.database.DatabaseState
import dev.agentle.core.database.DatabaseTransactions
import dev.agentle.core.database.TermCache
import dev.agentle.core.model.PersonalEvent
import dev.agentle.data.ingest.FloorSnapshot
import dev.agentle.data.ingest.IngestSession
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

/** An in-memory, unencrypted database over BundledSQLiteDriver for :data Robolectric tests (round 4 correction 4). */
internal class TestDataAccess : AutoCloseable {
    private val queryContext =
        Executors.newSingleThreadExecutor { r -> Thread(r, "data-test-db").apply { isDaemon = true } }.asCoroutineDispatcher()
    val database: AgentleDatabase =
        AgentleDatabases.inMemory(ApplicationProvider.getApplicationContext(), driver(), queryContext)

    /** BundledSQLiteDriver, or AndroidSQLiteDriver when the host cannot load the bundled native library (as CI). */
    private fun driver(): SQLiteDriver = try {
        BundledSQLiteDriver().also { it.open(":memory:").close() }
    } catch (expected: LinkageError) {
        AndroidSQLiteDriver()
    }
    private val transactions = DatabaseTransactions(database, queryContext)

    private val provider = object : DatabaseProvider {
        override val state: StateFlow<DatabaseState> = MutableStateFlow(DatabaseState.Open)

        override suspend fun database(): AgentleDatabase = this@TestDataAccess.database

        override suspend fun transactions(): DatabaseTransactions = this@TestDataAccess.transactions

        override suspend fun close() = Unit

        override suspend fun reopen() = Unit
    }

    val access = DataAccess(provider, TermCache())

    suspend fun insert(events: List<PersonalEvent>, nowMs: Long, accountId: String? = null) {
        access.write {
            val session = IngestSession(this, access.terms, FloorSnapshot.NONE, nowMs)
            session.upsert(events, session.accountTerm(accountId))
            session.finish()
        }
    }

    override fun close() {
        database.close()
        queryContext.close()
    }
}
