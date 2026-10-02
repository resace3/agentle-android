package dev.agentle.analytics.features.realtime

import dev.agentle.analytics.features.MissingReason
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome

/**
 * What an input port answers for one query (R10 §5.1): the data, or the data condition that makes it unavailable.
 *
 * Every port method returns `Outcome<InputAnswer<T>>` and never throws for data conditions:
 * - expected conditions (usage access not granted, `queryEvents` returned null before the first unlock, no canonical
 *   source connected) are [Unavailable] with the [MissingReason] the feature should report;
 * - technical failures (a database error, an exception from a system service) are `Outcome.Failure`; the engine maps
 *   them with [missingReasonFor].
 */
public sealed interface InputAnswer<out T> {
    public data class Available<out T>(val value: T) : InputAnswer<T>

    public data class Unavailable(val reason: MissingReason) : InputAnswer<Nothing>

    public companion object {
        /** A successful answer carrying [value]. */
        public fun <T> available(value: T): Outcome<InputAnswer<T>> = Outcome.success(Available(value))

        /** A successful answer saying the data is unavailable for [reason]. */
        public fun unavailable(reason: MissingReason): Outcome<InputAnswer<Nothing>> = Outcome.success(Unavailable(reason))
    }
}

/**
 * The [MissingReason] a feature reports when a port fails with [error]. Permission errors keep their meaning, a lost
 * account connection is `SOURCE_DISCONNECTED`, and every other failure is `API_UNAVAILABLE` (R10 §5.3: an exception or
 * an "unknown" answer). Only the error class decides; the detail text is never read.
 */
public fun missingReasonFor(error: AppError): MissingReason = when (error) {
    is AppError.PermissionDenied, is AppError.PermissionPermanentlyDenied -> MissingReason.NO_PERMISSION
    is AppError.AuthenticationRequired, is AppError.TokenExpired -> MissingReason.SOURCE_DISCONNECTED
    else -> MissingReason.API_UNAVAILABLE
}

/** Collapses a port result into either the value or the reason it is missing. */
internal sealed interface Read<out T> {
    data class Ok<out T>(val value: T) : Read<T>

    data class Fail(val reason: MissingReason) : Read<Nothing>
}

internal fun <T> Outcome<InputAnswer<T>>.toRead(): Read<T> = when (this) {
    is Outcome.Failure -> Read.Fail(missingReasonFor(error))

    is Outcome.Success -> when (val answer = value) {
        is InputAnswer.Available -> Read.Ok(answer.value)
        is InputAnswer.Unavailable -> Read.Fail(answer.reason)
    }
}

internal fun <T> Outcome<T>.toPlainRead(): Read<T> = when (this) {
    is Outcome.Failure -> Read.Fail(missingReasonFor(error))
    is Outcome.Success -> Read.Ok(value)
}
