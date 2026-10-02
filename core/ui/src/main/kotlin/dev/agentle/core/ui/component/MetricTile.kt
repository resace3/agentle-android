package dev.agentle.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import dev.agentle.core.ui.status.StatusBadge
import dev.agentle.core.ui.status.StatusSpec
import dev.agentle.core.ui.theme.AgentleSpacing

/**
 * One metric: [label], [value] and optional [supportingText] (for example "as of 14:05" or the reason a value is
 * missing). When the value is unknown pass the reason as [value] with [valueKnown] false: it is shown in a smaller
 * style, because "no data yet" is never shown as zero. [status] adds a status badge (icon and text).
 */
@Composable
public fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueKnown: Boolean = true,
    supportingText: String? = null,
    icon: ImageVector? = null,
    status: StatusSpec? = null,
    onClick: (() -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .heightIn(min = AgentleSpacing.minTouchTarget)
                .padding(AgentleSpacing.l),
            verticalArrangement = Arrangement.spacedBy(AgentleSpacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AgentleSpacing.s)) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(AgentleSpacing.iconSmall),
                    )
                }
                Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = value,
                style = if (valueKnown) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                color = if (valueKnown) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (supportingText != null) {
                Text(text = supportingText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (status != null) StatusBadge(spec = status)
        }
    }
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, colors = colors) { content() }
    } else {
        Card(modifier = modifier.semantics(mergeDescendants = true) {}, colors = colors) { content() }
    }
}
