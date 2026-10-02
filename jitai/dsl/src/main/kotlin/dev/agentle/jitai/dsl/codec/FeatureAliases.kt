package dev.agentle.jitai.dsl.codec

import dev.agentle.analytics.features.RealtimeFeatureCatalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Renamed catalog feature ids (integrator correction testing-build): renaming an id in [RealtimeFeatureCatalog] must
 * add `old -> new` here, so stored rules keep decoding. Stored definitions and stored condition trees are read with
 * every old id replaced by its current id, and the next canonical encoding writes the new id (the migration).
 *
 * Model output is not migrated: the prompt lists current ids only, so an old id in a proposal is E010.
 */
public object FeatureAliases {
    /** Old feature id -> current feature id. Empty in catalog version 1 (no id has been renamed). */
    public val ALIASES: Map<String, String> = emptyMap()

    /** The current id for [id] ([id] itself when it was never renamed). */
    public fun canonical(id: String, aliases: Map<String, String> = ALIASES): String = aliases[id] ?: id

    /**
     * Problems of an alias table: an old id that is still a catalog id, a target that is not one, or a chain (a target
     * that is itself an old id). Empty for a valid table.
     */
    public fun problems(aliases: Map<String, String> = ALIASES, catalogIds: Set<String> = RealtimeFeatureCatalog.ids): List<String> =
        aliases.flatMap { (old, new) ->
            listOfNotNull(
                "\"$old\" is still a catalog id".takeIf { old in catalogIds },
                "\"$new\" is not a catalog id".takeIf { new !in catalogIds },
                "\"$new\" is itself renamed".takeIf { new in aliases },
            )
        }

    /**
     * [element] with every old id replaced by its current id: the `feature` of each condition leaf and each `{{old}}`
     * placeholder in a text. Numbers and other values keep their tokens. The element comes from the strict reader
     * (depth <= 20), so the recursion is bounded.
     */
    public fun migrate(element: JsonElement, aliases: Map<String, String> = ALIASES): JsonElement =
        if (aliases.isEmpty()) element else rewrite(element, aliases, key = null)

    private fun rewrite(element: JsonElement, aliases: Map<String, String>, key: String?): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (name, value) -> rewrite(value, aliases, name) })

        is JsonArray -> JsonArray(element.map { rewrite(it, aliases, key = null) })

        is JsonPrimitive -> when {
            !element.isString -> element
            key == FEATURE_KEY -> JsonPrimitive(canonical(element.content, aliases))
            else -> JsonPrimitive(placeholders(element.content, aliases))
        }
    }

    private fun placeholders(text: String, aliases: Map<String, String>): String = if (!text.contains("{{")) {
        text
    } else {
        PLACEHOLDER.replace(text) { match -> aliases[match.groupValues[1]]?.let { "{{$it}}" } ?: match.value }
    }

    private const val FEATURE_KEY = "feature"
    private val PLACEHOLDER = Regex("""\{\{([a-z0-9_]+)\}\}""")
}
