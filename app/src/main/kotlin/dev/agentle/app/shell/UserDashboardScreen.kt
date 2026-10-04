package dev.agentle.app.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/** A dashboard from the sidebar: one card per metric with a bar per day, the latest day and the average. */
@Composable
fun UserDashboardScreen(id: String, viewModel: UserDashboardViewModel, onRemoved: () -> Unit, modifier: Modifier = Modifier) {
    val dashboards by viewModel.store.dashboards.collectAsStateWithLifecycle()
    val spec = dashboards.firstOrNull { it.id == id }
    if (spec == null) {
        Text("This dashboard was removed.", modifier.padding(16.dp))
        return
    }
    val dataFlow = remember(spec) { viewModel.data(spec) }
    val data by dataFlow.collectAsStateWithLifecycle(initialValue = null)
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Last ${spec.days} days, worked out on this phone from your stored data", style = MaterialTheme.typography.labelLarge)
        }
        items(spec.metrics) { metric -> MetricCard(metric, data) }
        item {
            TextButton(onClick = {
                viewModel.store.remove(spec.id)
                onRemoved()
            }) { Text("Remove dashboard") }
        }
    }
}

@Composable
private fun MetricCard(metric: DashboardMetric, data: DashboardData?) {
    val series = data?.series?.firstOrNull { it.metric == metric }
    val values = series?.days?.mapNotNull { it.second }.orEmpty()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(metric.label, style = MaterialTheme.typography.titleMedium)
            when {
                data == null -> Note("Loading…")
                data.unreadable -> Note("The data stored on this phone couldn't be read.")
                series == null || values.isEmpty() -> Note("No data yet. Turn on a source for it under More, Data sources.")
                else -> Chart(series, values)
            }
        }
    }
}

@Composable
private fun Chart(series: MetricSeries, values: List<Long>) {
    val (lastDay, lastValue) = series.days.last { it.second != null }
    Text(
        "${dayLabel(lastDay)}: ${number(lastValue ?: 0L)} · average ${number(values.average().roundToLong())}",
        style = MaterialTheme.typography.bodyMedium,
    )
    Bars(series, Modifier.fillMaxWidth().height(72.dp))
    Row(Modifier.fillMaxWidth()) {
        Text(dayLabel(series.days.first().first), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.weight(1f))
        Text(dayLabel(series.days.last().first), style = MaterialTheme.typography.labelSmall)
    }
}

/** One bar per day, scaled to the largest day; a day without data has no bar. */
@Composable
private fun Bars(series: MetricSeries, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val largest = series.days.maxOf { it.second ?: 0L }.coerceAtLeast(1L)
    Canvas(modifier.semantics { contentDescription = "${series.metric.label} per day" }) {
        val slot = size.width / series.days.size
        val barWidth = slot * BAR_SHARE
        series.days.forEachIndexed { index, (_, value) ->
            if (value != null && value > 0) {
                val barHeight = size.height * value.toFloat() / largest.toFloat()
                drawRect(
                    color,
                    topLeft = Offset(index * slot + (slot - barWidth) / 2, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                )
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val BAR_SHARE = 0.7f

private fun dayLabel(date: LocalDate): String = date.toJavaLocalDate().format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))

private fun number(value: Long): String = NumberFormat.getIntegerInstance().format(value)
