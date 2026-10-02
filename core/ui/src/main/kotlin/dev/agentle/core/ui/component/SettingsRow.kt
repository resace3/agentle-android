package dev.agentle.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import dev.agentle.core.ui.theme.AgentleSpacing

/**
 * A settings or list row at least 48 dp high: optional [icon], [title], [subtitle] and [trailing] content. The whole
 * row is the touch target when [onClick] is set (role Button); otherwise it is read as one text node.
 */
@Composable
public fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = if (onClick != null) {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    } else {
        Modifier.semantics(mergeDescendants = true) {}
    }
    RowLayout(
        modifier = modifier.then(interaction),
        title = title,
        subtitle = subtitle,
        icon = icon,
        enabled = enabled,
        trailing = trailing,
    )
}

/**
 * A row with a switch. The whole row toggles (role Switch), so the touch target is the row, never only the switch.
 */
@Composable
public fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    RowLayout(
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        title = title,
        subtitle = subtitle,
        icon = icon,
        enabled = enabled,
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

@Composable
private fun RowLayout(
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    enabled: Boolean,
    trailing: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val contentAlpha = if (enabled) 1f else DISABLED_ALPHA
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AgentleSpacing.minTouchTarget + AgentleSpacing.s)
            .padding(horizontal = AgentleSpacing.screenGutter, vertical = AgentleSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AgentleSpacing.l),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                modifier = Modifier.size(AgentleSpacing.icon),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AgentleSpacing.xxs)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                )
            }
        }
        if (trailing != null) trailing()
    }
}

private const val DISABLED_ALPHA = 0.6f
