package dev.agentle.ai.context

import dev.agentle.ai.api.AiRequestEnvelope
import dev.agentle.ai.api.AiRequestMode
import dev.agentle.ai.api.BlockKind
import dev.agentle.ai.api.ContextBlock
import dev.agentle.ai.api.ContextItem
import dev.agentle.ai.api.DataItem
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.EventType
import dev.agentle.core.model.SourceFamily
import dev.agentle.core.model.TextOrigin
import dev.agentle.core.time.ClosedOpenRange
import kotlin.time.Duration.Companion.days

/** Reason codes of a refused request (the `detail` of `AppError.ConsentViolation`; never content). */
public object GateCodes {
    public const val PURPOSE_MISMATCH: String = "purpose_mismatch"
    public const val INSTRUCTIONS: String = "instructions_not_registered"
    public const val RANGE: String = "range_outside_purpose"
    public const val TOO_MANY_BLOCKS: String = "too_many_blocks"
    public const val TOO_MANY_ITEMS: String = "too_many_items"
    public const val BLOCK: String = "block_not_allowed"
    public const val ITEM_KIND: String = "item_kind_not_allowed"
    public const val FIELD: String = "field_unknown"
    public const val NO_LINEAGE: String = "lineage_missing"
    public const val THIRD_PARTY: String = "third_party_text"
    public const val CATEGORY: String = "category_not_allowed"
    public const val SOURCE: String = "source_family_not_allowed"
    public const val VALUE: String = "value_not_reduced"
    public const val RAW_EVENTS: String = "raw_events_unconfirmed"
    public const val USER_TEXT: String = "user_text_not_allowed"
    public const val USER_TEXT_MISSING: String = "user_text_required"
    public const val NO_STANDING: String = "no_standing_consent"
    public const val OUTSIDE_STANDING: String = "outside_standing_consent"
    public const val CONSENT_REQUIRED: String = "consent_required"
    public const val NO_ACCOUNT: String = "no_account"
    public const val VERSION_CHANGED: String = "consent_version_changed"
    public const val NOT_IN_FLIGHT: String = "not_in_flight"
    public const val DIGEST_MISMATCH: String = "digest_mismatch"

    /** The request is larger than its purpose's byte cap ([PurposeCaps.maxBytes], privacy-ai-13). */
    public const val CAP_BYTES: String = "cap_bytes_exceeded"

    /** The request holds more individual events than its purpose's cap ([PurposeCaps.maxEvents]). */
    public const val CAP_EVENTS: String = "cap_events_exceeded"

    /** The request's time range spans more days than its purpose's cap ([PurposeCaps.maxDays]). */
    public const val CAP_DAYS: String = "cap_days_exceeded"
}

/** Everything the gate checks an envelope against, decided from a fresh consent read. */
internal data class GateDecision(
    val spec: PurposeSpec,
    val mode: AiRequestMode,
    val categories: Set<AiDataCategory>,
    val sources: Set<SourceFamily>,
    /** The range the request must lie within; null when the purpose has none. */
    val rangeLimit: ClosedOpenRange?,
    val rawEventsConfirmed: Boolean,
    /** The standing consent of a background request; null for user-initiated requests. */
    val standing: StandingConsent?,
    val instructions: String,
)

private data class GateViolation(
    val code: String,
    val categories: Set<AiDataCategory> = emptySet(),
    val sources: Set<SourceFamily> = emptySet(),
)

/**
 * The final gate (docs/ARCHITECTURE.md section 9). It re-checks a whole envelope against a [GateDecision] and fails
 * closed with `AppError.ConsentViolation`. Its categories name what was refused (category and source-family names),
 * and its detail is a [GateCodes] reason. The ContextSelectionEngine runs it on every envelope it builds. The
 * EgressGuard runs it again, with a decision of its own, on every envelope it sends.
 */
internal object EnvelopeGate {
    const val MAX_BLOCKS: Int = 20
    const val MAX_ITEMS: Int = 20
    private const val MAX_EVENT_VALUES = 20

