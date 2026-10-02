package dev.agentle.jitai.dsl.nl

import com.google.common.truth.Truth.assertThat
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.core.time.LocalTimeWindow
import dev.agentle.jitai.dsl.codec.ArraySpec
import dev.agentle.jitai.dsl.codec.BooleanSpec
import dev.agentle.jitai.dsl.codec.ConstIntSpec
import dev.agentle.jitai.dsl.codec.DiscriminatorSpec
import dev.agentle.jitai.dsl.codec.EnumSpec
import dev.agentle.jitai.dsl.codec.FeatureIdSpec
import dev.agentle.jitai.dsl.codec.IntSpec
import dev.agentle.jitai.dsl.codec.LiteralSpec
import dev.agentle.jitai.dsl.codec.NullableSpec
import dev.agentle.jitai.dsl.codec.NumberSpec
import dev.agentle.jitai.dsl.codec.ObjectSpec
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.codec.Schemas
import dev.agentle.jitai.dsl.codec.Spec
import dev.agentle.jitai.dsl.codec.StringSpec
import dev.agentle.jitai.dsl.codec.UnionSpec
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiEventType
import dev.agentle.jitai.dsl.model.JitaiKind
import dev.agentle.jitai.dsl.model.OutcomeMetric
import dev.agentle.jitai.dsl.model.QuietHoursPolicy
import dev.agentle.jitai.dsl.model.SnoozeMode
import dev.agentle.jitai.dsl.model.SnoozeOption
import dev.agentle.jitai.dsl.model.Tone
import dev.agentle.jitai.dsl.model.WeekDay
import dev.agentle.jitai.dsl.rule.OnUnknown
import dev.agentle.jitai.dsl.rule.Operator
import dev.agentle.jitai.dsl.testing.get
import kotlinx.datetime.LocalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** R10 §13.1-13.3: the versioned prompt, the generated catalog, the published schema and the round inputs. */
class NlContractTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("enums")
    fun `schema enums are the Kotlin enums (available members only)`(pointer: String, expected: List<String?>) {
        val values = NlContract.schema[pointer].jsonObject.getValue("enum").jsonArray.map { (it as JsonPrimitive).contentOrNullValue() }

        assertThat(values).containsExactlyElementsIn(expected).inOrder()
    }

    @Test
    fun `the S4 walker mirrors the published schema`() {
        val problems = ArrayList<String>()

        SchemaMirror(NlContract.schema.jsonObject, problems).compare(NlContract.schema, Schemas.proposal, "")

        assertThat(problems).isEmpty()
    }

    @Test
    fun `the schema mirror notices a different schema`() {
        val problems = ArrayList<String>()
        val draft = NlContract.schema["/\$defs/draft"]

        SchemaMirror(NlContract.schema.jsonObject, problems).compare(draft, Schemas.definition, "")

        assertThat(problems).isNotEmpty()
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `schema required lists match the decoded classes`() {
        fun required(pointer: String) =
            NlContract.schema[pointer].jsonObject.getValue("required").jsonArray.map { it.jsonPrimitive.content }

        assertThat(required("")).containsExactlyElementsIn(JitaiProposal.serializer().descriptor.elementNames).inOrder()
        assertThat(required("/\$defs/draft")).containsExactlyElementsIn(JitaiDraft.serializer().descriptor.elementNames).inOrder()
        assertThat(required("/\$defs/delivery")).containsExactlyElementsIn(DraftDelivery.serializer().descriptor.elementNames).inOrder()
        assertThat(required("/\$defs/question")).containsExactlyElementsIn(Question.serializer().descriptor.elementNames).inOrder()
        assertThat(required("/\$defs/assumption")).containsExactlyElementsIn(Assumption.serializer().descriptor.elementNames).inOrder()
        assertThat(required("/\$defs/unsupported")).containsExactlyElementsIn(Unsupported.serializer().descriptor.elementNames).inOrder()
        assertThat(NlContract.schema["/\$schema"].jsonPrimitive.content).isEqualTo("https://json-schema.org/draft/2020-12/schema")
        assertThat(NlContract.minifiedSchema.length).isIn(10_000..11_000)
    }

    @Test
    fun `the prompt is jitai-nl-v1 with the generated catalog and the minified schema`() {
        val prompt = NlContract.instructions

        assertThat(prompt).startsWith("jitai-nl-v1\n\nROLE\nYou convert one request from the user of Agentle")
        listOf("OUTPUT FORMAT", "RULE LANGUAGE", "LIMITS (anything else is rejected)", "ASSUMPTIONS AND QUESTIONS", "TEXT", "SAFETY")
            .forEach { assertThat(prompt).contains("\n$it\n") }
        (1..23).forEach { assertThat(prompt).contains("\n$it. ") }
        assertThat(prompt).contains("\nCATALOG (version ${NlContract.catalogVersion})\n${NlContract.catalogText}\n\nSCHEMA\n")
        assertThat(prompt).endsWith("\nSCHEMA\n" + NlContract.minifiedSchema)
        assertThat(prompt).contains("and event for activity\n    changes, new sleep data or charging.")
        assertThat(prompt).doesNotContain("places")
        assertThat(prompt).doesNotContain("{catalog")
        assertThat(NlContract.PROMPT_VERSION).isEqualTo("jitai-nl-v1")
    }

    @Test
    fun `catalog lines come from the catalog the validator reads (lifecycle-battery-06)`() {
        val lines = NlContract.catalogLines
        val available = RealtimeFeatureCatalog.all.filter { it.isAvailable }

        assertThat(
            lines.filter {
                it.startsWith("feature ")
            }.map { it.split(' ')[1] },
        ).containsExactlyElementsIn(available.map { it.id }).inOrder()
        assertThat(lines.filter { it.startsWith("event ") }.map { it.split(' ')[1] })
            .containsExactlyElementsIn(JitaiEventType.entries.filter { it.isAvailable }.map { it.name }).inOrder()
        assertThat(lines.count { it.startsWith("metric ") }).isEqualTo(OutcomeMetric.entries.size)
        assertThat(lines.count { it.startsWith("category ") }).isEqualTo(JitaiCategory.entries.size)
        assertThat(lines.count { it.startsWith("channel ") }).isEqualTo(DeliveryChannel.entries.size)
        assertThat(NlContract.catalogText).doesNotContain("location_class")
        assertThat(NlContract.catalogText).doesNotContain("LOCATION_CLASS_CHANGED")
        assertThat(line("feature steps_today ")).startsWith(
            "feature steps_today | INT steps | 0..150000 | ops gt gte lt lte eq neq between in | args - | typical 3000 5000 7500 | ",
        )
        assertThat(line("feature app_minutes_since ")).startsWith(
            "feature app_minutes_since | INT min | 0..1440 | ops gt gte lt lte eq neq between in | args appLabel|package, since | " +
                "typical 20 30 45 | ",
        )
        assertThat(
            line("feature sleep_minutes_last_night "),
        ).contains("| INT min | 0..1440 | ops gt gte lt lte eq neq between in | args - | typical 360 420 |")
        assertThat(line("feature device_interactive ")).startsWith("feature device_interactive | BOOL | - | ops eq neq | args - | - | ")
        assertThat(
            line("feature bedtime_last_night "),
        ).contains("| NIGHT_TIME | \"HH:mm\" on a night clock from 12:00 to 11:59 | ops gt gte lt lte between |")
        assertThat(line("feature day_type ")).contains("| ENUM | WEEKDAY WEEKEND | ops eq neq in |")
        assertThat(line("feature day_of_week ")).contains("| DAY_OF_WEEK | MON TUE WED THU FRI SAT SUN |")
        assertThat(line("feature foreground_app ")).contains("| PACKAGE | an Android package name | ops eq neq in |")
        assertThat(line("feature minutes_since_last_delivery ")).contains("| args jitai |")
        assertThat(line("feature app_category_minutes_since ")).contains("| args category, since |")
        assertThat(
            line("metric STEPS_AFTER "),
        ).isEqualTo("metric STEPS_AFTER | proximal | window 10..120 | args - | steps in the window after the decision")
        assertThat(line("metric APP_MINUTES_AFTER ")).contains("| args appLabel|package |")
        assertThat(line("metric APP_CATEGORY_MINUTES_AFTER ")).contains("| args category |")
        assertThat(line("metric BEDTIME_NEXT ")).startsWith("metric BEDTIME_NEXT | distal | window null | args - |")
        assertThat(line("category PHYSICAL_ACTIVITY ")).isEqualTo("category PHYSICAL_ACTIVITY | walking, exercise and movement reminders")
        assertThat(line("event POWER_CONNECTED ")).endsWith("(best effort: only while Agentle runs)")
        assertThat(line("event SLEEP_SESSION_AVAILABLE ")).isEqualTo("event SLEEP_SESSION_AVAILABLE | new sleep data arrives")
    }

    @Test
    fun `catalog version is the first 12 hex digits of the catalog text hash`() {
        assertThat(NlContract.catalogVersion).matches("[0-9a-f]{12}")
        assertThat(NlContract.catalogVersion).isEqualTo(RuleCodec.sha256Hex(NlContract.catalogText).take(12))
    }

    @Test
    fun `first round items match R10 13_2`() {
        val settings = NlSettings(quietHours = LocalTimeWindow(LocalTime(22, 0), LocalTime(7, 0)))
        val items = NlContract.firstRound(settings, "  Remind me to wind down if I use Instagram too much after 10 PM.\n")

        assertThat(items).containsExactly(
            NlItem(NlRole.DEVELOPER, "SETTINGS\nquietHours: 22:00-07:00\nweekendDays: SAT,SUN\nclock: 12h\nlocale: en-US"),
            NlItem(NlRole.USER, "USER_REQUEST\nRemind me to wind down if I use Instagram too much after 10 PM."),
        ).inOrder()
        assertThat(
            NlContract.settingsInput(
                NlSettings(quietHours = null, weekendDays = listOf(WeekDay.FRI), use24HourClock = true, locale = "de-DE"),
            ),
        )
            .isEqualTo("SETTINGS\nquietHours: off\nweekendDays: FRI\nclock: 24h\nlocale: de-DE")
    }

    @Test
    fun `answers go back sorted after the previous reply`() {
        val previous = listOf(NlItem(NlRole.USER, "USER_REQUEST\nx"))

        val items = NlContract.answerRound(previous, "{\"reply\":1}", mapOf(QuestionId.Q2 to " 6 PM ", QuestionId.Q1 to "Weekdays"))

        assertThat(items).containsExactly(
            previous.single(),
            NlItem(NlRole.ASSISTANT, "{\"reply\":1}"),
            NlItem(NlRole.USER, "ANSWERS\nq1: Weekdays\nq2: 6 PM"),
        ).inOrder()
    }

    @Test
    fun `requests are 1-500 characters after trimming`() {
        assertThat(NlContract.isRequestAcceptable("")).isFalse()
        assertThat(NlContract.isRequestAcceptable("   ")).isFalse()
        assertThat(NlContract.isRequestAcceptable(" x ")).isTrue()
        assertThat(NlContract.isRequestAcceptable("a".repeat(500))).isTrue()
        assertThat(NlContract.isRequestAcceptable("a".repeat(501))).isFalse()
        assertThat(NlContract.MAX_MODEL_CALLS).isEqualTo(NlContract.MAX_REPAIR_ROUNDS + NlContract.MAX_CLARIFICATION_ROUNDS + 1)
    }

    private fun line(prefix: String): String = NlContract.catalogLines.single { it.startsWith(prefix) }

    private fun JsonPrimitive.contentOrNullValue(): String? = if (this is JsonNull) null else content

    companion object {
        private fun enum(pointer: String, values: List<String?>) = Arguments.of(pointer, values)

        private val CATEGORIES = listOf(null) + RealtimeFeatureCatalog.APP_CATEGORIES

        @JvmStatic
        fun enums(): List<Arguments> = listOf(
            enum("/properties/status", ProposalStatus.entries.map { it.name }),
            enum("/\$defs/jitaiCategory", JitaiCategory.entries.map { it.name }),
            enum("/\$defs/unsupported/properties/reason", UnsupportedReason.entries.map { it.name }),
            enum("/\$defs/question/properties/id", QuestionId.entries.map { it.wire }),
            enum("/\$defs/draft/properties/kind", JitaiKind.entries.map { it.name }),
            enum("/\$defs/eventType", JitaiEventType.entries.filter { it.isAvailable }.map { it.name }),
            enum("/\$defs/window/properties/days/anyOf/1/items", WeekDay.entries.map { it.name }),
            enum("/\$defs/group/properties/type", listOf("all", "any")),
            enum(
                "/\$defs/compare/properties/type",
                Operator.entries.filter {
                    it != Operator.BETWEEN && it != Operator.IN
                }.map { it.wire },
            ),
            enum("/\$defs/onUnknown", listOf(null) + OnUnknown.entries.map { it.name }),
            enum("/\$defs/featureId", RealtimeFeatureCatalog.all.filter { it.isAvailable }.map { it.id }),
            enum("/\$defs/args/properties/category", CATEGORIES),
            enum("/\$defs/delivery/properties/channel", DeliveryChannel.entries.map { it.name }),
            enum("/\$defs/delivery/properties/quietHoursPolicy", QuietHoursPolicy.entries.map { it.name }),
            enum("/\$defs/content/anyOf/3/properties/tone", Tone.entries.map { it.name }),
            enum("/\$defs/snooze/properties/mode", SnoozeMode.entries.map { it.name }),
            enum("/\$defs/snooze/properties/options/items", SnoozeOption.entries.map { it.name }),
            enum("/\$defs/metricRef/properties/metric", OutcomeMetric.entries.map { it.name }),
        )
    }
}

