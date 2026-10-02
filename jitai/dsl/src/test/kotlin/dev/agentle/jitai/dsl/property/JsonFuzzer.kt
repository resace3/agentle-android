package dev.agentle.jitai.dsl.property

import dev.agentle.analytics.features.RealtimeFeatureCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/**
 * Seeded JSON fuzzer: structural edits of a parsed document (replace, remove, add or rename keys, duplicate items, wrap,
 * move subtrees), edits of the raw text (bytes, truncation, duplicate keys, prefixes) and random documents (deep, large,
 * random keys). Number tokens such as `45.0`, `4.5e1` and `1e999` are kept verbatim.
 */
internal class JsonFuzzer(private val random: Random) {
    /** [edits] structural edits of [text], which must be one JSON value. */
    fun mutate(text: String, edits: Int): String {
        var element = Json.parseToJsonElement(text)
        repeat(edits) { element = edit(element) }
        return element.toString()
    }

    /** One to three edits of the raw text. */
    fun mutateText(text: String): String {
        val out = StringBuilder(text)
        repeat(1 + random.nextInt(3)) { textEdit(out) }
        return out.toString()
    }

    /** A random document: mostly objects with the DSL's own keys, sometimes too deep or too large. */
    fun randomDocument(): String = when (random.nextInt(8)) {
        0 -> random.nextInt(15, 40).let { depth -> "[".repeat(depth) + "]".repeat(depth) }
        1 -> "{\"jitai\": \"" + "x".repeat(17_000) + "\"}"
        2 -> pick(STRINGS).let { JsonPrimitive(it).toString() }
        else -> randomObject(depth = 0).toString()
    }

    private fun edit(root: JsonElement): JsonElement {
        val paths = ArrayList<List<Any>>()
        collect(root, emptyList(), paths)
        val path = paths[random.nextInt(paths.size)]
        return when (random.nextInt(EDITS)) {
            0, 1, 2 -> update(root, path) { interesting(root) }
            3 -> if (path.isEmpty()) root else update(root, path) { null }
            4 -> update(root, path) { node -> if (node is JsonObject) JsonObject(node + (pick(KEYS) to interesting(root))) else node }
            5 -> update(root, path) { node -> duplicateItem(node) }
            6 -> update(root, path) { node -> JsonArray(listOf(node)) }
            else -> update(root, path) { node -> rename(node) }
        } ?: root
    }

    private fun duplicateItem(node: JsonElement): JsonElement =
        if (node is JsonArray && node.isNotEmpty()) JsonArray(node + node[random.nextInt(node.size)]) else node

    private fun rename(node: JsonElement): JsonElement {
        if (node !is JsonObject || node.isEmpty()) return node
        val victim = node.keys.elementAt(random.nextInt(node.size))
        return JsonObject(node.entries.associate { (key, value) -> (if (key == victim) pick(KEYS) else key) to value })
    }

    private fun collect(node: JsonElement, path: List<Any>, into: MutableList<List<Any>>) {
        into += path
        when (node) {
            is JsonObject -> node.forEach { (key, value) -> collect(value, path + key, into) }
            is JsonArray -> node.forEachIndexed { index, value -> collect(value, path + index, into) }
            else -> Unit
        }
    }

    /** A copy with [op] applied at [path]; null from [op] removes the node (the root is kept). */
    private fun update(node: JsonElement, path: List<Any>, op: (JsonElement) -> JsonElement?): JsonElement? {
        if (path.isEmpty()) return op(node)
        val head = path.first()
        val rest = path.drop(1)
        return when {
            node is JsonObject && head is String -> {
                val updated = update(node.getValue(head), rest, op)
                JsonObject(LinkedHashMap(node).apply { if (updated == null) remove(head) else put(head, updated) })
            }

            node is JsonArray && head is Int -> {
                val list = node.toMutableList()
                val updated = update(list[head], rest, op)
                if (updated == null) list.removeAt(head) else list[head] = updated
                JsonArray(list)
            }

            else -> node
        }
    }

    private fun interesting(root: JsonElement): JsonElement = when (random.nextInt(VALUE_KINDS)) {
        0 -> Json.parseToJsonElement(pick(NUMBERS))
        1 -> JsonPrimitive(pick(STRINGS))
        2 -> JsonPrimitive(pick(FEATURES))
        3 -> JsonNull
        4 -> JsonPrimitive(random.nextBoolean())
        5 -> subtree(root)
        6 -> JsonArray(List(random.nextInt(4)) { JsonPrimitive(pick(STRINGS)) })
        else -> randomObject(depth = 3)
    }

    private fun subtree(root: JsonElement): JsonElement {
        val paths = ArrayList<List<Any>>()
        collect(root, emptyList(), paths)
        var node = root
        for (segment in paths[random.nextInt(paths.size)]) {
            node = if (segment is String) (node as JsonObject).getValue(segment) else (node as JsonArray)[segment as Int]
        }
        return node
    }

    private fun randomObject(depth: Int): JsonElement = JsonObject(
        (0 until random.nextInt(1, 6)).associate { pick(KEYS) to randomValue(depth + 1) },
    )

