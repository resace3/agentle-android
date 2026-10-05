package dev.agentle.ai.api.validation

import dev.agentle.ai.api.OutputSchema
import dev.agentle.ai.api.screen.ScreenCatalog
import dev.agentle.ai.api.screen.ScreenRules
import dev.agentle.ai.api.screen.ScreenSpec
import kotlinx.serialization.Serializable

/** A chat answer (ChatReplySchema v2): the text shown in the chat and, when the user asked for one, a screen. */
@Serializable
public data class ChatReplyOutput(val schemaVersion: Int, val reply: String, val screen: ScreenSpec?)

/**
 * ChatReplySchema v2, the reply contract of chat questions. The model writes the answer and may design a screen from
 * Agentle's parts ([ScreenCatalog], the component shape of Google's A2UI), which is declarative data only: its metric
 * parts name a metric code and a day count, and the app computes every number on the device from stored events;
 * nothing in the reply is executed. v1 had a dashboard (a title, metric codes and a day count) instead of the screen.
 */
public object ChatReplySchema {
    public const val NAME: String = "ChatReplySchema"
    public const val VERSION: Int = 2
    public const val MAX_BYTES: Int = 16_384
    public const val MAX_DAYS: Int = ScreenCatalog.MAX_DAYS

    /** Every metric a screen can show, with the description the model reads. */
    public val METRICS: Map<String, String> = ScreenCatalog.METRICS

    public val REPLY: TextRules = TextRules(maxChars = 700, maxSentences = 6)

    public val ROOT: SchemaNode.ObjectNode = SchemaNode.ObjectNode(
        properties = linkedMapOf(
            "schemaVersion" to SchemaNode.ConstNode(VERSION.toLong(), OutputCodes.UNSUPPORTED_SCHEMA_VERSION),
            "reply" to textNode(REPLY),
            "screen" to ScreenRules.node(nullable = true),
        ),
    )

    public val SCHEMA: OutputSchema =
        OutputSchema(NAME, VERSION, renderJsonSchema("urn:agentle:chat-reply:v$VERSION", "$NAME v$VERSION", ROOT).toString())

    /** The metric list as the model reads it in the instructions: one `CODE: description` per line. */
    public val METRIC_GUIDE: String = ScreenCatalog.METRIC_GUIDE

    public val validator: SchemaValidator<ChatReplyOutput> = Validator

    private object Validator : SchemaBackedValidator<ChatReplyOutput>(SCHEMA, ROOT, ChatReplyOutput.serializer(), MAX_BYTES) {
        override fun semanticIssues(value: ChatReplyOutput, context: OutputValidationContext): List<ValidationIssue> =
            textPolicyIssues(listOf(TextField("/reply", value.reply, REPLY)), context) +
                value.screen?.let { ScreenRules.semanticIssues(it, "/screen", context) }.orEmpty()
    }
}