/**
 * Compares the published JSON schema with the closed [Spec] tree of the S4 walker: the same objects, keys, unions,
 * arrays, nullability and enums. Value constraints (patterns, lengths, ranges) are S6 checks and are not compared.
 */
private class SchemaMirror(private val root: JsonObject, private val problems: MutableList<String>) {
    private val visited = HashSet<Pair<String, Spec>>()

    fun compare(node: JsonElement, spec: Spec, path: String) {
        val ref = (node as? JsonObject)?.get("\$ref")?.jsonPrimitive?.content
        if (ref != null && !visited.add(ref to spec)) return
        val resolved = resolve(node)
        when (spec) {
            is NullableSpec -> nullable(resolved, spec, path)

            is ObjectSpec -> objectSpec(resolved, spec, path)

            is UnionSpec -> union(resolved, spec, path)

            is ArraySpec -> if (type(resolved) !=
                "array"
            ) {
                problem(path, "not an array")
            } else {
                compare(resolved.getValue("items"), spec.item, "$path/*")
            }

            is EnumSpec -> enumSpec(resolved, spec, path)

            is ConstIntSpec -> if (resolved["const"]?.jsonPrimitive?.content != spec.value.toString()) problem(path, "const differs")

            else -> scalar(resolved, spec, path)
        }
    }

