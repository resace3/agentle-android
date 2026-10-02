package dev.agentle.jitai.dsl.validation

import java.text.Normalizer

/** String normalization and lengths (R10 §11.2 E060, §11.4 step 5). */
internal object TextRules {
    /** Unicode NFC with leading and trailing whitespace trimmed: the stored form of every rule text. */
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC).trim()

    /** Length in Unicode code points of the stored form (so S8 cannot fail on a text S6 accepted). */
    fun length(text: String): Int {
        val normalized = normalize(text)
        return normalized.codePointCount(0, normalized.length)
    }

    /** At most [max] code points of [text] from [start] (a UTF-16 index), for message snippets. */
    fun snippet(text: String, start: Int, max: Int = SNIPPET_LENGTH): String {
        val from = start.coerceIn(0, text.length)
        var end = from
        var count = 0
        while (end < text.length && count < max) {
            end += Character.charCount(text.codePointAt(end))
            count++
        }
        return text.substring(from, end)
    }

    private const val SNIPPET_LENGTH = 24
}
