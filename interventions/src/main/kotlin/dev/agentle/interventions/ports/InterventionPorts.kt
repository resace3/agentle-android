package dev.agentle.interventions.ports

import android.content.Context
import android.content.Intent
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.MediaArtifact
import dev.agentle.interventions.card.InterventionCard
import dev.agentle.jitai.engine.delivery.PendingCard
import dev.agentle.jitai.dsl.model.SnoozeOption
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/** What the user did with a delivered intervention (notification action, tap, swipe, or in-app card). */
enum class ResponseKind {
    /** Tapped the notification, a Listen or Watch action, or the in-app card. */
    OPENED,

    /** Picked one of the rule's snooze options ([InterventionResponse.snooze]). */
    SNOOZED,

    /** "Not now": a snooze until the active window ends ([InterventionResponse.snooze] is `UNTIL_WINDOW_END`). */
    NOT_NOW,

    /** "Stop this JITAI": disable the JITAI that made the decision (reversible from the JITAI list). */
    STOP_JITAI,

    /** Swiped the notification away, or dismissed the in-app card. */
    DISMISSED,
}

/** Where the response came from. */
enum class ResponseSurface { NOTIFICATION, IN_APP_CARD }

/**
 * One response. [nonce] is the per-delivery nonce the action, the content intent or the card presented; the recorder
 * must reject a mismatch (red team oauth-security-12). [snooze] is set for SNOOZED and NOT_NOW.
 */
data class InterventionResponse(
    val decisionKey: String,
    val nonce: String,
    val kind: ResponseKind,
    val surface: ResponseSurface,
    val snooze: SnoozeOption? = null,
)

/** The recorder's verdict; mirrors the engine's `ResponseStatus`. */
enum class ResponseVerdict {
    RECORDED,
    ALREADY_RESPONDED,

    /** No decision with that key (retention or "delete intervention history" removed it). */
    NOT_FOUND,

    /** The nonce did not match: nothing was recorded and nothing changes on the device. */
    REJECTED,

    /** A snooze option the rule's snooze policy does not offer (any more): nothing was recorded. */
    INVALID_OPTION,
    ;

    /** RECORDED or ALREADY_RESPONDED: the nonce matched, so local side effects (cancel, remove the card) may run. */
    val accepted: Boolean get() = this == RECORDED || this == ALREADY_RESPONDED
}

/**
 * Records responses. [dev.agentle.interventions.wiring.EngineResponseRecorder] implements it over
 * `JitaiEngine.recordResponse`; the wiring team binds that one once the engine is in the graph.
 */
fun interface InterventionResponseRecorder {
    suspend fun record(response: InterventionResponse): Outcome<ResponseVerdict>
}

/** What the engine said when an in-app card was shown. */
enum class CardDisplay {
    /** CARD_PENDING became DELIVERED (or already was): the card counts as delivered and stays until answered. */
    SHOWN,

    /** The decision is no longer an in-app card (expired, cancelled or deleted): the card goes. */
    GONE,
}

/**
 * The engine's side of in-app cards (jitai-correctness-13): the CARD_PENDING cards with their text and expiry, and "the
 * card was displayed" (CARD_PENDING -> DELIVERED). [dev.agentle.interventions.wiring.EngineCardDecisions] implements it
 * over `JitaiEngine.pendingCards` and `JitaiEngine.markCardDisplayed`.
 */
interface CardDecisions {
    suspend fun pendingCards(): Outcome<List<PendingCard>>

    suspend fun markDisplayed(decisionKey: String): Outcome<CardDisplay>
}

/**
 * Disables a JITAI ("Stop this JITAI"). The wiring team implements it with the same disable path as the JITAI list's
 * Disable button: the repository sets status DISABLED and deletes the JITAI's timer rows in the same transaction.
 * [dev.agentle.interventions.wiring.EngineResponseRecorder] then calls `JitaiEngine.onDefinitionChanged(jitaiId)` and
 * records NOT_HELPFUL, in that order.
 */