    private val LABEL = Regex("^[a-z][a-z0-9_]{0,39}$")
    val CODE: Regex = Regex("^[A-Z][A-Z0-9_]{0,39}$")
    val UNIT: Regex = Regex("^[a-z%][a-z0-9_%/]{0,11}$")
    private val TIME = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
    private val LOCAL_DATE_TIME = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T([01][0-9]|2[0-3]):[0-5][0-9]$")
    val EVENT_KEY: Regex = Regex("^[a-z][a-zA-Z0-9_]{0,39}$")
    private val EVENT_TYPES = EventType.entries.mapTo(HashSet()) { it.name }

    /** UTF-8 bytes of the personal input (data and user items); the app-constant instructions do not count. */
    fun personalBytes(envelope: AiRequestEnvelope): Int =
        envelope.dataInputJson.encodeToByteArray().size + (envelope.userInputJson?.encodeToByteArray()?.size ?: 0)

    fun check(envelope: AiRequestEnvelope, decision: GateDecision): Outcome<Unit> {
        val violations = envelopeViolations(envelope, decision) +
            envelope.blocks.flatMap { blockViolations(it, decision) } +
            capViolations(envelope, decision.spec.caps)
        if (violations.isEmpty()) return Outcome.Success(Unit)
        val names = violations.flatMapTo(sortedSetOf()) { violation ->
            violation.categories.map { it.name } + violation.sources.map { it.name }
        }
        return Outcome.Failure(AppError.ConsentViolation(names, violations.first().code))
    }

    /** The purpose's hard caps, checked last so a more specific refusal names the first reason. */
    private fun capViolations(envelope: AiRequestEnvelope, caps: PurposeCaps): List<GateViolation> = buildList {
        if (personalBytes(envelope) > caps.maxBytes) add(GateViolation(GateCodes.CAP_BYTES))
        if (envelope.blocks.filter { it.rawEvents }.sumOf { it.items.size } > caps.maxEvents) add(GateViolation(GateCodes.CAP_EVENTS))
        val start = envelope.rangeStart
        val end = envelope.rangeEnd
        if (start != null && end != null && end - start > caps.maxDays.days) add(GateViolation(GateCodes.CAP_DAYS))
    }

    private fun envelopeViolations(envelope: AiRequestEnvelope, decision: GateDecision): List<GateViolation> = buildList {
        val spec = decision.spec
        if (envelope.purpose != spec.purpose || envelope.mode != decision.mode) add(GateViolation(GateCodes.PURPOSE_MISMATCH))
        if (envelope.instructions != decision.instructions) add(GateViolation(GateCodes.INSTRUCTIONS))
        if (!rangeWithin(envelope, decision.rangeLimit)) add(GateViolation(GateCodes.RANGE))
        if (envelope.blocks.size > MAX_BLOCKS) add(GateViolation(GateCodes.TOO_MANY_BLOCKS))
        if (decision.mode == AiRequestMode.BACKGROUND && decision.standing == null) add(GateViolation(GateCodes.NO_STANDING))
        val userText = envelope.userText
        val userTextAllowed = spec.userText == UserTextRule.REQUIRED && decision.mode == AiRequestMode.USER_INITIATED
        when {
            userText == null -> if (userTextAllowed) add(GateViolation(GateCodes.USER_TEXT_MISSING))

            !userTextAllowed || userText.origin != TextOrigin.USER_REQUEST || userText.aiGenerated ->
                add(GateViolation(GateCodes.USER_TEXT))

            !SafeText.isSafe(userText.raw, spec.userTextMaxChars) -> add(GateViolation(GateCodes.VALUE))
        }
    }

    private fun rangeWithin(envelope: AiRequestEnvelope, limit: ClosedOpenRange?): Boolean {
        val start = envelope.rangeStart
        val end = envelope.rangeEnd
        if (start != null && end != null && start > end) return false
        return if (limit == null) {
            start == null && end == null
        } else {
            start != null && end != null && start >= limit.start && end <= limit.end
        }
    }

    private fun blockViolations(block: ContextBlock, decision: GateDecision): List<GateViolation> = buildList {
        if (!LABEL.matches(block.label)) add(GateViolation(GateCodes.BLOCK))
        if (!blockAllowed(block.kind, decision)) {
            val code = if (block.kind == BlockKind.RAW_EVENTS) GateCodes.RAW_EVENTS else GateCodes.BLOCK
            add(GateViolation(code, block.lineage.categories))
        }
        if (block.items.size > MAX_ITEMS) add(GateViolation(GateCodes.TOO_MANY_ITEMS))
        block.items.forEach { addAll(itemViolations(block.kind, it, decision)) }
    }

