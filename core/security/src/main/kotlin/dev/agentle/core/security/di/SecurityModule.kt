package dev.agentle.core.security.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.agentle.core.security.AndroidBootCountSource
import dev.agentle.core.security.AndroidKeystoreKekProvider
import dev.agentle.core.security.BootCountSource
import dev.agentle.core.security.CryptoEraser
import dev.agentle.core.security.DatabaseKeyManager
import dev.agentle.core.security.InstallIdProvider
import dev.agentle.core.security.KeyEncryptionKeyProvider
import dev.agentle.core.security.SecretVault
import dev.agentle.core.security.SecurityPaths
import dev.agentle.core.security.TinkSecretVault
import dev.agentle.core.security.UnlockFailureTracker
import dev.agentle.core.time.AgentleClock
import javax.inject.Singleton

/**
 * The Android Keystore key-encryption keys, in a module of their own so tests replace it with
 * `@TestInstallIn(replaces = [KekModule::class])` and an in-memory provider.
 */
@Module
@InstallIn(SingletonComponent::class)
object KekModule {
    @Provides
    @Singleton
    fun keyEncryptionKeys(): KeyEncryptionKeyProvider = AndroidKeystoreKekProvider()
}

/** Key management, the token vault, install identity and crypto-erasure. The app binds [AgentleClock]. */
@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {
    @Provides
    @Singleton
    fun securityPaths(@ApplicationContext context: Context): SecurityPaths = SecurityPaths.from(context)

    @Provides
    @Singleton
    fun bootCounts(@ApplicationContext context: Context): BootCountSource = AndroidBootCountSource(context)

    @Provides
    @Singleton
    fun databaseKeyManager(kek: KeyEncryptionKeyProvider, paths: SecurityPaths): DatabaseKeyManager = DatabaseKeyManager(kek, paths)

    @Provides
    @Singleton
    fun unlockFailureTracker(paths: SecurityPaths, bootCounts: BootCountSource, clock: AgentleClock): UnlockFailureTracker =
        UnlockFailureTracker(paths, bootCounts, clock)

    @Provides
    @Singleton
    fun secretVault(@ApplicationContext context: Context, kek: KeyEncryptionKeyProvider, paths: SecurityPaths): SecretVault =
        TinkSecretVault(kek, paths, context.packageName)

    @Provides
    @Singleton
    fun installIdProvider(paths: SecurityPaths): InstallIdProvider = InstallIdProvider(paths)

    @Provides
    @Singleton
    fun cryptoEraser(kek: KeyEncryptionKeyProvider, paths: SecurityPaths): CryptoEraser = CryptoEraser(kek, paths)
}
