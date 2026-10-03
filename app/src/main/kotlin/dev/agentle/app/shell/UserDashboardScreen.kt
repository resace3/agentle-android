package dev.agentle.app.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** A dashboard ChatGPT made: one card per metric with a total per day. */
@Composable
fun UserDashboardScreen(id: String, viewModel: UserDashboardViewModel, onRemoved: () -> Unit, modifier: Modifier = Modifier) {
    val dashboards by viewModel.store.dashboards.collectAsStateWithLifecycle()
    val spec = dashboards.firstOrNull { it.id == id }
    if (spec == null) {
        Text("This dashboard was removed.", modifier.padding(16.dp))
        return
    }
    val seriesFlow = remember(spec) { viewModel.series(spec) }
    val series by seriesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Last ${spec.days} days", style = MaterialTheme.typography.labelLarge) }
        items(series) { metric ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(metric.metric.label, style = MaterialTheme.typography.titleMedium)
                    metric.days.forEach { (date, value) ->
                        Text("$date: ${value ?: "no data"}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item {
            TextButton(onClick = {
                viewModel.store.remove(spec.id)
                onRemoved()
            }) { Text("Remove dashboard") }
        }
    }
}
