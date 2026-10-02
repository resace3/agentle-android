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
    private val DOMAIN = Regex("(?<![\\p{L}\\p{N}_-])[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)*\\.(?:${TLDS.joinToString("|")})(?![\\p{L}\\p{N}_-])")
    private val EMAIL = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
    private val PHONE = Regex("\\d(?:[ .()\\-]*\\d){6,}")
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
        val folded = fold(text)
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
            if (isControlOrInvisible(cp)) {
                return LintFinding(LintCheck.L5, String(Character.toChars(cp)), "%04X".format(Locale.ROOT, cp))
            }
            i += Character.charCount(cp)
        }
        return null
    }

    /** NFKC and case folding (lower case in the root locale), the form L1-L4, L6 and L7 run on. */
    public fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    private fun isControlOrInvisible(cp: Int): Boolean =
        Character.getType(cp) == Character.CONTROL.toInt() ||
            cp in 0x200B..0x200D ||
            cp == 0x2028 ||
            cp == 0x2029 ||
            cp in 0x202A..0x202E ||
            cp in 0x2066..0x2069 ||
            cp == 0xFEFF

    private fun firstMatch(regex: Regex, text: String): String? = regex.find(text)?.value
}
