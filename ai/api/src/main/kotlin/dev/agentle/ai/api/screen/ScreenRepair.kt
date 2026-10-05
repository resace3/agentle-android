package dev.agentle.ai.api.screen

import dev.agentle.ai.api.validation.OutputCodes
import dev.agentle.ai.api.validation.ValidationIssue

/**
 * The one retry of a chat answer whose screen failed [ScreenRules]: the request again, led by what was wrong in words.
 * Like A2UI's VALIDATION_FAILED error, the model learns which rule broke; it never sees its own output again, and the
 * issue codes and paths carry no model text.
 */
public object ScreenRepair {
    /** True when every issue is in the screen, so the answer is worth one retry. */
    public fun applies(issues: List<ValidationIssue>): Boolean =
        issues.isNotEmpty() && issues.all { it.path == SCREEN || it.path.startsWith("$SCREEN/") }

    /** The retried request: the rules that broke first (the request is cut at its end if it gets too long), then [request]. */
    public fun request(request: String, issues: List<ValidationIssue>): String {
        val broken = issues.map { RULES[it.code] ?: OTHER }.distinct()
        return "Your last screen for this request broke these rules, so design it again and change nothing else: " +
            broken.joinToString(". ") + ". The request: " + request
    }

    private const val SCREEN = "/screen"
    private const val OTHER = "a part did not match the parts list"
    private val RULES = mapOf(
        OutputCodes.SCREEN_DUPLICATE_ID to "two parts had the same id",
        OutputCodes.SCREEN_ROOT_MISSING to "no part had the id root",
        OutputCodes.SCREEN_BAD_REFERENCE to "a child id did not name exactly one other part",
        OutputCodes.SCREEN_UNREACHABLE to "a part was not placed inside root",
        OutputCodes.SCREEN_TOO_DEEP to "parts were nested more than four levels deep",
        OutputCodes.SCREEN_TOTAL_OF_RATE to "a tile showed the total of a heart rate",
        OutputCodes.NUMBER_IN_POOLED_TEXT to "a title or text had a number in it",
        OutputCodes.TEXT_LENGTH to "a title or text was too long",
        OutputCodes.TOO_MANY_SENTENCES to "a title or text was too long",
        OutputCodes.ARRAY_SIZE to "there were too many parts, or a row had more than three",
        OutputCodes.TEXT_CONTAINS_CONTACT to "a text had a link, address or phone number",
        OutputCodes.TEXT_FORBIDDEN_CONTENT to "a text had markup, medical advice or a claim about causes",
    )
}
