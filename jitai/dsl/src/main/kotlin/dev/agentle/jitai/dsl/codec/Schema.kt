package dev.agentle.jitai.dsl.codec

import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.jitai.dsl.model.JitaiEventType
import dev.agentle.jitai.dsl.rule.RuleLiteral
import dev.agentle.jitai.dsl.rule.UtcInstantSerializer
import dev.agentle.jitai.dsl.validation.IssueCode
import dev.agentle.jitai.dsl.validation.IssueSink
import dev.agentle.jitai.dsl.validation.Stage
import dev.agentle.jitai.dsl.validation.TypeDescriptions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A closed schema for the S4 walk (R10 §11.1). It checks keys, JSON types, enum members and discriminators only;
 * string patterns, lengths, sizes and ranges are S6 checks with their specific codes.
 */
internal sealed interface Spec {
    /** What a value must be, for E008 messages ("an object", "an integer or null"). */
    val expected: String
}

/** A closed object. [optionalKeys] true means an absent key reads as `null` (only `args`). */
internal class ObjectSpec(
    val name: String,
    val optionalKeys: Boolean = false,
    val forbidden: Set<String> = emptySet(),
    fields: () -> Map<String, Spec>,
) : Spec {
    val fields: Map<String, Spec> by lazy(fields)
    override val expected: String get() = "an object"
}

/** An object with any keys whose values all match [value] (`provenance.appLabels`). */
internal class MapSpec(val value: Spec) : Spec {
    override val expected: String get() = "an object"
}

/** Any JSON object (`evidence`). */
internal data object AnyObjectSpec : Spec {
    override val expected: String get() = "an object"
}

internal class ArraySpec(val item: Spec) : Spec {
    override val expected: String get() = "an array"
}

internal class NullableSpec(val inner: Spec) : Spec {
    override val expected: String get() = "${inner.expected} or null"
}

internal data object StringSpec : Spec {
    override val expected: String get() = "a string"
}

internal data object BooleanSpec : Spec {
    override val expected: String get() = "a boolean"
}

/** An integer token (no fraction or exponent) that fits in 32 bits. */
internal data object IntSpec : Spec {
    override val expected: String get() = "an integer"
}

internal data object NumberSpec : Spec {
    override val expected: String get() = "a number"
}

/** A string from [values]; any other string is [unknownCode] (E009, or E030 for event types). */
internal class EnumSpec(val values: List<String>, val unknownCode: IssueCode = IssueCode.E009) : Spec {
    override val expected: String get() = "a string"
}

/** The value of a union's `type` key; already checked when the branch was chosen. */
internal data object DiscriminatorSpec : Spec {
    override val expected: String get() = "a string"
}

/** A sealed hierarchy with discriminator `type` (R10 §3.6). */
internal class UnionSpec(val name: String, branches: () -> Map<String, ObjectSpec>) : Spec {
    val branches: Map<String, ObjectSpec> by lazy(branches)
    override val expected: String get() = "an object"
}

/** A leaf literal: any number token, string or boolean. Other JSON values are E015 (R10 §11.2: literals use E015). */
internal data object LiteralSpec : Spec {
    override val expected: String get() = "a number, string or boolean"
}

/** A feature id: any string here; membership is E010/E028 in S6. */
internal data object FeatureIdSpec : Spec {
    override val expected: String get() = "a string"
}

/** The integer [value] exactly; anything else is [code] (E004 for `schemaVersion`). */
internal class ConstIntSpec(val value: Int, val code: IssueCode) : Spec {
    override val expected: String get() = "the integer $value"
}

/** The boolean `true` (`approvalRequired` of a discovered proposal: there is no autonomous mode in v1). */
internal data object TrueSpec : Spec {
    override val expected: String get() = "true"
}

/** A canonical instant `yyyy-MM-ddTHH:mm:ssZ`. */
internal data object InstantSpec : Spec {
    override val expected: String get() = "an ISO-8601 UTC instant (yyyy-MM-ddTHH:mm:ssZ)"
}

