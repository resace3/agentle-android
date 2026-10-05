package dev.agentle.ai.api.screen

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * A screen ChatGPT designed from Agentle's own parts ([ScreenCatalog]), in the component shape of Google's A2UI 0.9.1:
 * a flat list of parts with ids, drawn from the part whose id is [ScreenCatalog.ROOT_ID]. It is declarative data only.
 * A metric part names a metric and a day range and the phone computes its numbers; nothing in a screen is executed.
 * [ScreenRules] decides whether a screen may be saved and drawn.
 */
@Serializable
public data class ScreenSpec(val title: String, val components: List<ScreenPart>) {
    /** Every metric code the screen's parts show, in order of first use. */
    public val metrics: List<String>
        get() = components.mapNotNull { part ->
            when (part) {
                is MetricTilePart -> part.metric
                is TrendChartPart -> part.metric
                else -> null
            }
        }.distinct()

    /** The longest day range of the screen's parts, or 1 for a screen without metrics. */
    public val days: Int
        get() = components.maxOfOrNull { part ->
            when (part) {
                is MetricTilePart -> part.days
                is TrendChartPart -> part.days
                else -> 1
            }
        } ?: 1
}

/** One part of a [ScreenSpec]; the A2UI `component` field names its type. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator(ScreenCatalog.TYPE_KEY)
public sealed interface ScreenPart {
    public val id: String
}

/** The parts named by [children], top to bottom. */
@Serializable
@SerialName(ScreenCatalog.COLUMN)
public data class ColumnPart(override val id: String, val children: List<String>) : ScreenPart

/** At most [ScreenCatalog.MAX_ROW_ITEMS] parts side by side. */
@Serializable
@SerialName(ScreenCatalog.ROW)
public data class RowPart(override val id: String, val children: List<String>) : ScreenPart

/** One part on a raised card. */
@Serializable
@SerialName(ScreenCatalog.CARD)
public data class CardPart(override val id: String, val child: String) : ScreenPart

/** A heading, body text or caption ([ScreenCatalog.TEXT_VARIANTS]). */
@Serializable
@SerialName(ScreenCatalog.TEXT)
public data class TextPart(override val id: String, val text: String, val variant: String) : ScreenPart

/** A thin line between parts. */
@Serializable
@SerialName(ScreenCatalog.DIVIDER)
public data class DividerPart(override val id: String) : ScreenPart

/** One number: the total, average or latest day ([ScreenCatalog.TILE_SHOWS]) of [metric] over the last [days]. */
@Serializable
@SerialName(ScreenCatalog.METRIC_TILE)
public data class MetricTilePart(override val id: String, val metric: String, val days: Int, val show: String) : ScreenPart

/** [metric] per day over the last [days], as bars or a line ([ScreenCatalog.CHART_STYLES]). */
@Serializable
@SerialName(ScreenCatalog.TREND_CHART)
public data class TrendChartPart(override val id: String, val metric: String, val days: Int, val style: String) : ScreenPart

/** The parts a screen may use and their limits, shared by the model's instructions, the checks and the renderer. */
public object ScreenCatalog {
    /** The A2UI catalog id of Agentle's parts. */
    public const val ID: String = "urn:agentle:a2ui:catalog:v1"

    /** The A2UI protocol version of the messages the app builds ([A2uiScreenMessages]). */
    public const val A2UI_VERSION: String = "v0.9.1"

    /** The A2UI field that names a part's type. */
    public const val TYPE_KEY: String = "component"

    /** The part A2UI draws first. */
    public const val ROOT_ID: String = "root"

    public const val COLUMN: String = "Column"
    public const val ROW: String = "Row"
    public const val CARD: String = "Card"
    public const val TEXT: String = "Text"
    public const val DIVIDER: String = "Divider"
    public const val METRIC_TILE: String = "MetricTile"
    public const val TREND_CHART: String = "TrendChart"

    public const val MAX_PARTS: Int = 20

    /** Levels of nesting, counting the root as one: Column, Card, Row and a tile fill four. */
    public const val MAX_DEPTH: Int = 4
    public const val MAX_ROW_ITEMS: Int = 3
    public const val MAX_DAYS: Int = 90

    /** Lowercase letters, digits and `_`, starting with a letter. */
    public const val ID_PATTERN: String = "^[a-z][a-z0-9_]{0,23}$"

    /** Every metric a part can show, with the description the model reads. */
    public val METRICS: Map<String, String> = linkedMapOf(
        "STEPS" to "steps per day",
        "DISTANCE_METERS" to "distance walked or run per day, in meters",
        "ACTIVE_CALORIES" to "active calories burned per day",
        "EXERCISE_MINUTES" to "minutes of recorded exercise per day",
        "SLEEP_MINUTES" to "minutes asleep per night, counted on the day the user woke up",
        "HEART_RATE_AVG" to "average heart rate per day, in beats per minute",
        "RESTING_HEART_RATE" to "resting heart rate per day, in beats per minute",
        "SCREEN_TIME_MINUTES" to "minutes the phone screen was on per day",
        "UNLOCKS" to "phone unlocks per day",
        "NOTIFICATIONS" to "notifications received per day",
    )

    /** Metrics that are rates: adding up their days means nothing, so a tile shows their average or latest day. */
    public val RATE_METRICS: Set<String> = setOf("HEART_RATE_AVG", "RESTING_HEART_RATE")

    public val TEXT_VARIANTS: List<String> = listOf("h1", "h2", "h3", "body", "caption")
    public val TILE_SHOWS: List<String> = listOf("total", "average", "latest")
    public val CHART_STYLES: List<String> = listOf("bar", "line")

    /** What each part does, as the model reads it in the instructions and in the renderer's catalog. */
    public val PART_GUIDE: Map<String, String> = linkedMapOf(
        COLUMN to "puts the parts listed in children top to bottom",
        ROW to "puts at most $MAX_ROW_ITEMS parts listed in children side by side",
        CARD to "draws its one child part on a raised card",
        TEXT to "shows text as an h1, h2 or h3 heading, body text or a caption, without digits or number words",
        DIVIDER to "draws a thin line between parts",
        METRIC_TILE to "shows one number of a metric over the last days: its total, its average or its latest day " +
            "(total is not used for heart rates)",
        TREND_CHART to "charts a metric per day over the last days as bars or a line, with its latest day and average",
    )

    /** The metric list as the model reads it in the instructions: one `CODE: description` per line. */
    public val METRIC_GUIDE: String = METRICS.entries.joinToString("\n") { (code, description) -> "$code: $description" }

    /** The part list as the model reads it in the instructions: one `Type: what it does` per line. */
    public val PART_LIST: String = PART_GUIDE.entries.joinToString("\n") { (type, description) -> "$type: $description" }
}
