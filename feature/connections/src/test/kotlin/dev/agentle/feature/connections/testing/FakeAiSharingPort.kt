package dev.agentle.feature.connections.testing

import dev.agentle.ai.api.AiPurpose
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.testing.TestAgentleClock
import dev.agentle.feature.connections.port.AiCategoryRestriction
import dev.agentle.feature.connections.port.AiConsentChange
import dev.agentle.feature.connections.port.AiConsentState
import dev.agentle.feature.connections.port.AiDataSharingPort
import dev.agentle.feature.connections.port.AiRequestPreview
import dev.agentle.feature.connections.port.AiRequestRecord
import dev.agentle.feature.connections.port.AiSharingCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

/**
 * A scripted [AiDataSharingPort] that behaves like the consent store: a grant needs a connected account and a category
 * that can be sent, a revoke always works and reports [cancelOnRevoke] as the cancelled requests.
 */
internal class FakeAiSharingPort(
    initial: AiConsentState = ConnectionsFixtures.consent(),
    history: List<AiRequestRecord> = emptyList(),
    private val clock: TestAgentleClock = ConnectionsFixtures.clock(),
) : AiDataSharingPort {
    val consentState = MutableStateFlow(initial)
    val historyState = MutableStateFlow(history)

    var failState: Boolean = false

    override val consent: Flow<AiConsentState> = flow {
        check(!failState) { "scripted state failure" }
        emitAll(consentState)
    }

    override val history: Flow<List<AiRequestRecord>> = historyState

    var gate: CompletableDeferred<Unit>? = null
    var setFailure: AppError? = null
    var cancelOnRevoke: List<AiPurpose> = emptyList()
    var previewOutcome: (AiPurpose) -> Outcome<AiRequestPreview> = { Outcome.Success(ConnectionsFixtures.preview(it)) }

    val setCalls = mutableListOf<Pair<AiSharingCategory, Boolean>>()
    val previewCalls = mutableListOf<AiPurpose>()

    override suspend fun setAllowed(category: AiSharingCategory, allowed: Boolean): Outcome<AiConsentChange> {
        gate?.await()
        setCalls += category to allowed
        setFailure?.let { return Outcome.Failure(it) }
        val state = consentState.value
        val restriction = state.categories.first { it.category == category }.restriction
        if (allowed && restriction == AiCategoryRestriction.NEVER_SENT) {
            return Outcome.Failure(AppError.ConsentViolation(setOf(category.name)))
        }
        if (allowed && !state.accountConnected) return Outcome.Failure(AppError.AuthenticationRequired("chatgpt"))
        val now = clock.now()
        consentState.update { current ->
            current.copy(
                categories = current.categories.map {
                    if (it.category == category) {
                        it.copy(allowed = allowed, grantedAt = now.takeIf { allowed }, outdatedGrant = false)
                    } else {
                        it
                    }
                },
                lastChangedAt = now,
            )
        }
        return Outcome.Success(
            AiConsentChange(category, allowed, state.consentVersion, if (allowed) emptyList() else cancelOnRevoke),
        )
    }

    override suspend fun preview(purpose: AiPurpose): Outcome<AiRequestPreview> {
        gate?.await()
        previewCalls += purpose
        return previewOutcome(purpose)
    }
}
