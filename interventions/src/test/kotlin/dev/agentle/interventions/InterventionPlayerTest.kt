package dev.agentle.interventions

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.Logger
import dev.agentle.core.common.getOrNull
import dev.agentle.core.model.GenerationMethod
import dev.agentle.core.model.MediaKind
import dev.agentle.interventions.storage.BundledMedia
import dev.agentle.interventions.storage.InMemoryMediaMetadataStore
import dev.agentle.interventions.storage.MediaLibrary
import dev.agentle.interventions.storage.MediaPaths
import dev.agentle.interventions.storage.MediaSpec
import dev.agentle.interventions.storage.PendingDeliveries
import dev.agentle.interventions.voice.AudioOutputInspector
import dev.agentle.interventions.voice.InterventionPlayer
import dev.agentle.interventions.voice.OutputRoute
import dev.agentle.interventions.voice.PauseReason
import dev.agentle.interventions.voice.PlaybackEngine
import dev.agentle.interventions.voice.PlaybackListener
import dev.agentle.interventions.voice.PlayerState
import dev.agentle.interventions.voice.SkipReason
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 37])
class InterventionPlayerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audio = context.getSystemService(AudioManager::class.java)

    private class FakeEngine : PlaybackEngine {
        val calls = mutableListOf<String>()
        var listener: PlaybackListener? = null

        override fun play(uri: Uri, listener: PlaybackListener) {
            calls += "play"
            this.listener = listener
        }

        override fun pause() {
            calls += "pause"
        }

        override fun resume() {
            calls += "resume"
        }

        override fun stop() {
            calls += "stop"
        }

        override fun setVideoSurface(surface: Surface?) = Unit

        override fun release() {
            calls += "release"
        }
    }

    private val engine = FakeEngine()
    private var quiet = false
    private val library = MediaLibrary(
        MediaPaths({ context.noBackupFilesDir }, { context.cacheDir }),
        InMemoryMediaMetadataStore(),
        Fixtures.clock(),
        PendingDeliveries { emptySet() },
        Logger.NONE,
        io = UnconfinedTestDispatcher(),
    )
    private val player = InterventionPlayer(context, library, BundledMedia(context.assets), engine, { quiet }, audio)

    private suspend fun stored(): String {
        val target = library.newTarget(MediaKind.VOICE, "wav")
        target.file.parentFile.mkdirs()
        target.file.writeBytes(ByteArray(10))
        val spec = MediaSpec(MediaKind.VOICE, GenerationMethod.LOCAL_TTS, "audio/wav", null, null, null)
        return "media:" + library.register(target, spec).getOrNull()!!.artifact.id
    }

    private fun audible() {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
    }

    @Test
    fun `plays only while visible and with the media present`() = runTest {
        audible()
        val ref = stored()
        assertThat(player.play(ref, true, false)).isEqualTo(PlayerState.Skipped(ref, SkipReason.NOT_VISIBLE))
        player.onHostVisible()
        assertThat(player.play("media:none", true, false)).isEqualTo(PlayerState.Skipped("media:none", SkipReason.MEDIA_MISSING))
        assertThat(player.play(ref, true, false)).isEqualTo(PlayerState.Playing(ref))
        engine.listener!!.onEnded()
        assertThat(player.state.value).isEqualTo(PlayerState.Completed(ref))
    }

    @Test
    fun `a muted stream skips`() = runTest {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        player.onHostVisible()
        val ref = stored()
        assertThat(player.play(ref, true, false)).isEqualTo(PlayerState.Skipped(ref, SkipReason.MUTED))
        assertThat(engine.calls).doesNotContain("play")
    }

    @Test
    fun `headphones only skips on an unknown or speaker route, Bluetooth headsets count as private`() = runTest {
        audible()
        player.onHostVisible()
        val ref = stored()
        assertThat(player.play(ref, true, true)).isEqualTo(PlayerState.Skipped(ref, SkipReason.NOT_PRIVATE))
        assertThat(AudioOutputInspector.classify(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).isPrivate).isTrue()
        assertThat(AudioOutputInspector.classify(AudioDeviceInfo.TYPE_BLE_HEADSET).isPrivate).isTrue()
        assertThat(AudioOutputInspector.classify(AudioDeviceInfo.TYPE_BLE_SPEAKER).isPrivate).isFalse()
        assertThat(AudioOutputInspector.classify(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)).isEqualTo(OutputRoute.BUILT_IN_SPEAKER)
        assertThat(AudioOutputInspector.classify(AudioDeviceInfo.TYPE_HEARING_AID).isPrivate).isTrue()
    }

    @Test
    fun `autoplay is skipped under do not disturb, a tap is not`() = runTest {
        audible()
        player.onHostVisible()
        quiet = true
        val ref = stored()
        assertThat(player.play(ref, false, false)).isEqualTo(PlayerState.Skipped(ref, SkipReason.DO_NOT_DISTURB))
        assertThat(player.play(ref, true, false)).isEqualTo(PlayerState.Playing(ref))
    }

    @Test
    fun `focus denied skips, focus loss and becoming noisy pause`() = runTest {
        audible()
        player.onHostVisible()
        val ref = stored()
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertThat(player.play(ref, true, false)).isEqualTo(PlayerState.Skipped(ref, SkipReason.FOCUS_DENIED))
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        player.play(ref, true, false)
        shadowOf(audio).lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertThat(player.state.value).isEqualTo(PlayerState.Paused(ref, PauseReason.FOCUS_LOSS))
        assertThat(player.resume()).isEqualTo(PlayerState.Playing(ref))
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY).setPackage(context.packageName))
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(player.state.value).isEqualTo(PlayerState.Paused(ref, PauseReason.BECOMING_NOISY))
    }

    @Test
    fun `hiding the host pauses and stop gives focus back`() = runTest {
        audible()
        player.onHostVisible()
        val ref = stored()
        player.play(ref, true, false)
        player.onHostHidden()
        assertThat(player.state.value).isEqualTo(PlayerState.Paused(ref, PauseReason.HIDDEN))
        player.stop()
        assertThat(player.state.value).isEqualTo(PlayerState.Stopped)
        assertThat(engine.calls.last()).isEqualTo("stop")
    }
}
