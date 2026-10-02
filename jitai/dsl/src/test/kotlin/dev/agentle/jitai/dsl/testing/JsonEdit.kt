package dev.agentle.jitai.dsl.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** Small JSON editing helpers for table-driven mutations of the R10 examples (pointer segments are not escaped). */
internal fun json(text: String): JsonElement = Json.parseToJsonElement(text)

internal operator fun JsonElement.get(pointer: String): JsonElement {
    var node = this
    for (segment in segments(pointer)) {
        node = when (node) {
            is JsonObject -> node[segment] ?: error("no key $segment in $pointer")
            is JsonArray -> node[segment.toInt()]
            else -> error("$pointer goes through a primitive")
        }
    }
    return node
}

/** A copy with the value at [pointer] replaced (or added as the last key of its object). */
internal fun JsonElement.with(pointer: String, value: JsonElement): JsonElement = update(segments(pointer)) { value }

internal fun JsonElement.with(pointer: String, value: String): JsonElement = with(pointer, JsonPrimitive(value))

internal fun JsonElement.with(pointer: String, value: Number): JsonElement = with(pointer, JsonPrimitive(value))

internal fun JsonElement.with(pointer: String, value: Boolean): JsonElement = with(pointer, JsonPrimitive(value))

internal fun JsonElement.withNull(pointer: String): JsonElement = with(pointer, JsonNull)

/** A copy without the key (or array item) at [pointer]. */
internal fun JsonElement.without(pointer: String): JsonElement = update(segments(pointer)) { null }

private fun segments(pointer: String): List<String> = pointer.removePrefix("/").split('/').filter { it.isNotEmpty() }

private fun JsonElement.update(segments: List<String>, op: (JsonElement?) -> JsonElement?): JsonElement {
    require(segments.isNotEmpty()) { "empty pointer" }
    val head = segments.first()
    val rest = segments.drop(1)
    return when (this) {
        is JsonObject -> {
            val child = this[head]
            val updated = if (rest.isEmpty()) op(child) else (child ?: error("no key $head")).update(rest, op)
            val map = LinkedHashMap(this)
            if (updated == null) map.remove(head) else map[head] = updated
            JsonObject(map)
        }

        is JsonArray -> {
            val index = head.toInt()
            val list = toMutableList()
            val updated = if (rest.isEmpty()) op(list.getOrNull(index)) else list[index].update(rest, op)
            when {
                updated == null -> list.removeAt(index)
                index == list.size -> list.add(updated)
                else -> list[index] = updated
            }
            JsonArray(list)
        }

        else -> error("cannot descend into a primitive")
    }
}

/** The five proposal arg keys with nulls, overridden by [values]. */
internal fun proposalArgs(vararg values: Pair<String, String?>): JsonObject {
    val given = values.toMap()
    return buildJsonObject {
        for (key in listOf("package", "appLabel", "category", "since", "jitai")) {
            put(key, given[key]?.let(::JsonPrimitive) ?: JsonNull)
        }
    }
}

/** A comparison leaf in proposal form (`value` may be any JSON literal). */
internal fun leaf(
    type: String,
    feature: String,
    value: JsonElement,
    vararg args: Pair<String, String?>,
    onUnknown: String? = null,
): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(type))
    put("feature", JsonPrimitive(feature))
    put("args", proposalArgs(*args))
    put("value", value)
    put("onUnknown", onUnknown?.let(::JsonPrimitive) ?: JsonNull)
}

internal fun leaf(type: String, feature: String, value: Int, vararg args: Pair<String, String?>, onUnknown: String? = null): JsonObject =
    leaf(type, feature, JsonPrimitive(value), *args, onUnknown = onUnknown)

internal fun leaf(type: String, feature: String, value: String, vararg args: Pair<String, String?>, onUnknown: String? = null): JsonObject =
    leaf(type, feature, JsonPrimitive(value), *args, onUnknown = onUnknown)

internal fun between(feature: String, min: Int, max: Int): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("between"))
    put("feature", JsonPrimitive(feature))
    put("args", proposalArgs())
    put("min", JsonPrimitive(min))
    put("max", JsonPrimitive(max))
    put("onUnknown", JsonNull)
}

internal fun inList(feature: String, values: List<JsonElement>): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("in"))
    put("feature", JsonPrimitive(feature))
    put("args", proposalArgs())
    put("values", JsonArray(values))
    put("onUnknown", JsonNull)
}

internal fun group(type: String, vararg children: JsonElement): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(type))
    put("of", buildJsonArray { children.forEach { add(it) } })
}

internal fun negate(child: JsonElement): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("not"))
    put("of", child)
}

internal fun timeIn(start: String, end: String): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("local_time_in"))
    put("start", JsonPrimitive(start))
    put("end", JsonPrimitive(end))
}
