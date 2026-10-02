package dev.agentle.interventions.voice

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** ExoPlayer backend; focus and noisy handling stay in [InterventionPlayer]. Main thread only. */
class ExoPlaybackEngine(private val context: Context) : PlaybackEngine {
    private var player: ExoPlayer? = null
    private var callbacks: Player.Listener? = null

    override fun play(uri: Uri, listener: PlaybackListener) {
        val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build()
        val p = player ?: ExoPlayer.Builder(context).setAudioAttributes(attributes, false).build().also { player = it }
        callbacks?.let { p.removeListener(it) }
        val next = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) listener.onEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                listener.onError("PLAYBACK_" + error.errorCodeName)
            }
        }
        p.addListener(next)
        callbacks = next
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        p.playWhenReady = true
    }

    override fun pause() {
        player?.pause()
    }

    override fun resume() {
        player?.play()
    }

    override fun stop() {
        player?.stop()
    }

    override fun setVideoSurface(surface: Surface?) {
        player?.setVideoSurface(surface)
    }

    override fun release() {
        player?.release()
        player = null
    }
}
