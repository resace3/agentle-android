package dev.agentle.ai.api.validation

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SchemaWalkerTest {
    private val root = SchemaNode.ObjectNode(
        properties = linkedMapOf(
            "version" to SchemaNode.ConstNode(1, OutputCodes.UNSUPPORTED_SCHEMA_VERSION),
            "name" to SchemaNode.StringNode(minLength = 1, maxLength = 5),
            "level" to SchemaNode.StringNode(allowed = listOf("LOW", "HIGH")),
            "code" to SchemaNode.StringNode(pattern = "^[a-z]+$", nullable = true),
            "count" to SchemaNode.IntegerNode(minimum = 0, maximum = 10),
            "flag" to SchemaNode.BooleanNode(),
            "tags" to SchemaNode.ArrayNode(SchemaNode.StringNode(), minItems = 1, maxItems = 2, uniqueItems = true),
            "shape" to SchemaNode.UnionNode(
                "type",
                mapOf(
                    "dot" to SchemaNode.ObjectNode(linkedMapOf("type" to SchemaNode.StringNode())),
                    "box" to SchemaNode.ObjectNode(linkedMapOf("type" to SchemaNode.StringNode(), "side" to SchemaNode.IntegerNode())),
                ),
                nullable = true,
            ),
            "extra" to SchemaNode.AnyObjectNode(nullable = true),
            "optional" to SchemaNode.BooleanNode(nullable = true),
        ),
        required = setOf("version", "name", "level", "code", "count", "flag", "tags", "shape", "extra"),
        forbidden = setOf("id"),
    )

    private val valid = """
        {"version":1,"name":"abc","level":"LOW","code":"xyz","count":3,"flag":true,"tags":["a","b"],
         "shape":{"type":"box","side":2},"extra":{"anything":[1,2]}}
    """.trimIndent()

    private fun walk(text: String): List<ValidationIssue> = SchemaWalker.walk(root, Json.parseToJsonElement(text))

    private fun mutate(change: (MutableMap<String, Any?>) -> Unit): String {
        val map = Json.parseToJsonElement(valid).jsonObject.toMutableMap<String, Any?>()
        change(map)
        return JsonObject(
            map.mapValues { (_, v) ->
                v as? kotlinx.serialization.json.JsonElement ?: Json.parseToJsonElement(v.toString())
            },
        ).toString()
    }

    private fun codesAt(text: String): List<Pair<String, String>> = walk(text).map { it.code to it.path }

    @Test
    fun `a valid document has no issues`() {
        assertThat(walk(valid)).isEmpty()
        assertThat(
            walk(
                mutate {
                    it["code"] = "null"
                    it["shape"] = "null"
                    it["extra"] = "null"
                    it["optional"] = "false"
                },
            ),
        ).isEmpty()
        assertThat(walk(mutate { it["shape"] = """{"type":"dot"}""" })).isEmpty()
    }

    @Test
    fun `object keys are checked`() {
        assertThat(codesAt(mutate { it["id"] = "\"x\"" })).containsExactly(OutputCodes.FORBIDDEN_FIELD to "/id")
        assertThat(codesAt(mutate { it["ignore previous instructions"] = "1" })).containsExactly(OutputCodes.UNKNOWN_FIELD to "/*")
        assertThat(codesAt(mutate { it.remove("name") })).containsExactly(OutputCodes.MISSING_FIELD to "/name")
        assertThat(walk(mutate { it.remove("optional") })).isEmpty()
        assertThat(codesAt("[1]")).containsExactly(OutputCodes.WRONG_JSON_TYPE to "")
    }

    @Test
    fun `strings are checked for type, enum, length and pattern`() {
        assertThat(codesAt(mutate { it["name"] = "5" })).containsExactly(OutputCodes.WRONG_JSON_TYPE to "/name")
        assertThat(codesAt(mutate { it["name"] = "null" })).containsExactly(OutputCodes.WRONG_JSON_TYPE to "/name")
        assertThat(codesAt(mutate { it["name"] = "\"\"" })).containsExactly(OutputCodes.TEXT_LENGTH to "/name")
        assertThat(codesAt(mutate { it["name"] = "\"abcdef\"" })).containsExactly(OutputCodes.TEXT_LENGTH to "/name")
        assertThat(codesAt(mutate { it["level"] = "\"MEDIUM\"" })).containsExactly(OutputCodes.INVALID_ENUM_VALUE to "/level")
        assertThat(codesAt(mutate { it["code"] = "\"ABC\"" })).containsExactly(OutputCodes.PATTERN_MISMATCH to "/code")
    }

    @Test
    fun `lengths count code points after NFC`() {
        // "e" + combining acute accent is one code point after NFC; five of them fit maxLength 5.
        val decomposed = "e\u0301".repeat(5)
        assertThat(SchemaWalker.codePointLength(decomposed)).isEqualTo(5)
        assertThat(SchemaWalker.codePointLength("\uD83D\uDE00")).isEqualTo(1)
        assertThat(walk(mutate { it["name"] = JsonPrimitive(decomposed) })).isEmpty()
    }

    @Test
    fun `integers reject fractions, exponents, strings and out of range values`() {
        assertThat(codesAt(mutate { it["count"] = "3.0" })).containsExactly(OutputCodes.WRONG_JSON_TYPE to "/count")
        assertThat(codesAt(mutate { it["count"] = "1e1" })).containsExactly(OutputCodes.WRONG_JSON_TYPE to "/count")
        assertThat(codesAt(mutate { it["count"] = "\"3\"" })).containsExactly(OutputCodes.WRONG_JSON_TYPE to "/count")
        assertThat(codesAt(mutate { it["count"] = "11" })).containsExactly(OutputCodes.NUMBER_OUT_OF_RANGE to "/count")
        assertThat(codesAt(mutate { it["count"] = "-1" })).containsExactly(OutputCodes.NUMBER_OUT_OF_RANGE to "/count")
        assertThat(codesAt(mutate { it["count"] = "99999999999999999999" })).containsExactly(OutputCodes.NUMBER_OUT_OF_RANGE to "/count")
    }

    @Test
    fun `booleans and constants are exact`() {
        assertThat(codesAt(mutate { it["flag"] = "\"true\"" })).containsExactly(OutputCodes.WRONG_JSON_TYPE to "/flag")
        assertThat(codesAt(mutate { it["flag"] = "1" })).containsExactly(OutputCodes.WRONG_JSON_TYPE to "/flag")
        assertThat(codesAt(mutate { it["version"] = "2" })).containsExactly(OutputCodes.UNSUPPORTED_SCHEMA_VERSION to "/version")
        assertThat(codesAt(mutate { it["version"] = "\"1\"" })).containsExactly(OutputCodes.UNSUPPORTED_SCHEMA_VERSION to "/version")
        assertThat(codesAt(mutate { it["version"] = "null" })).containsExactly(OutputCodes.UNSUPPORTED_SCHEMA_VERSION to "/version")
    }

    @Test
    fun `arrays are checked for size, uniqueness and items`() {
        assertThat(codesAt(mutate { it["tags"] = "[]" })).containsExactly(OutputCodes.ARRAY_SIZE to "/tags")
        assertThat(codesAt(mutate { it["tags"] = "[\"a\",\"b\",\"c\"]" })).containsExactly(OutputCodes.ARRAY_SIZE to "/tags")
        assertThat(codesAt(mutate { it["tags"] = "[\"a\",\"a\"]" })).containsExactly(OutputCodes.DUPLICATE_ITEMS to "/tags")
        assertThat(codesAt(mutate { it["tags"] = "[\"a\",3]" })).containsExactly(OutputCodes.WRONG_JSON_TYPE to "/tags/1")
        assertThat(codesAt(mutate { it["tags"] = "{}" })).containsExactly(OutputCodes.WRONG_JSON_TYPE to "/tags")
    }

    @Test
    fun `unions are told apart by their discriminator`() {
        assertThat(codesAt(mutate { it["shape"] = "{\"side\":2}" })).containsExactly(OutputCodes.MISSING_FIELD to "/shape/type")
        assertThat(codesAt(mutate { it["shape"] = "{\"type\":1}" })).containsExactly(OutputCodes.WRONG_JSON_TYPE to "/shape/type")
        assertThat(codesAt(mutate { it["shape"] = "{\"type\":\"star\"}" })).containsExactly(OutputCodes.INVALID_ENUM_VALUE to "/shape/type")
        assertThat(codesAt(mutate { it["shape"] = "{\"type\":\"box\"}" })).containsExactly(OutputCodes.MISSING_FIELD to "/shape/side")
        assertThat(codesAt(mutate { it["shape"] = "[]" })).containsExactly(OutputCodes.WRONG_JSON_TYPE to "/shape")
        assertThat(codesAt(mutate { it["extra"] = "[]" })).containsExactly(OutputCodes.WRONG_JSON_TYPE to "/extra")
    }

    @Test
    fun `all defects are collected`() {
        val issues = walk(
            mutate {
                it["name"] = "1"
                it["count"] = "\"x\""
                it["junk"] = "1"
            },
        )
        assertThat(
            issues.map {
                it.code
            },
        ).containsExactly(OutputCodes.WRONG_JSON_TYPE, OutputCodes.WRONG_JSON_TYPE, OutputCodes.UNKNOWN_FIELD)
        assertThat(issues.map { it.stage }.toSet()).containsExactly(ValidationStage.S4_SCHEMA)
    }

    @Test
    fun `pointers escape slash and tilde`() {
        assertThat(SchemaWalker.child("", "a/b")).isEqualTo("/a~1b")
        assertThat(SchemaWalker.child("/x", "m~n")).isEqualTo("/x/m~0n")
    }

    @Test
    fun `nodes refuse inconsistent definitions`() {
        assertThrows<IllegalArgumentException> { SchemaNode.ObjectNode(mapOf("a" to SchemaNode.BooleanNode()), required = setOf("b")) }
        assertThrows<IllegalArgumentException> {
            SchemaNode.ObjectNode(mapOf("a" to SchemaNode.BooleanNode()), forbidden = setOf("a"))
        }
        assertThrows<IllegalArgumentException> {
            SchemaNode.UnionNode("type", mapOf("x" to SchemaNode.ObjectNode(mapOf("a" to SchemaNode.BooleanNode()))))
        }
        assertThat(SchemaNode.ConstNode(1).nullable).isFalse()
    }

    @Test
    fun `the rendered schema is closed and complete`() {
        val schema = renderJsonSchema("urn:test", "Test", root)
        assertThat(schema.getValue("\$schema").jsonPrimitive.content).isEqualTo("https://json-schema.org/draft/2020-12/schema")
        assertThat(schema.getValue("\$id").jsonPrimitive.content).isEqualTo("urn:test")
        assertThat(schema.getValue("additionalProperties").jsonPrimitive.content).isEqualTo("false")
        assertThat(schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }).doesNotContain("optional")
        val properties = schema.getValue("properties").jsonObject
        assertThat(properties.getValue("version").toString()).isEqualTo("""{"const":1}""")
        assertThat(properties.getValue("code").jsonObject.getValue("type").toString()).isEqualTo("""["string","null"]""")
        assertThat(properties.getValue("level").jsonObject.getValue("enum").toString()).isEqualTo("""["LOW","HIGH"]""")
        assertThat(properties.getValue("count").toString()).isEqualTo("""{"type":"integer","minimum":0,"maximum":10}""")
        assertThat(properties.getValue("tags").toString())
            .isEqualTo("""{"type":"array","items":{"type":"string"},"minItems":1,"maxItems":2,"uniqueItems":true}""")
        val shape = properties.getValue("shape").jsonObject.getValue("anyOf") as JsonArray
        assertThat(shape.first().toString()).isEqualTo("""{"type":"null"}""")
        assertThat(shape[2].jsonObject.getValue("properties").jsonObject.getValue("type").toString()).isEqualTo("""{"const":"box"}""")
        assertThat(properties.getValue("extra").toString()).isEqualTo("""{"type":["object","null"]}""")
        val nullableEnum = renderJsonSchema("u", "t", SchemaNode.StringNode(allowed = listOf("A"), nullable = true))
        assertThat(nullableEnum.getValue("enum").toString()).isEqualTo("""["A",null]""")
        val bounded = renderJsonSchema("u", "t", SchemaNode.StringNode(minLength = 1, maxLength = 2, pattern = "^a$"))
        assertThat(bounded.keys).containsAtLeast("minLength", "maxLength", "pattern")
    }
}