    private fun scalar(resolved: JsonObject, spec: Spec, path: String) {
        when (spec) {
            StringSpec, FeatureIdSpec -> if (type(resolved) != "string" && "enum" !in resolved && "\$ref" !in resolved) {
                problem(path, "not a string")
            }

            DiscriminatorSpec -> if ("const" !in resolved && "enum" !in resolved) problem(path, "no discriminator")

            IntSpec -> if (type(resolved) != "integer") problem(path, "not an integer")

            NumberSpec -> if (type(resolved) != "number") problem(path, "not a number")

            BooleanSpec -> if (type(resolved) != "boolean") problem(path, "not a boolean")

            LiteralSpec -> if (resolved["type"] !is JsonArray) problem(path, "literal is not a type list")

            else -> problem(path, "unexpected spec $spec")
        }
    }

    private fun nullable(node: JsonObject, spec: NullableSpec, path: String) {
        val anyOf = node["anyOf"]?.jsonArray
        val types = node["type"] as? JsonArray
        val enum = node["enum"]?.jsonArray
        when {
            anyOf != null && anyOf.any { type(resolve(it)) == "null" } ->
                anyOf.filter { type(resolve(it)) != "null" }.forEach { compare(it, spec.inner, path) }

            types != null && JsonPrimitive("null") in types -> {
                val rest = types.filter { it.jsonPrimitive.content != "null" }
                compare(JsonObject(node + ("type" to (rest.singleOrNull() ?: JsonArray(rest)))), spec.inner, path)
            }

            enum != null && JsonNull in enum -> compare(JsonObject(node + ("enum" to JsonArray(enum - JsonNull))), spec.inner, path)

            else -> problem(path, "nullable in the walker only")
        }
    }

