package dev.agentle.ai.api.validation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.Normalizer

/**
 * Stage S4: walks a parsed document against a [SchemaNode] and collects every defect (it does not stop at the first).
 * Paths are JSON pointers; keys the schema does not know are written as `*` so model text never reaches a path.
 */
public object SchemaWalker {
    private val INTEGER = Regex("^-?(0|[1-9][0-9]*)$")
    private val patterns = HashMap<String, Regex>()

    public fun walk(root: SchemaNode, document: JsonElement): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        visit(root, document, "", issues)
        return issues
    }

    /** JSON pointer of [key] under [parent] (RFC 6901 escaping). */
    public fun child(parent: String, key: String): String = "$parent/" + key.replace("~", "~0").replace("/", "~1")

    /** Unicode code points of [text] after NFC normalization. */
    public fun codePointLength(text: String): Int {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        return normalized.codePointCount(0, normalized.length)
    }

    private fun issue(issues: MutableList<ValidationIssue>, code: String, path: String) {
        issues += ValidationIssue(code, path, ValidationStage.S4_SCHEMA)
    }

    private fun visit(node: SchemaNode, element: JsonElement, path: String, issues: MutableList<ValidationIssue>) {
        if (element is JsonNull && node !is SchemaNode.ConstNode) {
            if (!node.nullable) issue(issues, OutputCodes.WRONG_JSON_TYPE, path)
            return
        }
        when (node) {
            is SchemaNode.ObjectNode -> visitObject(node, element, path, issues)

            is SchemaNode.ArrayNode -> visitArray(node, element, path, issues)

            is SchemaNode.StringNode -> visitString(node, element, path, issues)

            is SchemaNode.IntegerNode -> visitInteger(node, element, path, issues)

            is SchemaNode.BooleanNode -> if (!isBoolean(element)) issue(issues, OutputCodes.WRONG_JSON_TYPE, path)

            is SchemaNode.ConstNode -> if (!(isIntegerToken(element) && (element as JsonPrimitive).content == node.value.toString())) {
                issue(issues, node.code, path)
            }

            is SchemaNode.UnionNode -> visitUnion(node, element, path, issues)

            is SchemaNode.AnyObjectNode -> if (element !is JsonObject) issue(issues, OutputCodes.WRONG_JSON_TYPE, path)
        }
    }

    private fun visitObject(node: SchemaNode.ObjectNode, element: JsonElement, path: String, issues: MutableList<ValidationIssue>) {
        if (element !is JsonObject) {
            issue(issues, OutputCodes.WRONG_JSON_TYPE, path)
            return
        }
        element.keys.forEach { key ->
            when (key) {
                in node.forbidden -> issue(issues, OutputCodes.FORBIDDEN_FIELD, child(path, key))
                !in node.properties -> issue(issues, OutputCodes.UNKNOWN_FIELD, "$path/*")
            }
        }
        node.properties.forEach { (key, childNode) ->
            val value = element[key]
            if (value == null) {
                if (key in node.required) issue(issues, OutputCodes.MISSING_FIELD, child(path, key))
            } else {
                visit(childNode, value, child(path, key), issues)
            }
        }
    }

    private fun visitArray(node: SchemaNode.ArrayNode, element: JsonElement, path: String, issues: MutableList<ValidationIssue>) {
        if (element !is JsonArray) {
            issue(issues, OutputCodes.WRONG_JSON_TYPE, path)
            return
        }
        if (element.size < node.minItems || (node.maxItems != null && element.size > node.maxItems)) {
            issue(issues, OutputCodes.ARRAY_SIZE, path)
        }
        if (node.uniqueItems && element.toSet().size != element.size) issue(issues, OutputCodes.DUPLICATE_ITEMS, path)
        element.forEachIndexed { index, item -> visit(node.items, item, "$path/$index", issues) }
    }

    private fun visitString(node: SchemaNode.StringNode, element: JsonElement, path: String, issues: MutableList<ValidationIssue>) {
        if (element !is JsonPrimitive || !element.isString) {
            issue(issues, OutputCodes.WRONG_JSON_TYPE, path)
            return
        }
        val text = element.content
        if (node.allowed != null && text !in node.allowed) {
            issue(issues, OutputCodes.INVALID_ENUM_VALUE, path)
            return
        }
        val length = codePointLength(text)
        if ((node.minLength != null && length < node.minLength) || (node.maxLength != null && length > node.maxLength)) {
            issue(issues, OutputCodes.TEXT_LENGTH, path)
        }
        if (node.pattern != null) {
            val regex = synchronized(patterns) { patterns.getOrPut(node.pattern) { Regex(node.pattern) } }
            if (!regex.containsMatchIn(text)) issue(issues, OutputCodes.PATTERN_MISMATCH, path)
        }
    }

    private fun visitInteger(node: SchemaNode.IntegerNode, element: JsonElement, path: String, issues: MutableList<ValidationIssue>) {
        if (!isIntegerToken(element)) {
            issue(issues, OutputCodes.WRONG_JSON_TYPE, path)
            return
        }
        val value = (element as JsonPrimitive).content.toLongOrNull()
        if (value == null || (node.minimum != null && value < node.minimum) || (node.maximum != null && value > node.maximum)) {
            issue(issues, OutputCodes.NUMBER_OUT_OF_RANGE, path)
        }
    }

    private fun visitUnion(node: SchemaNode.UnionNode, element: JsonElement, path: String, issues: MutableList<ValidationIssue>) {
        if (element !is JsonObject) {
            issue(issues, OutputCodes.WRONG_JSON_TYPE, path)
            return
        }
        val tagPath = child(path, node.discriminator)
        val tag = element[node.discriminator]
        when {
            tag == null -> issue(issues, OutputCodes.MISSING_FIELD, tagPath)

            tag !is JsonPrimitive || !tag.isString -> issue(issues, OutputCodes.WRONG_JSON_TYPE, tagPath)

            else -> {
                val variant = node.variants[tag.content]
                if (variant == null) issue(issues, OutputCodes.INVALID_ENUM_VALUE, tagPath) else visitObject(variant, element, path, issues)
            }
        }
    }

    private fun isIntegerToken(element: JsonElement): Boolean =
        element is JsonPrimitive && !element.isString && INTEGER.matches(element.content)

    private fun isBoolean(element: JsonElement): Boolean =
        element is JsonPrimitive && !element.isString && (element.content == "true" || element.content == "false")
}
