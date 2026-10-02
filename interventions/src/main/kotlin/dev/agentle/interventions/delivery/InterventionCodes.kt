package dev.agentle.interventions.delivery

/**
 * Content-free failure codes of this module (logs, `PrepareResult.Unavailable`, `PostResult.Failed`). Never an
 * exception message. The engine turns any `Unavailable` into its own downgrade reason (`TTS_UNAVAILABLE` for VOICE,
 * `MEDIA_UNAVAILABLE` otherwise) and logs the code below next to it.
 */
object InterventionCodes {
    // ---- VOICE (R09 §2.8 and §11, F1-F9)
    const val TTS_ENGINE_MISSING: String = "TTS_ENGINE_MISSING"

    /** Engines are installed, but none is allow-listed (privacy-ai-19). */
    const val TTS_ENGINE_NOT_ALLOWED: String = "TTS_ENGINE_NOT_ALLOWED"
    const val TTS_INIT_FAILED: String = "TTS_INIT_FAILED"
    const val TTS_INIT_TIMEOUT: String = "TTS_INIT_TIMEOUT"
    const val TTS_LANGUAGE_MISSING: String = "TTS_LANGUAGE_MISSING"
    const val TTS_LANGUAGE_UNSUPPORTED: String = "TTS_LANGUAGE_UNSUPPORTED"
    const val TTS_NO_OFFLINE_VOICE: String = "TTS_NO_OFFLINE_VOICE"
    const val TTS_QUEUE_REJECTED: String = "TTS_QUEUE_REJECTED"
    const val TTS_SYNTHESIS_ERROR: String = "TTS_SYNTHESIS_ERROR"
    const val TTS_STOPPED: String = "TTS_STOPPED"
    const val TTS_TIMEOUT: String = "TTS_TIMEOUT"
    const val TTS_INVALID_OUTPUT: String = "TTS_INVALID_OUTPUT"
    const val TTS_EMPTY_TEXT: String = "TTS_EMPTY_TEXT"

    // ---- IMAGE and VIDEO (F14, F22, F23)
    const val IMAGE_UNAVAILABLE: String = "IMAGE_UNAVAILABLE"
    const val IMAGE_RENDER_FAILED: String = "IMAGE_RENDER_FAILED"
    const val VIDEO_UNAVAILABLE: String = "VIDEO_UNAVAILABLE"
    const val MEDIA_QUOTA: String = "MEDIA_QUOTA"
    const val MEDIA_STORE_FAILED: String = "MEDIA_STORE_FAILED"

    // ---- any channel
    const val PREPARE_ERROR: String = "PREPARE_ERROR"

    // ---- post
    const val CHANNEL_NONE: String = "CHANNEL_NONE"
    const val ACTIVITY_INTENT_NOT_EXPLICIT: String = "ACTIVITY_INTENT_NOT_EXPLICIT"
    const val NOTIFY_FAILED: String = "NOTIFY_FAILED"
}
