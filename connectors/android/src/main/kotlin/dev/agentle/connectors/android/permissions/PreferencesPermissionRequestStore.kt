package dev.agentle.connectors.android.permissions

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import dev.agentle.connectors.api.PermissionRequestStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Fallback [PermissionRequestStore] used only while ANDROID-DATA binds none: a private SharedPreferences file in this
 * module (no personal data: permission names and special-access ids only). It keeps the "requested once" flags and the
 * last UI-derived verdicts across process restarts, so a background pass in a fresh process still keeps
 * DENIED_PERMANENTLY (red team testing-build-15). ANDROID-DATA's DataStore binding replaces it (docs/ARCHITECTURE.md
 * §5.2); this file is then never read again.
 */
public class PreferencesPermissionRequestStore(context: Context, private val io: CoroutineDispatcher) : PermissionRequestStore {
    private val prefs: SharedPreferences by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
    private val lock = Mutex()

    override suspend fun requestedPermissions(): Set<String> = readSet(KEY_REQUESTED)

    override suspend fun markRequested(permissions: Collection<String>): Unit = edit(KEY_REQUESTED) { it + permissions }

    override suspend fun settingsVisited(): Set<String> = readSet(KEY_VISITED)

    override suspend fun markSettingsVisited(specialAccess: String): Unit = edit(KEY_VISITED) { it + specialAccess }

    override suspend fun permanentlyDenied(): Set<String> = readSet(KEY_PERMANENT)

    override suspend fun recordUiVerdicts(verdicts: Map<String, Boolean>): Unit = edit(KEY_PERMANENT) { current ->
        current + verdicts.filterValues { it }.keys - verdicts.filterValues { !it }.keys
    }

    private suspend fun readSet(key: String): Set<String> = withContext(io) { prefs.getStringSet(key, emptySet())?.toSet().orEmpty() }

    // commit() on the IO dispatcher: the caller needs the flag stored before the next pass reads it.
    @SuppressLint("ApplySharedPref")
    private suspend fun edit(key: String, transform: (Set<String>) -> Set<String>) = lock.withLock {
        withContext(io) {
            val next = transform(prefs.getStringSet(key, emptySet())?.toSet().orEmpty())
            prefs.edit().putStringSet(key, next).commit()
        }
        Unit
    }

    private companion object {
        const val FILE = "agentle_collectors_permission_requests"
        const val KEY_REQUESTED = "requested"
        const val KEY_VISITED = "settings_visited"
        const val KEY_PERMANENT = "permanently_denied"
    }
}
