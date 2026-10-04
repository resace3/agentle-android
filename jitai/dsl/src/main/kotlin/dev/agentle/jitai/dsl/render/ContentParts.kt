package dev.agentle.jitai.dsl.render

import dev.agentle.jitai.dsl.model.ContentStrategy

/**
 * One piece of notification text (integrator correction jitai-correctness-18): placeholder values and app names are
 * personal, so the posted notification shows a general text unless the user turned on detailed notifications, and the
 * app shows the personal parts only inside the app. A UI renders [Literal] parts as they are and decides per
 * [isPersonal] part.
 */
public sealed interface ContentPart {
    /** The text as written in the template. */
    public val text: String

    /** True when the part is derived from a placeholder or names an app. */
    public val isPersonal: Boolean

    public data class Literal(override val text: String) : ContentPart {
        override val isPersonal: Boolean get() = false
    }

    /** `{{featureId}}`, filled locally at delivery time (R10 §3.3). */
    public data class Placeholder(val featureId: String) : ContentPart {
        override val text: String get() = "{{$featureId}}"
        override val isPersonal: Boolean get() = true
    }

    /** An app name the rule refers to, as it appears in the text. */
    public data class AppName(override val text: String) : ContentPart {
        override val isPersonal: Boolean get() = true
    }
}

/** Splits content text into [ContentPart]s. Pure; malformed placeholders stay literal text (the validator rejects them). */
public object ContentParts {
    private val PLACEHOLDER = Regex("\\{\\{([a-z0-9_]+)\\}\\}")

    /** Every text of [content] that can reach the user: titles, bodies, variant items, the fallback and the caption. */
    public fun texts(content: ContentStrategy): List<String> = when (content) {
        is ContentStrategy.Static -> listOf(content.title, content.body)
        is ContentStrategy.Template -> listOf(content.title, content.body)
        is ContentStrategy.Variants -> content.items.flatMap { listOf(it.title, it.body) }
        is ContentStrategy.AiText -> listOf(content.fallback.title, content.fallback.body)
        is ContentStrategy.LocalMedia -> listOf(content.caption.title, content.caption.body)
    }

    /** [text] as parts; occurrences of [appLabels] (case-insensitive, longest first) become [ContentPart.AppName]. */
    public fun parse(text: String, appLabels: Collection<String> = emptyList()): List<ContentPart> {
        val parts = ArrayList<ContentPart>()
        var i = 0
        for (match in PLACEHOLDER.findAll(text)) {
            if (match.range.first > i) parts += literalParts(text.substring(i, match.range.first), appLabels)
            parts += ContentPart.Placeholder(match.groupValues[1])
            i = match.range.last + 1
        }
        if (i < text.length) parts += literalParts(text.substring(i), appLabels)
        return parts
    }

    /** True when [content] contains a placeholder or names one of [appLabels]. */
    public fun hasPersonalParts(content: ContentStrategy, appLabels: Collection<String> = emptyList()): Boolean =
        texts(content).any { text -> parse(text, appLabels).any { it.isPersonal } }

    private fun literalParts(text: String, appLabels: Collection<String>): List<ContentPart> {
        val labels = appLabels.filter { it.isNotBlank() }.sortedByDescending { it.length }
        if (labels.isEmpty()) return listOf(ContentPart.Literal(text))
        val parts = ArrayList<ContentPart>()
        var i = 0
        var literalStart = 0
        while (i < text.length) {
            val label = labels.firstOrNull { text.regionMatches(i, it, 0, it.length, ignoreCase = true) }
            if (label == null) {
                i++
                continue
            }
            if (i > literalStart) parts += ContentPart.Literal(text.substring(literalStart, i))
            parts += ContentPart.AppName(text.substring(i, i + label.length))
            i += label.length
            literalStart = i
        }
        if (literalStart < text.length) parts += ContentPart.Literal(text.substring(literalStart))
        return parts
    }
}