fun interface JitaiStopper {
    suspend fun stop(jitaiId: String): Outcome<Unit>
}

/**
 * Intervention settings (DataStore, owned by the settings team). Defaults are the privacy defaults: no in-app card
 * fallback. Network voices are never used (privacy-ai-19), so there is no setting for them.
 * What a post may show (detailed text, wearables) is not here: it arrives on every
 * `RenderedIntervention` (`postedTitle`, `postedBody`, `detailed`, `localOnly`), from the engine's
 * `EngineSettings.notificationPrivacy`, so there is one source of truth.
 *
 * @property inAppCards keep a blocked delivery as an in-app card. Bind it to the same DataStore value as
 *   `EngineSettings.inAppCards`: the engine moves the decision to CARD_PENDING only when that is on.
 * @property voiceLocaleTag BCP 47 tag of the voice language; null uses the device locale.
 * @property speakOnlyOnPrivateOutput play voice only on headphones, Bluetooth audio or hearing aids (R09 §2.7).
 * @property mediaExpiryDays lifetime of generated media (R09 §9.4).
 */
data class InterventionSettings(
    val inAppCards: Boolean = false,
    val voiceLocaleTag: String? = null,
    val speakOnlyOnPrivateOutput: Boolean = false,
    val mediaExpiryDays: Int = DEFAULT_MEDIA_EXPIRY_DAYS,
) {
    companion object {
        const val DEFAULT_MEDIA_EXPIRY_DAYS: Int = 14
    }
}

/** Reads [InterventionSettings]; a failure falls back to the defaults. */
fun interface InterventionSettingsSource {
    suspend fun settings(): Outcome<InterventionSettings>
}

/**
 * TTS engines the user allowed by name (privacy-ai-19): the engine receives the spoken text, so an engine other than
 * Speech Services by Google (`com.google.android.tts`, allowed by default) is used only after the user allowed that
 * package in settings. Empty by default; a failure counts as empty.
 */
fun interface TtsEngineConsent {
    suspend fun allowedEngines(): Outcome<Set<String>>
}

/**
 * The activity that shows `AppRoute.InterventionDetail` (the app's MainActivity). The intent must be explicit (a
 * component set); this module adds the action, the data URI, the nonce extra and the flags, and refuses an implicit one.
 */
fun interface InterventionActivityIntents {
    fun launchIntent(context: Context): Intent
}

/** A `media_artifact` row plus its last access (for LRU eviction). */
data class MediaRecord(val artifact: MediaArtifact, val lastAccessedAt: Instant? = null)

/** Metadata of generated media (the data team's `media_artifact` table, `MediaDao`). Files are this module's job. */
interface MediaMetadataStore {
    suspend fun insert(record: MediaRecord): Outcome<Unit>

    suspend fun get(id: String): Outcome<MediaRecord?>

    suspend fun all(): Outcome<List<MediaRecord>>

    suspend fun touch(id: String, at: Instant): Outcome<Unit>

    suspend fun delete(ids: Collection<String>): Outcome<Int>
}

/**
 * In-app cards: the media kept by `DeliveryPort.keepAsCard` and the copy of a shown card (text and expiry come from
 * [CardDecisions]). Cards hold the in-app title and body, so the implementation keeps them with the other intervention
 * content (the encrypted database), never in plain DataStore.
 */
interface InterventionCardStore {
    /** Every stored card, oldest first, including expired ones and ones the engine no longer lists (the reader filters). */
    fun observe(): Flow<List<InterventionCard>>

    /** Inserts or replaces the card with the same decision key. */
    suspend fun put(card: InterventionCard): Outcome<Unit>

    suspend fun get(decisionKey: String): Outcome<InterventionCard?>

    suspend fun all(): Outcome<List<InterventionCard>>

    /** True when a card was removed. */
    suspend fun remove(decisionKey: String): Outcome<Boolean>
}
