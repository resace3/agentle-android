package dev.agentle.core.datastore

import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException

/** The app settings (`settings.v1.json`). Reads never throw: a file that cannot be read yields the defaults. */
interface SettingsStore {
    /** The current settings, sanitized ([AppSettings.sanitized]). */
    val settings: Flow<AppSettings>

    suspend fun current(): AppSettings

    /** Applies [transform] atomically. False when the file could not be written (nothing changed). */
    suspend fun update(transform: (AppSettings) -> AppSettings): Boolean
}

/**
 * [SettingsStore] on a typed DataStore. A corrupted file is reset to the defaults and reported (a diagnostic with the
 * error class only); an I/O error on read yields the defaults for that read. Debug options are visible and writable
 * only when [debugOptionsAllowed] (the fake flavor); otherwise they read as defaults and writes keep the stored value.
 */
class DataStoreSettingsStore internal constructor(
    private val store: DataStore<AppSettings>,
    private val debugOptionsAllowed: Boolean,
    private val diagnostics: StoreDiagnostics,
) : SettingsStore {
    override val settings: Flow<AppSettings> = store.data
        .catch { error ->
            if (error !is IOException) throw error
            diagnostics.onIoFailure(NAME, "read", error.javaClass.simpleName)
            emit(AppSettings())
        }
        .map { visible(it.sanitized()) }

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings): Boolean = try {
        store.updateData { stored ->
            val next = transform(visible(stored.sanitized())).sanitized()
            if (debugOptionsAllowed) next else next.copy(debug = stored.debug)
        }
        true
    } catch (expected: IOException) {
        diagnostics.onIoFailure(NAME, "write", expected.javaClass.simpleName)
        false
    }

    private fun visible(settings: AppSettings): AppSettings = if (debugOptionsAllowed) settings else settings.copy(debug = DebugOptions())

    companion object {
        const val NAME: String = "settings"

        fun create(
            file: () -> File,
            scope: CoroutineScope,
            debugOptionsAllowed: Boolean,
            diagnostics: StoreDiagnostics = StoreDiagnostics.NONE,
        ): DataStoreSettingsStore = DataStoreSettingsStore(
            jsonDataStore(NAME, file, AppSettings.serializer(), AppSettings(), scope, diagnostics) { AppSettings() },
            debugOptionsAllowed,
            diagnostics,
        )

        /** For tests: a store over any [DataStore] (a fault-injecting one, for example). */
        internal fun over(store: DataStore<AppSettings>, debugOptionsAllowed: Boolean, diagnostics: StoreDiagnostics) =
            DataStoreSettingsStore(store, debugOptionsAllowed, diagnostics)
    }
}

/**
 * The Permission Center's persistent flags (docs/research/01 §5.2, §5.3): runtime permissions requested at least once
 * and special-access Settings screens the user opened and came back from. Same operations as the collectors'
 * `PermissionRequestStore` port, so the adapter is a delegation.
 */
class PermissionFlagStore(private val settings: SettingsStore) {
    suspend fun requestedPermissions(): Set<String> = settings.current().permissions.requested

    suspend fun markRequested(permissions: Collection<String>): Boolean = settings.update {
        it.copy(permissions = it.permissions.copy(requested = it.permissions.requested + permissions))
    }

    suspend fun settingsVisited(): Set<String> = settings.current().permissions.settingsVisited

    suspend fun markSettingsVisited(specialAccess: String): Boolean = settings.update {
        it.copy(permissions = it.permissions.copy(settingsVisited = it.permissions.settingsVisited + specialAccess))
    }
}

/** The collection choices collectors read (the collectors' `CollectionSettingsStore` port, minus known places). */
class CollectionChoiceStore(private val settings: SettingsStore) {
    val choices: Flow<CollectionChoices> = settings.settings.map { it.collection }

    suspend fun update(transform: (CollectionChoices) -> CollectionChoices): Boolean =
        settings.update { it.copy(collection = transform(it.collection)) }
}
