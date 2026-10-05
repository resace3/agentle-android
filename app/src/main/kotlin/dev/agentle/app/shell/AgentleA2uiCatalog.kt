package dev.agentle.app.shell

import androidx.a2ui.compose.runtime.A2uiComponentProperties
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.runtime.A2uiComponentState
import androidx.a2ui.compose.runtime.A2uiProperty
import androidx.a2ui.compose.runtime.observeA2uiComponentState
import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.agentle.ai.api.screen.ScreenCatalog

/**
 * Agentle's parts ([ScreenCatalog]) for Google's A2UI renderer. A screen may use these and nothing else: no images,
 * links, web content, inputs or actions. Each part reads only its own literal fields; a metric part draws numbers the
 * phone computed ([LocalScreenData]), never numbers from the screen.
 */
internal object AgentleA2uiCatalog {
    val components: List<A2uiComponent> = listOf(
        ColumnPartComponent,
        RowPartComponent,
        CardPartComponent,
        TextPartComponent,
        DividerPartComponent,
        MetricTileComponent,
        TrendChartComponent,
    )

    val catalog: A2uiCatalog = A2uiCatalog(catalogId = ScreenCatalog.ID, components = components)
}

/** A child part as A2UI resolved it; a part that failed A2UI's own check draws a short note instead. */
@Composable
private fun Child(state: A2uiComponentState, modifier: Modifier) {
    when (state) {
        is A2uiComponentState.Success -> A2uiComponent(component = state.component, modifier = modifier)
        is A2uiComponentState.Error -> Note("This part couldn't be drawn (${state.exception.code}).", modifier)
        A2uiComponentState.Loading -> Unit
    }
}

private fun guide(type: String): String = ScreenCatalog.PART_GUIDE.getValue(type)

private val metricCodes: List<String> = ScreenCatalog.METRICS.keys.toList()

private fun metricOf(code: String?): DashboardMetric? = DashboardMetric.entries.firstOrNull { it.name == code }

private object ColumnPartComponent : A2uiComponent {
    private val children = A2uiProperty.childList(key = "children", required = true, description = "The ids of the parts, top to bottom.")

    override val name: String = ScreenCatalog.COLUMN
    override val description: String = guide(ScreenCatalog.COLUMN)
    override val properties: List<A2uiProperty<*>> = listOf(children)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val references = properties.bindChildReferences(children).orEmpty()
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            references.forEach { reference ->
                key(reference.id) { Child(observeA2uiComponentState(reference), Modifier.fillMaxWidth()) }
            }
        }
    }
}

private object RowPartComponent : A2uiComponent {
    private val children = A2uiProperty.childList(key = "children", required = true, description = "The ids of the parts, side by side.")

    override val name: String = ScreenCatalog.ROW
    override val description: String = guide(ScreenCatalog.ROW)
    override val properties: List<A2uiProperty<*>> = listOf(children)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val references = properties.bindChildReferences(children).orEmpty()
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            references.forEach { reference ->
                key(reference.id) { Child(observeA2uiComponentState(reference), Modifier.weight(1f)) }
            }
        }
    }
}

private object CardPartComponent : A2uiComponent {
    private val child = A2uiProperty.componentId(key = "child", required = true, description = "The id of the one part on the card.")

    override val name: String = ScreenCatalog.CARD
    override val description: String = guide(ScreenCatalog.CARD)
    override val properties: List<A2uiProperty<*>> = listOf(child)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val childId = properties[child] ?: return
        Card(modifier.fillMaxWidth()) {
            Box(Modifier.padding(16.dp)) { Child(observeA2uiComponentState(childId), Modifier.fillMaxWidth()) }
        }
    }
}

private object TextPartComponent : A2uiComponent {
    private val text = A2uiProperty.string(key = "text", required = true, description = "The text, without digits or number words.")
    private val variant = A2uiProperty.stringEnum(
        key = "variant",
        enumValues = ScreenCatalog.TEXT_VARIANTS,
        required = true,
        description = "h1, h2 or h3 for a heading, body for text, caption for a small note.",
    )

    override val name: String = ScreenCatalog.TEXT
    override val description: String = guide(ScreenCatalog.TEXT)
    override val properties: List<A2uiProperty<*>> = listOf(text, variant)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        ScreenTextView(properties[text].orEmpty(), properties[variant] ?: "body", modifier)
    }
}

private object DividerPartComponent : A2uiComponent {
    override val name: String = ScreenCatalog.DIVIDER
    override val description: String = guide(ScreenCatalog.DIVIDER)
    override val properties: List<A2uiProperty<*>> = emptyList()

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        HorizontalDivider(modifier)
    }
}

private object MetricTileComponent : A2uiComponent {
    private val metric = A2uiProperty.stringEnum(key = "metric", enumValues = metricCodes, required = true, description = METRIC_CODE)
    private val days = A2uiProperty.number(key = "days", required = true, description = "How many of the last days to count.")
    private val show = A2uiProperty.stringEnum(
        key = "show",
        enumValues = ScreenCatalog.TILE_SHOWS,
        required = true,
        description = "total, average or latest day.",
    )

    override val name: String = ScreenCatalog.METRIC_TILE
    override val description: String = guide(ScreenCatalog.METRIC_TILE)
    override val properties: List<A2uiProperty<*>> = listOf(metric, days, show)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val shown = metricOf(properties[metric]) ?: return
        MetricTileView(shown, properties[days]?.toInt() ?: DEFAULT_DAYS, properties[show] ?: "average", modifier)
    }
}

private object TrendChartComponent : A2uiComponent {
    private val metric = A2uiProperty.stringEnum(key = "metric", enumValues = metricCodes, required = true, description = METRIC_CODE)
    private val days = A2uiProperty.number(key = "days", required = true, description = "How many of the last days to chart.")
    private val style = A2uiProperty.stringEnum(
        key = "style",
        enumValues = ScreenCatalog.CHART_STYLES,
        required = true,
        description = "bar or line.",
    )

    override val name: String = ScreenCatalog.TREND_CHART
    override val description: String = guide(ScreenCatalog.TREND_CHART)
    override val properties: List<A2uiProperty<*>> = listOf(metric, days, style)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val charted = metricOf(properties[metric]) ?: return
        TrendChartView(charted, properties[days]?.toInt() ?: DEFAULT_DAYS, properties[style] ?: "bar", modifier)
    }
}

private const val DEFAULT_DAYS = 7
private const val METRIC_CODE = "The metric code."
