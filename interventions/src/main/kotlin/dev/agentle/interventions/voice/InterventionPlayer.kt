package dev.agentle.interventions.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.core.content.ContextCompat
import dev.agentle.interventions.storage.BundledMedia
import dev.agentle.interventions.storage.MediaLibrary
import dev.agentle.interventions.storage.MediaRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why playback did not start; the screen shows the text instead (R09 §2.8, F10-F14). */
enum class SkipReason {
    /** The host screen is not visible: nothing plays in the background. */
    NOT_VISIBLE,

    /** The file was evicted or deleted, or the bundled clip is missing. */
    MEDIA_MISSING,

    /** The media stream is muted or at volume 0 ("Volume is off"). */
    MUTED,

    /** "Only on headphones" is on and the route is the speaker (or unknown). */
    NOT_PRIVATE,

    /** Autoplay while Do Not Disturb filters; a tap on Listen still plays. */
    DO_NOT_DISTURB,

    /** `AUDIOFOCUS_REQUEST_FAILED`: a call is active, or the app is not the top app. */
    FOCUS_DENIED,
}

/** Why playback paused; [InterventionPlayer.resume] plays on. */
enum class PauseReason { FOCUS_LOSS, BECOMING_NOISY, HIDDEN, USER }

/** What the player is doing; the screen renders it. */
sealed interface PlayerState {
    data object Idle : PlayerState

    data class Playing(val ref: String) : PlayerState

    data class Paused(val ref: String, val reason: PauseReason) : PlayerState

    data class Skipped(val ref: String?, val reason: SkipReason) : PlayerState

    data class Completed(val ref: String) : PlayerState

    /** The user stopped playback (cancel). */
    data object Stopped : PlayerState

    /** The playback backend failed; [code] is content-free. */
    data class Failed(val ref: String, val code: String) : PlayerState
}

/** Events from a [PlaybackEngine], on the main thread. */
interface PlaybackListener {
    fun onEnded()

    fun onError(code: String)
}

/** A playback backend: ExoPlayer in production ([ExoPlaybackEngine]), a fake in tests. Main thread only. */
interface PlaybackEngine {
    fun play(uri: Uri, listener: PlaybackListener)

    fun pause()

    fun resume()

    fun stop()

    /** Where a VIDEO renders (a SurfaceView's surface); null detaches it. */
    fun setVideoSurface(surface: Surface?)

    fun release()
}

/**
 * Plays an intervention's WAV or MP4, only while its screen is visible (R09 §2.1, §5): the host calls [onHostVisible] /
 * [onHostHidden] from `ON_START` / `ON_STOP`, and [release] when it goes away. Before playing it checks the mute state,
 * the "headphones only" option and, for autoplay, Do Not Disturb; then it asks for transient, may-duck audio focus
 * (also the gate: a denied request plays nothing). Focus loss and `ACTION_AUDIO_BECOMING_NOISY` pause; [stop] is the
 * user's cancel. Routes and volume are never changed. Main thread only.
 */
