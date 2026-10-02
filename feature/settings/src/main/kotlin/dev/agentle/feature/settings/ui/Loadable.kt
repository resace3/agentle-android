package dev.agentle.feature.settings.ui

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** The load state of a screen's main read model. */
internal sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>

    data class Ready<out T>(val value: T) : Loadable<T>

    /** The port reported `AppError.UnsupportedFeature`: this build cannot provide the data (not an error to retry). */
    data object NotAvailable : Loadable<Nothing>

    data class Failed(val error: AppError) : Loadable<Nothing>
}

internal fun <T> Outcome<T>.toLoadable(): Loadable<T> = when (this) {
    is Outcome.Success -> Loadable.Ready(value)
    is Outcome.Failure -> if (error is AppError.UnsupportedFeature) Loadable.NotAvailable else Loadable.Failed(error)
}

internal fun <T> Loadable<T>.valueOrNull(): T? = (this as? Loadable.Ready)?.value

/**
 * A port's read model as [Loadable]: [Loadable.Loading] first, then each outcome. A port that throws instead of
 * emitting a failure shows as [AppError.Unexpected] with the exception's class name only (never its message).
 */
internal fun <T> Flow<Outcome<T>>.asLoadable(): Flow<Loadable<T>> = map { it.toLoadable() }
    .onStart { emit(Loadable.Loading) }
    .catch { emit(Loadable.Failed(AppError.Unexpected(it::class.simpleName))) }

/** Collects [source] again (from [Loadable.Loading]) each time this counter changes: the "retry" of a screen. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> Flow<Int>.reloading(source: () -> Flow<Outcome<T>>): Flow<Loadable<T>> = flatMapLatest { source().asLoadable() }
