package dev.agentle.ai.api.validation

import dev.agentle.ai.api.OutputSchema
import kotlinx.serialization.Serializable

/** A dashboard the model proposes in a chat reply: a title, the metric codes to chart and how many days to show. */
@Serializable
public data class DashboardProposal(val title: String, val metrics: List<String>, val days: Int)

/** A chat answer (ChatReplySchema v1): the text shown in the chat and, when the user asked for one, a dashboard. */
@Serializable
public data class ChatReplyOutput(val schemaVersion: Int, val reply: String, val dashboard: DashboardProposal?)

/**
 * ChatReplySchema v1, the reply contract of chat questions. The model writes the answer and may propose a dashboard,
 * which is declarative data only: a title, metric codes from [METRICS] and a day count. The app computes every metric on
 * the device from stored events; nothing in the reply is executed.
 */
public object ChatReplySchema {
    public const val NAME: String = "ChatReplySchema"
    public const val VERSION: Int = 1
    public const val MAX_BYTES: Int = 8192
    public const val MAX_METRICS: Int = 6
    public const val MAX_DAYS: Int = 90

    /** Every metric a dashboard can chart, with the description the model reads. */
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

    public val REPLY: TextRules = TextRules(maxChars = 700, maxSentences = 6)
    public val TITLE: TextRules = TextRules(maxChars = 40, maxSentences = 1)

    public val ROOT: SchemaNode.ObjectNode = SchemaNode.ObjectNode(
        properties = linkedMapOf(
            "schemaVersion" to SchemaNode.ConstNode(VERSION.toLong(), OutputCodes.UNSUPPORTED_SCHEMA_VERSION),
            "reply" to textNode(REPLY),
            "dashboard" to SchemaNode.ObjectNode(
                properties = linkedMapOf(
                    "title" to textNode(TITLE),
                    "metrics" to SchemaNode.ArrayNode(
                        SchemaNode.StringNode(allowed = METRICS.keys.toList()),
                        minItems = 1,
                        maxItems = MAX_METRICS,
                        uniqueItems = true,
                    ),
                    "days" to SchemaNode.IntegerNode(minimum = 1, maximum = MAX_DAYS.toLong()),
                ),
                nullable = true,
            ),
        ),
    )

    public val SCHEMA: OutputSchema =
        OutputSchema(NAME, VERSION, renderJsonSchema("urn:agentle:chat-reply:v$VERSION", "$NAME v$VERSION", ROOT).toString())

    /** The metric list as the model reads it in the instructions: one `CODE: description` per line. */
    public val METRIC_GUIDE: String = METRICS.entries.joinToString("\n") { (code, description) -> "$code: $description" }

    public val validator: SchemaValidator<ChatReplyOutput> = Validator

    private object Validator : SchemaBackedValidator<ChatReplyOutput>(SCHEMA, ROOT, ChatReplyOutput.serializer(), MAX_BYTES) {
        override fun semanticIssues(value: ChatReplyOutput, context: OutputValidationContext): List<ValidationIssue> {
            val fields = listOfNotNull(
                TextField("/reply", value.reply, REPLY),
                value.dashboard?.let { TextField("/dashboard/title", it.title, TITLE) },
            )
            return textPolicyIssues(fields, context)
        }
    }
}
