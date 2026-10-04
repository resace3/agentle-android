package dev.agentle.ai.api.validation

import java.text.Normalizer
import java.util.Locale

/**
 * The checks of [AiTextPolicy]. L1-L8 are the lint of docs/research/10-jitai-engine-design.md section 11.6 with the
 * same ids; L9-L12 are the additions of the architecture red team (privacy-ai-06); L13 is the rule for text generated
 * ahead of delivery (jitai-correctness-17). [code] is the validation error code reported for the check.
 */
public enum class AiTextCheck(public val id: String, public val code: String) {
    URL("L1", OutputCodes.TEXT_CONTAINS_CONTACT),
    EMAIL("L2", OutputCodes.TEXT_CONTAINS_CONTACT),
    PHONE_NUMBER("L3", OutputCodes.TEXT_CONTAINS_CONTACT),
    MARKUP("L4", OutputCodes.TEXT_FORBIDDEN_CONTENT),
    CONTROL_OR_INVISIBLE("L5", OutputCodes.CONTROL_OR_INVISIBLE_CHARS),
    MEDICAL_WORDING("L6", OutputCodes.TEXT_FORBIDDEN_CONTENT),
    CAUSAL_CLAIM("L7", OutputCodes.TEXT_FORBIDDEN_CONTENT),
    PLACEHOLDER("L8", OutputCodes.PLACEHOLDER_NOT_ALLOWED),
    NUMBER_PROVENANCE("L9", OutputCodes.NUMBER_NOT_IN_PROVENANCE),
    SENTENCE_LIMIT("L10", OutputCodes.TOO_MANY_SENTENCES),
    MEDICAL_IMPERATIVE("L11", OutputCodes.MEDICAL_IMPERATIVE),
    LENGTH("L12", OutputCodes.TEXT_LENGTH),

    /** A digit or an English number word in text that may hold no number ([TextRules.numbersForbidden]). */
    NUMBER_IN_POOLED_TEXT("L13", OutputCodes.NUMBER_IN_POOLED_TEXT),
}

/**
 * Limits for one AI-written text field. Lengths count code points after NFC. [placeholdersAllowed] is only true for
 * JITAI template text, whose `{{feature}}` placeholders the JITAI validator checks. [numbersForbidden] is true for text
 * generated ahead of delivery, such as the pooled `ai_text` of a JITAI (jitai-correctness-17): it is written hours
 * before it is shown, so any number in it would be stale or invented; numbers reach a delivery only through template
 * placeholders filled at delivery time. Then any digit and any English number word fails L13, and L9 is not needed.
 */
public data class TextRules(
    val maxChars: Int,
    val maxSentences: Int,
    val minChars: Int = 1,
    val placeholdersAllowed: Boolean = false,
    val numbersForbidden: Boolean = false,
) {
    init {
        require(!(placeholdersAllowed && numbersForbidden)) { "pooled text has no placeholders" }
    }

    public companion object {
        /** The title of a pooled JITAI `ai_text` (R10 section 3.3 limits). */
        public val POOLED_TITLE: TextRules = TextRules(maxChars = 60, maxSentences = 1, numbersForbidden = true)

        /** The body of a pooled JITAI `ai_text`. */
        public val POOLED_BODY: TextRules = TextRules(maxChars = 240, maxSentences = 3, numbersForbidden = true)
    }
}

/** The text to use and, when the AI text failed, the ids of the failed checks (the only thing recorded). */
public data class TextDecision(val text: String, val usedTemplate: Boolean, val failedChecks: List<AiTextCheck>)

/**
 * The policy applied to every AI-produced string in every schema (privacy-ai-06): run it before storing an AI text
 * and again before displaying or delivering it; on failure use the app's local template text and record only the check
 * ids ([select]). AI text is always rendered as plain text. Word lists are English-only in v1 (R10 section 11.6).
 */
public object AiTextPolicy {
    private val INVISIBLE = setOf(0x200B, 0x200C, 0x200D, 0x2028, 0x2029, 0xFEFF) + (0x202A..0x202E) + (0x2066..0x2069)
    private const val C1_START = 0x80
    private const val C1_END = 0x9F
    private const val DEL = 0x7F
    private const val C0_END = 0x1F

    /**
     * Top-level domains recognized by L1 in `label.tld` tokens without a scheme. Bundled design list: common generic
     * and country domains plus those often seen in spam; R10 asks for "a bundled list" without naming it.
     */
    public val TOP_LEVEL_DOMAINS: Set<String> = setOf(
        "com", "net", "org", "info", "biz", "io", "ai", "app", "dev", "co", "me", "ly", "gg", "tv", "xyz", "top", "site",
        "online", "link", "click", "shop", "store", "live", "page", "cc", "ru", "cn", "de", "uk", "fr", "us", "ca", "au",
        "in", "jp", "br", "nl", "es", "it", "pl", "se", "ch", "be", "at", "dk", "no", "fi", "ie", "nz", "za", "mx", "ar",
        "kr", "tk", "ml", "ga", "cf", "gq", "to", "ws", "su", "eu", "asia", "mobi", "pro", "name", "email", "example",
        "gov", "edu", "mil", "int", "sh", "so", "gl", "im", "is", "fm", "am", "news", "blog", "club", "win", "bid",
    )

