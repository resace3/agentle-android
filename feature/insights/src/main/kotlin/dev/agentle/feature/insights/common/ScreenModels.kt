package dev.agentle.feature.insights.common

import dev.agentle.core.common.AppError
import dev.agentle.core.common.AppException
import dev.agentle.core.ui.navigation.AppRoute

/** One-off effects a ViewModel sends to its screen through a `Channel` (ARCHITECTURE §16). */
internal sealed interface ScreenEffect {
    data class Navigate(val route: AppRoute) : ScreenEffect

    data object Back : ScreenEffect

    data class Message(val message: UserMessage) : ScreenEffect
}

/** Short confirmations and failures shown in a snackbar; the text is resolved from string resources. */
internal enum class UserMessage {
    PAUSED,
    RESUMED,
    DISABLED,
    ACTION_FAILED,
    RESUME_BLOCKED,
    SAVED_AS_DRAFT,
    ACTIVATED,
    SAVE_FAILED,
    SAVE_BLOCKED,
    FEEDBACK_SAVED,
    FEEDBACK_FAILED,
    PROPOSAL_REJECTED,
    PROPOSAL_ALREADY_HANDLED,
}

/**
 * Why a screen could not load. [code] is the [AppError.code] (or `unexpected`): never an exception message, which
 * may contain personal data.
 */
internal data class LoadError(val code: String) {
    val retryable: Boolean get() = code != NOT_FOUND

    companion object {
        const val NOT_FOUND: String = "not_found"
        const val UNEXPECTED: String = "unexpected"

        /** The error of a failed flow: the [AppError] code of an [AppException], else `unexpected` (class name only). */
        fun of(throwable: Throwable): LoadError = LoadError((throwable as? AppException)?.error?.code ?: UNEXPECTED)

        fun of(error: AppError): LoadError = LoadError(error.code)
    }
}

/** The states of the AI features (spec §12): what to show when a request cannot be made or failed. */
internal enum class AiProblem {
    /** ChatGPT is not connected or its sign-in expired. */
    NOT_CONNECTED,

    /** A data category of the request is not allowed for AI; nothing was sent. */
    CONSENT_MISSING,

    /** The plan's usage limit or a rate limit was reached. */
    USAGE_LIMIT,

    /** No network. */
    OFFLINE,

    /** The feature is not available in this build. */
    UNAVAILABLE,

    /** Any other failure (server error, an answer that failed the AI text policy). */
    FAILED,
    ;

    /** The one route that fixes the problem, if any. */
    val fix: AppRoute?
        get() = when (this) {
            NOT_CONNECTED -> AppRoute.ChatGpt
            CONSENT_MISSING -> AppRoute.AiDataSharing
            else -> null
        }

    /** Whether trying again without user action can succeed. */
    val retryable: Boolean get() = this == OFFLINE || this == FAILED || this == USAGE_LIMIT

    companion object {
        fun of(error: AppError): AiProblem = when (error) {
            is AppError.AuthenticationRequired, is AppError.TokenExpired -> NOT_CONNECTED
            is AppError.ConsentViolation -> CONSENT_MISSING
            is AppError.RateLimited, is AppError.NotEligible -> USAGE_LIMIT
            is AppError.NetworkUnavailable -> OFFLINE
            is AppError.UnsupportedFeature -> UNAVAILABLE
            else -> FAILED
        }
    }
}
