package dev.agentle.ai.context

import dev.agentle.ai.api.AiPurpose
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.SourceFamily

/**
 * The build-time sharing decision (docs/ARCHITECTURE.md section 9): which categories and source families the
 * [ContextSelectionEngine] may put into a request. It denies by default.
 *
 * The engine trusts this object, and nothing else does. The [EgressGuard] decides again with its own instance. The
 * send-time check that runs immediately before a provider sends reads the consent store once more, with code of its
 * own. So a wrong default here cannot pass both checks.
 */
public interface AiSharingPolicy {
    /** Categories [consent] allows for [purpose] and the account [accountSub]. Nothing is allowed when [accountSub] is null. */
    public fun allowedCategories(consent: ConsentSnapshot, purpose: AiPurpose, accountSub: String?): Set<AiDataCategory>

    /** The standing consent of [purpose] if it is valid for the current version and [accountSub], else null. */
    public fun standingConsent(consent: ConsentSnapshot, purpose: AiPurpose, accountSub: String?): StandingConsent?

    /** Source families whose data may be sent at all. */
    public fun allowedSources(): Set<SourceFamily>
}

/**
 * The v1 policy (privacy-ai-02). A category is allowed only by an explicit grant for the same purpose, the current
 * consent version and the active account. Third-party text is never allowed, whatever was stored.
 */
public class DenyByDefaultSharingPolicy(
    private val currentVersion: Int = AiConsentDisclosure.VERSION,
    private val sources: SourceFamilyPolicy = SourceFamilyPolicy(),
) : AiSharingPolicy {
    override fun allowedCategories(consent: ConsentSnapshot, purpose: AiPurpose, accountSub: String?): Set<AiDataCategory> {
        if (accountSub.isNullOrBlank()) return emptySet()
        return consent.grants
            .filter { it.purpose == purpose && it.consentVersion == currentVersion && it.accountSub == accountSub }
            .map { it.category }
            .filterNotTo(sortedSetOf()) { it.thirdPartyText }
    }

    override fun standingConsent(consent: ConsentSnapshot, purpose: AiPurpose, accountSub: String?): StandingConsent? {
        if (accountSub.isNullOrBlank()) return null
        return consent.standing.lastOrNull { it.purpose == purpose && it.consentVersion == currentVersion && it.accountSub == accountSub }
    }

    override fun allowedSources(): Set<SourceFamily> = SourceFamily.entries.filterTo(sortedSetOf(), sources::allows)
}

/**
 * Which source families may reach an AI provider at all (privacy-ai-04):
 * - [SourceFamily.GH_API]: never in v1;
 * - [SourceFamily.HEALTH_CONNECT]: only in builds that set [healthConnectToAi], because R04 section 3.8 (control 10)
 *   leaves Health Connect data out of AI requests in Play builds;
 * - [SourceFamily.ON_DEVICE]: allowed (still per category and purpose).
 */
public class SourceFamilyPolicy(public val healthConnectToAi: Boolean = false) {
    public fun allows(family: SourceFamily): Boolean = when (family) {
        SourceFamily.GH_API -> false
        SourceFamily.HEALTH_CONNECT -> healthConnectToAi
        SourceFamily.ON_DEVICE -> true
    }
}
