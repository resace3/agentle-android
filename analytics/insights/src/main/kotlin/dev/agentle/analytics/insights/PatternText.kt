package dev.agentle.analytics.insights

import dev.agentle.core.model.SupportItem
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Fixed, non-causal wording of a finding (docs/research/10 §14.7): only "on nights when", "happened", "pattern" and
 * "together"-style phrasing, never an L7 word. The text is filled from the evidence numbers; no model writes it.
 * AI rewording (§14.9) is not used until the shared AiTextPolicy of `:ai:api` is available (integrator correction 5).
 */
public object PatternText {
    public fun title(tier: PatternTier): String = when (tier) {
        PatternTier.STRONG -> "A clear pattern in your data"
        else -> "A possible pattern in your data"
    }

    /** The condition of the exposed nights, completing "On 17 of 24 nights (71%) when ...". */
    public fun exposurePhrase(exposure: Exposure): String = when (exposure) {
        Exposure.SCREEN_30 -> "your screen time between 10 PM and midnight was 30 minutes or more"
        Exposure.SCREEN_45 -> "your screen time between 10 PM and midnight was 45 minutes or more"
        Exposure.SCREEN_60 -> "your screen time between 10 PM and midnight was 60 minutes or more"
        Exposure.SOCIAL_20 -> "you spent 20 minutes or more in social apps between 10 PM and midnight"
        Exposure.NOTIFICATIONS_20 -> "you got 20 or more notifications between 9 PM and midnight"
        Exposure.STEPS_UNDER_5K -> "you walked fewer than 5,000 steps that day"
    }

    /** What happened on those nights. */
    public fun outcomePhrase(outcome: NightOutcome): String = when (outcome) {
        NightOutcome.LATE_BEDTIME -> "you went to bed at least 30 minutes later than your usual bedtime"
        NightOutcome.SHORT_SLEEP -> "you slept at least 45 minutes less than usual"
        NightOutcome.HIGH_RESTING_HR -> "your resting heart rate the next day was at least 3 bpm above your usual level"
    }

    /** A short label of the exposure for tables. */
    public fun exposureLabel(exposure: Exposure): String = when (exposure) {
        Exposure.SCREEN_30 -> "30+ minutes of screen time after 10 PM"
        Exposure.SCREEN_45 -> "45+ minutes of screen time after 10 PM"
        Exposure.SCREEN_60 -> "60+ minutes of screen time after 10 PM"
        Exposure.SOCIAL_20 -> "20+ minutes in social apps after 10 PM"
        Exposure.NOTIFICATIONS_20 -> "20+ notifications after 9 PM"
        Exposure.STEPS_UNDER_5K -> "Fewer than 5,000 steps"
    }

    /** A short label of the outcome for tables. */
    public fun outcomeLabel(outcome: NightOutcome): String = when (outcome) {
        NightOutcome.LATE_BEDTIME -> "Bedtime 30+ minutes later than usual"
        NightOutcome.SHORT_SLEEP -> "Sleep 45+ minutes shorter than usual"
        NightOutcome.HIGH_RESTING_HR -> "Resting heart rate 3+ bpm above usual"
    }

    /** The finding of [result] (a claim: both arms are non-empty). */
    public fun finding(result: HypothesisResult): String {
        val t = result.table
        val p1 = PatternStatistics.percent(requireNotNull(t.rateExposed))
        val p0 = PatternStatistics.percent(requireNotNull(t.rateUnexposed))
        val other = when (t.c) {
            0 -> "this did not happen"
            1 -> "this happened once ($p0%)"
            else -> "this happened ${t.c} times ($p0%)"
        }
        val text = "On ${t.a} of ${t.exposed} nights ($p1%) when ${exposurePhrase(result.hypothesis.exposure)}, " +
            "${outcomePhrase(result.hypothesis.outcome)}. On the other ${t.unexposed} nights $other. ${strataSentence(result)} " +
            "This is a pattern in your own data, not proof: something else, such as a busy day, may explain both."
        return requireClean(text)
    }

    /** Which night types the pattern was checked on (the strata check passed for every checked type). */
    public fun strataSentence(result: HypothesisResult): String {
        val work = result.checked(NightType.WORK_NIGHT)
        val weekend = result.checked(NightType.WEEKEND_NIGHT)
        return when {
            work && weekend -> "The pattern showed up on work nights and on weekend nights."
            work -> "The pattern showed up on work nights; weekend nights were too few to check."
            weekend -> "The pattern showed up on weekend nights; work nights were too few to check."
            else -> "Work nights and weekend nights were too few to check separately."
        }
    }

    /** The supporting rows of an insight card. */
    public fun supportItems(result: HypothesisResult): List<SupportItem> {
        val t = result.table
        val items = mutableListOf(
            SupportItem("Nights compared", t.n.toString()),
            SupportItem("Outcome", outcomeLabel(result.hypothesis.outcome)),
            SupportItem(
                exposureLabel(result.hypothesis.exposure),
                "${t.a} of ${t.exposed} nights (${PatternStatistics.percent(t.rateExposed!!)}%)",
            ),
            SupportItem("Other nights", "${t.c} of ${t.unexposed} nights (${PatternStatistics.percent(t.rateUnexposed!!)}%)"),
        )
        result.interval?.let { ci ->
            items += SupportItem("Likely range of the difference", "${points(ci.lower)} to ${points(ci.upper)} percentage points")
        }
        items.forEach { requireClean(it.label + " " + it.value) }
        return items
    }

