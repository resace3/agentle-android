package dev.agentle.feature.connections.chatgpt

import dev.agentle.ai.api.AiCapabilities
import dev.agentle.ai.api.AiCapability
import dev.agentle.ai.api.AiProviderState
import dev.agentle.ai.api.CapabilitySupport
import dev.agentle.core.common.AppError
import dev.agentle.feature.connections.port.AiRequestRecord
import dev.agentle.feature.connections.port.ChatGptConnectResult
import dev.agentle.feature.connections.port.ChatGptConnectionState
import dev.agentle.feature.connections.port.ChatGptDisconnectResult
import dev.agentle.feature.connections.port.PlanUsageAvailability
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** What the ChatGPT screen's main card shows. */
internal enum class ChatGptPhase {
    LOADING,
    LOAD_FAILED,
    NOT_AVAILABLE,
    DISCONNECTED,
    WAITING_FOR_BROWSER,
    CONNECTED,
    NEEDS_REAUTH,
    NOT_ELIGIBLE,
    USAGE_LIMITED,
    PROVIDER_UNAVAILABLE,
}

internal enum class ChatGptBusy { SIGNING_IN, DISCONNECTING }

/** One row of the capability table, as the provider reports it. */
internal data class CapabilityRow(val capability: AiCapability, val support: CapabilitySupport)

/** The disconnect dialog: whether the user also wants this device's registration forgotten. */
internal data class ChatGptDisconnectDialog(val forgetRegistration: Boolean = false)

/** A message about the outcome of something the user did, shown inline until dismissed. */
internal sealed interface ChatGptNotice {
    /** How a sign-in ended (also an interrupted one found after a restart). */
    data class SignIn(val result: ChatGptConnectResult) : ChatGptNotice

    data class Disconnect(val result: ChatGptDisconnectResult, val forgotRegistration: Boolean) : ChatGptNotice

    /** Recording that the user saw the plan notice failed. */
    data class ActionFailed(val error: AppError) : ChatGptNotice
}

internal sealed interface ChatGptAction {
    /** "Continue with ChatGPT" (also "Reconnect" and "Open the sign-in page again" while waiting). */
    data object Connect : ChatGptAction

    /** "Use your ChatGPT plan" after plan usage was declined: asks for consent again. */
    data object UsePlan : ChatGptAction

    /** After an account mismatch: connect the other account instead (it replaces the current one). */
    data object UseDifferentAccount : ChatGptAction

    data object CancelSignIn : ChatGptAction

    data object AcknowledgePlanNotice : ChatGptAction

    data object RequestDisconnect : ChatGptAction

    data class SetForgetRegistration(val forget: Boolean) : ChatGptAction

    data object ConfirmDisconnect : ChatGptAction

    data object DismissDisconnect : ChatGptAction

    data object DismissNotice : ChatGptAction

    data object Retry : ChatGptAction
}

/**
 * The ChatGPT screen state. [accountLabel] is personal and left out of [toString]. [capabilities] null means unknown
 * (never assumed); [planUsage] is exactly what the provider last reported.
 */
internal data class ChatGptUiState(
    val phase: ChatGptPhase = ChatGptPhase.LOADING,
    val accountLabel: String? = null,
    val model: String? = null,
    val capabilities: ImmutableList<CapabilityRow>? = null,
    val planUsage: PlanUsageAvailability = PlanUsageAvailability.Unknown,
    val providerReason: String? = null,
    val usageLimitedUntil: Instant? = null,
    val lastRequest: AiRequestRecord? = null,
    val showPlanNotice: Boolean = false,
    val busy: ChatGptBusy? = null,
    val notice: ChatGptNotice? = null,
    val disconnectDialog: ChatGptDisconnectDialog? = null,
    val zone: TimeZone = TimeZone.UTC,
) {
    /** A ChatGPT account is bound (whatever its health), so "Disconnect" applies. */
    val bound: Boolean get() = phase in BOUND_PHASES

    val canDisconnect: Boolean get() = bound && busy == null

    /** Plan usage was declined at sign-in: the screen offers "Use your ChatGPT plan". */
    val planUsageDeclined: Boolean
        get() = planUsage == PlanUsageAvailability.NotGranted ||
            (phase == ChatGptPhase.NOT_ELIGIBLE && providerReason == PLAN_USAGE_NOT_GRANTED)

    override fun toString(): String =
        "ChatGptUiState(phase=$phase, account=${if (accountLabel == null) "none" else "<redacted>"}, model=$model, " +
            "capabilities=${capabilities?.size}, planUsage=$planUsage, reason=$providerReason, " +
            "until=$usageLimitedUntil, lastRequest=${lastRequest?.id}, planNotice=$showPlanNotice, busy=$busy, " +
            "notice=$notice, dialog=$disconnectDialog)"

    companion object {
        /** The provider's reason code when plan usage was declined at sign-in (docs/research/06 §8.1). */
        const val PLAN_USAGE_NOT_GRANTED: String = "plan_usage_not_granted"

        private val BOUND_PHASES: Set<ChatGptPhase> = setOf(
            ChatGptPhase.CONNECTED,
            ChatGptPhase.NEEDS_REAUTH,
            ChatGptPhase.NOT_ELIGIBLE,
            ChatGptPhase.USAGE_LIMITED,
            ChatGptPhase.PROVIDER_UNAVAILABLE,
        )
    }
}

