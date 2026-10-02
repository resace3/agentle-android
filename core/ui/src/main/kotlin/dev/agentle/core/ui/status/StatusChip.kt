package dev.agentle.core.ui.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.agentle.core.ui.theme.AgentleSpacing
import dev.agentle.core.ui.theme.AgentleTheme

/**
 * A status pill: an icon and a text label on the tone's container color. The icon repeats the label, so it is
 * decorative for accessibility services; the chip is read as one node ("Allowed"). The icon is sized in sp so it grows
 * with the label at large font scales.
 */
@Composable
public fun StatusChip(tone: StatusTone, icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    StatusPill(
        tone = tone,
        icon = icon,
        label = label,
        textStyle = MaterialTheme.typography.labelLarge,
        iconSize = 18.sp,
        padding = PaddingValues(horizontal = AgentleSpacing.m, vertical = AgentleSpacing.xs + AgentleSpacing.xxs),
        modifier = modifier,
    )
}

/** [StatusChip] for a resolved [StatusSpec]. */
@Composable
public fun StatusChip(spec: StatusSpec, modifier: Modifier = Modifier) {
    StatusChip(tone = spec.tone, icon = spec.icon, label = stringResource(spec.label), modifier = modifier)
}

/** A compact status pill for dense rows and tiles; same contract as [StatusChip]. */
@Composable
public fun StatusBadge(tone: StatusTone, icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    StatusPill(
        tone = tone,
        icon = icon,
        label = label,
        textStyle = MaterialTheme.typography.labelMedium,
        iconSize = 14.sp,
        padding = PaddingValues(horizontal = AgentleSpacing.s, vertical = AgentleSpacing.xxs),
        modifier = modifier,
    )
}

/** [StatusBadge] for a resolved [StatusSpec]. */
@Composable
public fun StatusBadge(spec: StatusSpec, modifier: Modifier = Modifier) {
    StatusBadge(tone = spec.tone, icon = spec.icon, label = stringResource(spec.label), modifier = modifier)
}

@Composable
private fun StatusPill(
    tone: StatusTone,
    icon: ImageVector,
    label: String,
    textStyle: TextStyle,
    iconSize: TextUnit,
    padding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val colors = AgentleTheme.statusPalette.colorsFor(tone)
    val iconDp = with(LocalDensity.current) { iconSize.toDp() }
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.small,
        color = colors.container,
        contentColor = colors.content,
    ) {
        Row(
            modifier = Modifier.padding(padding),
            horizontalArrangement = Arrangement.spacedBy(AgentleSpacing.xs + AgentleSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(iconDp))
            Text(text = label, style = textStyle)
        }
    }
}