/** Walks a [JsonElement] against a [Spec] and collects every finding (R10 §11.1 S4). */
internal class SchemaWalker(private val sink: IssueSink, private val stage: Stage = Stage.S4) {
    fun walk(element: JsonElement, spec: Spec, path: String) {
        when (spec) {
            is ObjectSpec -> walkObject(element, spec, path)
            is UnionSpec -> walkUnion(element, spec, path)
            is ArraySpec -> walkArray(element, spec, path)
            is NullableSpec -> if (element !is JsonNull) walkNonNull(element, spec, path)
            is MapSpec -> walkMap(element, spec, path)
            is EnumSpec -> walkEnum(element, spec, path)
            is ConstIntSpec -> walkConstInt(element, spec, path)
            is LiteralSpec -> walkLiteral(element, path, feature = null)
            else -> walkScalar(element, spec, path)
        }
    }

    private fun walkNonNull(element: JsonElement, spec: NullableSpec, path: String) {
        // A nullable value of the wrong type is reported with the nullable expectation ("an integer or null").
        if (!jsonTypeMatches(element, spec.inner)) {
            typeError(element, spec, path)
            return
        }
        walk(element, spec.inner, path)
    }

    private fun walkObject(element: JsonElement, spec: ObjectSpec, path: String) {
        val obj = element as? JsonObject ?: return typeError(element, spec, path)
        val feature = (obj["feature"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        for ((key, value) in obj) {
            val childPath = JsonPointer.child(path, key)
            val fieldSpec = spec.fields[key]
            when {
                fieldSpec == null && key in spec.forbidden -> sink.add(IssueCode.E005, stage, childPath)
                fieldSpec == null -> sink.add(IssueCode.E006, stage, childPath)
                fieldSpec is LiteralSpec -> walkLiteral(value, childPath, feature)
                fieldSpec is ArraySpec && fieldSpec.item is LiteralSpec -> walkLiteralArray(value, fieldSpec, childPath, feature)
                else -> walk(value, fieldSpec, childPath)
            }
        }
        if (!spec.optionalKeys) {
            spec.fields.keys.filterNot { it in obj }.forEach { sink.add(IssueCode.E007, stage, JsonPointer.child(path, it)) }
        }
    }

    private fun walkUnion(element: JsonElement, spec: UnionSpec, path: String) {
        val obj = element as? JsonObject ?: return typeError(element, spec, path)
        val typePath = JsonPointer.child(path, "type")
        val typeElement = obj["type"] ?: return sink.add(IssueCode.E007, stage, typePath)
        val type = (typeElement as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return typeError(typeElement, StringSpec, typePath)
        val branch = spec.branches[type]
        if (branch == null) {
            val params = mapOf("allowed" to spec.branches.keys.joinToString(", "), "value" to type)
            sink.add(IssueCode.E009, stage, typePath, params)
            return
        }
        walkObject(obj, branch, path)
    }

    private fun walkArray(element: JsonElement, spec: ArraySpec, path: String) {
        val array = element as? JsonArray ?: return typeError(element, spec, path)
        array.forEachIndexed { index, item -> walk(item, spec.item, JsonPointer.child(path, index)) }
    }

    private fun walkMap(element: JsonElement, spec: MapSpec, path: String) {
        val obj = element as? JsonObject ?: return typeError(element, spec, path)
        obj.forEach { (key, value) -> walk(value, spec.value, JsonPointer.child(path, key)) }
    }

    private fun walkEnum(element: JsonElement, spec: EnumSpec, path: String) {
        val primitive = element as? JsonPrimitive
        if (primitive == null || primitive is JsonNull || !primitive.isString) return typeError(element, spec, path)
        val value = primitive.content
        if (value in spec.values) return
        if (spec.unknownCode == IssueCode.E030) {
            val allowed = JitaiEventType.entries.filter { it.isAvailable }.joinToString(", ") { it.name }
            sink.add(IssueCode.E030, stage, path, mapOf("value" to value, "allowed" to allowed))
        } else {
            sink.add(IssueCode.E009, stage, path, mapOf("allowed" to spec.values.joinToString(", "), "value" to value))
        }
    }

    private fun walkConstInt(element: JsonElement, spec: ConstIntSpec, path: String) {
        val primitive = element as? JsonPrimitive
        val ok = primitive != null && primitive !is JsonNull && !primitive.isString && primitive.content == spec.value.toString()
        if (!ok) sink.add(spec.code, stage, path, mapOf("value" to element.toString()))
    }

    private fun walkLiteralArray(element: JsonElement, spec: ArraySpec, path: String, feature: String?) {
        val array = element as? JsonArray ?: return typeError(element, spec, path)
        array.forEachIndexed { index, item -> walkLiteral(item, JsonPointer.child(path, index), feature) }
    }

    private fun walkLiteral(element: JsonElement, path: String, feature: String?) {
        if (RuleLiteral.fromJson(element) != null) return
        val definition = feature?.let { RealtimeFeatureCatalog[it] }
        val expected = definition?.let { TypeDescriptions.literal(it) } ?: LiteralSpec.expected
        val params = mapOf("expected" to expected, "feature" to (feature ?: "this feature"), "json" to element.toString())
        sink.add(IssueCode.E015, stage, path, params)
    }

    private fun walkScalar(element: JsonElement, spec: Spec, path: String) {
        if (!jsonTypeMatches(element, spec)) return typeError(element, spec, path)
        val primitive = element as? JsonPrimitive ?: return
        when (spec) {
            IntSpec -> if (primitive.content.toIntOrNull() == null) {
                sink.add(IssueCode.E008, stage, path, mapOf("expected" to spec.expected, "actual" to "an integer out of range"))
            }

            InstantSpec -> if (UtcInstantSerializer.parse(primitive.content) == null) {
                sink.add(IssueCode.E008, stage, path, mapOf("expected" to spec.expected, "actual" to "another string"))
            }

            TrueSpec -> if (primitive.content != "true") {
                sink.add(IssueCode.E008, stage, path, mapOf("expected" to spec.expected, "actual" to "false"))
            }

            NumberSpec -> if (primitive.content.toDoubleOrNull()?.isFinite() != true) {
                sink.add(IssueCode.E008, stage, path, mapOf("expected" to spec.expected, "actual" to "a number out of range"))
            }

            else -> Unit
        }
    }

    private fun typeError(element: JsonElement, spec: Spec, path: String) {
        sink.add(IssueCode.E008, stage, path, mapOf("expected" to spec.expected, "actual" to actualType(element)))
    }

    companion object {
        /** The JSON type name of [element] for E008 ("non-integer number" for `1.5` where an integer is required). */
        fun actualType(element: JsonElement): String = when (element) {
            is JsonNull -> "null"

            is JsonObject -> "object"

            is JsonArray -> "array"

            is JsonPrimitive -> when {
                element.isString -> "string"
                element.content == "true" || element.content == "false" -> "boolean"
                RuleLiteral.INTEGER.matches(element.content) -> "integer"
                else -> "non-integer number"
            }
        }

        /** True when the JSON type of [element] is the one [spec] needs (ranges and patterns are not checked). */
        fun jsonTypeMatches(element: JsonElement, spec: Spec): Boolean {
            val kind = actualType(element)
            return when (spec) {
                is ObjectSpec, is MapSpec, AnyObjectSpec, is UnionSpec -> kind == "object"
                is ArraySpec -> kind == "array"
                is NullableSpec -> kind == "null" || jsonTypeMatches(element, spec.inner)
                StringSpec, is EnumSpec, DiscriminatorSpec, FeatureIdSpec, InstantSpec -> kind == "string"
                BooleanSpec, TrueSpec -> kind == "boolean"
                IntSpec -> kind == "integer"
                NumberSpec -> kind == "integer" || kind == "non-integer number"
                is ConstIntSpec, LiteralSpec -> true
            }
        }
    }
}
