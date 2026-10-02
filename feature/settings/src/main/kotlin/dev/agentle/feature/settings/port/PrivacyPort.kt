package dev.agentle.feature.settings.port

import dev.agentle.ai.api.AiProviderState
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.DataCategory
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/** A summary of AI sharing for the privacy screen; the AI Data Sharing screen has the details and the switches. */
public data class AiSharingSummary(
    /** The ChatGPT connection. The privacy screen shows only its kind, never the account label. */
    val provider: AiProviderState,
    /** Categories with a current consent grant (empty by default: consent is an allow-list). */
    val allowedCategories: Set<DataCategory>,
    /** When the last AI request was sent; null if none. */
    val lastRequestAt: Instant?,
)

/** The protections in force, as the privacy screen lists them (R04 s3.10). */
public data class PrivacyState(
    /** The database is encrypted with SQLCipher (R04 s3.2); null when it could not be checked. */
    val databaseEncrypted: Boolean?,
    val aiSharing: AiSharingSummary?,
    /** Mirrors the notification setting: posted text is generic while false. */
    val detailedNotifications: Boolean,
    /** Mirrors the notification setting: notifications stay on the phone while false. */
    val showOnWearables: Boolean,
    /** "Protect all screens": `FLAG_SECURE` on every screen, not only on screens with personal details. */
    val protectAllScreens: Boolean,
    /** The app preview in Recents is hidden (`setRecentsScreenshotEnabled(false)`, API 33+). */
    val recentsPreviewHidden: Boolean,
)

/** Privacy (`AppRoute.Privacy`): what is stored where, what leaves the phone, AI sharing and protections in force. */
public interface PrivacyPort {
    /**
     * The current protections; emits again when one changes. With ChatGPT disconnected, [AiSharingSummary.provider]
     * is `Disconnected` and [AiSharingSummary.allowedCategories] may still list stored grants. A part that cannot be
     * read is null. Emits `Outcome.Failure` (`DatabaseError`, `UnsupportedFeature`) when nothing can be read.
     */
    public val state: Flow<Outcome<PrivacyState>>

    /** Turns "protect all screens" on or off; applied to the window at once. Failures: `DatabaseError`. Main-safe. */
    public suspend fun setProtectAllScreens(enabled: Boolean): Outcome<Unit>
}
