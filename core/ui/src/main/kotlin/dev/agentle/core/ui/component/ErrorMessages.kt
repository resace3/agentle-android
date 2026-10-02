package dev.agentle.core.ui.component

import androidx.annotation.StringRes
import dev.agentle.core.ui.R

/**
 * The user-facing message of each stable `AppError.code` (docs/ARCHITECTURE.md §15). Unknown codes, including codes
 * added later, fall back to a generic message: the UI never shows an error's developer detail.
 */
private val messages: Map<String, Int> = mapOf(
    "permission_denied" to R.string.ui_error_permission_denied,
    "permission_permanently_denied" to R.string.ui_error_permission_permanently_denied,
    "authentication_required" to R.string.ui_error_authentication_required,
    "token_expired" to R.string.ui_error_token_expired,
    "rate_limited" to R.string.ui_error_rate_limited,
    "network_unavailable" to R.string.ui_error_network_unavailable,
    "remote_server_error" to R.string.ui_error_remote_server_error,
    "parsing_error" to R.string.ui_error_parsing_error,
    "database_error" to R.string.ui_error_database_error,
    "unsupported_feature" to R.string.ui_error_unsupported_feature,
    "validation_error" to R.string.ui_error_validation_error,
    "consent_violation" to R.string.ui_error_consent_violation,
    "not_eligible" to R.string.ui_error_not_eligible,
    "cancelled" to R.string.ui_error_cancelled,
    "unexpected" to R.string.ui_error_unexpected,
)

/** The message resource for [code]; [R.string.ui_error_unexpected] for codes this version does not know. */
@StringRes
public fun errorMessageRes(code: String): Int = messages[code] ?: R.string.ui_error_unexpected

/** Whether [code] has its own message (false means the generic fallback is shown). */
public fun hasErrorMessage(code: String): Boolean = code in messages
