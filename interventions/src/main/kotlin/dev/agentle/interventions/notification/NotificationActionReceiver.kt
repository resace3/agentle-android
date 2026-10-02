package dev.agentle.interventions.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors
import dev.agentle.interventions.di.InterventionsEntryPoint
import dev.agentle.interventions.response.InterventionResponses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Handles notification actions and the delete intent. Declared `android:exported="false"`, so only this app's own
 * immutable PendingIntents reach it. It records the response through [InterventionResponses] (which checks the nonce)
 * and never starts an activity (Android 12 trampoline rule; Listen and Watch open the activity directly).
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = NotificationAction.from(intent) ?: return
        val app = context.applicationContext
        val host = app as? InterventionsHost
        val responses = host?.interventionResponses ?: entryPoint(app).interventionResponses()
        val scope = host?.interventionScope ?: entryPoint(app).interventionScope()
        // Null when called outside a broadcast dispatch (a direct call in a test).
        val pending: PendingResult? = goAsync()
        scope.launch {
            try {
                responses.apply(action.toResponse())
            } finally {
                pending?.finish()
            }
        }
    }

    private fun entryPoint(app: Context): InterventionsEntryPoint =
        EntryPointAccessors.fromApplication(app, InterventionsEntryPoint::class.java)
}

/**
 * Lets an Application hand the receiver its dependencies without Hilt (tests, or an app that wires this module by
 * hand). The Hilt app does not implement it; the receiver then uses [InterventionsEntryPoint].
 */
interface InterventionsHost {
    val interventionResponses: InterventionResponses
    val interventionScope: CoroutineScope
}
