package dev.agentle.app.shell

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's dashboards, kept on the device only (app-private preferences, not encrypted: a dashboard holds a title,
 * metric codes, day ranges and the layout of its parts, never a value). The first start adds a sleep and an activity
 * dashboard; the user can remove them like any other.
 */
@Singleton
class DashboardStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("user_dashboards", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(load())

    val dashboards: StateFlow<List<DashboardSpec>> = state.asStateFlow()

    private val mutableEditing = MutableStateFlow<String?>(null)

    /** The id of the dashboard the chat is changing, or null; set by "Change with ChatGPT" (not saved). */
    val editing: StateFlow<String?> = mutableEditing.asStateFlow()

    /** Starts changing the dashboard [id] in the chat; null stops. */
    fun edit(id: String?) {
        mutableEditing.value = id
    }

    init {
        if (!prefs.getBoolean(SEEDED, false)) {
            state.update { saved -> STARTERS + saved.filterNot { d -> STARTERS.any { it.id == d.id } } }
            prefs.edit().putString(KEY, json.encodeToString(state.value)).putBoolean(SEEDED, true).apply()
        }
    }

    /** Adds [spec], or replaces the dashboard with its id in place (a screen changed in the chat keeps its spot). */
    fun add(spec: DashboardSpec) = save { all ->
        if (all.any { it.id == spec.id }) all.map { if (it.id == spec.id) spec else it } else all + spec
    }

    fun remove(id: String) {
        save { it.filterNot { d -> d.id == id } }
        mutableEditing.update { if (it == id) null else it }
    }

    private fun save(change: (List<DashboardSpec>) -> List<DashboardSpec>) {
        state.update(change)
        prefs.edit().putString(KEY, json.encodeToString(state.value)).apply()
    }

    /** The saved dashboards; one that no longer reads (say, a part a later version added) is skipped, not all of them. */
    private fun load(): List<DashboardSpec> {
        val saved = runCatching { prefs.getString(KEY, null)?.let { json.decodeFromString<List<JsonElement>>(it) } }.getOrNull()
        return saved.orEmpty().mapNotNull { runCatching { json.decodeFromJsonElement<DashboardSpec>(it) }.getOrNull() }
    }

    private companion object {
        const val KEY = "dashboards"
        const val SEEDED = "starters_added"
        const val STARTER_DAYS = 7

        val STARTERS = listOf(
            DashboardSpec(
                "starter-sleep",
                "Sleep Dashboard",
                listOf(DashboardMetric.SLEEP_MINUTES, DashboardMetric.RESTING_HEART_RATE, DashboardMetric.HEART_RATE_AVG),
                STARTER_DAYS,
            ),
            DashboardSpec(
                "starter-activity",
                "Activity Dashboard",
                listOf(
                    DashboardMetric.STEPS,
                    DashboardMetric.DISTANCE_METERS,
                    DashboardMetric.ACTIVE_CALORIES,
                    DashboardMetric.EXERCISE_MINUTES,
                ),
                STARTER_DAYS,
            ),
        )
    }
}
