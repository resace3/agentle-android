package dev.agentle.jitai.engine.ports

import dev.agentle.core.common.Outcome
import dev.agentle.core.model.DataCategory
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiEventType
import dev.agentle.jitai.engine.content.RenderedIntervention
import dev.agentle.jitai.engine.time.MonotonicStamp
import java.security.SecureRandom
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The JITAI definitions (`jitai_definition`, ANDROID-DATA's `JitaiRepository`).
 *
 * Every change of a definition (save of a new version, enable, disable, delete, a status change, and the two changes the
 * engine itself requests below) deletes that JITAI's timer rows in the **same transaction** as the change; the engine
 * re-plans afterwards (jitai-correctness-05/07). A timer row that survives anyway is caught by the version check of the
 * firing timer.
 */
public interface JitaiRepositoryPort {
    /** The current version of every stored definition that is not ARCHIVED; the engine selects the effective ones. */
    public suspend fun definitions(): Outcome<List<JitaiDefinition>>

    /** G02: the definition's `expiresAt` passed; move its status to EXPIRED (R10 §9.5) and delete its timer rows. */
    public suspend fun markExpired(jitaiId: String, at: Instant): Outcome<Unit>

    /**
     * R10 §9.6: [consecutiveIgnored] reached 5; pause the JITAI (deleting its timer rows) and ask in-app, not by
     * notification, what to do.
     */
    public suspend fun pauseForBackoff(jitaiId: String, consecutiveIgnored: Int, at: Instant): Outcome<Unit>
}

/**
 * Collection profiles (docs/ARCHITECTURE.md §11, §13; R02 §3.2: 60 / 30 / 15 minutes). They set only the period of the
 * BACKSTOP timer row; interval slots follow the rules' own `everyMinutes` (jitai-correctness-09).
 */
public enum class TickProfile(public val tickMinutes: Int) { LOW(60), BALANCED(30), HIGH(15) }

/** Quiet hours (R10 §9.3): same half-open, midnight-crossing semantics as active windows. Default 22:00-07:00, on. */
public data class QuietHours(val start: String = "22:00", val end: String = "07:00", val enabled: Boolean = true)

/**
 * How rendered values look (R10 §3.3): locale-aware integers and `HH:mm` or `h:mm a` per the device setting.
 *
 * @property genericTitle the posted title when detailed notifications are off (jitai-correctness-18); the app passes
 *   its localized string.
 * @property genericBody the posted body when detailed notifications are off.
 */
public data class DisplaySettings(
    val localeTag: String = "en-US",
    val use24HourClock: Boolean = true,
    val genericTitle: String = DEFAULT_GENERIC_TITLE,
    val genericBody: String = DEFAULT_GENERIC_BODY,
) {
    public companion object {
        public const val DEFAULT_GENERIC_TITLE: String = "Agentle"
        public const val DEFAULT_GENERIC_BODY: String = "You have a new check-in. Open Agentle to see it."
    }
}

/**
 * What a posted notification may show (jitai-correctness-18). Both are off by default.
 *
 * @property detailedNotifications the posted text equals the in-app text (placeholders, app labels); otherwise it is the
 *   generic text of [DisplaySettings].
 * @property bridgeToWearables posts may be bridged to a watch; otherwise every post is local-only.
 */
public data class NotificationPrivacy(val detailedNotifications: Boolean = false, val bridgeToWearables: Boolean = false)

/** The AI-sharing consent in force; pooled AI text generated under other terms is not delivered (red team privacy-ai-01). */
public data class AiTextConsent(val version: Int = 0, val allowedCategories: Set<DataCategory> = emptySet())

/**
 * Engine settings from DataStore (R10 §3.5, §9.2). [effective] clamps every value to the hard ceilings, so a corrupted or
 * hostile settings file cannot raise the budgets.
 *
 * @property channelCaps per-channel daily caps; a channel without an entry is capped by [globalMaxPerDay].
 * @property maxEventAgeMinutes per-event-type bound on `now - eventAt`; every type defaults to
 *   [DEFAULT_MAX_EVENT_AGE_MINUTES] (jitai-correctness item 14).
 * @property tickProfile the collection profile; sets the BACKSTOP period only.
 * @property inAppCards when notifications are blocked at the claim, keep the intervention as an in-app card
 *   (CARD_PENDING) instead of SUPPRESSED(NOTIFICATIONS_BLOCKED) (jitai-correctness-13). Off by default.
 */
public data class EngineSettings(
    val globalMaxPerDay: Int = 6,
    val globalMaxPerWeek: Int = 30,
    val minGapMinutes: Int = 30,
    val channelCaps: Map<DeliveryChannel, Int> = DEFAULT_CHANNEL_CAPS,
    val quietHours: QuietHours = QuietHours(),
    val rolloverMinute: Int = 240,
    val pauseUntil: Instant? = null,
    val maxEventAgeMinutes: Map<JitaiEventType, Int> = emptyMap(),
    val tickProfile: TickProfile = TickProfile.BALANCED,
    val display: DisplaySettings = DisplaySettings(),
    val aiConsent: AiTextConsent = AiTextConsent(),
    val notificationPrivacy: NotificationPrivacy = NotificationPrivacy(),
    val inAppCards: Boolean = false,
) {
    /** These settings clamped to the hard ceilings of R10 §9.2. */
    public fun effective(): EngineSettings {
        val day = globalMaxPerDay.coerceIn(0, HARD_MAX_PER_DAY)
        return copy(
            globalMaxPerDay = day,
            globalMaxPerWeek = globalMaxPerWeek.coerceIn(0, HARD_MAX_PER_WEEK),
            minGapMinutes = minGapMinutes.coerceIn(HARD_MIN_GAP_MINUTES, MAX_GAP_MINUTES),
            channelCaps = channelCaps.mapValues { (_, cap) -> cap.coerceIn(0, day) },
            rolloverMinute = rolloverMinute.coerceIn(0, MAX_ROLLOVER_MINUTE),
            maxEventAgeMinutes = maxEventAgeMinutes.mapValues { (_, age) -> age.coerceIn(1, MAX_EVENT_AGE_MINUTES) },
        )
    }

    /** The daily cap of [channel] (G15): its own cap, never above the global daily cap. */
    public fun channelCap(channel: DeliveryChannel): Int = minOf(channelCaps[channel] ?: globalMaxPerDay, globalMaxPerDay)

    /** The age bound of [type] events (red team lifecycle-battery-05). */
    public fun maxEventAge(type: JitaiEventType): Duration = (maxEventAgeMinutes[type] ?: DEFAULT_MAX_EVENT_AGE_MINUTES).minutes

    public companion object {
        public const val HARD_MAX_PER_DAY: Int = 12
        public const val HARD_MAX_PER_WEEK: Int = 60
        public const val HARD_MIN_GAP_MINUTES: Int = 15
        public const val MAX_GAP_MINUTES: Int = 240
        public const val MAX_ROLLOVER_MINUTE: Int = 360
        public const val DEFAULT_MAX_EVENT_AGE_MINUTES: Int = 10
        public const val MAX_EVENT_AGE_MINUTES: Int = 120

        /** VOICE 2, VIDEO 1, IMAGE 3 per engine day; NOTIFICATION follows the global cap (R10 §9.2). */
        public val DEFAULT_CHANNEL_CAPS: Map<DeliveryChannel, Int> =
            mapOf(DeliveryChannel.VOICE to 2, DeliveryChannel.VIDEO to 1, DeliveryChannel.IMAGE to 3)
    }
}

/** `NotificationManager.getCurrentInterruptionFilter()` (R10 §5.4 B). UNKNOWN counts as Do Not Disturb on (G07). */
public enum class InterruptionFilter {
    ALL,
    PRIORITY,
    ALARMS,
    NONE,
    UNKNOWN,
    ;

    /** G07 blocks unless the filter is a definite ALL (R10 §6.5, §9.1). */
    public val blocksDelivery: Boolean get() = this != ALL
}

/**
 * Live notification state besides the delivery prerequisite (G07, event availability).
 *
 * @property notificationListenerConnected the listener's connection state; the best-effort event types follow it
 *   (red team lifecycle-battery-07).
 */
public data class NotificationSystemState(
    val interruptionFilter: InterruptionFilter = InterruptionFilter.ALL,
    val notificationListenerConnected: Boolean = true,
)

/** Settings (DataStore) and live notification state. */
public interface SettingsPort {
    public suspend fun engineSettings(): Outcome<EngineSettings>

    public suspend fun notificationState(): Outcome<NotificationSystemState>

    /** 16 random bytes created at first launch, excluded from backup; seeds the micro-randomization draw (R10 §15.3). */
    public suspend fun installSalt(): Outcome<ByteArray>
}

/** Where a trigger event came from. Only live events are dispatched when old (red team lifecycle-battery-05). */
public enum class EventOrigin { LIVE, POLLING, SYNC_REPLAY, BACKFILL }

/** The kind of change that assigned the event's `change_seq` (red team database-sync-04). */
public enum class ChangeTransition { INSERTED, PROCESSED, UPDATED, TOMBSTONED }

/**
 * One trigger-relevant change from the event table, ordered by [changeSeq] (assigned on every insert, semantic update
 * and tombstone; never the insert-only rowid).
 *
 * @property eventAt when the event happened (wall clock); every trigger event carries it.
 * @property activityState for ACTIVITY_STATE_CHANGED, the activity entered (`WALKING`, ...).
 * @property stamp optional monotonic stamp of the event, preferred over [eventAt] for the age check.
 */
public data class TriggerEvent(
    val changeSeq: Long,
    val type: JitaiEventType,
    val eventAt: Instant,
    val origin: EventOrigin = EventOrigin.LIVE,
    val transition: ChangeTransition = ChangeTransition.INSERTED,
    val activityState: String? = null,
    val stamp: MonotonicStamp? = null,
) {
    /** Only a new row, or `processed` false -> true of a sleep session, is a semantic transition that dispatches. */
    public val isSemantic: Boolean
        get() = transition == ChangeTransition.INSERTED ||
            (transition == ChangeTransition.PROCESSED && type == JitaiEventType.SLEEP_SESSION_AVAILABLE)
}

/** Trigger-relevant changes after a watermark (ANDROID-DATA, over the event table's `change_seq`). */
public interface TriggerEventFeed {
    public suspend fun eventsAfter(changeSeq: Long, limit: Int): Outcome<List<TriggerEvent>>
}

/**
 * Brings the daily features of dirty days up to date right before a pass takes its snapshot, so an event pass sees the
 * data whose ingest triggered it (a sleep session, a step sync; jitai-correctness-08). The snapshot itself is still read
 * only through [dev.agentle.analytics.features.FeatureResolver]. The analytics team wires its dirty-day recompute here; a
 * resolver that recomputes dirty days inside `resolve` needs nothing, so the port is optional. A failure is logged and
 * the pass goes on: the resolver then reports the affected values as stale or missing, never as current.
 */
public fun interface DailyFeatureRefresher {
    public suspend fun refreshDirtyDays(at: Instant): Outcome<Unit>
}

/**
 * One pooled `ai_text` item (R10 §3.3), generated ahead of time. Checked at delivery (red team privacy-ai-01,
 * jitai-correctness-17).
 *
 * @property contentHash `RuleCodec.contentHash` of the definition it was generated for; the pool is keyed by it, so an
 *   edited rule never delivers text written for its old version.
 * @property consentVersion the AI-sharing consent version the request was made under.
 * @property categories the data categories its request used.
 * @property snapshotHash [dev.agentle.jitai.engine.content.SnapshotHashes.contextHash] of the context it was written for.
 */
public data class PooledText(
    val id: String,
    val jitaiId: String,
    val contentHash: String,
    val title: String,
    val body: String,
    val createdAt: Instant,
    val consentVersion: Int,
    val categories: Set<DataCategory>,
    val snapshotHash: String?,
)

/**
 * The pool of generated texts, keyed by the rule's content hash (AI-CONTEXT fills it; no network call sits on the
 * delivery path). Items are never older than 24 hours when delivered and are purged when their rule is edited.
 */
public interface AiTextPoolPort {
    /** The items generated for the rule content [contentHash]. */
    public suspend fun pooled(contentHash: String): Outcome<List<PooledText>>

    public suspend fun get(itemId: String): Outcome<PooledText?>

    public suspend fun markUsed(itemId: String, decisionKey: String): Outcome<Unit>

    /** Deletes the items of [jitaiId] whose content hash is not [keepContentHash] (all of them when it is null). */
    public suspend fun purge(jitaiId: String, keepContentHash: String?): Outcome<Int>

    /** Deletes the items created before [createdBefore] (the 24-hour limit). */
    public suspend fun purgeExpired(createdBefore: Instant): Outcome<Int>
}

/** Slow media prepared before the delivery lease starts (red team lifecycle-battery-18). */
public data class PreparedDelivery(val intervention: RenderedIntervention, val mediaRef: String? = null)

/** Result of [DeliveryPort.prepare]. */
public sealed interface PrepareResult {
    public data class Ready(val prepared: PreparedDelivery) : PrepareResult

    /** The channel's media could not be produced (TTS engine missing, asset missing); the engine downgrades. */
    public data class Unavailable(val code: String) : PrepareResult
}

/** Result of [DeliveryPort.post]. */
public sealed interface PostResult {
    public data object Posted : PostResult

    /** The delivery prerequisite failed at the post itself (it changed after the claim). */
    public data object Blocked : PostResult

    /** A permanent error with a content-free [code]. */
    public data class Failed(val code: String) : PostResult
}

/**
 * The delivery prerequisite of one category (jitai-correctness-13): notifications enabled (POST_NOTIFICATIONS granted
 * on API 33+ and `areNotificationsEnabled()`), the category channel's importance is not NONE, and notifications are not
 * paused (`areNotificationsPaused()`). Checked at the decision (G05) and again at the claim.
 */
public data class DeliveryPrerequisite(
    val notificationsEnabled: Boolean,
    val channelImportanceNone: Boolean,
    val notificationsPaused: Boolean,
) {
    public val met: Boolean get() = notificationsEnabled && !channelImportanceNone && !notificationsPaused

    public companion object {
        public val MET: DeliveryPrerequisite = DeliveryPrerequisite(true, channelImportanceNone = false, notificationsPaused = false)

        /** What an unreadable state counts as: not met (fail closed). */
        public val UNKNOWN: DeliveryPrerequisite = DeliveryPrerequisite(false, channelImportanceNone = true, notificationsPaused = true)
    }
}

/**
 * Delivery on the device (the interventions team's `NotificationDeliverer`): never depended on directly, only through
 * this port, so the engine never reaches `:interventions`.
 */
public interface DeliveryPort {
    /** The live delivery prerequisite for [category] (jitai-correctness-13). */
    public suspend fun prerequisite(category: JitaiCategory): Outcome<DeliveryPrerequisite>

    /** Prepares slow media (TTS synthesis for VOICE) while the row is still DECIDED, never inside the lease. */
    public suspend fun prepare(intervention: RenderedIntervention): PrepareResult

    /**
     * Posts with `tag = decisionKey`; posting an active tag again updates it and alerts once (R10 §8.5). Posts
     * [RenderedIntervention.postedTitle] / [RenderedIntervention.postedBody] and sets `setLocalOnly` from
     * [RenderedIntervention.localOnly] (jitai-correctness-18).
     */
    public suspend fun post(prepared: PreparedDelivery): PostResult

    /** Whether a notification with [tag] is active (`getActiveNotifications()`), for crash recovery. */
    public suspend fun isActive(tag: String): Outcome<Boolean>

    /** Releases prepared media that will not be posted (the claim was lost or refused, or the post was blocked). */
    public suspend fun discard(prepared: PreparedDelivery)

    /**
     * The delivery became an in-app card (CARD_PENDING, jitai-correctness-13), at the claim or after a post that returned
     * [PostResult.Blocked]: keep [prepared]'s media for the card instead of releasing it. Called exactly when the row
     * moved to CARD_PENDING; [discard] stays the call for every delivery that will not be shown. The card's content comes
     * back after a restart through `JitaiEngine.pendingCards()`. The default releases the media like [discard].
     */
    public suspend fun keepAsCard(prepared: PreparedDelivery): Outcome<Unit> {
        discard(prepared)
        return Outcome.success(Unit)
    }
}

/** Random per-delivery nonces (red team oauth-security-12). Tests inject a seeded source. */
public fun interface NonceSource {
    public fun nextNonce(): String
}

/** 128-bit nonces from [SecureRandom], hex encoded. */
public class SecureNonceSource(private val random: SecureRandom = SecureRandom()) : NonceSource {
    override fun nextNonce(): String {
        val bytes = ByteArray(NONCE_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private companion object {
        const val NONCE_BYTES = 16
    }
}
