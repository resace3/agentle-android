package dev.agentle.core.ui.permission

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Android's "App info" screen of this app, where runtime permissions are granted after a permanent denial. */
public fun Context.appDetailsSettingsIntent(): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

/**
 * Starts [intent] (a system Settings screen). When no activity handles it, falls back to this app's details screen and
 * then to the Settings app's main screen (docs/research/01 §6). Returns false when nothing could be opened.
 */
public fun Context.openSettingsScreen(intent: Intent?): Boolean {
    val candidates = listOfNotNull(intent, appDetailsSettingsIntent(), Intent(Settings.ACTION_SETTINGS))
    for (candidate in candidates) {
        if (this !is android.app.Activity) candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = try {
            startActivity(candidate)
            true
        } catch (ignored: ActivityNotFoundException) {
            false
        } catch (ignored: SecurityException) {
            false
        }
        if (started) return true
    }
    return false
}
