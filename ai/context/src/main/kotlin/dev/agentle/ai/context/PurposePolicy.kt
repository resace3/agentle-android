package dev.agentle.ai.context

import dev.agentle.ai.api.AiPurpose
import dev.agentle.ai.api.AiRequestEnvelope
import dev.agentle.ai.api.OutputSchema
import dev.agentle.ai.api.validation.ChatReplySchema
import dev.agentle.ai.api.validation.InsightSchema
import dev.agentle.ai.api.validation.JitaiProposalSchema
import dev.agentle.ai.api.validation.MediaPromptSchema
import dev.agentle.ai.api.validation.OutputValidationContext
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.AiDataCategory.ACTIVITY
import dev.agentle.core.model.AiDataCategory.APP_IDENTITY
import dev.agentle.core.model.AiDataCategory.HEART
import dev.agentle.core.model.AiDataCategory.INTERVENTION_HISTORY
import dev.agentle.core.model.AiDataCategory.NOTIFICATION_COUNTS
import dev.agentle.core.model.AiDataCategory.SCREEN_TIME_TOTALS
import dev.agentle.core.model.AiDataCategory.SELF_REPORTS
import dev.agentle.core.model.AiDataCategory.SETTINGS
import dev.agentle.core.model.AiDataCategory.SLEEP
import dev.agentle.core.model.AiDataCategory.STEPS
import dev.agentle.core.time.ClosedOpenRange
import dev.agentle.core.time.EngineDay
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Whether a purpose takes the user's own typed request. */
public enum class UserTextRule { NONE, REQUIRED }

/** Defaults offered in the standing-consent template of a purpose that may run in the background (privacy-ai-01). */
public data class BackgroundRule(val cadence: Duration, val dailyBudget: Int)

/**
 * The fixed rules of one purpose (docs/ARCHITECTURE.md section 9).
 *
 * - [categories]: the allow-list, intersected with the user's grants. It never holds third-party text.
 * - [primaryCategories]: at least one sent item must carry one of these. Empty means no such rule. With
 *   [requiresData], a request without any item fails instead of going out empty.
 * - [lookbackDays] local days ending at the start of the current engine day, or at "now" when [includesToday]. Zero
 *   means no time range.
 * - [rawEvents]: individual events may be added, each time with a fresh user confirmation.
 * - [userText] and [userTextMaxChars]: the user's typed request.
 * - [itemKinds]: the kinds of values the purpose may send.
 * - [background]: null if the purpose never runs in the background.
 * - [outputSchema]: the expected reply (null: plain text).
 * - [acceptsAiGenerated]: whether AI-written text may be part of the context (the aiGenerated taint).
 */
public data class PurposeSpec(
    val purpose: AiPurpose,
    val categories: Set<AiDataCategory>,
    val primaryCategories: Set<AiDataCategory>,
    val requiresData: Boolean,
    val lookbackDays: Int,
    val includesToday: Boolean,
    val rawEvents: Boolean,
    val userText: UserTextRule,
    val userTextMaxChars: Int,
    val itemKinds: Set<ItemKind>,
    val background: BackgroundRule?,
    val outputSchema: OutputSchema?,
    val acceptsAiGenerated: Boolean = false,
    val caps: PurposeCaps = PurposeCaps.forPurpose(purpose, lookbackDays),
) {
    init {
        require(categories.none { it.thirdPartyText }) { "$purpose: third-party text is never sent in v1" }
        require(categories.containsAll(primaryCategories)) { "$purpose: primary categories must be allowed" }
        require(lookbackDays >= 0) { "$purpose: negative lookback" }
    }
}

/** The v1 purpose table. */
public object PurposePolicy {
    private val AGGREGATES = setOf(ItemKind.QUANTITY, ItemKind.TIME_OF_DAY, ItemKind.CODE)
    private val NIGHTLY = BackgroundRule(cadence = 12.hours, dailyBudget = 1)

    /** At most this many characters of the user's typed request (R10 section 13.1). */
    public const val REQUEST_MAX_CHARS: Int = 500

    /**
     * At most this many characters of a chat request: the user's words plus, when changing a saved screen, the app's
     * description of that screen (layout words only, no values), which alone can pass [REQUEST_MAX_CHARS].
     */
    public const val CHAT_MAX_CHARS: Int = 2_000