internal sealed interface ChatGptRemote {
    data object Loading : ChatGptRemote

    data object Failed : ChatGptRemote

    data class Ready(val state: ChatGptConnectionState) : ChatGptRemote
}

/** What only the ViewModel knows. [disconnected]: a disconnect returned and the port may not show it yet. */
internal data class ChatGptLocal(
    val busy: ChatGptBusy? = null,
    val notice: ChatGptNotice? = null,
    val disconnectDialog: ChatGptDisconnectDialog? = null,
    val disconnected: Boolean = false,
    val planNoticeAcknowledged: Boolean = false,
)

internal fun reduceChatGpt(remote: ChatGptRemote, local: ChatGptLocal, zone: TimeZone): ChatGptUiState {
    val base = ChatGptUiState(
        busy = local.busy,
        notice = local.notice,
        disconnectDialog = local.disconnectDialog,
        zone = zone,
    )
    val state = (remote as? ChatGptRemote.Ready)?.state
        ?: return base.copy(phase = if (remote is ChatGptRemote.Failed) ChatGptPhase.LOAD_FAILED else ChatGptPhase.LOADING)
    if (!state.available) return base.copy(phase = ChatGptPhase.NOT_AVAILABLE)
    val provider = if (local.disconnected) AiProviderState.Disconnected else state.provider
    val waiting = local.busy == ChatGptBusy.SIGNING_IN || state.signInInProgress || provider == AiProviderState.Connecting
    val phase = if (waiting) ChatGptPhase.WAITING_FOR_BROWSER else phaseOf(provider)
    val connected = provider as? AiProviderState.Connected
    val bound = !local.disconnected && provider != AiProviderState.Disconnected && provider != AiProviderState.Connecting
    return base.copy(
        phase = phase,
        accountLabel = connected?.accountLabel,
        model = connected?.model,
        capabilities = if (bound) state.capabilities?.rows() else null,
        planUsage = if (bound) state.planUsage else PlanUsageAvailability.Unknown,
        providerReason = (provider as? AiProviderState.NotEligible)?.reason
            ?: (provider as? AiProviderState.Unavailable)?.reason,
        usageLimitedUntil = (provider as? AiProviderState.UsageLimited)?.untilEpochMs
            ?.let(Instant::fromEpochMilliseconds),
        lastRequest = state.lastRequest,
        showPlanNotice = phase == ChatGptPhase.CONNECTED && !state.planNoticeAcknowledged &&
            !local.planNoticeAcknowledged,
    )
}

private fun phaseOf(provider: AiProviderState): ChatGptPhase = when (provider) {
    AiProviderState.Disconnected -> ChatGptPhase.DISCONNECTED
    AiProviderState.Connecting -> ChatGptPhase.WAITING_FOR_BROWSER
    is AiProviderState.Connected -> ChatGptPhase.CONNECTED
    AiProviderState.NeedsReauth -> ChatGptPhase.NEEDS_REAUTH
    is AiProviderState.NotEligible -> ChatGptPhase.NOT_ELIGIBLE
    is AiProviderState.UsageLimited -> ChatGptPhase.USAGE_LIMITED
    is AiProviderState.Unavailable -> ChatGptPhase.PROVIDER_UNAVAILABLE
}

/** Every capability in a stable order, with what the provider reported (absent means not available). */
private fun AiCapabilities.rows(): ImmutableList<CapabilityRow> =
    AiCapability.entries.map { CapabilityRow(it, this[it]) }.toImmutableList()
