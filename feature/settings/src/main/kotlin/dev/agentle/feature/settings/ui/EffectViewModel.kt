package dev.agentle.feature.settings.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow

/** Upstream flows stay active 5 s after the last collector leaves, so a configuration change does not restart them. */
internal val WhileUiSubscribed: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000)

/** A settings ViewModel with a channel of one-off [SettingsEffect]s, collected by [HandleSettingsEffects]. */
internal open class EffectViewModel : ViewModel() {
    private val effectChannel = Channel<SettingsEffect>(Channel.BUFFERED)

    /** One-off effects; each is delivered to one collector once. */
    val effects: Flow<SettingsEffect> = effectChannel.receiveAsFlow()

    protected fun send(effect: SettingsEffect) {
        effectChannel.trySend(effect)
    }

    /** Shows the message for a failed action. */
    protected fun report(error: AppError) {
        send(error.toMessage())
    }

    /** Starts a system Settings page the port built, or says it cannot be opened. */
    protected fun openSystemPage(intent: Outcome<Intent>) {
        when (intent) {
            is Outcome.Success -> send(SettingsEffect.OpenIntent(intent.value))
            is Outcome.Failure -> send(cannotOpenSettingsMessage)
        }
    }
}