    public val specs: Map<AiPurpose, PurposeSpec> = listOf(
        insight(AiPurpose.SLEEP_INSIGHT, setOf(SLEEP, SCREEN_TIME_TOTALS, STEPS, ACTIVITY), primary = setOf(SLEEP)),
        insight(AiPurpose.ACTIVITY_INSIGHT, setOf(ACTIVITY, STEPS, HEART), primary = setOf(ACTIVITY, STEPS)),
        insight(
            AiPurpose.SCREEN_TIME_INSIGHT,
            setOf(SCREEN_TIME_TOTALS, APP_IDENTITY, NOTIFICATION_COUNTS),
            primary = setOf(SCREEN_TIME_TOTALS, APP_IDENTITY),
            kinds = AGGREGATES + ItemKind.APP_USAGE,
        ),
        PurposeSpec(
            purpose = AiPurpose.GENERAL_QUESTION,
            categories = AiDataCategory.entries.filterTo(LinkedHashSet()) { !it.thirdPartyText && it != SETTINGS },
            primaryCategories = emptySet(),
            requiresData = false,
            lookbackDays = 28,
            includesToday = true,
            rawEvents = true,
            userText = UserTextRule.REQUIRED,
            userTextMaxChars = CHAT_MAX_CHARS,
            itemKinds = ItemKind.entries.toSet(),
            background = null,
            outputSchema = ChatReplySchema.SCHEMA,
        ),
        PurposeSpec(
            purpose = AiPurpose.PATTERN_EXPLANATION,
            categories = setOf(SLEEP, ACTIVITY, STEPS, SCREEN_TIME_TOTALS, NOTIFICATION_COUNTS, HEART, INTERVENTION_HISTORY, SELF_REPORTS),
            primaryCategories = emptySet(),
            requiresData = true,
            lookbackDays = 56,
            includesToday = false,
            rawEvents = true,
            userText = UserTextRule.NONE,
            userTextMaxChars = 0,
            itemKinds = AGGREGATES + ItemKind.EVENT,
            background = null,
            outputSchema = InsightSchema.SCHEMA,
        ),
        PurposeSpec(
            purpose = AiPurpose.JITAI_FROM_NATURAL_LANGUAGE,
            categories = setOf(SETTINGS),
            primaryCategories = emptySet(),
            requiresData = false,
            lookbackDays = 0,
            includesToday = false,
            rawEvents = false,
            userText = UserTextRule.REQUIRED,
            userTextMaxChars = REQUEST_MAX_CHARS,
            itemKinds = setOf(ItemKind.TIME_OF_DAY, ItemKind.CODE),
            background = null,
            outputSchema = JitaiProposalSchema.SCHEMA,
        ),
        PurposeSpec(
            purpose = AiPurpose.JITAI_PROPOSAL_WORDING,
            categories = setOf(SLEEP, ACTIVITY, STEPS, SCREEN_TIME_TOTALS, NOTIFICATION_COUNTS, INTERVENTION_HISTORY),
            primaryCategories = emptySet(),
            requiresData = true,
            lookbackDays = 56,
            includesToday = false,
            rawEvents = false,
            userText = UserTextRule.NONE,
            userTextMaxChars = 0,
            itemKinds = setOf(ItemKind.QUANTITY, ItemKind.CODE),
            background = BackgroundRule(cadence = 1.hours, dailyBudget = 4),
            outputSchema = null,
        ),
        PurposeSpec(
            purpose = AiPurpose.INTERVENTION_TEXT,
            categories = setOf(INTERVENTION_HISTORY, SETTINGS),
            primaryCategories = setOf(INTERVENTION_HISTORY),
            requiresData = true,
            lookbackDays = 7,
            includesToday = false,
            rawEvents = false,
            userText = UserTextRule.NONE,
            userTextMaxChars = 0,
            itemKinds = setOf(ItemKind.CODE),
            background = BackgroundRule(cadence = 1.hours, dailyBudget = 6),
            outputSchema = MediaPromptSchema.SCHEMA,
        ),
    ).associateBy { it.purpose }

    init {
        require(specs.keys == AiPurpose.entries.toSet()) { "every purpose needs a spec" }
    }

    public fun spec(purpose: AiPurpose): PurposeSpec = specs.getValue(purpose)

