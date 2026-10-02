package dev.agentle.jitai.dsl.validation

import java.text.Normalizer
import java.util.Locale

/** The lint checks of R10 §11.6. Ids stay exactly L1-L8 (AiTextPolicy in `:ai:api` reuses them). */
public enum class LintCheck(public val code: IssueCode?, public val category: String?) {
    /** URL: `https?://`, `www.`, or a `label.tld` token with a bundled TLD. */
    L1(IssueCode.E061, null),

    /** Email address. */
    L2(IssueCode.E061, null),

    /** Phone number: 7 or more digits with spaces, dots, dashes or parentheses between them. */
    L3(IssueCode.E061, null),

    /** Markup: `<` + letter or `/`, `](`, a backtick, `**`. */
    L4(IssueCode.E062, "markup"),

    /** C0/C1 controls (line breaks included) and invisible or bidi characters. */
    L5(IssueCode.E066, null),

    /** Medical wording (word-start stems). */
    L6(IssueCode.E062, "medical wording"),

    /** Causal claims (whole words or phrases; the first word also with -s, -d, -es, -ed). */
    L7(IssueCode.E062, "a causal claim"),

    /** `{{` in generated `ai_text`: the pool item is dropped (no validator code). */
    L8(null, null),
}

/** One lint hit. [match] is the matched text (from the checked text itself), [hex] the code point for L5. */
public data class LintFinding(val check: LintCheck, val match: String, val hex: String? = null)

/**
 * Lint for AI-written text (R10 §11.6): fixed, compiled patterns and word lists that ship with the app, applied to the
 * NFKC case-folded text (L5 to the raw text). English-only in v1. A coarse safety net, not a moderation system.
 */
public object TextLint {
    /** Version of the bundled patterns and word lists. */
    public const val VERSION: Int = 1

    /** TLDs for `label.tld` tokens (L1). Two-letter TLDs that are common English words are left out on purpose. */
    public val TLDS: List<String> = listOf(
        "com", "net", "org", "edu", "gov", "mil", "info", "biz", "io", "ai", "app", "dev", "ly", "gg", "tv", "xyz",
        "site", "online", "store", "shop", "link", "club", "top", "uk", "de", "fr", "eu", "ca", "au", "jp", "cn", "ru",
        "br", "nl", "es", "ch", "se", "pl",
    )

    /** Medical stems (L6): match any word that begins with one of them. */
    public val MEDICAL_STEMS: List<String> = listOf(
        "diagnos", "disorder", "addict", "insomnia", "depress", "prescri", "dosage", "medicat", "symptom", "syndrome",
        "therap", "psychiatr", "disease",
    )

    /** Causal phrases (L7). */
    public val CAUSAL_PHRASES: List<String> = listOf(
        "cause", "caused by", "because of", "due to", "leads to", "results in", "makes you", "proves", "effect of",
    )

    private val URL = Regex("https?://|www\\.")
    private val DOMAIN = Regex(
        "(?<![\\p{L}\\p{N}_-])[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)*\\.(?:${TLDS.joinToString("|")})(?![\\p{L}\\p{N}_-])",
    )

    /** Starts only at a token start and takes the local part possessively, so a long token is matched in linear time. */
    private val EMAIL = Regex("(?<![^\\s@])[^\\s@]++@[^\\s@]*\\.[^\\s@]+")

    /** Any decimal digit; separators include the Unicode hyphens U+2010-2015 and the minus sign U+2212. */
    private val PHONE = Regex("\\p{Nd}(?:[ .()\\-\\u2010-\\u2015\\u2212]*\\p{Nd}){6,}")

    /** Ideographic, halfwidth and small full stops read as "." in a link or address (L1, L2). */
    private val DOTS = Regex("[\\u3002\\uFF61\\uFE52]")
    private val MARKUP = Regex("<[\\p{L}/]|\\]\\(|`|\\*\\*")
    private val MEDICAL = Regex("(?<![\\p{L}\\p{N}])(?:${MEDICAL_STEMS.joinToString("|")})[\\p{L}\\p{N}]*")
    private val CAUSAL = Regex(
        "(?<![\\p{L}\\p{N}])(?:" + CAUSAL_PHRASES.joinToString("|") { phrase ->
            val words = phrase.split(' ')
            val first = Regex.escape(words.first()) + "(?:s|d|es|ed)?"
            (listOf(first) + words.drop(1).map { Regex.escape(it) }).joinToString("\\s+")
        } + ")(?![\\p{L}\\p{N}])",
    )

