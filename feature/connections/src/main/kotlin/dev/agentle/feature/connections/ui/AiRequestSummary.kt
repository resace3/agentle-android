package dev.agentle.feature.connections.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.agentle.feature.connections.R
import dev.agentle.feature.connections.port.AiRequestOutcome
import dev.agentle.feature.connections.port.AiRequestRecord

/**
 * One AI request as metadata: purpose and outcome, time, categories and size. Never the payload, the prompt or the
 * answer (the record has none).
 */
@Composable
internal fun AiRequestSummary(record: AiRequestRecord, formatter: InstantFormatter, modifier: Modifier = Modifier) {
    val outcome = stringResource(outcomeLabel(record.outcome))
    val outcomeWithCode = record.errorCode?.let { stringResource(R.string.connections_error_with_code, outcome, it) } ?: outcome
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        StatusRow(
            icon = outcomeIcon(record.outcome),
            text = stringResource(R.string.connections_ai_history_details, stringResource(purposeLabel(record.purpose)), outcomeWithCode),
            tone = outcomeTone(record.outcome),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(text = formatter.format(record.at), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = stringResource(R.string.connections_ai_history_details, categoryList(record.categories), formatBytes(record.bytes)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (record.background) {
            Text(
                text = stringResource(R.string.connections_ai_history_background),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun outcomeIcon(outcome: AiRequestOutcome): Int = when (outcome) {
    AiRequestOutcome.SENT -> R.drawable.connections_ic_check_circle
    AiRequestOutcome.IN_FLIGHT -> R.drawable.connections_ic_hourglass
    AiRequestOutcome.DENIED, AiRequestOutcome.NOT_SENT -> R.drawable.connections_ic_block
    AiRequestOutcome.CANCELLED -> R.drawable.connections_ic_remove_circle
    AiRequestOutcome.FAILED -> R.drawable.connections_ic_error
}

private fun outcomeTone(outcome: AiRequestOutcome): Tone = when (outcome) {
    AiRequestOutcome.SENT -> Tone.POSITIVE
    AiRequestOutcome.IN_FLIGHT, AiRequestOutcome.CANCELLED, AiRequestOutcome.NOT_SENT -> Tone.NEUTRAL
    AiRequestOutcome.DENIED -> Tone.WARNING
    AiRequestOutcome.FAILED -> Tone.ERROR
}
