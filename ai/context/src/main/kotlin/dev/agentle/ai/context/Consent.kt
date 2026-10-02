package dev.agentle.ai.context

import dev.agentle.ai.api.AiEnvelopeJson
import dev.agentle.ai.api.AiPurpose
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.SourceFamily
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * One explicit permission (privacy-ai-02): values of [category] may be sent to the AI provider for [purpose], under the
 * disclosure of [consentVersion], by the ChatGPT account [accountSub]. Every category is off until the user grants it.
 * There are no default grants, and `DataCategory.sensitiveByDefault` plays no part in AI sharing.
 */
public data class ConsentGrant(
    val category: AiDataCategory,
    val purpose: AiPurpose,
    val consentVersion: Int,
    val grantedAt: Instant,
    val accountSub: String,
)

/**
 * The previewed template a user accepted for background sends of [purpose] (privacy-ai-01). It lists the exact
 * [fields], [categories] and [sourceFamilies] a background request may hold and how far back it may look
 * ([lookbackDays] local days). It also allows at most one send per [cadence] and at most [dailyBudget] sends per engine
 * day. Background requests hold aggregates only. A background envelope that is not a field-wise subset of this template
 * fails with `AppError.ConsentViolation`.
 */
public data class StandingConsent(
    val purpose: AiPurpose,
    val fields: Set<String>,
    val categories: Set<AiDataCategory>,
    val sourceFamilies: Set<SourceFamily>,
    val lookbackDays: Int,
    val cadence: Duration,
    val dailyBudget: Int,
    val consentVersion: Int,
    val grantedAt: Instant,
    val accountSub: String,
)

/** The stored consent state: grants and standing consents as stored, before any version or account filter. */
public data class ConsentSnapshot(val grants: List<ConsentGrant>, val standing: List<StandingConsent>) {
    public companion object {
        /** Nothing granted: the state of a store that was never written. */
        public val EMPTY: ConsentSnapshot = ConsentSnapshot(emptyList(), emptyList())
    }
}

/** The result of reading the consent store. Anything but [Readable] denies every category. */
public sealed interface ConsentState {
    public data class Readable(val snapshot: ConsentSnapshot) : ConsentState

    /** The store failed, or it holds something this version cannot trust. [code] is a `CODE_` constant of [AiConsentRepository]. */
    public data class Unreadable(val code: String) : ConsentState
}

/**
 * Persistence port for the consent document (implemented by the data layer, for example as one encrypted DataStore
 * entry). [AiConsentRepository] owns the format and every rule. The store only keeps the text.
 */
public interface AiConsentStore {
    /** The stored document, or null if none was ever saved. May throw on I/O failure or corruption: readers then deny. */
    public suspend fun load(): String?

    /** Replaces the document atomically, then emits on [changes]. */
    public suspend fun save(document: String)

    /** Hot and without replay: emits once after every change, so requests in flight can be cancelled. */
    public val changes: Flow<Unit>
}

/**
 * The ChatGPT account requests are sent with (wired to the Sign in with ChatGPT token store). Grants name the account
 * they were given for, so signing in with another account needs fresh grants. Null means nobody is signed in.
 */
public fun interface AiAccountSource {
    public suspend fun activeAccountSub(): String?
}

/**
 * The text a user agrees to before the first grant (privacy-ai-02). It names OpenAI and says that requests are linked to
 * the user's ChatGPT account. Any change to [TEXT] needs a new [VERSION]. Grants of older versions are then ignored, so
 * the user has to grant again. A test pins [fingerprint] to enforce this.
 */
public object AiConsentDisclosure {
    public const val VERSION: Int = 1

    public const val PROVIDER: String = "OpenAI"

    public const val TEXT: String =
        "Agentle can send data you choose to OpenAI, the company behind ChatGPT, to answer your questions and to write " +
            "insights and reminders. Agentle makes these requests with your ChatGPT sign-in, so they are linked to your " +
            "ChatGPT account, and OpenAI's terms and privacy policy apply to them. Agentle sends only the categories you " +
            "turn on, only for the features you allow, and keeps a log on this phone of every request it sends. " +
            "Notification text, calendar titles and the names of contacts, Wi-Fi networks and Bluetooth devices are never " +
            "sent. Requests that run in the background, such as a nightly insight, need a separate permission, send " +
            "summaries only and appear in the log. If you turn a category off while a request is running, Agentle cancels " +
            "it and discards any answer."

    /** SHA-256 of [TEXT]; pinned by a test so the text cannot change without a [VERSION] bump. */
    public fun fingerprint(): String = AiEnvelopeJson.sha256Hex(TEXT)
}
