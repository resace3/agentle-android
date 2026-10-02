package dev.agentle.core.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.agentle.core.ui.R
import dev.agentle.core.ui.icon.AgentleIcons

/**
 * A confirmation dialog. The [destructive] variant (deleting data, stopping a JITAI) shows a warning icon and an
 * error-colored confirm button whose label names the action ("Delete", never "OK"), so the danger is not shown by
 * color alone.
 */
@Composable
public fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = stringResource(R.string.ui_action_cancel),
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        icon = if (destructive) {
            { Icon(imageVector = AgentleIcons.warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
        } else {
            null
        },
        title = { Text(text = title, modifier = Modifier.semantics { heading() }) },
        text = { Text(text = message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}