    /**
     * The time range of [spec] at [now] in the clock's [zone] (never the JVM default zone). It covers whole engine days
     * (04:00 to 04:00 local) and ends at the start of the current engine day, or at [now] when the purpose includes
     * today. Null for purposes without a range.
     */
    public fun range(spec: PurposeSpec, now: Instant, zone: TimeZone): ClosedOpenRange? {
        if (spec.lookbackDays == 0) return null
        val today = EngineDay.of(now, zone)
        val start = EngineDay.bounds(today.minus(DatePeriod(days = spec.lookbackDays)), zone).start
        val end = if (spec.includesToday) now else EngineDay.bounds(today, zone).start
        return ClosedOpenRange(start, end)
    }

    /**
     * The output-validation context for the reply to [envelope]. Pooled intervention text gets no number at all (round 3,
     * jitai-correctness-17). Natural-language rule requests may repeat numbers of their contract ([templates] plus the
     * instructions). A chat reply is checked without number provenance: it is shown once in the chat and never stored,
     * and it may restate sent values in other units (minutes as hours, a daily average of a total). Every other reply may
     * use only numbers that were sent.
     */
    public fun outputContext(envelope: AiRequestEnvelope, templates: List<String> = emptyList()): OutputValidationContext =
        when (envelope.purpose) {
            AiPurpose.INTERVENTION_TEXT -> OutputValidationContext.forPooledText(envelope)
            AiPurpose.GENERAL_QUESTION -> OutputValidationContext(envelope.categories, provenance = null)
            AiPurpose.JITAI_FROM_NATURAL_LANGUAGE -> OutputValidationContext.forEnvelope(envelope, templates + envelope.instructions)
            else -> OutputValidationContext.forEnvelope(envelope, templates)
        }

    private fun insight(
        purpose: AiPurpose,
        categories: Set<AiDataCategory>,
        primary: Set<AiDataCategory>,
        kinds: Set<ItemKind> = AGGREGATES,
    ): PurposeSpec = PurposeSpec(
        purpose = purpose,
        categories = categories,
        primaryCategories = primary,
        requiresData = true,
        lookbackDays = 14,
        includesToday = false,
        rawEvents = false,
        userText = UserTextRule.NONE,
        userTextMaxChars = 0,
        itemKinds = kinds,
        background = NIGHTLY,
        outputSchema = InsightSchema.SCHEMA,
    )
}

/**
 * Hard per-purpose limits (privacy-ai-13) on the personal input ([EnvelopeGate.personalBytes]), failing closed with
 * [GateCodes.CAP_BYTES], [GateCodes.CAP_EVENTS] or
 * [GateCodes.CAP_DAYS]. [maxOutputTokens] is set on the request as `max_output_tokens` (R06 section 4.4). The numbers
 * are UNVERIFIED design choices: no research document fixes them.
 */
public data class PurposeCaps(val maxBytes: Int, val maxEvents: Int, val maxDays: Int, val maxOutputTokens: Int) {
    public companion object {
        /** One extra day covers a range built just before the 04:00 rollover and a DST day of 25 hours. */
        public fun forPurpose(purpose: AiPurpose, lookbackDays: Int): PurposeCaps {
            val days = lookbackDays + 1
            return when (purpose) {
                AiPurpose.SLEEP_INSIGHT, AiPurpose.ACTIVITY_INSIGHT, AiPurpose.SCREEN_TIME_INSIGHT ->
                    PurposeCaps(maxBytes = 16_384, maxEvents = 0, maxDays = days, maxOutputTokens = 800)

                AiPurpose.GENERAL_QUESTION -> PurposeCaps(maxBytes = 65_536, maxEvents = 200, maxDays = days, maxOutputTokens = 600)

                AiPurpose.PATTERN_EXPLANATION -> PurposeCaps(maxBytes = 32_768, maxEvents = 100, maxDays = days, maxOutputTokens = 800)

                AiPurpose.JITAI_FROM_NATURAL_LANGUAGE -> PurposeCaps(
                    maxBytes = 32_768,
                    maxEvents = 0,
                    maxDays = days,
                    maxOutputTokens = 4_096,
                )

                AiPurpose.JITAI_PROPOSAL_WORDING -> PurposeCaps(maxBytes = 8_192, maxEvents = 0, maxDays = days, maxOutputTokens = 200)

                AiPurpose.INTERVENTION_TEXT -> PurposeCaps(maxBytes = 8_192, maxEvents = 0, maxDays = days, maxOutputTokens = 400)
            }
        }
    }
}
