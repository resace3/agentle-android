package dev.agentle.feature.insights.jitai

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.feature.insights.common.LoadError
import dev.agentle.feature.insights.common.UserMessage
import dev.agentle.feature.insights.port.JitaiSummary
import dev.agentle.feature.insights.port.PauseReason
import dev.agentle.jitai.dsl.model.CreatedBy
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiKind
import dev.agentle.jitai.dsl.model.JitaiLifecycle
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.render.RenderOptions
import dev.agentle.jitai.dsl.render.RuleRenderer
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** One rule as the list and the detail screen show it. [rendering] is the DSL renderer's plain-language sentence. */
internal data class RuleCard(
    val id: String,
    val name: String,
    val status: JitaiStatus,
    val kind: JitaiKind,
    val rendering: String,
    val channel: DeliveryChannel,
    val deliveredToday: Int,
    val deliveredThisWeek: Int,
    val maxPerDay: Int?,
    val maxPerWeek: Int?,
    val pausedReason: PauseReason?,
    val needsAccess: Boolean,
    val createdBy: CreatedBy,
    val actions: ImmutableSet<RuleAction>,
)

/** What the user can do with a rule in its current state (R10 §3.4 transitions). */
internal enum class RuleAction { PAUSE, RESUME, EDIT, DISABLE }

/** Statuses shown on the Active tab and on the Paused tab (paused, drafts and ended trials). */
internal val ACTIVE_STATUSES: Set<JitaiStatus> = setOf(JitaiStatus.ACTIVE)
internal val PAUSED_STATUSES: Set<JitaiStatus> = setOf(JitaiStatus.PAUSED, JitaiStatus.DRAFT, JitaiStatus.EXPIRED)

internal fun JitaiSummary.toCard(options: RenderOptions): RuleCard {
    val definition = definition
    return RuleCard(
        id = definition.id,
        name = definition.name,
        status = definition.status,
        kind = definition.kind,
        rendering = RuleRenderer.render(definition, options),
        channel = definition.delivery.channel,
        deliveredToday = deliveredToday,
        deliveredThisWeek = deliveredThisWeek,
        maxPerDay = definition.maxPerDay,
        maxPerWeek = definition.maxPerWeek,
        pausedReason = pausedReason.takeIf { definition.status == JitaiStatus.PAUSED },
        needsAccess = needsAccess,
        createdBy = definition.createdBy,
        actions = actionsFor(definition.status, pausedReason),
    )
}

internal fun actionsFor(status: JitaiStatus, pausedReason: PauseReason?): ImmutableSet<RuleAction> = when (status) {
    JitaiStatus.ACTIVE -> setOf(RuleAction.PAUSE, RuleAction.EDIT, RuleAction.DISABLE)
    // A rule paused because it failed the checks of this app version must be edited before it can run again.
    JitaiStatus.PAUSED -> if (pausedReason == PauseReason.FAILED_CHECKS) {
        setOf(RuleAction.EDIT, RuleAction.DISABLE)
    } else {
        setOf(RuleAction.RESUME, RuleAction.EDIT, RuleAction.DISABLE)
    }
    JitaiStatus.DRAFT, JitaiStatus.EXPIRED -> setOf(RuleAction.EDIT, RuleAction.DISABLE)
    JitaiStatus.PROPOSED, JitaiStatus.DECLINED, JitaiStatus.ARCHIVED -> emptySet()
}.toImmutableSet()

/** A loadable section of a screen. */
internal sealed interface Load<out T> {
    data object Loading : Load<Nothing>

    data class Error(val error: LoadError) : Load<Nothing>

    data class Loaded<T>(val value: T) : Load<T>
}

internal inline fun <T, R> Load<T>.map(transform: (T) -> R): Load<R> = when (this) {
    is Load.Loaded -> Load.Loaded(transform(value))
    is Load.Error -> this
    Load.Loading -> Load.Loading
}

internal fun <T> Flow<T>.asLoad(): Flow<Load<T>> = map<T, Load<T>> { Load.Loaded(it) }
    .onStart { emit(Load.Loading) }
    .catch { emit(Load.Error(LoadError.of(it))) }

/** The snackbar message of a lifecycle action's result. */
internal fun Outcome<Unit>.message(success: UserMessage, resume: Boolean = false): UserMessage = when (this) {
    is Outcome.Success -> success
    is Outcome.Failure -> {
        val error = error
        val blocked = resume && error is AppError.ValidationError && JitaiLifecycle.ILLEGAL_TRANSITION !in error.codes
        if (blocked) UserMessage.RESUME_BLOCKED else UserMessage.ACTION_FAILED
    }
}
