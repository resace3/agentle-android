package dev.agentle.interventions.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech

/** Which engine a synthesis may use (privacy-ai-19). */
sealed interface EngineChoice {
    /** Speak through [packageName], pinned so the request goes to that engine. */
    data class Use(val packageName: String) : EngineChoice

    /** No TTS engine is installed (or the `<queries>` entry is missing). */
    data object Missing : EngineChoice

    /** Engines exist, but none is on the allow-list. */
    data object NotAllowed : EngineChoice
}

/**
 * The TTS engine policy (privacy-ai-19). The engine is another app that receives the spoken text, so it is an egress
 * path: text goes only to an allow-listed on-device engine, [GOOGLE_TTS] by default plus the engines the user allowed by
 * name ([dev.agentle.interventions.ports.TtsEngineConsent], empty by default). The device's default engine is used when
 * it is allowed; otherwise [GOOGLE_TTS] when installed; otherwise another user-allowed engine; otherwise none
 * ([EngineChoice.NotAllowed], and VOICE becomes a plain notification).
 */
class TtsEnginePolicy(private val context: Context) {
    /** Package names of the installed engines (services answering `TTS_SERVICE`), in the package manager's order. */
    fun installed(): List<String> = services().mapNotNull { it.serviceInfo?.packageName }.distinct()

    /** The engine picked in the system settings, when it is still installed. */
    fun systemDefault(installed: List<String> = installed()): String? =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)?.takeIf { it in installed }

    fun choose(userAllowed: Set<String>): EngineChoice {
        val installed = installed()
        if (installed.isEmpty()) return EngineChoice.Missing
        val allowed = userAllowed + GOOGLE_TTS
        val engine = systemDefault(installed)?.takeIf { it in allowed }
            ?: GOOGLE_TTS.takeIf { it in installed }
            ?: installed.firstOrNull { it in allowed }
        return engine?.let(EngineChoice::Use) ?: EngineChoice.NotAllowed
    }

    private fun services(): List<ResolveInfo> {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
    }

    companion object {
        /** Speech Services by Google: on-device voices; allowed without asking. */
        const val GOOGLE_TTS: String = "com.google.android.tts"
    }
}
