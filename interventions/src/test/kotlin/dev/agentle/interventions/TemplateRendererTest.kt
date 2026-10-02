package dev.agentle.interventions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.agentle.interventions.image.CardContent
import dev.agentle.interventions.image.CardSize
import dev.agentle.interventions.image.TemplateRenderer
import dev.agentle.jitai.dsl.model.JitaiCategory
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class TemplateRendererTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val renderer = TemplateRenderer(context)
    private val content = CardContent(
        title = "A short walk before your next meeting",
        body = "Ten minutes outside helps you reset. Stand up, stretch, and take the stairs if you can. " +
            "Come back when you feel ready to focus again.",
        category = JitaiCategory.PHYSICAL_ACTIVITY,
    )

    @Test
    fun `every size has its pixel dimensions and the text fits at font scale 2_0`() {
        CardSize.entries.forEach { size ->
            val card = renderer.render(content, size, fontScale = 2.0f)
            assertThat(card.bitmap.width).isEqualTo(size.width)
            assertThat(card.bitmap.height).isEqualTo(size.height)
            assertThat(card.layout.fits).isTrue()
        }
    }

    @Test
    fun `larger font scales give larger text, capped at 2_0`() {
        val normal = renderer.render(content, CardSize.SLIDE, fontScale = 1.0f).layout
        val large = renderer.render(content, CardSize.SLIDE, fontScale = 2.0f).layout
        val huge = renderer.render(content, CardSize.SLIDE, fontScale = 3.0f).layout
        assertThat(large.titleSizePx).isAtLeast(normal.titleSizePx)
        assertThat(huge).isEqualTo(large)
    }

    @Test
    fun `very long text ellipsizes instead of overflowing`() {
        val long = content.copy(body = "Walk. ".repeat(400))
        val layout = renderer.render(long, CardSize.NOTIFICATION, fontScale = 2.0f).layout
        assertThat(layout.fits).isTrue()
        assertThat(layout.ellipsized).isTrue()
    }

    @Test
    fun notificationCardAtFontScale2() {
        renderer.render(content, CardSize.NOTIFICATION, fontScale = 2.0f).bitmap.captureRoboImage()
    }
}
