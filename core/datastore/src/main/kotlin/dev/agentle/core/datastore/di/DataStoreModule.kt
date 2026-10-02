package dev.agentle.core.datastore.di

import android.content.Context
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.agentle.core.datastore.AiConsentStore
import dev.agentle.core.datastore.CollectionChoiceStore
import dev.agentle.core.datastore.ConsentVocabulary
import dev.agentle.core.datastore.DataStoreAiConsentStore
import dev.agentle.core.datastore.DataStoreSettingsStore
import dev.agentle.core.datastore.PermissionFlagStore
import dev.agentle.core.datastore.SettingsStore
import dev.agentle.core.datastore.StoreDiagnostics
import dev.agentle.core.datastore.StoreFiles
import dev.agentle.core.security.InstallIdProvider
import dev.agentle.core.time.AgentleClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.Optional
import javax.inject.Qualifier
import javax.inject.Singleton

/** Whether debug options are readable and writable: true in the fake flavor only. Bound by the app; absent means false. */
data class DebugOptionsPolicy(val allowed: Boolean)

/** The scope the stores' file I/O runs in. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StoreScope

/** Bindings other modules may supply: diagnostics (`:data`) and the debug flag (the app). */
@Module
@InstallIn(SingletonComponent::class)
interface DataStoreOptionalBindings {
    @BindsOptionalOf
    fun diagnostics(): StoreDiagnostics

    @BindsOptionalOf
    fun debugOptionsPolicy(): DebugOptionsPolicy
}

/**
 * The typed stores, all in `noBackupFilesDir/datastore/`. [ConsentVocabulary] (the known AI categories, purposes and
 * the current terms version) is bound by `:data`.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    @Provides
    @Singleton
    fun storeFiles(@ApplicationContext context: Context): StoreFiles = StoreFiles.inNoBackupDir { context.noBackupFilesDir }

    @Provides
    @Singleton
    @StoreScope
    fun storeScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun settingsStore(
        files: StoreFiles,
        @StoreScope scope: CoroutineScope,
        debugOptions: Optional<DebugOptionsPolicy>,
        diagnostics: Optional<StoreDiagnostics>,
    ): SettingsStore = DataStoreSettingsStore.create(
        file = { files.settings },
        scope = scope,
        debugOptionsAllowed = debugOptions.map { it.allowed }.orElse(false),
        diagnostics = diagnostics.orElse(StoreDiagnostics.NONE),
    )

    @Provides
    @Singleton
    fun aiConsentStore(
        files: StoreFiles,
        @StoreScope scope: CoroutineScope,
        vocabulary: ConsentVocabulary,
        installIds: InstallIdProvider,
        clock: AgentleClock,
        diagnostics: Optional<StoreDiagnostics>,
    ): AiConsentStore = DataStoreAiConsentStore.create(
        file = { files.aiConsent },
        scope = scope,
        vocabulary = vocabulary,
        installId = installIds::installId,
        clock = clock,
        diagnostics = diagnostics.orElse(StoreDiagnostics.NONE),
    )

    @Provides
    fun permissionFlags(settings: SettingsStore): PermissionFlagStore = PermissionFlagStore(settings)

    @Provides
    fun collectionChoices(settings: SettingsStore): CollectionChoiceStore = CollectionChoiceStore(settings)
}
