@file:OptIn(AiEnvelopeConstruction::class)

package dev.agentle.ai.context

import dev.agentle.ai.api.AiEnvelopeConstruction
import dev.agentle.ai.api.AiPurpose
import dev.agentle.ai.api.AiRequestEnvelope
import dev.agentle.ai.api.AiRequestMode
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.flatMap
import dev.agentle.core.common.map
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.TextOrigin
import dev.agentle.core.model.UntrustedText
import dev.agentle.core.time.AgentleClock
import java.security.SecureRandom
import java.util.Random
import kotlin.coroutines.cancellation.CancellationException

/**
 * One request to the [ContextSelectionEngine]. [userQuestion] is the user's typed request (required by
 * GENERAL_QUESTION and JITAI_FROM_NATURAL_LANGUAGE, refused elsewhere). [rawEvents] is the user's confirmation for
 * individual events. [subject] is an optional app code that selects data (see [AiDataQuery.subject]).
 */
public data class AiContextRequest(
    val purpose: AiPurpose,
    val userQuestion: String? = null,
    val mode: AiRequestMode = AiRequestMode.USER_INITIATED,
    val rawEvents: RawEventsConfirmation? = null,
    val subject: String? = null,
) {
    /** Never shows the question. */
    override fun toString(): String =
        "AiContextRequest(purpose=$purpose, mode=$mode, question=${userQuestion != null}, rawEvents=${rawEvents != null})"
}

/**
 * Builds the only thing that can be sent to a model: an [AiRequestEnvelope] (docs/ARCHITECTURE.md section 9).
 *
 * 1. The purpose fixes the category allow-list, the time range (whole engine days in the clock's zone) and what may be
 *    included ([PurposePolicy]).
 * 2. The allow-list is intersected with the user's grants, read fresh from the consent store. Every category is
 *    denied by default, and an unreadable store denies the request ([AiSharingPolicy], privacy-ai-02).
 *    Background requests also need a standing consent and send aggregates within it only (privacy-ai-01).
 * 3. Data comes from the feature layer ([AiContextDataSource]): aggregates by default, per-app usage and the user's own
 *    text when allowed, and individual events only with a fresh [RawEventsConfirmation].
 * 4. Each value gets the lineage of everything it was derived from: the producer's lineage plus the floor of its field
 *    ([AiFieldRegistry]). An unknown field gets UNKNOWN lineage. Text is reduced to the safe set ([SafeText]).
 *    Third-party text is refused, and AI-written text is left out unless the purpose needs it.
 * 5. Every block is quoted, untrusted data. The instructions are app constants ([AiInstructionSet]).
 * 6. The final gate ([EnvelopeGate]) re-checks the whole envelope and fails closed with `AppError.ConsentViolation`.
 *    It never filters silently. A producer that returns data outside the query fails the request.
 *
 * Nothing here is persisted. A retry builds a new envelope. Logs hold metadata only.
 */
