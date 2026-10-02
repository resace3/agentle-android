package dev.agentle.feature.connections.aisharing

import dev.agentle.ai.api.AiPurpose
import dev.agentle.core.common.AppError
import dev.agentle.feature.connections.port.AiCategoryConsent
import dev.agentle.feature.connections.port.AiCategoryRestriction
import dev.agentle.feature.connections.port.AiConsentState
import dev.agentle.feature.connections.port.AiRequestPreview
import dev.agentle.feature.connections.port.AiRequestRecord
import dev.agentle.feature.connections.port.AiSharingCategory
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

internal enum class AiSharingPhase { LOADING, LOAD_FAILED, NOT_AVAILABLE, READY }

/** One category row: the consent as stored, or the value the user just chose while it is being written. */
internal data class AiCategoryRow(
    val category: AiSharingCategory,
    val allowed: Boolean,
    val pending: Boolean,
    val grantedAt: Instant?,
    val outdatedGrant: Boolean,
    val restriction: AiCategoryRestriction,
) {
    /** The switch can be turned on: the category can be sent in this version and a ChatGPT account is connected. */
    fun canTurnOn(accountConnected: Boolean): Boolean = !pending && accountConnected && restriction != AiCategoryRestriction.NEVER_SENT
}

/** The request preview: hidden, being built, shown (personal content: never logged) or failed. */
internal sealed interface AiPreviewUi {
    data object Hidden : AiPreviewUi

    data class Building(val purpose: AiPurpose) : AiPreviewUi

    data class Shown(val preview: AiRequestPreview) : AiPreviewUi

    data class Failed(val purpose: AiPurpose, val error: AppError) : AiPreviewUi
}

internal sealed interface AiSharingNotice {
    data class TurnedOn(val category: AiSharingCategory) : AiSharingNotice

    /** Turned off; [cancelled] are the purposes of the requests in flight that were cancelled. */
    data class TurnedOff(val category: AiSharingCategory, val cancelled: ImmutableList<AiPurpose>) : AiSharingNotice

    data class ChangeFailed(val category: AiSharingCategory, val turningOn: Boolean, val error: AppError) : AiSharingNotice
}

internal sealed interface AiSharingAction {
    /** The user flipped a switch; turning on first asks for confirmation. */
    data class Toggle(val category: AiSharingCategory, val allowed: Boolean) : AiSharingAction

    data object ConfirmTurnOn : AiSharingAction

    data object DismissConfirm : AiSharingAction

    data class SelectPurpose(val purpose: AiPurpose) : AiSharingAction

    data object BuildPreview : AiSharingAction

    data object ClosePreview : AiSharingAction

    data object DismissNotice : AiSharingAction

    data object Retry : AiSharingAction
}

/** The AI Data Sharing screen state. The preview's content is personal and left out of [toString]. */
internal data class AiSharingUiState(
    val phase: AiSharingPhase = AiSharingPhase.LOADING,
    val consentVersion: Int = 0,
    val disclosure: String = "",
    val accountConnected: Boolean = false,
    val lastChangedAt: Instant? = null,
    val readFailed: Boolean = false,
    val categories: ImmutableList<AiCategoryRow> = persistentListOf(),
    val history: ImmutableList<AiRequestRecord> = persistentListOf(),
    val historyUnavailable: Boolean = false,
    val confirm: AiSharingCategory? = null,
    val purpose: AiPurpose = AiPurpose.entries.first(),
    val preview: AiPreviewUi = AiPreviewUi.Hidden,
    val notice: AiSharingNotice? = null,
    val zone: TimeZone = TimeZone.UTC,
) {
    val allowedCount: Int get() = categories.count { it.allowed }

    override fun toString(): String =
        "AiSharingUiState(phase=$phase, version=$consentVersion, account=$accountConnected, readFailed=$readFailed, " +
            "allowed=$allowedCount/${categories.size}, history=${history.size}, historyUnavailable=$historyUnavailable, " +
            "confirm=$confirm, purpose=$purpose, preview=${preview::class.simpleName}, notice=$notice)"
}

internal sealed interface AiSharingRemote {
    data object Loading : AiSharingRemote

    data object Failed : AiSharingRemote

    data class Ready(val consent: AiConsentState, val history: List<AiRequestRecord>) : AiSharingRemote
}

/** What only the ViewModel knows: switches being written (category to the chosen value), dialog, preview, notice. */
internal data class AiSharingLocal(
    val pending: ImmutableMap<AiSharingCategory, Boolean> = persistentMapOf(),
    val confirm: AiSharingCategory? = null,
    val purpose: AiPurpose = AiPurpose.entries.first(),
    val preview: AiPreviewUi = AiPreviewUi.Hidden,
    val notice: AiSharingNotice? = null,
)

internal fun reduceAiSharing(remote: AiSharingRemote, local: AiSharingLocal, zone: TimeZone): AiSharingUiState {
    val base = AiSharingUiState(
        confirm = local.confirm,
        purpose = local.purpose,
        preview = local.preview,
        notice = local.notice,
        zone = zone,
    )
    val ready = remote as? AiSharingRemote.Ready
        ?: return base.copy(
            phase = if (remote is AiSharingRemote.Failed) AiSharingPhase.LOAD_FAILED else AiSharingPhase.LOADING,
        )
    val consent = ready.consent
    if (!consent.available) return base.copy(phase = AiSharingPhase.NOT_AVAILABLE)
    return base.copy(
        phase = AiSharingPhase.READY,
        consentVersion = consent.consentVersion,
        disclosure = consent.disclosure,
        accountConnected = consent.accountConnected,
        lastChangedAt = consent.lastChangedAt,
        readFailed = consent.readFailed,
        categories = consent.categories.map { it.toRow(local.pending[it.category]) }.toImmutableList(),
        history = ready.history.toImmutableList(),
        historyUnavailable = consent.historyUnavailable,
    )
}

private fun AiCategoryConsent.toRow(pendingValue: Boolean?): AiCategoryRow = AiCategoryRow(
    category = category,
    allowed = pendingValue ?: allowed,
    pending = pendingValue != null,
    grantedAt = grantedAt.takeIf { allowed },
    outdatedGrant = outdatedGrant && !allowed,
    restriction = restriction,
)