    /** Checks L1-L7 on [text] (a name, description, content text, question, option, assumption or detail). */
    public fun check(text: String): List<LintFinding> {
        val findings = ArrayList<LintFinding>()
        invisible(text)?.let { findings += it }
        val folded = fold(text).replace(DOTS, ".")
        firstMatch(URL, folded)?.let { findings += LintFinding(LintCheck.L1, it) }
            ?: firstMatch(DOMAIN, folded)?.let { findings += LintFinding(LintCheck.L1, it) }
        firstMatch(EMAIL, folded)?.let { findings += LintFinding(LintCheck.L2, it) }
        firstMatch(PHONE, folded)?.let { findings += LintFinding(LintCheck.L3, it) }
        firstMatch(MARKUP, folded)?.let { findings += LintFinding(LintCheck.L4, it) }
        firstMatch(MEDICAL, folded)?.let { findings += LintFinding(LintCheck.L6, it) }
        firstMatch(CAUSAL, folded)?.let { findings += LintFinding(LintCheck.L7, it) }
        return findings
    }

    /** Checks a generated `ai_text` before it enters the pool: L1-L7 plus L8 (`{{`). Any finding drops the item. */
    public fun checkGenerated(text: String): List<LintFinding> {
        val findings = check(text).toMutableList()
        if (text.contains("{{")) findings += LintFinding(LintCheck.L8, "{{")
        return findings
    }

    /** L5 alone: the first control or invisible character, or null. Applied to rule text of every origin. */
    public fun invisible(text: String): LintFinding? {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isControlOrInvisible(cp) && !allowedInEmoji(text, i, cp)) {
                return LintFinding(LintCheck.L5, String(Character.toChars(cp)), "%04X".format(Locale.ROOT, cp))
            }
            i += Character.charCount(cp)
        }
        return null
    }

    /** NFKC and case folding (lower case in the root locale), the form L1-L4, L6 and L7 run on. */
    public fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    /**
     * L5 (review R2-1): every Cc, Cf, Cs, Co and Cn code point, the line and paragraph separators, and every
     * Default_Ignorable_Code_Point. ZWJ (U+200D) is allowed only between two emoji and VS16 (U+FE0F) only after one.
     */
    private fun isControlOrInvisible(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SURROGATE.toInt(), Character.PRIVATE_USE.toInt(),
        Character.UNASSIGNED.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
        -> true

        else -> DEFAULT_IGNORABLE.any { cp in it }
    }

    private fun allowedInEmoji(text: String, i: Int, cp: Int): Boolean {
        if (cp != ZWJ && cp != VS16) return false
        val before = if (i > 0) text.codePointBefore(i) else return false
        val previousIsEmoji = isEmoji(before) || (before == VS16 && i >= 2 && isEmoji(text.codePointBefore(i - 1)))
        if (cp == VS16) return isEmoji(before)
        val after = i + 1 < text.length && isEmoji(text.codePointAt(i + 1))
        return previousIsEmoji && after
    }

    private fun isEmoji(cp: Int): Boolean = EMOJI.any { cp in it }

    private const val ZWJ = 0x200D
    private const val VS16 = 0xFE0F

    /** Default_Ignorable_Code_Point (Unicode DerivedCoreProperties). */
    private val DEFAULT_IGNORABLE: List<IntRange> = listOf(
        0x00AD..0x00AD, 0x034F..0x034F, 0x061C..0x061C, 0x115F..0x1160, 0x17B4..0x17B5, 0x180B..0x180F,
        0x200B..0x200F, 0x202A..0x202E, 0x2060..0x206F, 0x3164..0x3164, 0xFE00..0xFE0F, 0xFEFF..0xFEFF,
        0xFFA0..0xFFA0, 0xFFF0..0xFFF8, 0x1BCA0..0x1BCA3, 0x1D173..0x1D17A, 0xE0000..0xE0FFF,
    )

    /** Pictographic ranges that ZWJ sequences and VS16 attach to. */
    private val EMOJI: List<IntRange> = listOf(
        0x00A9..0x00A9, 0x00AE..0x00AE, 0x203C..0x203C, 0x2049..0x2049, 0x2122..0x2122, 0x2139..0x2139,
        0x2194..0x21AA, 0x2300..0x23FF, 0x24C2..0x24C2, 0x25AA..0x25FE, 0x2600..0x27BF, 0x2934..0x2935,
        0x2B00..0x2BFF, 0x3030..0x3030, 0x303D..0x303D, 0x3297..0x3297, 0x3299..0x3299, 0x1F000..0x1FAFF,
    )

    private fun firstMatch(regex: Regex, text: String): String? = regex.find(text)?.value
}
