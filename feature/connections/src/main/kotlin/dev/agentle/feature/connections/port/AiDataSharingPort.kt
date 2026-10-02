package dev.agentle.feature.connections.port

import dev.agentle.ai.api.AiPurpose
import dev.agentle.core.common.Outcome
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * What the AI Data Sharing screen (`AppRoute.AiDataSharing`) needs from the AI consent layer (docs/ARCHITECTURE.md §9,
 * red team privacy-ai-02/03).
 *
 * Consent is an explicit allow-list per data category: a category without a current grant is denied, every category
 * starts off, and a consent-version bump (categories, purposes or the recipient disclosure changed) voids older grants
 * until the user grants them again. Turning a category off takes effect at once: requests in flight that use it are
 * cancelled, and every request is checked again against the store when it is sent.
 *
 * Every suspend function is main-safe and never throws except `CancellationException`.
 */
public interface AiDataSharingPort {
    /**
     * The consent store as the screen shows it; emits the current value on collection, then every change.
     *
     * - Nothing granted yet: every category with `allowed = false` and no grant time.
     * - The store cannot be read (I/O error, corruption, unknown key): [AiConsentState.readFailed] true and every
     *   category `allowed = false` (fail closed); the screen says so.
     * - No consent layer in this build: [AiConsentState.available] false and no categories.
     */
    public val consent: Flow<AiConsentState>

    /**
     * AI requests as metadata only (time, purpose, categories, size, outcome; never the payload), newest first, at
     * most 100. Emits an empty list when there are none. If the log cannot be read the flow emits an empty list and
     * [AiConsentState.historyUnavailable] is set.
     */
    public val history: Flow<List<AiRequestRecord>>

    /**
     * Grants or revokes [category] (for every purpose) under the current consent version and for the connected ChatGPT
     * account. The screen calls it with `allowed = true` only after the user confirmed the disclosure. Revoking takes
     * effect before it returns: requests in flight that used [category] are cancelled (their answers discarded) and
     * their purposes are listed in [AiConsentChange.cancelledRequests].
     *
     * Failure, with nothing changed: `database_error`, `unsupported_feature` (no consent layer in this build),
     * `authentication_required` (granting needs a connected ChatGPT account; revoking never does), `consent_violation`
     * (a category this version never sends, see [AiCategoryRestriction.NEVER_SENT]).
     */
    public suspend fun setAllowed(category: AiSharingCategory, allowed: Boolean): Outcome<AiConsentChange>

    /**
     * Builds, without sending anything, exactly what an AI request for [purpose] would send right now under the current
     * grants (no user question is included). The content is the sealed request envelope's exact text as the AI layer
     * would send it; the screen shows it read-only as sensitive content and never stores or logs it. Categories the
     * purpose would use but that are off are in [AiRequestPreview.leftOut].
     *
     * Failure: `unsupported_feature` (no AI layer in this build), `database_error` (data cannot be read),
     * `consent_violation` (the final gate found a category that is off; nothing is shown).
     */
    public suspend fun preview(purpose: AiPurpose): Outcome<AiRequestPreview>
}

/**
 * The data categories a user can allow for AI requests. Name-for-name the AI context layer's `AiDataCategory` (red team
 * privacy-ai-04; on team/ai-context, not on main yet), so the wiring maps them by name. A category the AI layer adds
 * later is simply not offered here until this list is updated, which keeps it denied.
 */
public enum class AiSharingCategory {
    SCREEN_TIME_TOTALS,
    APP_IDENTITY,
    NOTIFICATION_COUNTS,
    NOTIFICATION_TEXT,
    CALENDAR_BUSY,
    CALENDAR_TEXT,
    LOCATION_CLASS,
    ACTIVITY,
    STEPS,
    SLEEP,
    HEART,
    BODY,
    USER_TEXT,
    GOALS,
    SELF_REPORTS,
    DEVICE_STATE,
    INTERVENTION_HISTORY,
    SETTINGS,
}

/**
 * The consent store.
 *
 * @property available false when this build has no consent layer (then [categories] is empty).
 * @property consentVersion the version of the categories, purposes and recipient disclosure in force.
 * @property disclosure the exact recipient disclosure of [consentVersion] (it names OpenAI and says requests are linked
 *   to the user's ChatGPT account); the screen shows it before a category is turned on. Empty when unknown, and then
 *   the screen shows its own summary.
 * @property accountConnected a ChatGPT account is connected; grants belong to that account, so without one nothing
 *   can be turned on (turning off always works).
 * @property categories one entry per [AiSharingCategory] the layer offers, in display order.
 * @property lastChangedAt when the user last granted or revoked a category, if ever.
 * @property readFailed the store could not be read; everything is treated as off.
 * @property historyUnavailable the request log could not be read.
 */