    private fun objectSpec(node: JsonObject, spec: ObjectSpec, path: String) {
        val properties = node["properties"]?.jsonObject ?: return problem(path, "not an object")
        if (properties.keys != spec.fields.keys) problem(path, "keys ${properties.keys} != ${spec.fields.keys}")
        val required = node["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        if (!spec.optionalKeys && required.toSet() != properties.keys) problem(path, "required $required")
        spec.forbidden.forEach { if (it in properties) problem(path, "forbidden key $it is in the schema") }
        if (node["additionalProperties"] != JsonPrimitive(false)) problem(path, "open object")
        spec.fields.forEach { (key, child) -> properties[key]?.let { compare(it, child, "$path/$key") } }
    }

    private fun union(node: JsonObject, spec: UnionSpec, path: String) {
        val branches = node["anyOf"]?.jsonArray?.map { resolve(it) } ?: listOf(node)
        val byType = HashMap<String, JsonObject>()
        branches.forEach { branch ->
            val type = branch["properties"]?.jsonObject?.get("type")?.jsonObject
            val values =
                type?.get("const")?.let { listOf(it.jsonPrimitive.content) }
                    ?: type?.get("enum")?.jsonArray?.map { it.jsonPrimitive.content }
            values.orEmpty().forEach { byType[it] = branch }
        }
        if (byType.keys != spec.branches.keys) problem(path, "branches ${byType.keys} != ${spec.branches.keys}")
        spec.branches.forEach { (type, branch) -> byType[type]?.let { objectSpec(it, branch, "$path<$type>") } }
    }

    private fun enumSpec(node: JsonObject, spec: EnumSpec, path: String) {
        val values = node["enum"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: node["const"]?.let { listOf(it.jsonPrimitive.content) }
            ?: return problem(path, "not an enum")
        // Stored rules keep decoding unavailable events (E028 in S6), so the walker may know more values than the schema.
        val extra = spec.values - values.toSet()
        if (!spec.values.containsAll(values) || extra.any { name -> JitaiEventType.entries.none { it.name == name && !it.isAvailable } }) {
            problem(path, "enum $values != ${spec.values}")
        }
    }

    private fun resolve(node: JsonElement): JsonObject {
        var current = node.jsonObject
        while (true) {
            val ref = current["\$ref"]?.jsonPrimitive?.content ?: return current
            current = root.getValue("\$defs").jsonObject.getValue(ref.removePrefix("#/\$defs/")).jsonObject
        }
    }

    private fun type(node: JsonObject): String? = (node["type"] as? JsonPrimitive)?.content

    private fun problem(path: String, text: String) {
        problems += "${path.ifEmpty { "/" }}: $text"
    }
}
