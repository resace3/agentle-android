package dev.agentle.data

import dev.agentle.core.common.AppError
import dev.agentle.core.common.AppException
import dev.agentle.core.database.AgentleDatabase
import dev.agentle.core.database.DatabaseProvider
import dev.agentle.core.database.DatabaseTransactions
import dev.agentle.core.database.LocalDataUnavailableException
import dev.agentle.core.database.SqlScope
import dev.agentle.core.database.TermCache

/** One open transaction: raw SQL on its connection and the DAOs, which join it. */
internal class Tx(val sql: SqlScope, val db: AgentleDatabase)

/**
 * The only way `:data` reaches the database. Every call opens the database lazily through [DatabaseProvider] and
 * turns "local data unavailable" into [AppException] with [AppError.DatabaseError] (reason code only), so no caller ever
 * sees a type of `:core:database`. Writes run in the serialized writer (one IMMEDIATE transaction under one mutex);
 * reads run in one read transaction.
 */
internal class DataAccess(private val provider: DatabaseProvider, val terms: TermCache) {
    @Volatile private var registered: DatabaseTransactions? = null

    suspend fun database(): AgentleDatabase = guarded { provider.database() }

    suspend fun <T> write(block: suspend Tx.() -> T): T {
        val db = database()
        return transactions().write { Tx(this, db).block() }
    }

    suspend fun <T> read(block: suspend Tx.() -> T): T {
        val db = database()
        return transactions().read { Tx(this, db).block() }
    }

    /** Statements SQLite refuses inside a transaction (`wal_checkpoint`), under the write mutex. */
    suspend fun <T> outsideTransaction(block: suspend SqlScope.() -> T): T = transactions().outsideTransaction(block)

    suspend fun transactions(): DatabaseTransactions {
        val current = guarded { provider.transactions() }
        if (registered !== current) {
            synchronized(this) {
                if (registered !== current) {
                    // Terms created inside a rolled-back transaction must not survive in the cache.
                    current.addRollbackListener { terms.invalidate() }
                    terms.invalidate()
                    registered = current
                }
            }
        }
        return current
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (expected: LocalDataUnavailableException) {
        throw unavailable(expected.reason.name)
    }

    companion object {
        const val UNAVAILABLE_PREFIX: String = "local_data_unavailable:"

        /** The error every port reports while the database cannot be opened. */
        fun unavailable(reason: String): AppException = AppException(AppError.DatabaseError(UNAVAILABLE_PREFIX + reason))
    }
}