public data class AiConsentState(
    val available: Boolean,
    val consentVersion: Int,
    val categories: List<AiCategoryConsent>,
    val disclosure: String = "",
    val accountConnected: Boolean = false,
    val lastChangedAt: Instant? = null,
    val readFailed: Boolean = false,
    val historyUnavailable: Boolean = false,
)

/**
 * One category's consent.
 *
 * @property allowed a current grant exists (under [AiConsentState.consentVersion]).
 * @property grantedAt when the current grant was given.
 * @property outdatedGrant a grant exists for an older consent version: it no longer counts, the user must grant again.
 * @property restriction what this version never sends, whatever the grant says.
 */
public data class AiCategoryConsent(
    val category: AiSharingCategory,
    val allowed: Boolean,
    val grantedAt: Instant? = null,
    val outdatedGrant: Boolean = false,
    val restriction: AiCategoryRestriction = AiCategoryRestriction.NONE,
)

/** Limits of this version that apply whatever the user allows (docs/ARCHITECTURE.md §9, red team privacy-ai-04). */
public enum class AiCategoryRestriction {
    NONE,

    /** Text written by someone else (notification or calendar text): never sent in this version; cannot be allowed. */
    NEVER_SENT,

    /** Values that came from the Google Health API are never sent in this version; Health Connect and phone data can be. */
    WEARABLE_API_EXCLUDED,
}

/** The result of [AiDataSharingPort.setAllowed]: the change and the purposes of the in-flight requests it cancelled. */
public data class AiConsentChange(
    val category: AiSharingCategory,
    val allowed: Boolean,
    val consentVersion: Int,
    val cancelledRequests: List<AiPurpose> = emptyList(),
)

/**
 * What an AI request for [purpose] would send now. [instructions], [dataInput] and [userInput] are the exact strings
 * the AI layer would send (app-constant instructions, the quoted data item, the quoted user request); together they are
 * [bytes] UTF-8 bytes. Personal data: shown on screen only, never logged or stored ([toString] leaves them out).
 */
public data class AiRequestPreview(
    val purpose: AiPurpose,
    val categories: Set<AiSharingCategory>,
    val leftOut: Set<AiSharingCategory>,
    val rangeStart: Instant?,
    val rangeEnd: Instant?,
    val rawEvents: Boolean,
    val aggregates: Boolean,
    val bytes: Int,
    val consentVersion: Int,
    val instructions: String,
    val dataInput: String,
    val userInput: String? = null,
) {
    override fun toString(): String =
        "AiRequestPreview(purpose=$purpose, categories=$categories, leftOut=$leftOut, rawEvents=$rawEvents, " +
            "aggregates=$aggregates, bytes=$bytes, consentVersion=$consentVersion)"
}

/**
 * One AI request, as metadata only: never the payload, the prompt or the answer (the AI layer's `ai_request` record).
 *
 * @property at when the request was created.
 * @property categories the categories present in the request body.
 * @property bytes the approximate size of what was (or would have been) sent, in UTF-8 bytes.
 * @property background started by a schedule (a nightly insight, reminder text) rather than by the user.
 * @property rawEvents individual events were included, not only aggregates.
 * @property userText the user's own typed text was included.
 * @property errorCode the `AppError` code of a failed, denied or unsent request.
 */
public data class AiRequestRecord(
    val id: String,
    val at: Instant,
    val purpose: AiPurpose,
    val categories: Set<AiSharingCategory>,
    val bytes: Int,
    val outcome: AiRequestOutcome,
    val background: Boolean = false,
    val rawEvents: Boolean = false,
    val userText: Boolean = false,
    val errorCode: String? = null,
)

/** Where a request ended; name-for-name the AI layer's request status. */
public enum class AiRequestOutcome {
    /** Handed to the provider; no answer yet. */
    IN_FLIGHT,

    /** Sent and answered. */
    SENT,

    /** Refused by a consent check; nothing was sent. */
    DENIED,

    /** The provider refused before sending (not signed in, usage limit); nothing was sent. */
    NOT_SENT,

    /** Cancelled because the consent changed while it ran; any answer was discarded. */
    CANCELLED,

    /** Sent, then failed (network, timeout). */
    FAILED,
}