    private fun points(x: Double): Int = (x * PERCENT).roundToInt()

    /** Returns [text] if it passes [TextLint]; a failure is a defect in a fixed template. */
    public fun requireClean(text: String): String {
        val failed = TextLint.check(text)
        check(failed.isEmpty()) { "template text fails lint ${failed.map { it.id }}" }
        return text
    }

    private const val PERCENT = 100.0
}

/** Checks of [TextLint] (docs/research/10 §11.6), with the same ids. */
public enum class LintCheck(public val id: String) {
    URL("L1"),
    EMAIL("L2"),
    PHONE_NUMBER("L3"),
    MARKUP("L4"),
    CONTROL_OR_INVISIBLE("L5"),
    MEDICAL_WORDING("L6"),
    CAUSAL_CLAIM("L7"),
    PLACEHOLDER("L8"),
}

/**
 * The lint of docs/research/10 §11.6 (L1-L8) applied to the NFKC case-folded text, used here to guard the fixed
 * insight and proposal templates. The shared policy for AI-written text is AiTextPolicy in `:ai:api` (team AI-CONTEXT);
 * this local copy only proves that Agentle's own templates are clean. English word lists (v1).
 */
public object TextLint {
    /** L7 entries: whole words or phrases; the first word also matches with -s, -d, -es or -ed. */
    public val CAUSAL_PHRASES: List<String> =
        listOf("cause", "caused by", "because of", "due to", "leads to", "results in", "makes you", "proves", "effect of")

    /** L6 stems: a word that begins with one of them is medical wording. */
    public val MEDICAL_STEMS: List<String> = listOf("diagnos", "disorder", "addict", "insomnia", "depress", "prescri", "dosage", "medicat")

    private val TOP_LEVEL_DOMAINS = setOf(
        "com", "net", "org", "info", "biz", "io", "ai", "app", "dev", "co", "me", "ly", "tv", "xyz", "top", "site", "online",
        "link", "click", "shop", "ru", "cn", "de", "uk", "us", "ca", "au", "in", "jp", "fr", "eu", "gov", "edu",
    )
    private const val NOT_WORD_BEFORE = "(?<![\\p{L}\\p{N}_])"
    private const val NOT_WORD_AFTER = "(?![\\p{L}\\p{N}_])"
    private val SCHEME_OR_WWW = Regex("https?://|www\\.")
    private val DOMAIN = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}-]*(?:\\.[\\p{L}\\p{N}-]+)*\\.([\\p{L}]{2,})$NOT_WORD_AFTER")
    private val EMAIL = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
    private val PHONE = Regex("\\d(?:[ .()\\-]*\\d){6,}")
    private val MARKUP = Regex("<[\\p{L}/]|]\\(|`|\\*\\*")
    private val MEDICAL = Regex(NOT_WORD_BEFORE + "(?:" + MEDICAL_STEMS.joinToString("|") + ")")
    private val CAUSAL = Regex(
        CAUSAL_PHRASES.joinToString("|", prefix = "$NOT_WORD_BEFORE(?:", postfix = ")$NOT_WORD_AFTER") { phrase ->
            val words = phrase.split(' ')
            Regex.escape(words.first()) + "(?:s|d|es|ed)?" + words.drop(1).joinToString("") { "\\s+" + Regex.escape(it) }
        },
    )
    private val INVISIBLE = setOf(0x200B, 0x200C, 0x200D, 0x2028, 0x2029, 0xFEFF) + (0x202A..0x202E) + (0x2066..0x2069)

    /** The failed checks of [text], in check order; empty when clean. */
    public fun check(text: String): List<LintCheck> {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return buildList {
            if (SCHEME_OR_WWW.containsMatchIn(folded) ||
                DOMAIN.findAll(folded).any { it.groupValues[1] in TOP_LEVEL_DOMAINS }
            ) {
                add(LintCheck.URL)
            }
            if (EMAIL.containsMatchIn(folded)) add(LintCheck.EMAIL)
            if (PHONE.containsMatchIn(folded)) add(LintCheck.PHONE_NUMBER)
            if (MARKUP.containsMatchIn(folded)) add(LintCheck.MARKUP)
            if (folded.codePoints().anyMatch { isControlOrInvisible(it) }) add(LintCheck.CONTROL_OR_INVISIBLE)
            if (MEDICAL.containsMatchIn(folded)) add(LintCheck.MEDICAL_WORDING)
            if (CAUSAL.containsMatchIn(folded)) add(LintCheck.CAUSAL_CLAIM)
            if ("{{" in folded) add(LintCheck.PLACEHOLDER)
        }
    }

    private fun isControlOrInvisible(cp: Int): Boolean = cp <= C0_END || cp in DEL..C1_END || cp in INVISIBLE

    private const val C0_END = 0x1F
    private const val DEL = 0x7F
    private const val C1_END = 0x9F
}
