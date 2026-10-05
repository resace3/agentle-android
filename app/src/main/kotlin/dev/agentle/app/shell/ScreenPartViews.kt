package dev.agentle.app.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The numbers of the screen being drawn, worked out on the phone by [DashboardCalculator] over the screen's longest day
 * range; null while they load. The metric parts read their own days from it.
 */
val LocalScreenData = compositionLocalOf<DashboardData?> { null }

/** The last [days] days of [metric], oldest first, or null when [DashboardData] has no series for it. */
internal fun DashboardData.window(metric: DashboardMetric, days: Int): MetricSeries? =
    series.firstOrNull { it.metric == metric }?.let { it.copy(days = it.days.takeLast(days)) }

/** A Text part: a heading (`h1`-`h3`), body text or a caption. */
@Composable
internal fun ScreenTextView(text: String, variant: String, modifier: Modifier = Modifier) {
    val type = MaterialTheme.typography
    val style: TextStyle = when (variant) {
        "h1" -> type.headlineSmall
        "h2" -> type.titleLarge
        "h3" -> type.titleMedium
        "caption" -> type.bodySmall
        else -> type.bodyMedium
    }
    val color = if (variant == "caption") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Text(text, modifier, style = style, color = color)
}

/** A MetricTile part: the total, average or latest day of [metric] over the last [days]. */
@Composable
internal fun MetricTileView(metric: DashboardMetric, days: Int, show: String, modifier: Modifier = Modifier) {
    val data = LocalScreenData.current
    val values = data?.window(metric, days)?.days.orEmpty()
    val known = values.mapNotNull { it.second }
    val value = when {
        known.isEmpty() -> null
        show == "total" -> known.sum()
        show == "latest" -> values.last { it.second != null }.second
        else -> known.average().roundToLong()
    }
    val what = when (show) {
        "total" -> "Total"
        "latest" -> "Latest day"
        else -> "Daily average"
    }
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(metric.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            when {
                data == null -> "…"
                value == null -> "–"
                else -> number(value)
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        Text("$what, last $days days", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A TrendChart part: [metric] per day over the last [days] as bars or a line, with the latest day and the average. */
@Composable
internal fun TrendChartView(metric: DashboardMetric, days: Int, style: String, modifier: Modifier = Modifier) {
    val data = LocalScreenData.current
    val series = data?.window(metric, days)
    val values = series?.days?.mapNotNull { it.second }.orEmpty()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(metric.label, style = MaterialTheme.typography.titleMedium)
        when {
            data == null -> Note("Loading…")
            data.unreadable -> Note("The data stored on this phone couldn't be read.")
            series == null || values.isEmpty() -> Note("No data yet. Turn on a source for it under More, Data sources.")
            else -> Chart(series, values, line = style == "line")
        }
        Text("Last $days days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Chart(series: MetricSeries, values: List<Long>, line: Boolean) {
    val (lastDay, lastValue) = series.days.last { it.second != null }
    Text(
        "${dayLabel(lastDay)}: ${number(lastValue ?: 0L)} · average ${number(values.average().roundToLong())}",
        style = MaterialTheme.typography.bodyMedium,
    )
    val chartModifier = Modifier.fillMaxWidth().height(72.dp).semantics { contentDescription = "${series.metric.label} per day" }
    if (line) Line(series, chartModifier) else Bars(series, chartModifier)
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
    Canvas(modifier) {
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

/** A line through the days, scaled between the smallest and the largest day; a day without data breaks the line. */
@Composable
private fun Line(series: MetricSeries, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val known = series.days.mapNotNull { it.second }
    val low = known.min()
    val span = (known.max() - low).coerceAtLeast(1L).toFloat()
    Canvas(modifier) {
        val slot = size.width / series.days.size
        val width = LINE_WIDTH.toPx()
        fun point(index: Int, value: Long) = Offset(index * slot + slot / 2, size.height - size.height * (value - low) / span)
        series.days.forEachIndexed { index, (_, value) ->
            if (value != null) {
                val next = series.days.getOrNull(index + 1)?.second
                if (next != null) drawLine(color, point(index, value), point(index + 1, next), strokeWidth = width)
                drawCircle(color, radius = width, center = point(index, value))
            }
        }
    }
}

@Composable
internal fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val BAR_SHARE = 0.7f
private val LINE_WIDTH = 2.dp

private fun dayLabel(date: LocalDate): String = date.toJavaLocalDate().format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))

private fun number(value: Long): String = NumberFormat.getIntegerInstance().format(value)