public class ContextSelectionEngine(
    private val dataSource: AiContextDataSource,
    private val consent: AiConsentRepository,
    private val account: AiAccountSource,
    private val clock: AgentleClock,
    private val rawEventsLedger: RawEventsConsentLedger,
    private val policy: AiSharingPolicy = DenyByDefaultSharingPolicy(consent.currentVersion),
    private val instructions: AiInstructionSet = AiInstructionSet(),
    private val logger: Logger = Logger.NONE,
    private val random: Random = SecureRandom(),
) {
    /** A user-initiated request for [purpose] with the user's typed [userQuestion], if the purpose takes one. */
    public suspend fun build(purpose: AiPurpose, userQuestion: String?): Outcome<AiRequestEnvelope> =
        build(AiContextRequest(purpose, userQuestion))

    public suspend fun build(request: AiContextRequest): Outcome<AiRequestEnvelope> {
        val spec = PurposePolicy.spec(request.purpose)
        val requestId = REQUEST_PREFIX + RequestIds.next(random)
        val result = requestError(spec, request)?.let { Outcome.Failure(it) }
            ?: decide(spec, request, requestId).flatMap { decision -> assemble(spec, request, requestId, decision) }
        log(request, requestId, result)
        return result
    }

    /** The preview of a built envelope, shown before a user-initiated request is sent. */
    public fun preview(envelope: AiRequestEnvelope): AiRequestPreview = AiRequestPreview.of(envelope)

    /**
     * Call when the user confirms, on the preview, that individual events may be included. The returned confirmation
     * is good for one [build] of [purpose] within [RawEventsConsentLedger.TTL].
     */
    public fun confirmRawEvents(purpose: AiPurpose): Outcome<RawEventsConfirmation> = if (PurposePolicy.spec(purpose).rawEvents) {
        Outcome.Success(rawEventsLedger.issue(purpose))
    } else {
        Outcome.Failure(AppError.NotEligible(RAW_EVENTS_NOT_ALLOWED))
    }

    /**
     * The template the user reviews before allowing background sends of [purpose]: every aggregate field the purpose
     * can send, their categories and source families (only families that may be sent at all), the look-back and the
     * default cadence and daily budget. Accept it with [AiConsentRepository.acceptStanding].
     */
    public fun standingConsentTemplate(purpose: AiPurpose): Outcome<StandingConsentTemplate> {
        val spec = PurposePolicy.spec(purpose)
        val background = spec.background ?: return Outcome.Failure(AppError.NotEligible(BACKGROUND_NOT_ALLOWED))
        val fields = AiFieldRegistry.fieldsFor(purpose, spec.categories, spec.itemKinds.intersect(AGGREGATE_KINDS))
        val categories = fields.flatMapTo(sortedSetOf()) { it.categories.ifEmpty { spec.categories } }.intersect(spec.categories)
        val sources = fields.flatMapTo(sortedSetOf()) { it.sources }.intersect(policy.allowedSources())
        return Outcome.Success(
            StandingConsentTemplate(
                purpose = purpose,
                fields = fields.mapTo(sortedSetOf()) { it.code },
                categories = categories,
                sourceFamilies = sources,
                lookbackDays = spec.lookbackDays,
                cadence = background.cadence,
                dailyBudget = background.dailyBudget,
            ),
        )
    }

    private fun requestError(spec: PurposeSpec, request: AiContextRequest): AppError? {
        val background = request.mode == AiRequestMode.BACKGROUND
        val question = request.userQuestion
        return when {
            background && spec.background == null -> AppError.NotEligible(BACKGROUND_NOT_ALLOWED)

            spec.userText == UserTextRule.REQUIRED && (question.isNullOrBlank() || background) ->
                AppError.ValidationError(listOf(GateCodes.USER_TEXT_MISSING))

            spec.userText == UserTextRule.NONE && question != null -> AppError.ValidationError(listOf(GateCodes.USER_TEXT))

            request.rawEvents != null && (!spec.rawEvents || background) -> AppError.NotEligible(RAW_EVENTS_NOT_ALLOWED)

            request.subject != null && !EnvelopeGate.CODE.matches(request.subject) -> AppError.ValidationError(listOf(SUBJECT_INVALID))

            else -> null
        }
    }

    private suspend fun decide(spec: PurposeSpec, request: AiContextRequest, requestId: String): Outcome<GateDecision> {
        val snapshot = when (val state = consent.read()) {
            is ConsentState.Readable -> state.snapshot
            is ConsentState.Unreadable -> return violation(spec.categories, state.code)
        }
        val sub = account.activeAccountSub()
        val background = request.mode == AiRequestMode.BACKGROUND
        val standing = if (background) policy.standingConsent(snapshot, spec.purpose, sub) else null
        var categories: Set<AiDataCategory> = spec.categories.intersect(policy.allowedCategories(snapshot, spec.purpose, sub))
        var sources = policy.allowedSources()
        if (standing != null) {
            categories = categories.intersect(standing.categories)
            sources = sources.intersect(standing.sourceFamilies)
        }
        val rawConfirmed = request.rawEvents?.let { rawEventsLedger.consume(it, spec.purpose, requestId) } ?: false
        val lookback = minOf(spec.lookbackDays, standing?.lookbackDays ?: spec.lookbackDays)
        val failure = when {
            background && standing == null -> violation(spec.categories, GateCodes.NO_STANDING)

            request.rawEvents != null && !rawConfirmed -> violation(emptySet(), GateCodes.RAW_EVENTS)

            spec.primaryCategories.isNotEmpty() && categories.none(spec.primaryCategories::contains) ->
                violation(spec.primaryCategories, GateCodes.CONSENT_REQUIRED)

            spec.requiresData && categories.isEmpty() -> violation(spec.categories, GateCodes.CONSENT_REQUIRED)

            else -> null
        }
        return failure ?: Outcome.Success(
            GateDecision(
                spec = spec,
                mode = request.mode,
                categories = categories,
                sources = sources,
                rangeLimit = PurposePolicy.range(spec.copy(lookbackDays = lookback), clock.now(), clock.zone()),
                rawEventsConfirmed = rawConfirmed,
                standing = standing,
                instructions = instructions.forPurpose(spec.purpose),
            ),
        )
    }

    private suspend fun assemble(
        spec: PurposeSpec,
        request: AiContextRequest,
        requestId: String,
        decision: GateDecision,
    ): Outcome<AiRequestEnvelope> {
        val query = AiDataQuery(
            purpose = spec.purpose,
            mode = request.mode,
            categories = decision.categories,
            sourceFamilies = decision.sources,
            kinds = spec.itemKinds,
            range = decision.rangeLimit,
            zone = clock.zone(),
            fields = decision.standing?.fields,
            subject = request.subject,
        )
        val userText = request.userQuestion?.let { reduceQuestion(it, spec) }
        if (spec.userText == UserTextRule.REQUIRED && userText == null) {
            return Outcome.Failure(AppError.ValidationError(listOf(GateCodes.USER_TEXT_MISSING)))
        }
        return collect(spec, decision, query).flatMap { facts ->
            BlockAssembler(spec, clock.zone()).blocks(facts)
        }.flatMap { blocks ->
            val envelope = AiRequestEnvelope(
                requestId = requestId,
                purpose = spec.purpose,
                mode = request.mode,
                instructions = decision.instructions,
                userText = userText,
                blocks = blocks,
                rangeStart = decision.rangeLimit?.start,
                rangeEnd = decision.rangeLimit?.end,
                createdAt = clock.now(),
                consentVersion = consent.currentVersion,
            )
            EnvelopeGate.check(envelope, decision).flatMap { dataRequirement(spec, envelope) }.map { envelope }
        }
    }

    private fun reduceQuestion(question: String, spec: PurposeSpec): UntrustedText? {
        if (spec.userText != UserTextRule.REQUIRED) return null
        val reduced = SafeText.reduce(question, spec.userTextMaxChars)
        return if (reduced.isEmpty()) null else UntrustedText(reduced, TextOrigin.USER_REQUEST)
    }

    private fun dataRequirement(spec: PurposeSpec, envelope: AiRequestEnvelope): Outcome<Unit> {
        val items = envelope.blocks.flatMap { it.items }
        val primaryMissing = spec.primaryCategories.isNotEmpty() &&
            items.none { item -> item.lineage.categories.any(spec.primaryCategories::contains) }
        return when {
            spec.requiresData && items.isEmpty() -> Outcome.Failure(AppError.NotEligible(NO_DATA))
            primaryMissing -> Outcome.Failure(AppError.NotEligible(NO_DATA))
            else -> Outcome.Success(Unit)
        }
    }

    private suspend fun collect(spec: PurposeSpec, decision: GateDecision, query: AiDataQuery): Outcome<CollectedFacts> {
        val userInitiated = decision.mode == AiRequestMode.USER_INITIATED
        val categories = decision.categories
        val wantsAggregates = categories.isNotEmpty() && spec.itemKinds.any(AGGREGATE_KINDS::contains)
        val wantsApps = userInitiated && AiDataCategory.APP_IDENTITY in categories && ItemKind.APP_USAGE in spec.itemKinds
        val wantsTexts = userInitiated &&
            (AiDataCategory.USER_TEXT in categories || AiDataCategory.GOALS in categories) &&
            ItemKind.TEXT in spec.itemKinds
        val wantsRaw = userInitiated && decision.rawEventsConfirmed && ItemKind.EVENT in spec.itemKinds
        val aggregates = fetch(wantsAggregates) { dataSource.aggregates(query) }
        val apps = fetch(wantsApps) { dataSource.appUsage(query) }
        val texts = fetch(wantsTexts) { dataSource.userTexts(query) }
        val raw = fetch(wantsRaw) { dataSource.rawEvents(query) }
        val failure = listOf(aggregates, apps, texts, raw).firstNotNullOfOrNull { (it as? Outcome.Failure)?.error }
        return if (failure != null) {
            Outcome.Failure(failure)
        } else {
            Outcome.Success(CollectedFacts(aggregates.valueOrEmpty(), apps.valueOrEmpty(), texts.valueOrEmpty(), raw.valueOrEmpty()))
        }
    }

    private suspend fun <T> fetch(enabled: Boolean, block: suspend () -> Outcome<List<T>>): Outcome<List<T>> {
        if (!enabled) return Outcome.Success(emptyList())
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (expected: Exception) {
            Outcome.Failure(AppError.Unexpected(expected::class.simpleName))
        }
    }

    private fun <T> Outcome<List<T>>.valueOrEmpty(): List<T> = (this as? Outcome.Success)?.value.orEmpty()

    private fun violation(categories: Set<AiDataCategory>, code: String): Outcome.Failure =
        Outcome.Failure(AppError.ConsentViolation(categories.namesSorted(), code))

    private fun log(request: AiContextRequest, requestId: String, result: Outcome<AiRequestEnvelope>) {
        when (result) {
            is Outcome.Success -> {
                val envelope = result.value
                logger.i(
                    COMPONENT,
                    "ai request built",
                    mapOf(
                        "requestId" to requestId,
                        "purpose" to request.purpose,
                        "mode" to request.mode,
                        "blocks" to envelope.blocks.size,
                        "items" to envelope.blocks.sumOf { it.items.size },
                        "categories" to envelope.categories.namesSorted().joinToString(","),
                        "bytes" to envelope.approximateBytes,
                    ),
                )
            }

            is Outcome.Failure -> logger.w(
                COMPONENT,
                "ai request not built",
                error = result.error,
                fields = mapOf(
                    "requestId" to requestId,
                    "purpose" to request.purpose,
                    "mode" to request.mode,
                    "reason" to reasonOf(result.error),
                ),
            )
        }
    }

    public companion object {
        public const val REQUEST_PREFIX: String = "air-"
        public const val NO_DATA: String = "no_data"
        public const val BACKGROUND_NOT_ALLOWED: String = "background_not_allowed"
        public const val RAW_EVENTS_NOT_ALLOWED: String = "raw_events_not_allowed"
        public const val SUBJECT_INVALID: String = "subject_invalid"

        internal val AGGREGATE_KINDS: Set<ItemKind> = setOf(ItemKind.QUANTITY, ItemKind.TIME_OF_DAY, ItemKind.CODE)
        private const val COMPONENT = "ai.context"
    }
}

/**
 * A snake-case reason code of [error]: its detail, else the reason of `NotEligible` or the first code of
 * `ValidationError`. Null when there is none; anything that is not such a code is never logged or stored.
 */
internal fun reasonOf(error: AppError): String? {
    val candidate = when (error) {
        is AppError.NotEligible -> error.detail ?: error.reason
        is AppError.ValidationError -> error.detail ?: error.codes.firstOrNull()
        else -> error.detail
    }
    return candidate?.takeIf { REASON.matches(it) }
}

private val REASON = Regex("^[a-z][a-z0-9_]{0,47}$")

internal data class CollectedFacts(
    val aggregates: List<AggregateFact>,
    val apps: List<AppUsageFact>,
    val texts: List<UserTextFact>,
    val raw: List<RawEventFact>,
)
