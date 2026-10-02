package dev.agentle.ai.api.validation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The JSON Schema subset AI outputs are checked against: closed objects (`additionalProperties: false`, every listed
 * property required unless stated), arrays with size and uniqueness, strings with length, pattern and enum, integers
 * with a range, booleans, an integer constant, a union discriminated by a string property, and an opaque object whose
 * content another validator checks. Nothing else: no `$ref`, no numbers with fractions, no free maps.
 */
public sealed interface SchemaNode {
    public val nullable: Boolean

    public data class ObjectNode(
        val properties: Map<String, SchemaNode>,
        val required: Set<String> = properties.keys,
        /** Keys owned by the app: reported as [OutputCodes.FORBIDDEN_FIELD] instead of unknown. */
        val forbidden: Set<String> = emptySet(),
        override val nullable: Boolean = false,
    ) : SchemaNode {
        init {
            require(properties.keys.containsAll(required)) { "required keys must be properties" }
            require(forbidden.none { it in properties }) { "a forbidden key cannot be a property" }
        }
    }

    public data class ArrayNode(
        val items: SchemaNode,
        val minItems: Int = 0,
        val maxItems: Int? = null,
        val uniqueItems: Boolean = false,
        override val nullable: Boolean = false,
    ) : SchemaNode

    /** Lengths count Unicode code points after NFC normalization. */
    public data class StringNode(
        val minLength: Int? = null,
        val maxLength: Int? = null,
        val pattern: String? = null,
        val allowed: List<String>? = null,
        override val nullable: Boolean = false,
    ) : SchemaNode

    /** An integer token: digits only, no fraction or exponent (`3000.0` is rejected). */
    public data class IntegerNode(val minimum: Long? = null, val maximum: Long? = null, override val nullable: Boolean = false) : SchemaNode

    public data class BooleanNode(override val nullable: Boolean = false) : SchemaNode

    /** An integer constant such as `schemaVersion: 1`; a mismatch reports [code]. */
    public data class ConstNode(val value: Long, val code: String = OutputCodes.INVALID_ENUM_VALUE) : SchemaNode {
        override val nullable: Boolean get() = false
    }

    /** Objects told apart by the string property [discriminator] (for example `type`). */
    public data class UnionNode(val discriminator: String, val variants: Map<String, ObjectNode>, override val nullable: Boolean = false) :
        SchemaNode {
        init {
            require(variants.values.all { discriminator in it.properties }) { "every variant declares the discriminator" }
        }
    }

    /** Any JSON object; its content is checked by a delegate validator. */
    public data class AnyObjectNode(override val nullable: Boolean = false) : SchemaNode
}

/** Renders [root] as a JSON Schema 2020-12 document, for the prompt and for structured-output probes. */
public fun renderJsonSchema(id: String, title: String, root: SchemaNode): JsonObject {
    val body = render(root)
    return buildJsonObject {
        put("\$schema", "https://json-schema.org/draft/2020-12/schema")
        put("\$id", id)
        put("title", title)
        body.forEach { (key, value) -> put(key, value) }
    }
}

private fun typeOf(name: String, nullable: Boolean): JsonElement = if (nullable) {
    buildJsonArray {
        add(JsonPrimitive(name))
        add(JsonPrimitive("null"))
    }
} else {
    JsonPrimitive(name)
}

private fun render(node: SchemaNode): JsonObject = when (node) {
    is SchemaNode.ObjectNode -> buildJsonObject {
        put("type", typeOf("object", node.nullable))
        put("additionalProperties", false)
        put("required", JsonArray(node.properties.keys.filter { it in node.required }.map(::JsonPrimitive)))
        put("properties", JsonObject(node.properties.mapValues { (_, child) -> render(child) }))
    }

    is SchemaNode.ArrayNode -> buildJsonObject {
        put("type", typeOf("array", node.nullable))
        put("items", render(node.items))
        if (node.minItems > 0) put("minItems", node.minItems)
        node.maxItems?.let { put("maxItems", it) }
        if (node.uniqueItems) put("uniqueItems", true)
    }

    is SchemaNode.StringNode -> buildJsonObject {
        put("type", typeOf("string", node.nullable))
        node.allowed?.let { allowed ->
            put("enum", JsonArray(allowed.map(::JsonPrimitive) + if (node.nullable) listOf(JsonNull) else emptyList()))
        }
        node.minLength?.let { put("minLength", it) }
        node.maxLength?.let { put("maxLength", it) }
        node.pattern?.let { put("pattern", it) }
    }

    is SchemaNode.IntegerNode -> buildJsonObject {
        put("type", typeOf("integer", node.nullable))
        node.minimum?.let { put("minimum", it) }
        node.maximum?.let { put("maximum", it) }
    }

    is SchemaNode.BooleanNode -> buildJsonObject { put("type", typeOf("boolean", node.nullable)) }

    is SchemaNode.ConstNode -> buildJsonObject { put("const", node.value) }

    is SchemaNode.UnionNode -> buildJsonObject {
        val variants = node.variants.map { (tag, variant) ->
            val rendered = render(variant)
            JsonObject(
                rendered +
                    ("properties" to JsonObject(rendered.getValue("properties") as JsonObject + (node.discriminator to constOf(tag)))),
            )
        }
        put("anyOf", JsonArray(if (node.nullable) listOf(buildJsonObject { put("type", "null") }) + variants else variants))
    }

    is SchemaNode.AnyObjectNode -> buildJsonObject { put("type", typeOf("object", node.nullable)) }
}

private fun constOf(tag: String): JsonObject = buildJsonObject { put("const", tag) }
