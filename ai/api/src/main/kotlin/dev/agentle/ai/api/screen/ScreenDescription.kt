package dev.agentle.ai.api.screen

/**
 * A saved screen in plain sentences, sent with a request to change it. A chat request carries only the user's text,
 * reduced to letters, digits and a few marks before it is sent, so the layout travels as words rather than JSON. It
 * names the parts and metrics and repeats the screen's title and texts, which ChatGPT wrote and which passed
 * ScreenRules' text checks when the screen was saved; it holds no values.
 */
public object ScreenDescription {
    /**
     * [question] as a sentence, then the description of [screen]; null when the two do not fit in [maxChars] (the cap
     * on the user's text), because a cut description would make ChatGPT drop the parts it never saw.
     */
    public fun changeRequest(question: String, screen: ScreenSpec, maxChars: Int): String? {
        val asked = question.trim()
        if (asked.isEmpty()) return null
        val request = (if (asked.last() in ".?!") asked else "$asked.") + " " + describe(screen)
        return request.takeIf { it.length <= maxChars }
    }

    /**
     * For example: The saved screen Steps. Top to bottom it shows a heading Steps. Then a bar chart of steps per day over the
     * last 7 days.
     */
    public fun describe(screen: ScreenSpec): String {
        val byId = screen.components.associateBy { it.id }
        val root = byId[ScreenCatalog.ROOT_ID] ?: return "The saved screen ${screen.title}."
        val top = (root as? ColumnPart)?.children?.mapNotNull(byId::get) ?: listOf(root)
        val parts = top.map { phrase(it, byId, depth = 1) }
        return buildString {
            append("The saved screen ").append(screen.title).append(". Top to bottom it shows ")
            append(parts.joinToString(". Then ")).append('.')
        }
    }

    private fun phrase(part: ScreenPart, byId: Map<String, ScreenPart>, depth: Int): String {
        fun children(ids: List<String>) = if (depth >= ScreenCatalog.MAX_DEPTH) {
            "more parts"
        } else {
            and(ids.mapNotNull(byId::get).map { phrase(it, byId, depth + 1) })
        }
        return when (part) {
            is ColumnPart -> "a column of ${children(part.children)}"
            is RowPart -> "side by side ${children(part.children)}"
            is CardPart -> "a card with ${children(listOf(part.child))}"
            is TextPart -> "${TEXT_KINDS[part.variant] ?: "text"} ${part.text.trimEnd('.', '!', '?')}"
            is DividerPart -> "a line"
            is MetricTilePart -> "a tile with the ${TILE_KINDS[part.show] ?: part.show} of ${metric(part.metric)} ${last(part.days)}"
            is TrendChartPart -> "a ${STYLES[part.style] ?: "bar"} chart of ${metric(part.metric)} per day ${last(part.days)}"
        }
    }

    private fun and(items: List<String>): String = when (items.size) {
        0 -> "nothing"
        1 -> items.single()
        else -> items.dropLast(1).joinToString(" ") + " and " + items.last()
    }

    private fun metric(code: String): String = code.lowercase().replace('_', ' ')

    private fun last(days: Int): String = "over the last $days days"

    private val TEXT_KINDS =
        mapOf("h1" to "a big heading", "h2" to "a heading", "h3" to "a small heading", "body" to "text", "caption" to "a caption")
    private val TILE_KINDS = mapOf("total" to "total", "average" to "average", "latest" to "latest day")
    private val STYLES = mapOf("bar" to "bar", "line" to "line")
}
