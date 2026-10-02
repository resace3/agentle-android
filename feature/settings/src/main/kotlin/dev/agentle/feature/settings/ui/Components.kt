package dev.agentle.feature.settings.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.agentle.feature.settings.R

/** Horizontal padding of every settings row. */
internal val RowPadding = 16.dp

/** Kinds of status; each has its own icon and spoken label, so a status is never told by color only. */
internal enum class StatusKind(@param:DrawableRes val icon: Int, @param:StringRes val labelRes: Int) {
    OK(R.drawable.ic_settings_check_circle, R.string.settings_status_ok),
    DONE(R.drawable.ic_settings_check_circle, R.string.settings_status_done),
    INFO(R.drawable.ic_settings_info, R.string.settings_status_info),
    WARNING(R.drawable.ic_settings_warning, R.string.settings_status_warning),
    ERROR(R.drawable.ic_settings_error, R.string.settings_status_problem),
    IN_PROGRESS(R.drawable.ic_settings_schedule, R.string.settings_status_in_progress),
    PENDING(R.drawable.ic_settings_pending, R.string.settings_status_waiting),
    OFF(R.drawable.ic_settings_block, R.string.settings_status_off),
}

@Composable
private fun StatusKind.tint(): Color = when (this) {
    StatusKind.OK, StatusKind.DONE -> MaterialTheme.colorScheme.primary
    StatusKind.WARNING, StatusKind.ERROR -> MaterialTheme.colorScheme.error
    StatusKind.INFO, StatusKind.IN_PROGRESS -> MaterialTheme.colorScheme.secondary
    StatusKind.PENDING, StatusKind.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * A screen of the settings feature: a top bar whose title wraps at large font scales (no fixed height), a back
 * button, a snackbar host, and a scrolling column.
 */
@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = { SettingsTopBar(title = title, onBack = onBack, actions = actions) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingsTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .heightIn(min = 64.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_settings_back), contentDescription = stringResource(R.string.settings_back))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics { heading() },
            )
            actions()
        }
    }
}

/** A section heading (marked as a heading for screen readers). */
@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = RowPadding, end = RowPadding, top = 24.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/** Explanatory body text of a section. */
@Composable
internal fun BodyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = RowPadding, vertical = 4.dp),
    )
}

/** A bulleted list; each item is read as its own line. */
@Composable
internal fun BulletList(items: List<String>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = RowPadding, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            Row {
                Text(
                    "•",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clearAndSetSemantics {},
                )
                Spacer(Modifier.width(8.dp))
                Text(item, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A row that opens another screen, a system page or a dialog. */
@Composable
internal fun NavigationRow(title: String, summary: String?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = RowPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(
            painterResource(R.drawable.ic_settings_chevron),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** An icon plus text status line; the icon's spoken label ("Warning", "Done") precedes the text. */
@Composable
internal fun StatusLine(kind: StatusKind, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = RowPadding, vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painterResource(kind.icon),
            contentDescription = stringResource(kind.labelRes),
            tint = kind.tint(),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/**
 * A switch row: the whole row toggles, the switch itself is not a separate touch target. [horizontalPadding] is 0 inside
 * dialogs, which pad their content themselves.
 */
@Composable
internal fun SwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    horizontalPadding: Dp = RowPadding,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 56.dp)
            .padding(horizontal = horizontalPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** One choice of a single-choice group; put the rows in a `Modifier.selectableGroup()` container. */
@Composable
internal fun RadioRow(
    title: String,
    summary: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = RowPadding, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * A number with decrease and increase buttons. The buttons are disabled at the bounds, so the value can never leave
 * them through this control (the ViewModel clamps as well).
 */
@Composable
internal fun StepperRow(
    title: String,
    valueText: String,
    canDecrement: Boolean,
    canIncrement: Boolean,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = RowPadding, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {},
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(valueText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = onDecrement, enabled = canDecrement) {
            Icon(painterResource(R.drawable.ic_settings_remove), contentDescription = stringResource(R.string.settings_decrease, title))
        }
        IconButton(onClick = onIncrement, enabled = canIncrement) {
            Icon(painterResource(R.drawable.ic_settings_add), contentDescription = stringResource(R.string.settings_increase, title))
        }
    }
}

/** A label and its value, read as one line ("Android API level: 37"); both wrap at large font scales. */
@Composable
internal fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = RowPadding, vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** A row of buttons under a section, wrapping to the next line when they do not fit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ButtonRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = RowPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

/** A card with a status line as its title, body text and optional actions below. */
@Composable
internal fun NoticeCard(
    kind: StatusKind,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = RowPadding, vertical = 8.dp),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            StatusLine(kind = kind, text = title)
            if (body != null) BodyText(body)
            actions()
        }
    }
}
