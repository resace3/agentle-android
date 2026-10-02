package dev.agentle.feature.insights.port

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.nl.Question
import dev.agentle.jitai.dsl.nl.QuestionId
import dev.agentle.jitai.dsl.nl.UnsupportedReason
import dev.agentle.jitai.dsl.validation.ValidationContext
import dev.agentle.jitai.dsl.validation.ValidationIssue
import dev.agentle.jitai.dsl.validation.ValidationReport

/**
 * The JITAI builder, manual and natural-language modes (spec §18, R10 §11, §13). Implemented by APP-WIRING on top of
 * the JITAI store, the capability registry, the installed-app list and the natural-language pipeline of
 * `:ai:context` (`NlContract`, one repair round and two clarification rounds, R10 §13.1).
 *
 * Main safety: every function may be called from the main thread.
 */
public interface JitaiBuilderPort {
    /**
     * What validating and saving a rule needs: [ValidationContext] (the app's [dev.agentle.core.time.AgentleClock],
     * launcher apps, the user's stored rules, quiet hours and global caps, feature access from the capability
     * registry, the media library, the rule id generator and the redacting logger), plus the bundled media and the
     * notification state. Missing permissions are part of the value (`FeatureAccess` answers `NeedsAccess`, which the
     * validator reports as W03), never a failure. Errors: [AppError.DatabaseError].
     */
    public suspend fun environment(): Outcome<BuilderEnvironment>

    /**
     * The stored rule [jitaiId] for editing (any status except ARCHIVED), or a draft the port prepared for the builder
     * (from [NlConversion.Invalid.draftId] or `ProposalReviewPort.draftForEditing`). Errors:
     * [AppError.ValidationError] with code `jitai_not_found`, [AppError.DatabaseError].
     */
    public suspend fun definition(jitaiId: String): Outcome<JitaiDefinition>

    /**
     * Stores [definition] exactly as given (insert or replace by id; the builder has already applied the lifecycle
     * transition, so the status is DRAFT or ACTIVE). Defense in depth: an ACTIVE definition is re-validated with
     * `RuleValidator.revalidate` and rejected with [AppError.ValidationError] (its codes) when it fails; an ACTIVE
     * rule is never stored invalid. Other errors: [AppError.DatabaseError].
     */
    public suspend fun save(definition: JitaiDefinition): Outcome<Unit>

    /**
     * Turns the user's request (1-500 characters, `NlContract.isRequestAcceptable`) into a rule proposal through
     * ChatGPT: first round, validation, at most one repair round (R10 §13.1). Only the request text and the listed
     * settings are sent (no health or usage data, no app list).
     *
     * Errors: [AppError.AuthenticationRequired] or [AppError.TokenExpired] (ChatGPT not connected),
     * [AppError.ConsentViolation] (the user has not allowed sending rule requests to ChatGPT),
     * [AppError.NetworkUnavailable] (offline), [AppError.RateLimited] or [AppError.NotEligible] (usage limit),
     * [AppError.RemoteServerError], [AppError.UnsupportedFeature] (not available in this build).
     */
    public suspend fun convertNaturalLanguage(request: String): Outcome<NlConversion>

    /**
     * Sends the user's answers to the questions of [NlConversion.NeedsClarification] and continues the same request
     * (at most two clarification rounds in total). Same errors as [convertNaturalLanguage], plus
     * [AppError.ValidationError] with code `conversation_expired` for an unknown [conversationId].
     */
    public suspend fun answerClarification(conversationId: String, answers: Map<QuestionId, String>): Outcome<NlConversion>
}

/**
 * @property mediaAssets bundled pictures and clips for `local_media` content; empty when none ship with the app.
 * @property notificationsAllowed false when notifications are blocked for the app.
 */
public data class BuilderEnvironment(
    val context: ValidationContext,
    val mediaAssets: List<MediaAsset> = emptyList(),
    val notificationsAllowed: Boolean = true,
)

/** A bundled media item for `local_media` content: its catalog id and a short display label. */
public data class MediaAsset(val assetId: String, val label: String)

/** Result of one natural-language round (R10 §13.1 steps 6-9). */
public sealed interface NlConversion {
    /**
     * A valid proposal (status OK, no errors): stored as PROPOSED under [proposalId] for `AppRoute.ProposalReview`.
     * [report] is its validation report (definition, rendering, confirm items, warnings). [interpretation] is the
     * model's own summary of what it understood: untrusted text, shown as plain text only.
     */
    public data class Proposed(val proposalId: String, val report: ValidationReport, val interpretation: String) : NlConversion

    /** The model asked up to three questions (R10 §13.1 step 7); answer them with [JitaiBuilderPort.answerClarification]. */
    public data class NeedsClarification(val conversationId: String, val questions: List<Question>) : NlConversion

    /** The model could not express the request (R10 §13.3). [detail] is linted model text: plain text only, may be null. */
    public data class Refused(val reason: UnsupportedReason, val detail: String?) : NlConversion

    /**
     * The reply still failed validation after the repair round ("Agentle could not turn this into a safe rule").
     * [problems] are the validator's errors. [draftId] names a DRAFT the port stored for the manual builder (the
     * request text as description plus every part of the reply that decoded); null when nothing could be stored.
     */
    public data class Invalid(val problems: List<ValidationIssue>, val draftId: String?) : NlConversion
}
