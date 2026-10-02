package dev.agentle.core.database

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * Builds [AgentleDatabase] over an SQLite driver: SQLCipher in every app variant, an unencrypted driver in
 * Robolectric tests. Never with destructive migration fallback.
 */
object AgentleDatabases {
    fun build(context: Context, file: File, driver: SQLiteDriver, queryContext: CoroutineContext): AgentleDatabase =
        Room.databaseBuilder<AgentleDatabase>(context.applicationContext, file.absolutePath)
            .setDriver(driver)
            .setQueryCoroutineContext(queryContext)
            .addCallback(StorageCallback)
            .addMigrations(*Migrations.ALL)
            .build()

    /** An in-memory database for tests (Robolectric, with BundledSQLiteDriver or AndroidSQLiteDriver). */
    fun inMemory(context: Context, driver: SQLiteDriver, queryContext: CoroutineContext): AgentleDatabase =
        Room.inMemoryDatabaseBuilder<AgentleDatabase>(context.applicationContext)
            .setDriver(driver)
            .setQueryCoroutineContext(queryContext)
            .addCallback(StorageCallback)
            .build()
}

/**
 * Storage settings on every open (round 2 correction 8): WAL, `synchronous = NORMAL`, a WAL size limit, a busy timeout
 * and `secure_delete = ON`, set explicitly because SQLCipher does not take Room's defaults. On creation the engine
 * bookkeeping rows are seeded, including a random database generation.
 */
internal object StorageCallback : RoomDatabase.Callback() {
    const val JOURNAL_SIZE_LIMIT_BYTES: Long = 4L * 1024 * 1024
    const val BUSY_TIMEOUT_MS: Int = 5_000

    override suspend fun onCreate(connection: SQLiteConnection) {
        insertState(connection, EngineStateKeys.DB_GENERATION, null, UUID.randomUUID().toString())
        insertState(connection, EngineStateKeys.CHANGE_SEQ, 0L, null)
        insertState(connection, EngineStateKeys.DATA_EPOCH, 0L, null)
        insertState(connection, EngineStateKeys.JITAI_WATERMARK, 0L, null)
        insertState(connection, EngineStateKeys.JITAI_DIRTY, 0L, null)
    }

    override suspend fun onOpen(connection: SQLiteConnection) {
        connection.execSQL("PRAGMA journal_mode = WAL")
        connection.execSQL("PRAGMA synchronous = NORMAL")
        connection.execSQL("PRAGMA journal_size_limit = $JOURNAL_SIZE_LIMIT_BYTES")
        connection.execSQL("PRAGMA busy_timeout = $BUSY_TIMEOUT_MS")
        connection.execSQL("PRAGMA secure_delete = ON")
    }

    private fun insertState(connection: SQLiteConnection, name: String, intValue: Long?, textValue: String?) {
        val statement = connection.prepare("INSERT OR IGNORE INTO engine_state(name, int_value, text_value) VALUES (?, ?, ?)")
        try {
            statement.bindText(1, name)
            if (intValue == null) statement.bindNull(2) else statement.bindLong(2, intValue)
            if (textValue == null) statement.bindNull(3) else statement.bindText(3, textValue)
            statement.step()
        } finally {
            statement.close()
        }
    }
}

/**
 * Schema migrations. Version 1 is the first schema, so there are none yet; every later version adds a migration that
 * only adds columns, tables or indexes (round 2 correction 10) and a test in `MigrationTest`.
 */
object Migrations {
    val ALL: Array<Migration> = emptyArray()
}
