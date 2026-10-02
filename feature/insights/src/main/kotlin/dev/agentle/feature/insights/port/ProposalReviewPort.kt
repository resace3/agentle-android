package dev.agentle.feature.insights.port

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.DataCategory
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.nl.DiscoveredProposal
import dev.agentle.jitai.dsl.nl.JitaiProposal
import kotlinx.coroutines.flow.Flow

/**
 * The proposal review screen (R10 §11.5, §14.8; Journeys 6 and 7) for natural-language and AI-discovered proposals.
 * The screen re-validates the proposal itself with `RuleValidator` and the current [BuilderEnvironment] (so an app
 * the user picks for C01 is applied), and approves it with `JitaiLifecycle`; this port loads and stores.
 *
 * Main safety: every function may be called from the main thread.
 */
public interface ProposalReviewPort {
    /**
     * The proposal [proposalId], or `null` when it does not exist (expired, deleted, or an old link). Re-emits when
     * its state changes (for example after it was activated on another screen).
     */
    public fun proposal(proposalId: String): Flow<ProposalReviewData?>

    /** The validation environment, as [JitaiBuilderPort.environment]. Errors: [AppError.DatabaseError]. */
    public suspend fun environment(): Outcome<BuilderEnvironment>

    /**
     * Stores [definition] (already approved: status ACTIVE, `provenance.approvedRendering` set) as the rule made from
     * [proposalId] and marks the proposal accepted. When the proposal replaces a rule ([ProposalReviewData.replaces]),
     * the definition is stored as that rule's next version. Defense in depth: the implementation re-validates with
     * `RuleValidator.revalidate` and returns [AppError.ValidationError] (its codes) instead of storing an invalid rule.
     * Other errors: [AppError.ValidationError] with code `proposal_not_pending` when the proposal was already handled,
     * [AppError.DatabaseError].
     */
    public suspend fun activate(proposalId: String, definition: JitaiDefinition): Outcome<Unit>

    /**
     * Rejects the proposal: [ProposalRejection.DISCARD] for a natural-language proposal; for an AI-discovered one
     * [ProposalRejection.NOT_NOW] (the pattern is muted for 60 days) or [ProposalRejection.NEVER] (never suggested
     * again). Errors: `proposal_not_pending`, [AppError.DatabaseError].
     */
    public suspend fun reject(proposalId: String, rejection: ProposalRejection): Outcome<Unit>

    /**
     * Prepares the proposal for the manual builder and returns the id `JitaiBuilderPort.definition` loads. [definition]
     * is the proposal as currently validated (with the user's app choices), or null when it cannot be normalized yet;
     * the implementation then stores the draft as far as it decodes. The proposal stays pending. Errors:
     * `proposal_not_pending`, [AppError.DatabaseError].
     */
    public suspend fun draftForEditing(proposalId: String, definition: JitaiDefinition?): Outcome<String>
}

/**
 * One proposal and its source.
 *
 * @property sourceCategories data categories the proposal (and any AI text in it) was made from: the request's
 *   settings for natural language, the evidence's lineage for discovered patterns.
 * @property replaces the rule this proposal would change (shown as before and after); null for a new rule.
 */
public data class ProposalReviewData(
    val proposalId: String,
    val source: ProposalSource,
    val sourceCategories: Set<DataCategory> = emptySet(),
    val replaces: JitaiDefinition? = null,
    val state: ProposalState = ProposalState.PENDING,
)

/** Where a proposal came from. */
public sealed interface ProposalSource {
    /**
     * A natural-language request: the user's own [request] text, the decoded model reply, and the model's
     * [interpretation] (untrusted, plain text only).
     */
    public data class NaturalLanguage(val request: String, val proposal: JitaiProposal, val interpretation: String) : ProposalSource

    /** An AI-discovered proposal (R10 §14.7). Its `whyProposed.text` is template text made on the device. */
    public data class Discovered(val proposal: DiscoveredProposal) : ProposalSource
}

/** Review state of a proposal. */
public enum class ProposalState { PENDING, ACCEPTED, REJECTED, EXPIRED }

/** How the user rejected a proposal. */
public enum class ProposalRejection { DISCARD, NOT_NOW, NEVER }
