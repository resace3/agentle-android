package dev.agentle.feature.insights.port

import dev.agentle.analytics.features.FeatureValue
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.render.RenderOptions
import dev.agentle.jitai.dsl.rule.Condition
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * The intervention detail screen (`AppRoute.InterventionDetail(decisionKey)`, the notification's deep-link target):
 * what was delivered and when, why, the outcome, and feedback. Implemented by APP-WIRING on the engine's decision
 * log and trace store (R10 §6.7, §8.3).
 *
 * Main safety: every function may be called from the main thread.
 */
public interface InterventionPort {
    /**
     * The decision [decisionKey], or `null` when it no longer exists (history older than the retention period, or an
     * unknown key). Re-emits when its outcome or feedback changes. A missing trace (pruned) is an empty
     * [InterventionDetail.trace], not an error.
     */
    public fun intervention(decisionKey: String): Flow<InterventionDetail?>

    /**
     * Records "helpful" or "not helpful" for a delivered intervention (outcome metric SELF_REPORT_HELPFUL, R10 §15.1);
     * a second call replaces the first. Errors: [AppError.ValidationError] with code `not_delivered` for a decision that
     * delivered nothing, [AppError.DatabaseError].
     */
    public suspend fun sendFeedback(decisionKey: String, helpful: Boolean): Outcome<Unit>

    /**
     * Called once when this screen has shown the content of a [DeliveryResult.CARD_PENDING] decision: the in-app card
     * counts as displayed (engine CARD_PENDING -> DELIVERED, jitai-correctness-13). A decision in any other state is
     * left as it is and the result is success. Errors: [AppError.DatabaseError].
     */
    public suspend fun markCardShown(decisionKey: String): Outcome<Unit>
}

/**
 * One decision.
 *
 * @property jitaiId the rule, or null when the rule was deleted since.
 * @property jitaiName the rule's name at decision time.
 * @property deliveredAt when it was shown; null when nothing was delivered.
 * @property reason why the decision ended where it did; null when nothing needs explaining.
 * @property content the suggestion's full text (title and body as rendered for this decision), shown inside the app
 *   only; null when nothing was rendered. The posted notification itself may have shown generic text (detailed
 *   notifications are off by default), so the screen never claims the notification showed this.
 * @property trace the leaf conditions of the decision with their observed values (the trace summary of R10 §6.7).
 * @property renderOptions for rendering the trace conditions in the user's clock format and zone.
 */
public data class InterventionDetail(
    val decisionKey: String,
    val jitaiId: String?,
    val jitaiName: String,
    val channel: DeliveryChannel,
    val decidedAt: Instant,
    val deliveredAt: Instant?,
    val result: DeliveryResult,
    val reason: OutcomeReason? = null,
    val content: DeliveredContent? = null,
    val trace: List<TraceCondition> = emptyList(),
    val feedback: Feedback? = null,
    val renderOptions: RenderOptions,
)

public data class DeliveredContent(val title: String, val body: String)

/**
 * One leaf of the decision's trace.
 *
 * @property condition the leaf (a feature comparison or a time window) as stored in the rule.
 * @property observed the feature value the engine read; null for time windows.
 * @property overridden true when the result came from the leaf's `onUnknown` setting because the value was unknown.
 */
public data class TraceCondition(
    val condition: Condition,
    val observed: FeatureValue? = null,
    val result: TraceResult,
    val overridden: Boolean = false,
)

/** Three-valued result of a leaf (R10 §6). */
public enum class TraceResult { TRUE, FALSE, UNKNOWN }

/** The user's feedback on a delivered intervention. */
public enum class Feedback { HELPFUL, NOT_HELPFUL }
