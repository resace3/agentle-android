package dev.agentle.feature.settings.ui

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import dev.agentle.core.common.AppError
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.feature.settings.R

/** One-off effects a settings ViewModel sends to its screen through a channel. */
internal sealed interface SettingsEffect {
    /** A snackbar; [args] fill the string's placeholders (never personal data: codes and counts only). */
    data class Message(@param:StringRes val text: Int, val args: List<String> = emptyList()) : SettingsEffect

    /** Starts a system Settings page the port built. */
    data class OpenIntent(val intent: Intent) : SettingsEffect

    /** Opens the share sheet for a file the port wrote. */
    data class Share(val uri: Uri, val mimeType: String) : SettingsEffect

    data class Navigate(val route: AppRoute) : SettingsEffect

    data class ResetTo(val route: AppRoute) : SettingsEffect
}

/** The user-facing message for a failed action: specific where the code is actionable, else generic with the code. */
internal fun AppError.toMessage(): SettingsEffect.Message = when (this) {
    is AppError.UnsupportedFeature -> SettingsEffect.Message(R.string.settings_error_not_available)
    is AppError.DatabaseError -> SettingsEffect.Message(R.string.settings_error_database)
    is AppError.ValidationError -> SettingsEffect.Message(R.string.settings_error_validation)
    is AppError.NetworkUnavailable -> SettingsEffect.Message(R.string.settings_error_network)
    is AppError.PermissionDenied, is AppError.PermissionPermanentlyDenied -> SettingsEffect.Message(R.string.settings_error_permission)
    else -> SettingsEffect.Message(R.string.settings_error_generic, listOf(code))
}

/** The message shown when a system Settings page cannot be opened. */
internal val cannotOpenSettingsMessage: SettingsEffect.Message = SettingsEffect.Message(R.string.settings_error_cannot_open)
