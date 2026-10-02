package dev.agentle.core.common

import kotlin.coroutines.cancellation.CancellationException

/** Result type for operations that can fail with an [AppError]. */
public sealed interface Outcome<out T> {
    public data class Success<out T>(val value: T) : Outcome<T>

    public data class Failure(val error: AppError) : Outcome<Nothing>

    public companion object {
        public fun <T> success(value: T): Outcome<T> = Success(value)

        public fun failure(error: AppError): Outcome<Nothing> = Failure(error)
    }
}

public inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

public inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}

public fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Success)?.value

public fun <T> Outcome<T>.errorOrNull(): AppError? = (this as? Outcome.Failure)?.error

public fun <T> Outcome<T>.getOrThrow(): T = when (this) {
    is Outcome.Success -> value
    is Outcome.Failure -> throw AppException(error)
}

public inline fun <T> Outcome<T>.onFailure(block: (AppError) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) block(error)
    return this
}

public inline fun <T> Outcome<T>.onSuccess(block: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) block(value)
    return this
}

/**
 * Runs [block], mapping [AppException] to its error and any other non-cancellation throwable with [mapError].
 * Cancellation is always rethrown.
 */
public inline fun <T> outcomeOf(
    mapError: (Throwable) -> AppError = { AppError.Unexpected(it::class.simpleName) },
    block: () -> T,
): Outcome<T> = try {
    Outcome.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: AppException) {
    Outcome.Failure(e.error)
} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
    Outcome.Failure(mapError(e))
}
