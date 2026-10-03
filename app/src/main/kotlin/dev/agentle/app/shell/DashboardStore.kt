package dev.agentle.app.shell

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** The user's dashboards, kept on the device only (app-private preferences). */
@Singleton
class DashboardStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("user_dashboards", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(load())

    val dashboards: StateFlow<List<DashboardSpec>> = state.asStateFlow()

    fun add(spec: DashboardSpec) = save { it.filterNot { d -> d.id == spec.id } + spec }

    fun remove(id: String) = save { it.filterNot { d -> d.id == id } }

    private fun save(change: (List<DashboardSpec>) -> List<DashboardSpec>) {
        state.update(change)
        prefs.edit().putString(KEY, json.encodeToString(state.value)).apply()
    }

    private fun load(): List<DashboardSpec> =
        runCatching { prefs.getString(KEY, null)?.let { json.decodeFromString<List<DashboardSpec>>(it) } }.getOrNull().orEmpty()

    private companion object {
        const val KEY = "dashboards"
    }
}
