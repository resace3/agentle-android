package dev.agentle.feature.connections.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import com.google.common.truth.Truth.assertThat
import dev.agentle.feature.connections.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A [LabelValueRow] with a long value still reads normally on a 320 dp phone at font scale 1.3. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w320dp-h569dp-hdpi", fontScale = 1.3f)
class LabelValueRowLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun labelAndLongValueWrapToFewLines() {
        compose.setContent {
            LabelValueRow(
                icon = R.drawable.connections_ic_check_circle,
                label = "Plan usage",
                value = "Unknown until a request is made",
                tone = Tone.NEUTRAL,
            )
        }
        assertThat(lineCount("Plan usage")).isAtMost(2)
        assertThat(lineCount("Unknown until a request is made")).isAtMost(3)
    }

    private fun lineCount(text: String): Int {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single().lineCount
    }
}
