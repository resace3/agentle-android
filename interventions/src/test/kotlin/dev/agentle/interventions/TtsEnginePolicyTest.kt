package dev.agentle.interventions

import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.agentle.interventions.voice.EngineChoice
import dev.agentle.interventions.voice.TtsEnginePolicy
import dev.agentle.interventions.voice.TtsSynthesizer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 37])
class TtsEnginePolicyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val policy = TtsEnginePolicy(context)

    private fun install(pkg: String) {
        val component = ComponentName(pkg, "$pkg.TtsService")
        val pm = shadowOf(context.packageManager)
        pm.addOrUpdateService(
            android.content.pm.ServiceInfo().apply {
                packageName = pkg
                name = component.className
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = pkg }
            },
        )
        pm.addIntentFilterForService(
            component,
            IntentFilter(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).apply { addCategory("android.intent.category.DEFAULT") },
        )
    }

    private fun setDefault(pkg: String) {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH, pkg)
    }

    @Test
    fun `an allowed default engine is used`() {
        install(TtsEnginePolicy.GOOGLE_TTS)
        setDefault(TtsEnginePolicy.GOOGLE_TTS)
        assertThat(policy.choose(emptySet())).isEqualTo(EngineChoice.Use(TtsEnginePolicy.GOOGLE_TTS))
    }

    @Test
    fun `a disallowed default engine downgrades to Google TTS`() {
        install("com.example.cloudtts")
        install(TtsEnginePolicy.GOOGLE_TTS)
        setDefault("com.example.cloudtts")
        assertThat(policy.choose(emptySet())).isEqualTo(EngineChoice.Use(TtsEnginePolicy.GOOGLE_TTS))
    }

    @Test
    fun `only a disallowed engine is not allowed, unless the user allowed it by name`() {
        install("com.example.cloudtts")
        setDefault("com.example.cloudtts")
        assertThat(policy.choose(emptySet())).isEqualTo(EngineChoice.NotAllowed)
        assertThat(policy.choose(setOf("com.example.cloudtts"))).isEqualTo(EngineChoice.Use("com.example.cloudtts"))
    }

    @Test
    fun `no engine installed is missing`() {
        assertThat(policy.choose(emptySet())).isEqualTo(EngineChoice.Missing)
    }

    @Test
    fun `network voices are refused`() {
        val network = Voice("en-us-network", Locale.US, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, true, emptySet())
        val synth = Voice(
            "en-us-x-net",
            Locale.US,
            Voice.QUALITY_VERY_HIGH,
            Voice.LATENCY_NORMAL,
            false,
            setOf(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS),
        )
        assertThat(TtsSynthesizer.pickVoice(setOf(network, synth), Locale.US)).isNull()
        val local = Voice("en-us-local", Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet())
        assertThat(TtsSynthesizer.pickVoice(setOf(network, synth, local), Locale.US)).isEqualTo(local)
    }
}
