package dev.agentle.core.database.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.agentle.core.database.DatabaseFactory
import dev.agentle.core.database.DatabaseMaintenance
import dev.agentle.core.database.DatabaseProvider
import dev.agentle.core.database.DefaultDatabaseProvider
import dev.agentle.core.database.LocalDataReset
import dev.agentle.core.database.SqlCipherDatabaseFactory
import dev.agentle.core.database.TermCache
import dev.agentle.core.security.DatabaseKeyManager
import dev.agentle.core.security.SecurityPaths
import dev.agentle.core.security.UnlockFailureTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

/** The database's own single-threaded context: every query and transaction runs on it. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DatabaseQueryContext

/** Blocking file and key work of the data layer (unwrapping the key, files of media and markers). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DataIoContext

/** The encrypted database: the SQLCipher factory, the lazy provider, the term cache, maintenance and the reset flow. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    @DatabaseQueryContext
    fun queryContext(): CoroutineContext =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "agentle-db").apply { isDaemon = true } }
            .asCoroutineDispatcher()

    @Provides
    @Singleton
    @DataIoContext
    fun ioContext(): CoroutineContext = Dispatchers.IO

    @Provides
    @Singleton
    fun databaseFactory(@ApplicationContext context: Context, paths: SecurityPaths): DatabaseFactory =
        SqlCipherDatabaseFactory(context, paths)

    @Provides
    @Singleton
    fun databaseProvider(
        factory: DatabaseFactory,
        keys: DatabaseKeyManager,
        tracker: UnlockFailureTracker,
        @DatabaseQueryContext queryContext: CoroutineContext,
        @DataIoContext ioContext: CoroutineContext,
    ): DatabaseProvider = DefaultDatabaseProvider(factory, keys, tracker, queryContext, ioContext)

    @Provides
    @Singleton
    fun termCache(): TermCache = TermCache()

    @Provides
    @Singleton
    fun maintenance(
        @ApplicationContext context: Context,
        provider: DatabaseProvider,
        paths: SecurityPaths,
        @DataIoContext ioContext: CoroutineContext,
    ): DatabaseMaintenance = DatabaseMaintenance(context, provider, paths, ioContext)

    @Provides
    @Singleton
    fun localDataReset(
        provider: DatabaseProvider,
        keys: DatabaseKeyManager,
        tracker: UnlockFailureTracker,
        paths: SecurityPaths,
        @DataIoContext ioContext: CoroutineContext,
    ): LocalDataReset = LocalDataReset(provider, keys, tracker, paths, ioContext)
}
