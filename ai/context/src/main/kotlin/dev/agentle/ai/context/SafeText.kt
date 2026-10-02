package dev.agentle.ai.context

import java.text.Normalizer

/**
 * Reduces text that Agentle did not write (the user's own text and app labels) to the safe character set of
 * docs/ARCHITECTURE.md section 9: letters, digits, spaces and `.-&'`.
 *
 * Steps:
 * 1. NFKC normalization folds look-alike forms such as full-width letters.
 * 2. Letters and decimal digits are kept in every script, together with up to three combining marks directly after a
 *    letter, so that words in Devanagari or Thai survive.
 * 3. Curly apostrophes become `'` and dashes become `-`. A colon between two digits becomes `.`, so `5:30` stays
 *    readable as `5.30`.
 * 4. Other punctuation, symbols (emoji included) and whitespace become one space. Control, format (bidi and
 *    zero-width), private-use and unassigned characters are dropped.
 * 5. The result is trimmed and cut to the maximum number of code points.
 *
 * The result is idempotent. It cannot hold JSON, markup, links or line breaks, so it cannot break out of the JSON
 * string value it is sent in.
 */
public object SafeText {
    /** Maximum length of an app label (docs/ARCHITECTURE.md section 9). */
    public const val LABEL_MAX: Int = 40

    /** Maximum length of one sent text item (the EgressGuard limit). */
    public const val ITEM_MAX: Int = 200

    private const val MAX_MARKS = 3
    private const val SPACE = ' '.code
    private const val DOT = '.'.code
    private const val DASH = '-'.code
    private const val APOSTROPHE = '\''.code
    private const val COLON = ':'.code
    private val KEPT = setOf(DOT, DASH, '&'.code, APOSTROPHE)
    private val APOSTROPHES = setOf(0x2018, 0x2019, 0x201B, 0x2032)
    private val DASHES = setOf(0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2015, 0x2212)
    private val SEPARATOR_TYPES = setOf(
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
        Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
        Character.LETTER_NUMBER, Character.OTHER_NUMBER, Character.ENCLOSING_MARK,
    ).map { it.toInt() }.toSet()

    /** [raw] reduced to the safe set and at most [maxChars] code points. */
    public fun reduce(raw: String, maxChars: Int): String {
        require(maxChars > 0) { "maxChars must be positive" }
        val points = Normalizer.normalize(raw, Normalizer.Form.NFKC).codePoints().toArray()
        val out = ArrayList<Int>(points.size)
        var marks = 0
        points.forEachIndexed { index, cp ->
            val kept = keep(points, index, cp, out.lastOrNull(), marks)
            when {
                kept == null -> Unit

                kept == SPACE -> {
                    if (out.isNotEmpty() && out.last() != SPACE) out += SPACE
                    marks = 0
                }

                else -> {
                    marks = if (isMark(kept)) marks + 1 else 0
                    out += kept
                }
            }
        }
        val joined = Normalizer.normalize(String(out.toIntArray(), 0, out.size), Normalizer.Form.NFKC)
        return cut(joined.trim(), maxChars)
    }

    /** An app label reduced for sending: at most [LABEL_MAX] characters. */
    public fun appLabel(raw: String): String = reduce(raw, LABEL_MAX)

    /** True if [text] is non-empty, at most [maxChars] code points and already reduced. */
    public fun isSafe(text: String, maxChars: Int): Boolean = text.isNotEmpty() && reduce(text, maxChars) == text

    private fun cut(text: String, maxChars: Int): String {
        if (text.codePointCount(0, text.length) <= maxChars) return text
        return text.substring(0, text.offsetByCodePoints(0, maxChars)).trimEnd()
    }

    private fun keep(points: IntArray, index: Int, cp: Int, previous: Int?, marks: Int): Int? = when {
        Character.isLetter(cp) || Character.isDigit(cp) -> cp

        isMark(cp) -> cp.takeIf { previous != null && marks < MAX_MARKS && (Character.isLetter(previous) || isMark(previous)) }

        cp == COLON -> if (previous != null && Character.isDigit(previous) &&
            points.getOrNull(index + 1)?.let(Character::isDigit) == true
        ) {
            DOT
        } else {
            SPACE
        }

        cp in KEPT -> cp

        cp in APOSTROPHES -> APOSTROPHE

        cp in DASHES -> DASH

        Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> SPACE

        Character.getType(cp) in SEPARATOR_TYPES -> SPACE

        else -> null
    }

    private fun isMark(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
    }
}
