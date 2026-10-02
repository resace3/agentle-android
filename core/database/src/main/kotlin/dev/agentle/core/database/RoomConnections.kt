package dev.agentle.core.database

import androidx.room3.PooledConnection
import androidx.room3.deferredTransaction
import androidx.room3.immediateTransaction
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/*
 * The only file that touches Room 3's connection and transaction API (useWriterConnection, useReaderConnection,
 * immediateTransaction, deferredTransaction, PooledConnection.usePrepared). Those signatures were taken from the Room
 * 2.7 KMP API that Room 3 continues; they are UNVERIFIED against the Room 3.0.3 sources, which could not be read here.
 */

/** One row of a raw query, read by column index. */
class SqlRow internal constructor(private val statement: SQLiteStatement) {
    val columnCount: Int get() = statement.getColumnCount()

    fun columnName(index: Int): String = statement.getColumnName(index)

    fun isNull(index: Int): Boolean = statement.isNull(index)

    fun long(index: Int): Long = statement.getLong(index)

    fun longOrNull(index: Int): Long? = if (statement.isNull(index)) null else statement.getLong(index)

    fun double(index: Int): Double = statement.getDouble(index)

    fun text(index: Int): String = statement.getText(index)

    fun textOrNull(index: Int): String? = if (statement.isNull(index)) null else statement.getText(index)

    fun blobOrNull(index: Int): ByteArray? = if (statement.isNull(index)) null else statement.getBlob(index)

    /** The column as a string whatever its storage class (for scans that look at every value). */
    fun asText(index: Int): String? = if (statement.isNull(index)) null else statement.getText(index)
}

/**
 * Raw SQL on one connection, usually inside one transaction: dynamic predicates of deletions, retention and
 * maintenance. Arguments bind as Long, Int, Double, Float, Boolean (0/1), String, ByteArray or null.
 */
class SqlScope internal constructor(private val connection: PooledConnection) {
    /** Runs [sql] to completion and returns how many result rows it produced (PRAGMAs return one). */
    suspend fun execute(sql: String, vararg args: Any?): Int = connection.usePrepared(sql) { statement ->
        bindAll(statement, args)
        var rows = 0
        while (statement.step()) rows++
        rows
    }

    /** Rows changed by the last INSERT, UPDATE or DELETE on this connection. */
    suspend fun changes(): Long = queryLong("SELECT changes()") ?: 0L

    suspend fun queryLong(sql: String, vararg args: Any?): Long? = connection.usePrepared(sql) { statement ->
        bindAll(statement, args)
        if (statement.step() && !statement.isNull(0)) statement.getLong(0) else null
    }

    suspend fun queryText(sql: String, vararg args: Any?): String? = connection.usePrepared(sql) { statement ->
        bindAll(statement, args)
        if (statement.step() && !statement.isNull(0)) statement.getText(0) else null
    }

    suspend fun queryLongs(sql: String, vararg args: Any?): List<Long> = query(sql, *args) { it.long(0) }

    suspend fun queryTexts(sql: String, vararg args: Any?): List<String> = query(sql, *args) { it.text(0) }

    suspend fun <T> query(sql: String, vararg args: Any?, mapper: (SqlRow) -> T): List<T> = connection.usePrepared(sql) { statement ->
        bindAll(statement, args)
        val row = SqlRow(statement)
        val out = ArrayList<T>()
        while (statement.step()) out += mapper(row)
        out
    }

    private fun bindAll(statement: SQLiteStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, value ->
            val index = i + 1
            when (value) {
                null -> statement.bindNull(index)
                is Long -> statement.bindLong(index, value)
                is Int -> statement.bindLong(index, value.toLong())
                is Short -> statement.bindLong(index, value.toLong())
                is Boolean -> statement.bindLong(index, if (value) 1L else 0L)
                is Double -> statement.bindDouble(index, value)
                is Float -> statement.bindDouble(index, value.toDouble())
                is String -> statement.bindText(index, value)
                is ByteArray -> statement.bindBlob(index, value)
                else -> throw IllegalArgumentException("unsupported SQL argument type ${value::class.simpleName}")
            }
        }
    }
}

/**
 * Transactions on [database] (round 2 corrections 4 and 8): every write runs under one process-wide [Mutex] in one
 * IMMEDIATE transaction, so there is exactly one serialized writer; reads run in one DEFERRED transaction and see one
 * consistent state. A write started inside another write joins it instead of deadlocking on the mutex. All work runs
 * in [queryContext], the database's own single-threaded context, so the thread-bound transactions of the framework
 * SQLite stack (SQLCipher, AndroidSQLiteDriver) always stay on their thread.
 */
class DatabaseTransactions(private val database: AgentleDatabase, private val queryContext: CoroutineContext) {
    private val writeMutex = Mutex()
    private val rollbackListeners = CopyOnWriteArrayList<() -> Unit>()

    /** Called after a write transaction rolled back (caches of rows written inside it drop them). */
    fun addRollbackListener(listener: () -> Unit) {
        rollbackListeners += listener
    }

    suspend fun <T> write(block: suspend SqlScope.() -> T): T {
        currentCoroutineContext()[WriteTransaction]?.let { return it.scope.block() }
        return withContext(queryContext) {
            writeMutex.withLock {
                try {
                    database.useWriterConnection { transactor ->
                        transactor.immediateTransaction {
                            val scope = SqlScope(this)
                            withContext(WriteTransaction(scope)) { scope.block() }
                        }
                    }
                } catch (e: Throwable) {
                    rollbackListeners.forEach { it() }
                    throw e
                }
            }
        }
    }

    suspend fun <T> read(block: suspend SqlScope.() -> T): T {
        currentCoroutineContext()[WriteTransaction]?.let { return it.scope.block() }
        return withContext(queryContext) {
            database.useReaderConnection { transactor ->
                transactor.deferredTransaction { SqlScope(this).block() }
            }
        }
    }

    /**
     * Statements that SQLite refuses inside a transaction (`PRAGMA wal_checkpoint`, `VACUUM`), on the writer
     * connection and under the write mutex.
     */
    suspend fun <T> outsideTransaction(block: suspend SqlScope.() -> T): T {
        check(currentCoroutineContext()[WriteTransaction] == null) { "already inside a write transaction" }
        return withContext(queryContext) {
            writeMutex.withLock {
                database.useWriterConnection { transactor -> SqlScope(transactor).block() }
            }
        }
    }

    private class WriteTransaction(val scope: SqlScope) : AbstractCoroutineContextElement(WriteTransaction) {
        companion object Key : CoroutineContext.Key<WriteTransaction>
    }
}
