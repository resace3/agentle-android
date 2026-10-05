package dev.agentle.app.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentle.ai.api.screen.ScreenRules

/**
 * A dashboard from the sidebar, drawn by Google's A2UI renderer from its saved screen ([DashboardSpec.layout]; one card
 * per metric for a dashboard without one). "Change with ChatGPT" marks it for change and calls [onChange] to open the
 * chat, where the next questions change it.
 */
@Composable
fun UserDashboardScreen(
    id: String,
    viewModel: UserDashboardViewModel,
    onChange: () -> Unit,
    onRemoved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dashboards by viewModel.store.dashboards.collectAsStateWithLifecycle()
    val spec = dashboards.firstOrNull { it.id == id }
    if (spec == null) {
        Text("This dashboard was removed.", modifier.padding(16.dp))
        return
    }
    val layout = remember(spec) { spec.layout() }
    // A saved screen is checked again before it is drawn, as a guard on the app's own stored copy.
    val passes = remember(spec) { spec.screen?.let { ScreenRules.recheck(it).isEmpty() } ?: true }
    LaunchedEffect(spec.id, layout, passes) { if (passes) viewModel.screens.show(spec.id, layout) }
    val dataFlow = remember(spec) { viewModel.data(spec) }
    val data by dataFlow.collectAsStateWithLifecycle(initialValue = null)
    val surfaces by viewModel.screens.surfaces.collectAsStateWithLifecycle()
    val failures by viewModel.screens.failures.collectAsStateWithLifecycle()
    val surface = surfaces.firstOrNull { it.id == spec.id }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Worked out on this phone from your stored data", style = MaterialTheme.typography.labelLarge)
        CompositionLocalProvider(LocalScreenData provides data) {
            when {
                !passes -> Note("This saved screen didn't pass Agentle's checks, so it isn't drawn. Remove it or change it with ChatGPT.")
                surface != null -> A2uiScreen(surface, Modifier.fillMaxWidth())
                failures[spec.id] != null -> Note("This screen couldn't be drawn (${failures[spec.id]}).")
                else -> Note("Loading…")
            }
        }
        Row {
            TextButton(onClick = {
                viewModel.store.edit(spec.id)
                onChange()
            }) { Text("Change with ChatGPT") }
            TextButton(onClick = {
                viewModel.store.remove(spec.id)
                onRemoved()
            }) { Text("Remove dashboard") }
        }
    }
}