    private fun randomValue(depth: Int): JsonElement = when (random.nextInt(if (depth > 5) 4 else 6)) {
        0 -> JsonNull
        1 -> Json.parseToJsonElement(pick(NUMBERS))
        2 -> JsonPrimitive(pick(STRINGS + FEATURES))
        3 -> JsonPrimitive(random.nextBoolean())
        4 -> JsonArray(List(random.nextInt(4)) { randomValue(depth + 1) })
        else -> randomObject(depth)
    }

    private fun textEdit(out: StringBuilder) {
        when (random.nextInt(TEXT_EDITS)) {
            0 -> if (out.isNotEmpty()) out.deleteCharAt(random.nextInt(out.length))

            1 -> out.insert(random.nextInt(out.length + 1), CHARS[random.nextInt(CHARS.length)])

            2 -> if (out.isNotEmpty()) out.setLength(random.nextInt(out.length))

            3 -> {
                val from = random.nextInt(out.length + 1)
                val slice = out.substring(from, minOf(out.length, from + random.nextInt(1, 40)))
                out.insert(random.nextInt(out.length + 1), slice)
            }

            4 -> out.insert(0, pick(PREFIXES))

            5 -> out.indexOf("{").takeIf { it >= 0 }?.let { out.insert(it + 1, "\"schemaVersion\": 1, ") }

            else -> out.append(pick(SUFFIXES))
        }
    }

    private fun <T> pick(values: List<T>): T = values[random.nextInt(values.size)]

    private companion object {
        const val EDITS = 8
        const val VALUE_KINDS = 8
        const val TEXT_EDITS = 7
        const val CHARS = "{}[]\",:\\0123456789eE.-+ tfnul\u0000\u001Fé\u202E\u200B\uFEFF\uD83D"

        val FEATURES: List<String> = RealtimeFeatureCatalog.all.map { it.id } + listOf("location_class", "heart_rate_variability")

        val NUMBERS = listOf(
            "0", "-1", "1", "2", "3", "4", "15", "30", "45", "45.0", "4.5e1", "-0", "0.5", "0.3", "0.7", "60", "61", "100", "120",
            "360", "1440", "3000", "10080", "1e999", "9223372036854775808", "-9223372036854775809", "12345678901234567890",
        )

        val STRINGS = listOf(
            "", " ", "45", "x", "00:00", "07:00", "09:00", "17:00", "22:00", "23:59", "24:00", "7:00", "HOME", "WEEKEND", "MON",
            "Monday", "self", "any", "category:GENERAL", "category:FUN", "com.instagram.android", "com.nope.app", "not a package",
            "Instagram", "Insta", "3f6c1d2e-8b7a-4c1e-9a55-0d7e2b9c4a10", "ASSUME_TRUE", "ASSUME_FALSE", "INTERVENTION",
            "SUPPRESSION", "NOTIFICATION", "NONE", "VIDEO", "VOICE", "IMAGE", "ALLOW_WHEN_INTERACTIVE", "RESPECT", "daily_at",
            "interval", "event", "all", "any", "not", "gt", "gte", "lt", "eq", "neq", "between", "in", "local_time_in", "template",
            "static", "variants", "ai_text", "local_media", "PHYSICAL_ACTIVITY", "SLEEP_WIND_DOWN", "LOCATION_CLASS_CHANGED",
            "BOOT_COMPLETED", "USER_PRESENT", "OK", "NEEDS_CLARIFICATION", "UNSUPPORTED", "HEALTH_OR_SAFETY", "STEPS_AFTER",
            "APP_MINUTES_AFTER", "SUPPRESS_ONLY", "RE_EVALUATE_AFTER", "MINUTES_30", "UNTIL_TOMORROW", "WARM", "ROTATE",
            "{{steps_today}}", "{{", "}}", "{{nope}}", "{{app_minutes_since}}", "www.agentle.app", "me@example.org",
            "call 555 123 4567", "<b>hi</b>", "insomnia", "because of", "a\u0000b", "a\u202Eb", "\uD83D", "sunset_walk_01",
            "/jitai/conditions/value", "/jitai", "/other", "q1", "q4", "2026-10-01T10:00:00Z", "2026-10-01T10:00:00.5Z",
            "x".repeat(300),
        )

        val KEYS = listOf(
            "schemaVersion", "status", "jitai", "type", "feature", "args", "value", "onUnknown", "of", "min", "max", "values",
            "start", "end", "days", "name", "description", "kind", "category", "trigger", "events", "times", "everyMinutes",
            "maxLatenessMinutes", "debounceSeconds", "activeWindow", "conditions", "contextRequirements", "content", "title",
            "body", "items", "goal", "tone", "fallback", "assetId", "caption", "delivery", "channel", "quietHoursPolicy",
            "cooldownMinutes", "maxPerDay", "maxPerWeek", "priority", "snooze", "mode", "options", "expiresInDays", "outcome",
            "proximal", "distal", "metric", "windowMinutes", "suppression", "categories", "jitaiIds", "assumptions", "path",
            "text", "questions", "id", "unsupported", "reason", "detail", "package", "appLabel", "since", "color",
        )

        val PREFIXES = listOf("Sure! ", "```json\n", "\uFEFF", "  \n", "[", "{\"a\":1}", "\u0000")
        val SUFFIXES = listOf("\n```", " Done.", "}", "]", ",", "\u0000", " ")
    }
}
