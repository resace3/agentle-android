package dev.agentle.feature.connections.aisharing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.sensitiveContent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.agentle.feature.connections.R
import dev.agentle.feature.connections.port.AiRequestPreview
import dev.agentle.feature.connections.ui.InstantFormatter
import dev.agentle.feature.connections.ui.StatusRow
import dev.agentle.feature.connections.ui.Tone
import dev.agentle.feature.connections.ui.categoryList
import dev.agentle.feature.connections.ui.formatBytes
import dev.agentle.feature.connections.ui.purposeLabel

internal const val AI_PREVIEW_TAG: String = "ai_request_preview"

/**
 * Exactly what a request would send, read-only. The content is personal: the card is marked as sensitive content
 * (hidden while the screen is shared or recorded, Android 15+), and nothing here is logged or stored.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AiPreviewCard(preview: AiRequestPreview, formatter: InstantFormatter, onClose: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(modifier = modifier.fillMaxWidth().testTag(AI_PREVIEW_TAG)) {
        Column(
            modifier = Modifier
                .sensitiveContent()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusRow(
                icon = R.drawable.connections_ic_lock,
                text = stringResource(R.string.connections_ai_preview_title),
                tone = Tone.NEUTRAL,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            PreviewFacts(preview, formatter)
            ContentBlock(label = stringResource(R.string.connections_ai_preview_instructions), text = preview.instructions)
            ContentBlock(label = stringResource(R.string.connections_ai_preview_data), text = preview.dataInput)
            preview.userInput?.let { ContentBlock(label = stringResource(R.string.connections_ai_preview_user_input), text = it) }
            Text(
                text = stringResource(R.string.connections_ai_preview_private_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onClose) { Text(text = stringResource(R.string.connections_ai_preview_close)) }
        }
    }
}

@Composable
private fun PreviewFacts(preview: AiRequestPreview, formatter: InstantFormatter) {
    val yes = stringResource(R.string.connections_yes)
    val no = stringResource(R.string.connections_no)
    Fact(stringResource(R.string.connections_ai_preview_purpose, stringResource(purposeLabel(preview.purpose))))
    Fact(stringResource(R.string.connections_ai_preview_includes, categoryList(preview.categories)))
    if (preview.leftOut.isNotEmpty()) Fact(stringResource(R.string.connections_ai_preview_left_out, categoryList(preview.leftOut)))
    val start = preview.rangeStart
    val end = preview.rangeEnd
    if (start != null && end != null) {
        Fact(stringResource(R.string.connections_ai_preview_range, formatter.format(start), formatter.format(end)))
    }
    Fact(stringResource(R.string.connections_ai_preview_size, formatBytes(preview.bytes)))
    Fact(stringResource(R.string.connections_ai_preview_raw_events, if (preview.rawEvents) yes else no))
    Fact(stringResource(R.string.connections_ai_preview_aggregates, if (preview.aggregates) yes else no))
    Fact(stringResource(R.string.connections_ai_preview_version, preview.consentVersion))
}

@Composable
private fun Fact(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ContentBlock(label: String, text: String) {
    Text(text = label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(12.dp),
        )
    }
}