    private fun blockAllowed(kind: BlockKind, decision: GateDecision): Boolean {
        val userInitiated = decision.mode == AiRequestMode.USER_INITIATED
        return when (kind) {
            BlockKind.AGGREGATES -> true
            BlockKind.APP_USAGE, BlockKind.USER_TEXT -> userInitiated
            BlockKind.RAW_EVENTS -> userInitiated && decision.spec.rawEvents && decision.rawEventsConfirmed
        }
    }

    private fun itemViolations(blockKind: BlockKind, item: ContextItem, decision: GateDecision): List<GateViolation> = buildList {
        val data = item.item
        val kind = kindOf(data)
        val lineage = item.lineage
        if (kind !in decision.spec.itemKinds || blockKindOf(kind) != blockKind) add(GateViolation(GateCodes.ITEM_KIND))
        val field = AiFieldRegistry[data.field]
        val fieldOk = field != null && field.kind == kind && field.purposes?.contains(decision.spec.purpose) != false
        if (!fieldOk) add(GateViolation(GateCodes.FIELD, lineage.categories))
        if (lineage.categories.isEmpty()) add(GateViolation(GateCodes.NO_LINEAGE))
        val thirdParty = lineage.categories.filterTo(sortedSetOf()) { it.thirdPartyText }
        if (thirdParty.isNotEmpty()) add(GateViolation(GateCodes.THIRD_PARTY, thirdParty))
        val denied = lineage.categories - decision.categories
        if (denied.isNotEmpty()) add(GateViolation(GateCodes.CATEGORY, denied))
        val deniedSources = lineage.sources - decision.sources
        if (deniedSources.isNotEmpty()) add(GateViolation(GateCodes.SOURCE, sources = deniedSources))
        decision.standing?.let { standing ->
            val inside = data.field in standing.fields &&
                standing.categories.containsAll(lineage.categories) &&
                standing.sourceFamilies.containsAll(lineage.sources)
            if (!inside) add(GateViolation(GateCodes.OUTSIDE_STANDING, lineage.categories - standing.categories))
        }
        if (!valueOk(data, field)) add(GateViolation(GateCodes.VALUE))
    }

    private fun kindOf(item: DataItem): ItemKind = when (item) {
        is DataItem.Quantity -> ItemKind.QUANTITY
        is DataItem.TimeOfDay -> ItemKind.TIME_OF_DAY
        is DataItem.Code -> ItemKind.CODE
        is DataItem.Text -> ItemKind.TEXT
        is DataItem.AppUsage -> ItemKind.APP_USAGE
        is DataItem.Event -> ItemKind.EVENT
    }

    fun blockKindOf(kind: ItemKind): BlockKind = when (kind) {
        ItemKind.QUANTITY, ItemKind.TIME_OF_DAY, ItemKind.CODE -> BlockKind.AGGREGATES
        ItemKind.TEXT -> BlockKind.USER_TEXT
        ItemKind.APP_USAGE -> BlockKind.APP_USAGE
        ItemKind.EVENT -> BlockKind.RAW_EVENTS
    }

    private fun valueOk(item: DataItem, field: AiField?): Boolean = when (item) {
        is DataItem.Quantity -> item.value.isFinite() && UNIT.matches(item.unit) && (field?.unit == null || field.unit == item.unit)

        is DataItem.TimeOfDay -> TIME.matches(item.time)

        is DataItem.Code -> CODE.matches(item.code) && field?.codes?.contains(item.code) == true

        is DataItem.Text -> SafeText.isSafe(item.text, SafeText.ITEM_MAX)

        is DataItem.AppUsage -> SafeText.isSafe(item.app, SafeText.LABEL_MAX) && item.minutes >= 0 && (item.opens ?: 0) >= 0

        is DataItem.Event ->
            item.eventType in EVENT_TYPES &&
                LOCAL_DATE_TIME.matches(item.start) &&
                (item.end?.let(LOCAL_DATE_TIME::matches) ?: true) &&
                item.values.size <= MAX_EVENT_VALUES &&
                item.values.all { (key, value) -> EVENT_KEY.matches(key) && value.isFinite() }
    }
}