    /** L6 stems (R10 section 11.6): a word that begins with one of them is medical wording. */
    public val MEDICAL_STEMS: List<String> = listOf("diagnos", "disorder", "addict", "insomnia", "depress", "prescri", "dosage", "medicat")

    /**
     * L13 number words (English, design list): cardinals, ordinals and their plurals, fractions, multiples and frequency
     * words. Matched as whole words of letters, so "twenty-five" matches twice and "tend" or "someone" never match.
     */
    public val NUMBER_WORDS: Set<String> = buildNumberWords()

    /** L7 entries (R10 section 11.6): whole words or phrases; the first word also matches with -s, -d, -es or -ed. */
    public val CAUSAL_PHRASES: List<String> =
        listOf("cause", "caused by", "because of", "due to", "leads to", "results in", "makes you", "proves", "effect of")

    private val SCHEME_OR_WWW = Regex("https?://|www\\.")
    private val DOMAIN_TOKEN = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}.-]*")
    private val EMAIL = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
    private val PHONE = Regex("\\d(?:[ .()\\-]*\\d){6,}")
    private val MARKUP = Regex("<[\\p{L}/]|\\]\\(|`|\\*\\*")
    private val PLACEHOLDER = Regex("\\{\\{|\\}\\}")
    private val PLACEHOLDER_SPAN = Regex("\\{\\{[^\\{\\}]*\\}\\}")
    private const val NOT_WORD_BEFORE = "(?<![\\p{L}\\p{N}_])"
    private const val NOT_WORD_AFTER = "(?![\\p{L}\\p{N}_])"
    private val MEDICAL = Regex(NOT_WORD_BEFORE + "(?:" + MEDICAL_STEMS.joinToString("|") + ")")
    private val CAUSAL = Regex(
        CAUSAL_PHRASES.joinToString("|", prefix = "$NOT_WORD_BEFORE(?:", postfix = ")$NOT_WORD_AFTER") { phrase ->
            val words = phrase.split(' ')
            Regex.escape(words.first()) + "(?:s|d|es|ed)?" + words.drop(1).joinToString("") { "\\s+" + Regex.escape(it) }
        },
    )

    /**
     * L11, a product-owned list (design; UNVERIFIED as complete): telling the user to take, stop or change a medicine
     * or supplement, or to see a clinician or emergency service.
     */
    private val MEDICAL_IMPERATIVES: List<Regex> = listOf(
        Regex(
            NOT_WORD_BEFORE + "(?:take|taking|start taking|stop taking|increase|decrease|reduce|double|skip|try)" + NOT_WORD_AFTER +
                "[^.!?]{0,40}?" + NOT_WORD_BEFORE +
                "(?:pills?|tablets?|melatonin|supplements?|medicines?|medications?|meds|drugs?|doses?|antidepressants?|" +
                "sleeping pills?|ibuprofen|painkillers?|insulin)" + NOT_WORD_AFTER,
        ),
        Regex(
            NOT_WORD_BEFORE + "(?:see|consult|call|visit|contact|ask|talk to|speak to|speak with|talk with)\\s+(?:a |an |your |the )?" +
                "(?:doctors?|physicians?|gp|therapists?|psychiatrists?|psychologists?|nurses?|pharmacists?|specialists?|" +
                "cardiologists?|emergency services|emergency room|hospital|clinic|er)" + NOT_WORD_AFTER,
        ),
        Regex(NOT_WORD_BEFORE + "(?:call|dial)\\s+(?:911|112|999)" + NOT_WORD_AFTER),
    )
    private val ANY_NUMBER_CHAR = Regex("\\p{N}")
    private val LETTER_WORD = Regex("\\p{L}+")
    private val SENTENCE_END = Regex("[.!?\u2026\u3002]+(?=\\s+[\\p{Lu}\\p{N}\"'(]|\\s*$)")

    /**
     * Runs every check on [text]. [provenance] null skips L9 (a caller without the request, such as a display-time
     * re-check of text that passed L9 before storage). With [TextRules.numbersForbidden], L13 replaces L9.
     */
    public fun check(text: String, rules: TextRules, provenance: NumberProvenance?): List<AiTextCheck> {
        val failed = LinkedHashSet<AiTextCheck>()
        val length = SchemaWalker.codePointLength(text)
        if (length < rules.minChars || length > rules.maxChars) failed += AiTextCheck.LENGTH
        if (containsControlOrInvisible(text)) failed += AiTextCheck.CONTROL_OR_INVISIBLE
        val folded = fold(text)
        val withoutPlaceholders = if (rules.placeholdersAllowed) PLACEHOLDER_SPAN.replace(folded, " ") else folded
        if (SCHEME_OR_WWW.containsMatchIn(folded) || containsDomain(withoutPlaceholders)) failed += AiTextCheck.URL
        if (EMAIL.containsMatchIn(folded)) failed += AiTextCheck.EMAIL
        if (PHONE.containsMatchIn(withoutPlaceholders)) failed += AiTextCheck.PHONE_NUMBER
        if (MARKUP.containsMatchIn(folded)) failed += AiTextCheck.MARKUP
        if (MEDICAL.containsMatchIn(folded)) failed += AiTextCheck.MEDICAL_WORDING
        if (CAUSAL.containsMatchIn(folded)) failed += AiTextCheck.CAUSAL_CLAIM
        if (!rules.placeholdersAllowed && PLACEHOLDER.containsMatchIn(folded)) failed += AiTextCheck.PLACEHOLDER
        if (rules.numbersForbidden) {
            if (containsNumber(folded)) failed += AiTextCheck.NUMBER_IN_POOLED_TEXT
        } else if (provenance != null && !provenance.covers(withoutPlaceholders)) {
            failed += AiTextCheck.NUMBER_PROVENANCE
        }
        if (sentenceCount(text) > rules.maxSentences) failed += AiTextCheck.SENTENCE_LIMIT
        if (MEDICAL_IMPERATIVES.any { it.containsMatchIn(folded) }) failed += AiTextCheck.MEDICAL_IMPERATIVE
        return failed.toList()
    }

    /** [aiText] if it passes every check, otherwise [template]; the failed check ids are what callers record. */
    public fun select(aiText: String?, template: String, rules: TextRules, provenance: NumberProvenance?): TextDecision {
        if (aiText == null) return TextDecision(template, usedTemplate = true, failedChecks = emptyList())
        val failed = check(aiText, rules, provenance)
        return if (failed.isEmpty()) TextDecision(aiText, false, emptyList()) else TextDecision(template, true, failed)
    }

    /** Sentences: runs of text ended by `.`, `!`, `?` (or the end) where the next word starts a new sentence. */
    public fun sentenceCount(text: String): Int = SENTENCE_END.split(text.trim()).count { it.isNotBlank() }

    /** L5: C0 and C1 controls (line breaks included), zero-width characters, bidi controls and the BOM. */
    public fun containsControlOrInvisible(text: String): Boolean = text.codePoints().anyMatch { cp ->
        cp <= C0_END || cp == DEL || cp in C1_START..C1_END || cp in INVISIBLE
    }

    /** L13: any number character (after NFKC, so superscripts and fractions too) or any word of [NUMBER_WORDS]. */
    public fun containsNumber(text: String): Boolean {
        val folded = fold(text)
        return ANY_NUMBER_CHAR.containsMatchIn(folded) || LETTER_WORD.findAll(folded).any { it.value in NUMBER_WORDS }
    }

    private fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    private fun buildNumberWords(): Set<String> {
        val cardinals = listOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen",
            "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty", "sixty",
            "seventy", "eighty", "ninety", "hundred", "thousand", "million", "billion", "trillion",
        )
        val ordinals = listOf(
            "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth", "eleventh", "twelfth",
            "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth", "nineteenth", "twentieth",
            "thirtieth", "fortieth", "fiftieth", "sixtieth", "seventieth", "eightieth", "ninetieth", "hundredth", "thousandth",
            "millionth", "billionth",
        )
        val others = listOf(
            "half", "halves", "halve", "halved", "quarter", "quarters", "dozen", "dozens", "couple", "once", "twice", "thrice",
            "double", "doubled", "triple", "tripled", "quadruple", "quadrupled", "nought", "naught", "nil", "zeroes",
        )
        val plurals = cardinals.map { word ->
            when {
                word.endsWith("y") -> word.dropLast(1) + "ies"
                word.endsWith("x") -> word + "es"
                else -> word + "s"
            }
        }
        return (cardinals + ordinals + others + plurals + ordinals.map { it + "s" }).toSet()
    }

    private fun containsDomain(folded: String): Boolean = DOMAIN_TOKEN.findAll(folded).any { match ->
        val token = match.value.trimEnd('.', '-')
        val labels = token.split('.')
        labels.size >= 2 && labels.none { it.isEmpty() } && labels.last() in TOP_LEVEL_DOMAINS
    }
}
