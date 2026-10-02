package dev.agentle.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.agentle.core.ui.theme.AgentleSpacing

/**
 * A section title marked as a heading for screen readers, with an optional trailing [action] (for example "See all").
 */
@Composable
public fun SectionHeader(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AgentleSpacing.minTouchTarget)
            .padding(horizontal = AgentleSpacing.screenGutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AgentleSpacing.s),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (action != null) action()
    }
}
