package dev.agentle.jitai.dsl.nl

import dev.agentle.jitai.dsl.model.ActiveWindow
import dev.agentle.jitai.dsl.model.ContentStrategy
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.ExperimentMode
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiKind
import dev.agentle.jitai.dsl.model.OutcomeSpec
import dev.agentle.jitai.dsl.model.QuietHoursPolicy
import dev.agentle.jitai.dsl.model.SnoozePolicy
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.UtcInstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/** `status` of a natural-language reply (R10 §13.2 rule 3). */
@Serializable
public enum class ProposalStatus { OK, NEEDS_CLARIFICATION, UNSUPPORTED }

/** Why the model could not express the request (R10 §13.3). */
@Serializable
public enum class UnsupportedReason {
    NEEDS_UNAVAILABLE_DATA,
    NEEDS_FINER_TIMING,
    NEEDS_UNAVAILABLE_ACTION,
    NOT_A_REMINDER,
    HEALTH_OR_SAFETY,
    OTHER,
}

/** Clarification question ids. */
@Serializable
public enum class QuestionId {
    @SerialName("q1")
    Q1,

    @SerialName("q2")
    Q2,

    @SerialName("q3")
    Q3,
    ;

    public val wire: String get() = name.lowercase()
}

/**
 * One model reply in JitaiProposalSchema v1 (R10 §13.3). Every property is required on the wire; "not applicable" is an
 * explicit `null`. This is data: it is validated, normalized and rendered, never executed (R10 §11.7).
 */
@Serializable
public data class JitaiProposal(
    val schemaVersion: Int = SCHEMA_VERSION,
    val status: ProposalStatus,
    val unsupported: Unsupported? = null,
    val questions: List<Question> = emptyList(),
    val assumptions: List<Assumption> = emptyList(),
    val jitai: JitaiDraft? = null,
) {
    public companion object {
        public const val SCHEMA_VERSION: Int = 1
    }
}

@Serializable
public data class Unsupported(val reason: UnsupportedReason, val detail: String? = null)

@Serializable
public data class Question(val id: QuestionId, val text: String, val options: List<String>)

/** A default the model chose for a vague request: JSON pointer of the value plus one sentence (shown as C04). */
@Serializable
public data class Assumption(val path: String, val text: String)

/**
 * The rule part of a proposal: a [dev.agentle.jitai.dsl.model.JitaiDefinition] without app-owned fields (E005), with
 * `expiresInDays` instead of `expiresAt` and `appLabel` allowed in args.
 */
@Serializable
public data class JitaiDraft(
    val name: String,
    val description: String,
    val kind: JitaiKind,
    val category: JitaiCategory,
    val trigger: Trigger? = null,
    val activeWindow: ActiveWindow? = null,
    val conditions: Condition? = null,
    val contextRequirements: Condition? = null,
    val delivery: DraftDelivery,
    val content: ContentStrategy? = null,
    val cooldownMinutes: Int? = null,
    val maxPerDay: Int? = null,
    val maxPerWeek: Int? = null,
    val priority: Int,
    val snooze: SnoozePolicy? = null,
    val expiresInDays: Int? = null,
    val outcome: OutcomeSpec? = null,
    val suppression: DraftSuppression? = null,
)

/** Delivery of a draft; `deliveryDeadlineMinutes` is app-owned (E005). */
@Serializable
public data class DraftDelivery(
    val channel: DeliveryChannel,
    val quietHoursPolicy: QuietHoursPolicy,
    val notificationTimeoutMinutes: Int? = null,
)

/** Suppression targets of a draft; `jitaiIds` is app-owned (E005). */
@Serializable
public data class DraftSuppression(val categories: List<JitaiCategory>)

/** Evidence tier of a discovered pattern (R10 §14.4). */
@Serializable
public enum class DiscoveryTier { STRONG, MODERATE, WEAK, NONE, INSUFFICIENT }

/**
 * An AI-discovered proposal (R10 §14.7), produced by the local discovery pipeline. Its [jitai] goes through the same
 * validator as a natural-language proposal (origin AI, `createdBy = AI_DISCOVERED`, so `expiresInDays` is required).
 */
@Serializable
public data class DiscoveredProposal(
    val proposalId: String,
    val patternId: String,
    val hypothesisId: String,
    val exposure: String,
    val outcome: String,
    @Serializable(with = UtcInstantSerializer::class) val createdAt: Instant,
    val tier: DiscoveryTier,
    val approvalRequired: Boolean,
    val whyProposed: WhyProposed,
    val jitai: JitaiDraft,
    val expectedOutcome: String,
    val dataRequired: List<String>,
    val trial: Trial,
)

/** Template text plus the evidence numbers (kept with three significant digits). */
@Serializable
public data class WhyProposed(val text: String, val evidence: JsonObject)

@Serializable
public data class Trial(val days: Int, val experimentOffer: ExperimentOffer? = null)

@Serializable
public data class ExperimentOffer(val mode: ExperimentMode, val deliverProbability: Double? = null, val requiresConsent: Boolean)