class InterventionPlayer(
    private val context: Context,
    private val library: MediaLibrary,
    private val bundled: BundledMedia,
    private val engine: PlaybackEngine,
    private val quietMode: () -> Boolean,
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java),
    private val inspector: AudioOutputInspector = AudioOutputInspector(audioManager),
) {
    private val mutableState = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val state: StateFlow<PlayerState> = mutableState.asStateFlow()

    private var visible = false
    private var current: String? = null
    private var focusRequest: AudioFocusRequest? = null
    private var noisyRegistered = false
    private val main = Handler(Looper.getMainLooper())

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS) abandonFocus()
        // Speech pauses instead of ducking (R09 §5); the user resumes.
        if (change in FOCUS_LOSSES) pauseFor(PauseReason.FOCUS_LOSS)
    }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pauseFor(PauseReason.BECOMING_NOISY)
        }
    }

    private val listener = object : PlaybackListener {
        override fun onEnded() = finish(PlayerState.Completed(current.orEmpty()))

        override fun onError(code: String) = finish(PlayerState.Failed(current.orEmpty(), code))
    }

    fun onHostVisible() {
        visible = true
    }

    fun onHostHidden() {
        visible = false
        pauseFor(PauseReason.HIDDEN)
        abandonFocus()
    }

    /**
     * Plays [ref] (`media:<id>` or `asset:<path>`). [userInitiated] is a tap on Listen or Watch; a deep link's autoplay
     * passes false and is skipped under Do Not Disturb. [requirePrivateOutput] is the "headphones only" option.
     */
    suspend fun play(ref: String, userInitiated: Boolean, requirePrivateOutput: Boolean): PlayerState {
        val uri = resolve(ref)
        val output = inspector.current()
        val skip = when {
            !visible -> SkipReason.NOT_VISIBLE
            uri == null -> SkipReason.MEDIA_MISSING
            output.muted -> SkipReason.MUTED
            requirePrivateOutput && !output.isPrivate -> SkipReason.NOT_PRIVATE
            !userInitiated && quietMode() -> SkipReason.DO_NOT_DISTURB
            !requestFocus() -> SkipReason.FOCUS_DENIED
            else -> null
        }
        if (skip != null || uri == null) return set(PlayerState.Skipped(ref, skip ?: SkipReason.MEDIA_MISSING))
        engine.stop()
        current = ref
        registerNoisy()
        engine.play(uri, listener)
        return set(PlayerState.Playing(ref))
    }

    /** The user paused. */
    fun pause() {
        pauseFor(PauseReason.USER)
    }

    /** Plays on after a pause, asking for focus again. */
    fun resume(): PlayerState {
        val paused = mutableState.value as? PlayerState.Paused ?: return mutableState.value
        if (!visible) return set(PlayerState.Skipped(paused.ref, SkipReason.NOT_VISIBLE))
        if (!requestFocus()) return set(PlayerState.Skipped(paused.ref, SkipReason.FOCUS_DENIED))
        registerNoisy()
        engine.resume()
        return set(PlayerState.Playing(paused.ref))
    }

    /** The user's cancel: stops, gives focus back, and forgets the clip. */
    fun stop() {
        if (mutableState.value is PlayerState.Playing || mutableState.value is PlayerState.Paused) finish(PlayerState.Stopped)
    }

    fun setVideoSurface(surface: Surface?) {
        engine.setVideoSurface(surface)
    }

    fun release() {
        stop()
        engine.release()
    }

    private fun pauseFor(reason: PauseReason) {
        val playing = mutableState.value as? PlayerState.Playing ?: return
        engine.pause()
        unregisterNoisy()
        set(PlayerState.Paused(playing.ref, reason))
    }

    private fun finish(end: PlayerState) {
        engine.stop()
        unregisterNoisy()
        abandonFocus()
        current = null
        set(end)
    }

    private suspend fun resolve(ref: String): Uri? = when (val parsed = MediaRef.parse(ref)) {
        is MediaRef.Stored -> library.record(parsed.id)?.let { record ->
            library.touch(parsed.id)
            library.fileOf(record)?.let(Uri::fromFile)
        }
        is MediaRef.Bundled -> parsed.assetPath.takeIf(bundled::exists)?.let { Uri.parse("asset:///$it") }
        null -> null
    }

    private fun requestFocus(): Boolean {
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(SPEECH_ATTRIBUTES)
            .setOnAudioFocusChangeListener(focusListener, main)
            .setAcceptsDelayedFocusGain(false)
            .build()
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusRequest = request.takeIf { granted }
        return granted
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /**
     * `ACTION_AUDIO_BECOMING_NOISY` is a protected broadcast sent by the system, which reaches a not-exported receiver
     * (the brief: receivers are not exported); headphones or Bluetooth going away pauses instead of moving to the speaker.
     */
    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            context,
            noisy,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        context.unregisterReceiver(noisy)
        noisyRegistered = false
    }

    private fun set(state: PlayerState): PlayerState {
        mutableState.value = state
        return state
    }

    private companion object {
        val FOCUS_LOSSES = setOf(
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
        )
    }
}
