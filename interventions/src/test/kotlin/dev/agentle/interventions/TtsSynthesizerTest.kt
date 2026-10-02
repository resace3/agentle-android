package dev.agentle.interventions

import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.agentle.interventions.voice.TtsEnginePolicy
import dev.agentle.interventions.voice.TtsFactory
import dev.agentle.interventions.voice.TtsFailure
import dev.agentle.interventions.voice.TtsResult
import dev.agentle.interventions.voice.TtsSynthesizer
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 37])
class TtsSynthesizerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var created: TextToSpeech? = null

    private fun installGoogleTts() {
        val pkg = TtsEnginePolicy.GOOGLE_TTS
        val component = ComponentName(pkg, "$pkg.TtsService")
        val pm = shadowOf(context.packageManager)
        pm.addOrUpdateService(
            ServiceInfo().apply {
                packageName = pkg
                name = component.className
                applicationInfo = ApplicationInfo().apply { packageName = pkg }
            },
        )
        pm.addIntentFilterForService(
            component,
            IntentFilter(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).apply { addCategory("android.intent.category.DEFAULT") },
        )
    }

    /** Creates the shadowed engine client and answers `onInit` with [status] (null: never). */
    private fun factory(status: Int?) = TtsFactory { ctx, listener, engine ->
        TextToSpeech(ctx, {}, engine).also {
            created = it
            if (status != null) listener.onInit(status)
        }
    }

    private fun synthesizer(status: Int?) =
        TtsSynthesizer(context, factory = factory(status), io = UnconfinedTestDispatcher(), initTimeout = 5.seconds)

    private suspend fun TtsSynthesizer.run(): TtsResult = synthesize("Take a short walk.", Locale.US, File(tmp.root, "v.wav"))

    @Test
    fun `no engine installed fails as missing`() = runTest {
        assertThat(synthesizer(TextToSpeech.SUCCESS).run()).isEqualTo(TtsResult.Failure(TtsFailure.EngineMissing))
        assertThat(created).isNull()
    }

    @Test
    fun `blank text never reaches an engine`() = runTest {
        installGoogleTts()
        val result = TtsSynthesizer(context, factory = factory(TextToSpeech.SUCCESS)).synthesize(" ", Locale.US, File(tmp.root, "v.wav"))
        assertThat(result).isEqualTo(TtsResult.Failure(TtsFailure.EmptyText))
    }

    @Test
    fun `init failure and init timeout shut the engine down`() = runTest {
        installGoogleTts()
        assertThat(synthesizer(TextToSpeech.ERROR).run()).isEqualTo(TtsResult.Failure(TtsFailure.InitFailed(TextToSpeech.ERROR)))
        assertThat(shadowOf(created!!).isShutdown).isTrue()
        assertThat(synthesizer(null).run()).isEqualTo(TtsResult.Failure(TtsFailure.InitTimeout))
        assertThat(shadowOf(created!!).isShutdown).isTrue()
    }

    @Test
    fun `a language the engine does not have fails before synthesis`() = runTest {
        installGoogleTts()
        val result = synthesizer(TextToSpeech.SUCCESS).run() as TtsResult.Failure
        assertThat(result.failure).isAnyOf(TtsFailure.LanguageUnsupported, TtsFailure.LanguageMissing, TtsFailure.NoOfflineVoice)
        assertThat(shadowOf(created!!).isShutdown).isTrue()
        assertThat(File(tmp.root, "v.wav").exists()).isFalse()
    }

    @Test
    fun `cancelling stops and shuts the engine down and leaves no file`() = runTest {
        installGoogleTts()
        val job = launch { synthesizer(null).run() }
        testScheduler.advanceTimeBy(1_000)
        job.cancel()
        job.join()
        assertThat(shadowOf(created!!).isStopped).isTrue()
        assertThat(shadowOf(created!!).isShutdown).isTrue()
        assertThat(tmp.root.listFiles().orEmpty()).isEmpty()
    }
}
